import json
import time

import httpx
from PIL import Image

from pantry.matcher import normalize, suggest
from pantry.domain import Product
from pantry.receipts.pipeline import checks
from pantry.receipts.readers import OllamaReader, ReaderError
from pantry.receipts.schema import Receipt
from tests.conftest import FakeReader, photo_bytes, sample_receipt


def wait_for(client, rid, status, timeout=5):
    deadline = time.time() + timeout
    while time.time() < deadline:
        r = client.get(f"/api/receipts/{rid}").json()
        if r["status"] == status:
            return r
        time.sleep(0.02)
    raise AssertionError(f"receipt {rid} never reached {status}: {r['status']} {r.get('error')}")


def test_matcher_prefers_learned_alias():
    milk = Product(id="p1", name="Whole milk")
    oat = Product(id="p2", name="Oat milk")
    assert suggest("OAT MLK", "Oat milk", [milk, oat], {})[0]["product"].id == "p2"
    aliases = {normalize("ORG WHL MLK 1GAL"): "p1"}
    s = suggest("ORG WHL MLK 1GAL", "Organic whole milk", [milk, oat], aliases)
    assert s == [{"product": milk, "score": 1.0, "alias": True}]


def test_checks_flag_missing_line():
    ex = sample_receipt().model_dump()
    assert checks(ex) == []
    ex["items"].pop(0)
    assert any("subtotal" in c for c in checks(ex))
    ex["items"][0]["line_total"] = None
    assert any("without a readable price" in c for c in checks(ex))


def test_upload_read_review_commit(make_client):
    reader = FakeReader()
    client = make_client(reader=reader)
    # Products that already exist get suggested; the bag line is not food.
    milk = client.post("/api/products", json={"name": "Organic whole milk", "unit": "gallon", "location": "fridge"}).json()

    up = client.post("/api/receipts", files=[("files", ("r.jpg", photo_bytes(), "image/jpeg"))],
                     data={"one_receipt": "false"}).json()
    assert len(up["created"]) == 1 and up["skipped"] == []
    rid = up["created"][0]
    draft = wait_for(client, rid, "ready")
    assert draft["extraction"]["store"] == "Corner Grocer"
    assert draft["checks"] == []
    lines = draft["lines"]
    assert lines[0]["suggestions"][0]["product"]["id"] == milk["id"]
    assert lines[3]["is_food"] is False

    commit = client.post(f"/api/receipts/{rid}/commit", json={"date": "2026-10-30", "lines": [
        {"product_id": milk["id"], "qty": "1", "price": "5.49", "raw_text": lines[0]["raw_text"]},
        {"new_product": {"name": "Bananas", "unit": "lb", "location": "pantry"}, "qty": "2.31", "price": "1.36",
         "raw_text": "BANANAS"},
    ]})
    assert commit.status_code == 200, commit.text
    inv = {i["product"]["name"]: i for i in client.get("/api/inventory").json()}
    assert inv["Bananas"]["remaining"]["exact"] == "231/100"
    assert inv["Organic whole milk"]["lots"][0]["price"] == 5.49
    assert inv["Organic whole milk"]["lots"][0]["bought"] == "2026-10-30"

    # Committing twice is refused; the next receipt matches milk by the learned alias.
    assert client.post(f"/api/receipts/{rid}/commit", json={"lines": []}).status_code == 400
    up2 = client.post("/api/receipts", files=[("files", ("r2.jpg", photo_bytes(color="gray"), "image/jpeg"))]).json()
    d2 = wait_for(client, up2["created"][0], "ready")
    assert d2["lines"][0]["suggestions"][0]["alias"] is True
    assert d2["lines"][1]["suggestions"][0]["product"]["name"] == "Bananas"


def test_reimporting_same_photo_is_skipped(make_client):
    client = make_client(reader=FakeReader())
    data = photo_bytes()
    first = client.post("/api/receipts", files=[("files", ("a.jpg", data, "image/jpeg"))]).json()
    again = client.post("/api/receipts", files=[("files", ("a.jpg", data, "image/jpeg")),
                                                ("files", ("b.jpg", photo_bytes(color="red"), "image/jpeg")),
                                                ("files", ("c.txt", b"hello", "text/plain"))]).json()
    assert again["skipped"][0] == {"file": "a.jpg", "receipt": first["created"][0]}
    assert "error" in again["skipped"][1]
    assert len(again["created"]) == 1
    # Deleting a receipt frees its photo for import again.
    client.delete(f"/api/receipts/{first['created'][0]}")
    third = client.post("/api/receipts", files=[("files", ("a.jpg", data, "image/jpeg"))]).json()
    assert len(third["created"]) == 1


def test_one_receipt_groups_photos(make_client):
    reader = FakeReader()
    client = make_client(reader=reader)
    files = [("files", (f"{i}.jpg", photo_bytes(color=c), "image/jpeg")) for i, c in enumerate(["red", "blue"])]
    up = client.post("/api/receipts", files=files, data={"one_receipt": "true"}).json()
    assert len(up["created"]) == 1
    wait_for(client, up["created"][0], "ready")
    assert len(reader.calls[0]) == 2
    assert client.get(f"/receipt-images/{up['created'][0]}/2.jpg").status_code == 200
    assert client.get("/receipt-images/../pantry.sqlite3").status_code == 404


def test_reader_error_shown_and_retry(make_client):
    reader = FakeReader(error=ReaderError("model offline"))
    client = make_client(reader=reader)
    rid = client.post("/api/receipts", files=[("files", ("a.jpg", photo_bytes(), "image/jpeg"))]).json()["created"][0]
    r = wait_for(client, rid, "error")
    assert r["error"] == "model offline"
    reader.error = None
    client.post(f"/api/receipts/{rid}/retry")
    wait_for(client, rid, "ready")


def test_no_reader_configured(make_client):
    client = make_client(reader=None)
    rid = client.post("/api/receipts", files=[("files", ("a.jpg", photo_bytes(), "image/jpeg"))]).json()["created"][0]
    assert "No receipt reader" in wait_for(client, rid, "error")["error"]


def test_ollama_reader_request_and_parse():
    seen = {}

    def handler(req: httpx.Request):
        seen["body"] = json.loads(req.content)
        return httpx.Response(200, json={"message": {"role": "assistant", "content": sample_receipt().model_dump_json()}})

    reader = OllamaReader(model="qwen2.5vl:7b", transport=httpx.MockTransport(handler))
    result = reader.read([Image.new("RGB", (800, 3000), "white")])
    assert isinstance(result, Receipt) and result.store == "Corner Grocer"
    body = seen["body"]
    assert body["model"] == "qwen2.5vl:7b" and body["stream"] is False
    assert body["format"]["type"] == "object" and "items" in body["format"]["properties"]
    assert len(body["messages"][1]["images"]) >= 2  # tall photo sent as slices


def test_ollama_reader_bad_answer():
    def handler(req):
        return httpx.Response(200, json={"message": {"content": "sorry, I can't read that"}})

    reader = OllamaReader(transport=httpx.MockTransport(handler))
    try:
        reader.read([Image.new("RGB", (600, 800))])
    except ReaderError as e:
        assert "not a valid receipt" in str(e)
    else:
        raise AssertionError("expected ReaderError")


def test_claude_reader_request_and_parse():
    import anthropic
    import httpx2

    from pantry.receipts.readers import ClaudeReader

    seen = {}

    def handler(req):
        seen["body"] = json.loads(req.content)
        seen["beta"] = req.headers.get("anthropic-beta")
        return httpx2.Response(200, json={
            "id": "msg_1", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
            "content": [{"type": "text", "text": sample_receipt().model_dump_json()}],
            "stop_reason": "end_turn", "stop_sequence": None, "usage": {"input_tokens": 1, "output_tokens": 1}})

    client = anthropic.Anthropic(api_key="test", base_url="https://api.invalid",
                                 http_client=httpx2.Client(transport=httpx2.MockTransport(handler)))
    result = ClaudeReader(client=client).read([Image.new("RGB", (800, 3000), "white")])
    assert result.items[0].raw_text == "ORG WHL MLK 1GAL"
    body = seen["body"]
    assert body["model"] == "claude-opus-5-5"
    assert body["output_config"]["format"]["type"] == "json_schema"
    assert sum(1 for c in body["messages"][0]["content"] if c["type"] == "image") == 3
    assert "server-side-fallback" in seen["beta"]


def test_claude_reader_refusal():
    import anthropic
    import httpx2

    from pantry.receipts.readers import ClaudeReader

    def handler(req):
        return httpx2.Response(200, json={
            "id": "msg_1", "type": "message", "role": "assistant", "model": "claude-opus-5-5", "content": [],
            "stop_reason": "refusal", "stop_sequence": None, "usage": {"input_tokens": 1, "output_tokens": 0}})

    client = anthropic.Anthropic(api_key="test", base_url="https://api.invalid",
                                 http_client=httpx2.Client(transport=httpx2.MockTransport(handler)))
    try:
        ClaudeReader(client=client).read([Image.new("RGB", (600, 800))])
    except ReaderError as e:
        assert "declined" in str(e)
    else:
        raise AssertionError("expected ReaderError")

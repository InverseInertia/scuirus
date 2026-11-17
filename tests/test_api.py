from scuirus import openfoodfacts
from tests.conftest import barcode_png


def test_barcode_scan_flow(make_client):
    lookups = []
    client = make_client(lookup=lambda code: lookups.append(code) or {"barcode": code, "name": "Acme Milk"})

    decoded = client.post("/api/barcode", files={"image": ("f.png", barcode_png("036000291452"), "image/png")}).json()
    code = decoded["codes"][0]["code"]
    assert code == "036000291452"

    assert client.post("/api/scan", json={"barcode": code, "mode": "use"}).json()["status"] == "unknown"
    assert client.get(f"/api/lookup/{code}").json() == {"known": False, "suggestion": {"barcode": code, "name": "Acme Milk"}}

    p = client.post("/api/products", json={"name": "Acme Milk", "barcode": code, "unit": "carton",
                                           "default_use": "1/4"}).json()
    added = client.post("/api/scan", json={"barcode": code, "mode": "add", "qty": "2"}).json()
    assert added["status"] == "added" and added["remaining"]["exact"] == "2"
    used = client.post("/api/scan", json={"barcode": code, "mode": "use"}).json()
    assert used["qty"]["exact"] == "1/4" and used["remaining"]["display"] == "1 3/4"
    assert client.get(f"/api/lookup/{code}").json()["known"] is True
    assert p["default_use"]["exact"] == "1/4"


def test_lot_operations_and_history(make_client):
    client = make_client()
    p = client.post("/api/products", json={"name": "Yogurt", "location": "fridge"}).json()
    lot = client.post("/api/lots", json={"product_id": p["id"], "qty": "4", "price": "3.20",
                                         "expires": "2026-12-01"}).json()["lot"]
    r = client.post(f"/api/lots/{lot}/use", json={"qty": "1"}).json()
    assert r["remaining"]["exact"] == "3"
    client.post(f"/api/lots/{lot}/adjust", json={"remaining": "2", "expires": "2026-11-20"})
    inv = client.get("/api/inventory").json()[0]
    assert inv["remaining"]["exact"] == "2" and inv["nearest_expiry"] == "2026-11-20"
    assert inv["lots"][0]["location"] == "fridge"
    client.post(f"/api/lots/{lot}/use", json={"discard": True})
    assert client.get("/api/inventory").json() == []
    types = [e["type"] for e in client.get("/api/history").json()]
    assert types == ["discard", "adjust", "consume", "add"]


def test_validation_errors_are_400(make_client):
    client = make_client()
    p = client.post("/api/products", json={"name": "Salt"}).json()
    assert client.post("/api/lots", json={"product_id": p["id"], "qty": "abc"}).status_code == 400
    assert client.post("/api/lots", json={"product_id": p["id"], "qty": "-1"}).status_code == 400
    assert client.post("/api/lots", json={"product_id": "nope"}).status_code == 404
    assert client.post("/api/products", json={"name": "  "}).status_code == 400
    assert client.post("/api/barcode", files={"image": ("x.txt", b"nope", "text/plain")}).status_code == 400


def test_frontend_served(make_client):
    client = make_client()
    r = client.get("/")
    assert r.status_code == 200 and "<title>Scuirus</title>" in r.text
    assert client.get("/app.js").status_code == 200
    assert client.get("/manifest.webmanifest").status_code == 200


def test_open_food_facts_parse():
    body = {"status": 1, "product": {"product_name": "Whole Milk", "brands": "Acme, Other",
                                     "quantity": "1 gal", "categories_tags": ["en:dairies", "en:whole-milks"]}}
    assert openfoodfacts.parse("036000291452", body) == {
        "barcode": "036000291452", "name": "Acme Whole Milk", "brand": "Acme", "pack_size": "1 gal",
        "category": "whole milks"}
    assert openfoodfacts.parse("1", {"status": 0}) is None
    assert openfoodfacts.parse("1", {"status": 1, "product": {"product_name": ""}}) is None

from datetime import date
from fractions import Fraction as F

import pytest

from scuirus.service import Invalid, ScuirusService
from scuirus.store import Store


def test_scan_add_and_use(service):
    p = service.new_product("Milk", barcode="036000291452", unit="carton", default_use=F(1, 4))
    assert service.scan("036000291452", "add")["status"] == "added"
    r = service.scan("036000291452", "use")
    assert r["status"] == "used" and r["qty"] == F(1, 4) and r["remaining"] == F(3, 4)
    assert service.scan("999", "use") == {"status": "unknown", "barcode": "999"}
    service.remove_from_product(p.id, F(3, 4))
    assert service.scan("036000291452", "use")["status"] == "out_of_stock"


def test_shelf_life_sets_expiry(service, clock):
    p = service.new_product("Bread", shelf_life_days=5)
    lot = service.add_lot(p.id)
    assert service.snapshot.lots[lot].expires == date(2026, 11, 6)


def test_removal_is_capped_at_stock(service):
    p = service.new_product("Eggs")
    service.add_lot(p.id, F(2))
    r = service.remove_from_product(p.id, F(5))
    assert r["removed"] == 2 and r["shortfall"] == 3
    assert not service.snapshot.shortfalls


def test_duplicate_barcode_rejected(service):
    service.new_product("A", barcode="123")
    with pytest.raises(Invalid):
        service.new_product("B", barcode="123")


def test_state_survives_restart(tmp_path, clock):
    path = tmp_path / "db.sqlite3"
    s1 = ScuirusService(Store(path), clock=clock)
    p = s1.new_product("Rice", unit="lb")
    lot = s1.add_lot(p.id, F(5), price=F("7.99"))
    s1.remove_from_lot(lot, F(1, 3))
    s2 = ScuirusService(Store(path), clock=clock)
    assert s2.snapshot.lots[lot].remaining == F(14, 3)
    assert s2.snapshot.lots[lot].price == F("7.99")


def test_timestamps_strictly_increase_when_clock_stalls(service):
    p = service.new_product("Tea")
    for _ in range(5):
        service.add_lot(p.id)
    ts = [e.ts for e in service.history()]
    assert len(set(ts)) == 5


def test_commit_receipt_is_all_or_nothing(service):
    p = service.new_product("Milk")
    with pytest.raises(Exception):
        service.commit_receipt("r1", date(2026, 10, 30), [
            {"product_id": p.id, "qty": F(1)},
            {"product_id": "missing", "qty": F(1)},
        ])
    assert not service.snapshot.lots


def test_commit_receipt_learns_aliases(service):
    p = service.new_product("Whole milk")
    service.commit_receipt("r1", date(2026, 10, 30), [{"product_id": p.id, "qty": F(1), "price": F("5.49"),
                                                       "raw_text": "ORG WHL MLK 1GAL"}])
    assert service.store.aliases() == {"organic whole milk 1gal": p.id}
    lot = next(iter(service.snapshot.lots.values()))
    assert lot.bought == date(2026, 10, 30) and lot.source == "receipt:r1"

from datetime import date, datetime, timedelta, timezone
from fractions import Fraction as F

from scuirus.domain import Event, parse_qty, qty_display, replay, stats

T0 = datetime(2026, 1, 1, tzinfo=timezone.utc)


def ev(i, type, **kw):
    return Event(id=f"e{i:03d}", ts=T0 + timedelta(minutes=i), type=type, **kw)


def test_parse_qty_forms():
    assert parse_qty("2") == 2
    assert parse_qty("1/3") == F(1, 3)
    assert parse_qty("1.5") == F(3, 2)
    assert parse_qty("1 1/2") == F(3, 2)
    assert parse_qty("") is None
    assert parse_qty("abc") is None
    assert parse_qty("1/0") is None


def test_qty_display():
    assert qty_display(F(3, 2)) == "1 1/2"
    assert qty_display(F(1, 3)) == "1/3"
    assert qty_display(F(142, 100)) == "1.42"
    assert qty_display(F(4)) == "4"


def test_thirds_empty_a_lot_exactly():
    events = [ev(0, "add", lot="l1", product="p", qty=F(1))]
    events += [ev(i, "consume", lot="l1", qty=F(1, 3)) for i in range(1, 4)]
    snap = replay(events)
    assert snap.lots["l1"].remaining == 0
    assert snap.lots["l1"].depleted_at == T0 + timedelta(minutes=3)
    assert not snap.active_lots()


def test_product_removal_takes_earliest_expiry_first_and_spans_lots():
    events = [
        ev(0, "add", lot="late", product="p", qty=F(2), expires=date(2026, 3, 1)),
        ev(1, "add", lot="none", product="p", qty=F(2)),
        ev(2, "add", lot="soon", product="p", qty=F(1), expires=date(2026, 1, 10)),
        ev(3, "consume", product="p", qty=F(2)),
    ]
    snap = replay(events)
    assert snap.lots["soon"].remaining == 0
    assert snap.lots["late"].remaining == 1
    assert snap.lots["none"].remaining == 2


def test_overdraw_is_recorded_as_shortfall_not_negative_stock():
    snap = replay([ev(0, "add", lot="l", product="p", qty=F(1)), ev(1, "discard", product="p", qty=F(3))])
    assert snap.lots["l"].remaining == 0
    assert snap.lots["l"].discarded == 1
    assert snap.shortfalls["p"] == 2


def test_order_of_input_does_not_matter():
    events = [
        ev(0, "add", lot="a", product="p", qty=F(3), expires=date(2026, 2, 1)),
        ev(1, "add", lot="b", product="p", qty=F(3), expires=date(2026, 1, 15)),
        ev(2, "consume", product="p", qty=F(4)),
        ev(3, "adjust", lot="a", qty=F(1, 2)),
    ]
    a = replay(events)
    b = replay(list(reversed(events)))
    assert {k: v.remaining for k, v in a.lots.items()} == {k: v.remaining for k, v in b.lots.items()}
    assert a.lots["a"].remaining == F(1, 2)


def test_void_removes_lot_from_stock_and_stats():
    events = [ev(0, "add", lot="l", product="p", qty=F(2), price=F(5)), ev(1, "void", lot="l")]
    snap = replay(events)
    assert snap.remaining_for("p") == 0
    assert stats(snap, T0) == {}


def test_bad_events_become_warnings():
    snap = replay([ev(0, "consume", lot="ghost", qty=F(1)), ev(1, "add", product="p", qty=F(1))])
    assert len(snap.warnings) == 2


def test_duplicate_event_ids_count_once():
    e = ev(0, "add", lot="l", product="p", qty=F(1))
    c = ev(1, "consume", lot="l", qty=F(1, 2))
    snap = replay([e, c, c])
    assert snap.lots["l"].remaining == F(1, 2)


def test_stats():
    events = [
        ev(0, "add", lot="a", product="p", qty=F(2), price=F(6)),
        ev(1, "add", lot="b", product="p", qty=F(1), price=F(4)),
        ev(2, "consume", product="p", qty=F(2)),
        ev(3, "discard", product="p", qty=F(1, 2)),
    ]
    assert stats(replay(events), T0 + timedelta(days=5))["p"].usage_per_day is None  # too little history
    s = stats(replay(events), T0 + timedelta(days=10))["p"]
    assert s.spent == 10
    assert s.average_unit_price == F(10, 3)
    assert s.last_unit_price == 4
    assert s.waste_ratio == 0.2
    assert s.remaining == F(1, 2)
    assert s.usage_per_day == 2.5 / 10

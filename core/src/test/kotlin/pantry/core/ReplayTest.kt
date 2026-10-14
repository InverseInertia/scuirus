package pantry.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReplayTest {
    private fun add(id: String, ts: String, lot: String, qty: String, expires: String?, bought: String = "2026-10-01", price: String? = null) =
        ev(id, ts, EventType.ADD, lot = lot, product = "milk", qty = qty, price = price, bought = bought, expires = expires)

    @Test fun removalTakesEarliestExpiryFirstAndSpansLots() {
        val snap = Replay.run(listOf(
            add("a1", "2026-10-01T10:00:00.000Z", "late", "2", "2026-10-30"),
            add("a2", "2026-10-01T10:00:01.000Z", "soon", "1", "2026-10-10"),
            add("a3", "2026-10-01T10:00:02.000Z", "never", "1", null),
            ev("c1", "2026-10-02T10:00:00.000Z", EventType.CONSUME, product = "milk", qty = "2"),
        ))
        val byId = snap.lots.associateBy { it.id }
        assertEquals(r("0"), byId.getValue("soon").remaining)
        assertEquals(r("1"), byId.getValue("late").remaining)
        assertEquals(r("1"), byId.getValue("never").remaining)
        assertTrue(snap.warnings.isEmpty())
    }

    @Test fun fractionalRemovalsAddUpExactly() {
        val events = mutableListOf(add("a1", "2026-10-01T10:00:00.000Z", "l1", "1", "2026-10-30"))
        repeat(3) { i ->
            events += ev("c$i", "2026-10-02T10:00:0$i.000Z", EventType.CONSUME, lot = "l1", product = "milk", qty = "1/3")
        }
        val lot = Replay.run(events).lots.single()
        assertTrue(lot.remaining.isZero)
        assertEquals(Rational.ONE, lot.consumed)
        assertNotNull(lot.depletedAt)
    }

    @Test fun overRemovalIsRecordedAsShortfallNotNegativeStock() {
        val snap = Replay.run(listOf(
            add("a1", "2026-10-01T10:00:00.000Z", "l1", "1", null),
            ev("c1", "2026-10-02T10:00:00.000Z", EventType.CONSUME, product = "milk", qty = "3/2"),
        ))
        assertTrue(snap.lots.single().remaining.isZero)
        assertEquals(r("1/2"), snap.shortfalls["milk"])
    }

    @Test fun discardCountsAsWasteNotUse() {
        val snap = Replay.run(listOf(
            add("a1", "2026-10-01T10:00:00.000Z", "l1", "2", null),
            ev("d1", "2026-10-05T10:00:00.000Z", EventType.DISCARD, lot = "l1", product = "milk", qty = "1/2"),
            ev("c1", "2026-10-06T10:00:00.000Z", EventType.CONSUME, lot = "l1", product = "milk", qty = "1"),
        ))
        val lot = snap.lots.single()
        assertEquals(r("1/2"), lot.discarded)
        assertEquals(r("1"), lot.consumed)
        assertEquals(r("1/2"), lot.remaining)
    }

    @Test fun resultDoesNotDependOnInputOrderOrDuplicates() {
        val events = listOf(
            add("a1", "2026-10-01T10:00:00.000Z", "x", "2", "2026-10-20"),
            add("a2", "2026-10-01T10:00:01.000Z", "y", "2", "2026-10-15"),
            ev("c1", "2026-10-02T10:00:00.000Z", EventType.CONSUME, product = "milk", qty = "5/2", device = "devA"),
            ev("c2", "2026-10-02T10:00:00.000Z", EventType.CONSUME, product = "milk", qty = "1/4", device = "devB"),
        )
        val a = Replay.run(events)
        val b = Replay.run((events + events).shuffled(java.util.Random(7)))
        assertEquals(a.lots, b.lots)
        assertEquals(r("5/4"), a.remainingFor("milk"))
    }

    @Test fun adjustSetsRemainingAndCanReviveOrEmpty() {
        val snap = Replay.run(listOf(
            add("a1", "2026-10-01T10:00:00.000Z", "l1", "4", null),
            ev("j1", "2026-10-02T10:00:00.000Z", EventType.ADJUST, lot = "l1", qty = "5/2", expires = "2026-11-01"),
        ))
        val lot = snap.lots.single()
        assertEquals(r("5/2"), lot.remaining)
        assertEquals(java.time.LocalDate.parse("2026-11-01"), lot.expires)
    }

    @Test fun voidedLotsDisappearFromStockAndStats() {
        val events = listOf(
            add("a1", "2026-10-01T10:00:00.000Z", "oops", "9", null, price = "9.00"),
            ev("v1", "2026-10-01T10:05:00.000Z", EventType.VOID, lot = "oops"),
        )
        val snap = Replay.run(events)
        assertTrue(snap.activeLots.isEmpty())
        assertTrue(Stats.compute(snap, java.time.Instant.parse("2026-10-14T00:00:00Z")).isEmpty())
    }

    @Test fun badEventsProduceWarningsAndAreSkipped() {
        val snap = Replay.run(listOf(
            ev("c1", "2026-10-02T10:00:00.000Z", EventType.CONSUME, lot = "ghost", product = "milk", qty = "1"),
            ev("a0", "2026-10-02T10:00:01.000Z", EventType.ADD, lot = "z", product = "milk", qty = "0"),
        ))
        assertEquals(2, snap.warnings.size)
        assertTrue(snap.lots.isEmpty())
    }

    @Test fun statsReportPriceUsageAndWaste() {
        val snap = Replay.run(listOf(
            add("a1", "2026-10-01T00:00:00.000Z", "l1", "2", null, price = "6.00"),
            add("a2", "2026-10-08T00:00:00.000Z", "l2", "2", null, price = "8.00"),
            ev("c1", "2026-10-04T00:00:00.000Z", EventType.CONSUME, lot = "l1", product = "milk", qty = "2"),
            ev("d1", "2026-10-10T00:00:00.000Z", EventType.DISCARD, lot = "l2", product = "milk", qty = "1/2"),
        ))
        val s = Stats.compute(snap, java.time.Instant.parse("2026-10-11T00:00:00Z")).getValue("milk")
        assertEquals(r("14"), s.spent)
        assertEquals(r("7/2"), s.averageUnitPrice)
        assertEquals(r("4"), s.lastUnitPrice)
        assertEquals(r("5/2"), s.consumed + s.discarded)
        assertEquals(0.2, s.wasteRatio!!, 1e-9)
        assertEquals(3.0, s.averageDaysToUse!!, 1e-9)
        assertEquals(r("3/2"), s.remaining)
        assertEquals(0.25, s.usagePerDay!!, 1e-9)
        assertEquals(6.0, s.daysOfStock!!, 1e-9)
    }
}

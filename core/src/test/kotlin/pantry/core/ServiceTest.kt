package pantry.core

import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

class ServiceTest {
    private fun service(dir: java.nio.file.Path = tempDir(), device: String = "phone", clock: TestClock = TestClock()): PantryService =
        PantryService(PantryStore(dir, device), clock).also { it.load() }

    @Test fun scanInAndOutUsesDefaultAmountAndEarliestExpiry() {
        val s = service()
        val milk = s.newProduct("Whole milk", barcode = "0123456789012", unit = "carton", location = "fridge")
        s.saveProduct(milk.copy(defaultUse = r("1/4")))
        val early = s.addLot(milk.id, r("1"), expires = LocalDate.parse("2026-10-16"))
        val late = s.addLot(milk.id, r("1"), expires = LocalDate.parse("2026-10-25"))

        repeat(4) { s.scanRemove("0123456789012") }
        val after = s.snapshot.lots.associateBy { it.id }
        assertTrue(after.getValue(early).remaining.isZero)
        assertEquals(r("1"), after.getValue(late).remaining)

        val res = s.scanRemove("0123456789012")
        assertIs<ScanRemoveResult.Removed>(res)
        assertEquals(r("1/4"), res.removed)
        assertEquals(r("3/4"), res.remainingAfter)
    }

    @Test fun scanRemoveHandlesUnknownAndEmpty() {
        val s = service()
        assertIs<ScanRemoveResult.UnknownBarcode>(s.scanRemove("999"))
        val p = s.newProduct("Rice", barcode = "111")
        assertIs<ScanRemoveResult.OutOfStock>(s.scanRemove("111"))
        s.addLot(p.id, r("1/2"))
        val res = s.scanRemove("111", r("3"))
        assertIs<ScanRemoveResult.Removed>(res)
        assertEquals(r("1/2"), res.removed)
        assertTrue(s.snapshot.warnings.isEmpty() && s.snapshot.shortfalls.isEmpty())
    }

    @Test fun quickButtonsRemoveQuartersHalvesAndAll() {
        val s = service()
        val p = s.newProduct("Cheddar", unit = "block")
        val lot = s.addLot(p.id, r("2"))
        s.removeFromLot(lot, r("1/4"))
        s.removeFromLot(lot, r("1/2"))
        assertEquals(r("5/4"), s.snapshot.lots.single().remaining)
        val over = s.removeFromLot(lot, r("10"))
        assertEquals(r("5/4"), over.removed)
        assertEquals(r("35/4"), over.shortfall)
        assertTrue(s.inventory().isEmpty())
        assertTrue(s.snapshot.warnings.isEmpty())
    }

    @Test fun wholeLotRemovalAndDiscardAreSeparate() {
        val s = service()
        val p = s.newProduct("Spinach")
        val a = s.addLot(p.id, r("1"))
        val b = s.addLot(p.id, r("1"))
        s.removeWholeLot(a, discard = true)
        s.removeWholeLot(b)
        val st = s.stats().getValue(p.id)
        assertEquals(r("1"), st.discarded)
        assertEquals(r("1"), st.consumed)
        assertEquals(0.5, st.wasteRatio!!, 1e-9)
    }

    @Test fun shelfLifeFillsExpiryAndEditsStick() {
        val clock = TestClock("2026-10-14T12:00:00Z")
        val s = service(clock = clock)
        val p = s.newProduct("Yogurt", shelfLifeDays = 10)
        val lot = s.addLot(p.id, r("4"), price = r("3.20"))
        assertEquals(LocalDate.parse("2026-10-24"), s.snapshot.lots.single().expires)
        s.adjustLot(lot, remaining = r("3"), expires = LocalDate.parse("2026-10-20"), location = "fridge")
        val l = s.snapshot.lots.single()
        assertEquals(r("3"), l.remaining)
        assertEquals(LocalDate.parse("2026-10-20"), l.expires)
        assertEquals("fridge", l.location)
        assertEquals(r("4/5"), l.unitPrice)
    }

    @Test fun stateSurvivesReloadFromDisk() {
        val dir = tempDir()
        val s = service(dir)
        val p = s.newProduct("Eggs", barcode = "5551", unit = "dozen")
        s.addLot(p.id, r("2"), price = r("7.98"))
        s.scanRemove("5551", r("1/3"))
        val s2 = service(dir)
        assertEquals(s.snapshot.lots, s2.snapshot.lots)
        assertEquals(s.products, s2.products)
        assertEquals(r("5/3"), s2.snapshot.remainingFor(p.id))
    }

    @Test fun sameInstantOperationsStillReplayInOrder() {
        // The test clock never moves, so only the service's own timestamp bump keeps these in order.
        val s = service()
        val p = s.newProduct("Bread")
        val lot = s.addLot(p.id, r("1"))
        s.removeFromLot(lot, r("1/2"))
        s.removeFromLot(lot, r("1/2"))
        assertTrue(s.snapshot.warnings.isEmpty())
        assertTrue(s.inventory().isEmpty())
        val s2 = service(tempDir())  // fresh store, unrelated
        assertTrue(s2.snapshot.lots.isEmpty())
    }

    @Test fun receiptCommitWritesLotsFileAndLearnsAlias() {
        val dir = tempDir()
        val store = PantryStore(dir, "phone")
        val s = PantryService(store, TestClock()).also { it.load() }
        val chicken = s.newProduct("Chicken breast", unit = "lb")
        val path = s.commitReceipt(
            "Hannaford", LocalDate.parse("2026-10-12"), "HANNAFORD\nORG BNLS CHKN BRST 12.34\n",
            listOf(ConfirmedLine(chicken.id, r("2.5"), r("12.34"), rawText = "ORG BNLS CHKN BRST")),
        )
        assertTrue(Files.exists(dir.resolve(path)))
        val lot = s.snapshot.lots.single()
        assertEquals(LocalDate.parse("2026-10-12"), lot.bought)
        assertTrue(lot.source!!.startsWith("receipt:"))
        val s2 = service(dir)
        assertEquals(chicken.id, s2.aliases[ReceiptMatcher.normalize("ORG BNLS CHKN BRST")])
        assertTrue(store.readPending().any { it.startsWith("receipt") })
    }

    @Test fun eventFilesAreSeparatePerDevice() {
        val dir = tempDir()
        val a = service(dir, "phoneA")
        val p = a.newProduct("Jam")
        a.addLot(p.id)
        val b = service(dir, "phoneB")
        b.addLot(p.id)
        val shards = Files.walk(dir.resolve("events")).use { st -> st.filter { it.toString().endsWith(".jsonl") }.map { dir.relativize(it).toString() }.sorted().toList() }
        assertEquals(2, shards.size)
        assertTrue(shards[0].startsWith("events/phoneA/") && shards[1].startsWith("events/phoneB/"))
        assertEquals(2, b.snapshot.lots.size)
        assertNotNull(a.product(p.id))
    }

    @Test fun unreadableEventLinesAreReportedNotHidden() {
        val dir = tempDir()
        val s = service(dir)
        val p = s.newProduct("Salt")
        s.addLot(p.id)
        val shard = Files.walk(dir.resolve("events")).use { st -> st.filter { it.toString().endsWith(".jsonl") }.findFirst().get() }
        Files.write(shard, "this is not json\n".toByteArray(), java.nio.file.StandardOpenOption.APPEND)
        val s2 = service(dir)
        assertEquals(1, s2.loadProblems.size)
        assertEquals(1, s2.snapshot.lots.size)
    }
}

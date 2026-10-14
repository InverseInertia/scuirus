package pantry.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkflowTest {

    private fun sh(dir: Path, vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).directory(dir.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed: $out" }
        return out
    }

    private class Device(val dir: Path, name: String, clock: TestClock = TestClock()) {
        val store = PantryStore(dir, name)
        val service = PantryService(store, clock)
        val vc = CliGit(dir)
        val workflow = Workflow(store, vc)
        fun load() = service.load()
    }

    @Test fun messageBuilderSummarisesPendingWork() {
        val msg = CommitMessage.build(
            listOf("receipt\tHannaford\t3 lines", "add\tMilk\t1 carton", "add\tEggs\t1 dozen", "use\tMilk\t1/4 carton", "toss\tSpinach\t1 bag"),
            emptyList(),
        )
        assertEquals("Receipt from Hannaford, add 2, use 1, toss 1", msg.lines().first())
        assertTrue(msg.contains("- use Milk 1/4 carton"))
        assertEquals("Update 2 files", CommitMessage.build(emptyList(), listOf("a", "b")))
    }

    @Test fun syncIsBlockedUntilChangesAreCommitted() {
        if (!gitAvailable()) return
        val remote = tempDir("remote")
        sh(remote, "git", "init", "--bare", "-b", "main")
        val dir = tempDir("a")
        sh(dir, "git", "clone", remote.toString(), ".")
        val d = Device(dir, "phoneA")
        d.load()
        val p = d.service.newProduct("Milk", unit = "carton")
        d.service.addLot(p.id, r("2"))

        val blocked = d.workflow.sync()
        assertIs<SyncResult.Blocked>(blocked)
        assertTrue(blocked.dirtyPaths.any { it == "catalog.jsonl" })
        assertTrue(blocked.dirtyPaths.any { it.startsWith("events/phoneA/") })
        assertTrue(!blocked.dirtyPaths.any { it.contains(".pantry-local") })

        val committed = d.workflow.commit()
        assertIs<CommitResult.Committed>(committed)
        assertTrue(committed.message.startsWith("Add 1, catalog 1") || committed.message.startsWith("Catalog 1, add 1") || committed.message.contains("add 1"))
        assertTrue(d.store.readPending().isEmpty())
        assertIs<CommitResult.NothingToCommit>(d.workflow.commit())

        val synced = d.workflow.sync()
        assertIs<SyncResult.Synced>(synced)
        assertEquals(1, synced.pushedCommits)
    }

    @Test fun twoDevicesWorkOfflineAndMergeWithoutConflicts() {
        if (!gitAvailable()) return
        val remote = tempDir("remote")
        sh(remote, "git", "init", "--bare", "-b", "main")

        // Device A seeds the catalog and shares it.
        val dirA = tempDir("a")
        sh(dirA, "git", "clone", remote.toString(), ".")
        val a = Device(dirA, "phoneA")
        a.load()
        val milk = a.service.newProduct("Milk", barcode = "0100", unit = "carton")
        val lotA = a.service.addLot(milk.id, r("4"), expires = java.time.LocalDate.parse("2026-10-30"))
        a.workflow.commit()
        assertIs<SyncResult.Synced>(a.workflow.sync())

        // Device B clones, then both go offline and keep working.
        val dirB = tempDir("b")
        sh(dirB, "git", "clone", remote.toString(), ".")
        val b = Device(dirB, "phoneB", TestClock("2026-10-14T13:00:00Z"))
        b.load()
        assertEquals(r("4"), b.service.snapshot.remainingFor(milk.id))

        a.service.scanRemove("0100", r("1/2"))
        a.service.removeFromLot(lotA, r("1/4"))
        a.workflow.commit()

        b.service.scanRemove("0100", r("1"))
        b.service.addLot(milk.id, r("2"), expires = java.time.LocalDate.parse("2026-11-05"))
        b.workflow.commit()

        // A pushes first. B must rebase onto A's work.
        assertIs<SyncResult.Synced>(a.workflow.sync())
        val rb = b.workflow.sync()
        assertIs<SyncResult.Synced>(rb)
        assertEquals(1, rb.pushedCommits)
        assertEquals(1, rb.pulledCommits)

        assertIs<SyncResult.Synced>(a.workflow.sync())
        a.load()
        b.load()

        // 4 - 1/2 - 1/4 - 1 + 2 = 4 1/4, and both devices agree on every lot.
        assertEquals(r("17/4"), a.service.snapshot.remainingFor(milk.id))
        assertEquals(a.service.snapshot.lots, b.service.snapshot.lots)
        assertTrue(a.service.snapshot.warnings.isEmpty() && a.service.snapshot.shortfalls.isEmpty())
        assertTrue(a.workflow.status().isClean && b.workflow.status().isClean)
    }

    @Test fun discardRestoresLastCommitAndKeepsLocalNotesOut() {
        if (!gitAvailable()) return
        val dir = tempDir("solo")
        sh(dir, "git", "init", "-b", "main")
        val d = Device(dir, "phoneA")
        d.load()
        val p = d.service.newProduct("Rice")
        d.service.addLot(p.id, r("1"))
        d.workflow.commit("seed")

        d.service.addLot(p.id, r("5"))
        d.service.removeFromProduct(p.id, r("1/2"))
        assertEquals(r("11/2"), d.service.snapshot.remainingFor(p.id))
        assertTrue(d.workflow.suggestedMessage().startsWith("Add 1, use 1"))

        d.workflow.discard()
        d.load()
        assertEquals(r("1"), d.service.snapshot.remainingFor(p.id))
        assertTrue(d.workflow.status().isClean)
        assertTrue(d.store.readPending().isEmpty())
    }

    @Test fun historyAndRevertOfLatestCommit() {
        if (!gitAvailable()) return
        val dir = tempDir("hist")
        sh(dir, "git", "init", "-b", "main")
        val d = Device(dir, "phoneA")
        d.load()
        val p = d.service.newProduct("Rice")
        d.service.addLot(p.id, r("1"))
        d.workflow.commit("seed")
        d.service.addLot(p.id, r("9"))
        val c = d.workflow.commit("oops, wrong amount") as CommitResult.Committed

        val log = d.workflow.history()
        assertEquals(listOf("oops, wrong amount", "seed"), log.map { it.message })

        val rev = d.workflow.revert(c.sha)
        assertIs<RevertResult.Reverted>(rev)
        d.load()
        assertEquals(r("1"), d.service.snapshot.remainingFor(p.id))
        assertEquals(3, d.workflow.history().size)
    }

    @Test fun revertRefusesWhenTreeIsDirty() {
        if (!gitAvailable()) return
        val dir = tempDir("dirty")
        sh(dir, "git", "init", "-b", "main")
        val d = Device(dir, "phoneA")
        d.load()
        val p = d.service.newProduct("Rice")
        val sha = (d.workflow.commit("seed") as CommitResult.Committed).sha
        d.service.addLot(p.id, r("1"))
        assertIs<RevertResult.Failed>(d.workflow.revert(sha))
    }
}

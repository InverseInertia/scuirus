package pantry.core

import java.time.Instant

data class CommitInfo(val sha: String, val time: Instant, val author: String, val message: String)

/**
 * @property changedPaths files that differ from HEAD, including new untracked files
 * @property ahead local commits not yet on the remote, or null when there is no remote or it was never fetched
 */
data class RepoStatus(val changedPaths: List<String>, val ahead: Int?) {
    val isClean: Boolean get() = changedPaths.isEmpty()
}

sealed interface SyncResult {
    data class Synced(val pulledCommits: Int, val pushedCommits: Int) : SyncResult
    /** Sync refused because there are uncommitted changes. */
    data class Blocked(val dirtyPaths: List<String>) : SyncResult
    data class Conflict(val paths: List<String>) : SyncResult
    data class Failed(val message: String) : SyncResult
}

sealed interface RevertResult {
    data class Reverted(val newSha: String) : RevertResult
    data class Conflict(val paths: List<String>) : RevertResult
    data class Failed(val message: String) : RevertResult
}

/** Implemented with JGit on Android and with the git command line in tests. */
interface VersionControl {
    fun status(): RepoStatus
    /** Stages everything (including new files) and commits. Returns the new commit id. */
    fun commitAll(message: String): String
    /** Restores tracked files to HEAD and deletes untracked files that are not git-ignored. */
    fun discardUncommitted()
    fun log(limit: Int): List<CommitInfo>
    fun revert(sha: String): RevertResult
    /** Fetches, rebases local commits onto the remote branch, pushes. Call only on a clean tree. */
    fun pullRebasePush(): SyncResult
}

object CommitMessage {

    /**
     * Pending entries are tab-separated: kind, label, detail. Kinds are
     * add, use, toss, adjust, void, product, receipt.
     */
    fun build(pending: List<String>, changedPaths: List<String>): String {
        if (pending.isEmpty()) {
            val n = changedPaths.size
            return if (n == 0) "No changes" else "Update $n file${if (n == 1) "" else "s"}"
        }
        val rows = pending.map { it.split('\t') }
        fun count(kind: String) = rows.count { it.getOrNull(0) == kind }

        val parts = ArrayList<String>()
        val receipts = rows.filter { it.getOrNull(0) == "receipt" }
        if (receipts.isNotEmpty()) {
            val store = receipts.first().getOrNull(1)?.takeIf { it.isNotBlank() }
            parts += "Receipt" + (store?.let { " from $it" } ?: "") + if (receipts.size > 1) " (+${receipts.size - 1} more)" else ""
        }
        val adds = count("add")
        if (adds > 0) parts += "add $adds"
        if (count("use") > 0) parts += "use ${count("use")}"
        if (count("toss") > 0) parts += "toss ${count("toss")}"
        if (count("adjust") > 0) parts += "adjust ${count("adjust")}"
        if (count("void") > 0) parts += "void ${count("void")}"
        if (count("product") > 0) parts += "catalog ${count("product")}"

        val title = parts.joinToString(", ").replaceFirstChar { it.uppercase() }
        val shown = rows.filter { it.getOrNull(0) != "receipt" }.take(40).map { r ->
            val detail = r.getOrNull(2)?.takeIf { it.isNotBlank() }
            "- ${r.getOrNull(0)} ${r.getOrNull(1) ?: ""}" + (detail?.let { " $it" } ?: "")
        }
        val more = rows.size - shown.size - receipts.size
        val body = buildString {
            shown.forEach { appendLine(it) }
            if (more > 0) appendLine("- and $more more")
        }.trimEnd()
        return if (body.isEmpty()) title else "$title\n\n$body"
    }
}

sealed interface CommitResult {
    data class Committed(val sha: String, val message: String) : CommitResult
    object NothingToCommit : CommitResult
}

/**
 * Enforces the rules: every change is committed before it leaves the device, and sync never
 * runs on a dirty tree.
 */
class Workflow(private val store: PantryStore, private val vc: VersionControl) {

    fun status(): RepoStatus = vc.status()

    fun suggestedMessage(): String {
        val st = vc.status()
        return CommitMessage.build(store.readPending(), st.changedPaths)
    }

    fun commit(message: String? = null): CommitResult {
        val st = vc.status()
        if (st.isClean) {
            store.clearPending()
            return CommitResult.NothingToCommit
        }
        val msg = message?.takeIf { it.isNotBlank() } ?: CommitMessage.build(store.readPending(), st.changedPaths)
        val sha = vc.commitAll(msg)
        store.clearPending()
        return CommitResult.Committed(sha, msg)
    }

    fun discard() {
        vc.discardUncommitted()
        store.clearPending()
    }

    fun sync(): SyncResult {
        val st = vc.status()
        if (!st.isClean) return SyncResult.Blocked(st.changedPaths)
        return vc.pullRebasePush()
    }

    fun history(limit: Int = 50): List<CommitInfo> = vc.log(limit)

    fun revert(sha: String): RevertResult {
        val st = vc.status()
        if (!st.isClean) return RevertResult.Failed("Commit or discard your changes before reverting.")
        return vc.revert(sha)
    }
}

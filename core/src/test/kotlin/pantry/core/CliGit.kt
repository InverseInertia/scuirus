package pantry.core

import java.nio.file.Path
import java.time.Instant

/** VersionControl backed by the git command line. The Android app uses JGit; this lets the tests exercise real git. */
class CliGit(private val dir: Path) : VersionControl {

    private fun git(vararg args: String, ok: Boolean = true): String {
        val cmd = listOf("git", "-c", "user.name=Pantry Test", "-c", "user.email=test@example.invalid", "-c", "core.editor=true") + args
        val p = ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = p.waitFor()
        if (ok && code != 0) throw IllegalStateException("git ${args.joinToString(" ")} failed ($code): $out")
        lastCode = code
        return out
    }

    var lastCode = 0

    override fun status(): RepoStatus {
        val porcelain = git("status", "--porcelain", "-uall")
        val paths = porcelain.lines().filter { it.length > 3 }.map { it.substring(3).trim() }
        val ahead = git("rev-list", "--count", "@{u}..HEAD", ok = false).trim().toIntOrNull()
        return RepoStatus(paths, ahead)
    }

    override fun commitAll(message: String): String {
        git("add", "-A")
        git("commit", "-m", message)
        return git("rev-parse", "HEAD").trim()
    }

    override fun discardUncommitted() {
        git("reset", "--hard")
        git("clean", "-fd")
    }

    override fun log(limit: Int): List<CommitInfo> {
        val out = git("log", "-n", limit.toString(), "--format=%H%x1f%cI%x1f%an%x1f%B%x1e", ok = false)
        return out.split('\u001e').map { it.trim() }.filter { it.isNotEmpty() }.map {
            val f = it.split('\u001f')
            CommitInfo(f[0], Instant.parse(f[1]), f[2], f[3].trim())
        }
    }

    override fun revert(sha: String): RevertResult {
        git("revert", "--no-edit", sha, ok = false)
        if (lastCode == 0) return RevertResult.Reverted(git("rev-parse", "HEAD").trim())
        val conflicted = git("diff", "--name-only", "--diff-filter=U", ok = false).lines().filter { it.isNotBlank() }
        git("revert", "--abort", ok = false)
        return RevertResult.Conflict(conflicted)
    }

    override fun pullRebasePush(): SyncResult {
        val branch = git("rev-parse", "--abbrev-ref", "HEAD").trim()
        val fetchOut = git("fetch", "origin", ok = false)
        if (lastCode != 0) return SyncResult.Failed("fetch failed: ${fetchOut.trim()}")

        // A brand-new empty remote has no branch to rebase onto yet. Just push.
        val remoteHasBranch = run { git("rev-parse", "--verify", "--quiet", "origin/$branch", ok = false); lastCode == 0 }
        var behind = 0
        val ahead: Int
        if (remoteHasBranch) {
            behind = git("rev-list", "--count", "HEAD..origin/$branch").trim().toInt()
            ahead = git("rev-list", "--count", "origin/$branch..HEAD").trim().toInt()
            if (behind > 0) {
                val out = git("rebase", "origin/$branch", ok = false)
                if (lastCode != 0) {
                    val conflicted = git("diff", "--name-only", "--diff-filter=U", ok = false).lines().filter { it.isNotBlank() }
                    git("rebase", "--abort", ok = false)
                    return if (conflicted.isNotEmpty()) SyncResult.Conflict(conflicted) else SyncResult.Failed("rebase failed: ${out.trim()}")
                }
            }
        } else {
            ahead = git("rev-list", "--count", "HEAD").trim().toInt()
        }
        if (ahead > 0 || !remoteHasBranch) {
            val out = git("push", "-u", "origin", "HEAD", ok = false)
            if (lastCode != 0) return SyncResult.Failed("push failed: ${out.trim()}")
        }
        return SyncResult.Synced(pulledCommits = behind, pushedCommits = ahead)
    }
}

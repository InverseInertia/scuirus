package pantry.app.data

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import pantry.core.CommitInfo
import pantry.core.RepoStatus
import pantry.core.RevertResult
import pantry.core.SyncResult
import pantry.core.VersionControl
import java.io.File
import java.time.Instant

/** git over HTTPS using JGit. Mirrors the CliGit class the core tests run against real git. */
class JGitVersionControl(
    private val dir: File,
    private val settings: Settings,
) : VersionControl {

    private val author get() = PersonIdent("Pantry (${settings.deviceId})", "pantry@localhost")

    private fun credentials(): CredentialsProvider? =
        if (settings.token.isBlank()) null else UsernamePasswordCredentialsProvider(settings.username.ifBlank { "pantry" }, settings.token)

    private inline fun <T> git(block: (Git) -> T): T = Git.open(dir).use(block)

    override fun status(): RepoStatus = git { g ->
        val st = g.status().call()
        val paths = (st.added + st.changed + st.modified + st.removed + st.missing + st.untracked + st.conflicting)
            .toSortedSet().toList()
        RepoStatus(paths, aheadOfRemote(g.repository))
    }

    /** Commits ahead of origin/<branch> as last fetched. Null when there is no remote branch to compare with. */
    private fun aheadOfRemote(repo: Repository): Int? {
        val head = repo.resolve("HEAD") ?: return null
        val remote = repo.resolve("refs/remotes/origin/${repo.branch}") ?: return null
        return count(repo, from = remote, to = head)
    }

    /** Number of commits reachable from [to] but not from [from]. */
    private fun count(repo: Repository, from: ObjectId?, to: ObjectId): Int =
        RevWalk(repo).use { walk ->
            walk.markStart(walk.parseCommit(to))
            if (from != null) walk.markUninteresting(walk.parseCommit(from))
            var n = 0
            for (c in walk) n++
            n
        }

    override fun commitAll(message: String): String = git { g ->
        g.add().addFilepattern(".").call()
        g.add().addFilepattern(".").setUpdate(true).call() // also stages deletions
        g.commit().setMessage(message).setAuthor(author).setCommitter(author).call().name
    }

    override fun discardUncommitted() = git { g ->
        if (g.repository.resolve("HEAD") != null) g.reset().setMode(ResetCommand.ResetType.HARD).call()
        g.clean().setCleanDirectories(true).call() // git-ignored files such as .pantry-local are skipped by default
        Unit
    }

    override fun log(limit: Int): List<CommitInfo> = git { g ->
        if (g.repository.resolve("HEAD") == null) return@git emptyList()
        g.log().setMaxCount(limit).call().map {
            CommitInfo(it.name, Instant.ofEpochSecond(it.commitTime.toLong()), it.authorIdent.name, it.fullMessage.trim())
        }
    }

    override fun revert(sha: String): RevertResult = git { g ->
        try {
            val repo = g.repository
            val commit = RevWalk(repo).use { it.parseCommit(repo.resolve(sha)) }
            val cmd = g.revert().include(commit)
            val result = cmd.call()
            if (result != null) return@git RevertResult.Reverted(result.name)
            val unmerged = cmd.unmergedPaths ?: emptyList()
            g.reset().setMode(ResetCommand.ResetType.HARD).call()
            repo.writeRevertHead(null)
            RevertResult.Conflict(unmerged)
        } catch (e: Exception) {
            runCatching { g.reset().setMode(ResetCommand.ResetType.HARD).call() }
            RevertResult.Failed(e.message ?: "revert failed")
        }
    }

    override fun pullRebasePush(): SyncResult = git { g ->
        val repo = g.repository
        val branch = repo.branch
        val creds = credentials()
        try {
            g.fetch().setRemote("origin").setCredentialsProvider(creds).call()

            val head = repo.resolve("HEAD") ?: return@git SyncResult.Failed("Nothing committed yet.")
            val remote = repo.resolve("refs/remotes/origin/$branch")

            var behind = 0
            val ahead: Int
            if (remote != null) {
                behind = count(repo, from = head, to = remote)
                ahead = count(repo, from = remote, to = head)
                if (behind > 0) {
                    val res = g.rebase().setUpstreamName("refs/remotes/origin/$branch").call()
                    if (!res.status.isSuccessful) {
                        val conflicts = res.conflicts ?: emptyList()
                        g.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
                        return@git if (res.status == RebaseResult.Status.CONFLICTS || conflicts.isNotEmpty())
                            SyncResult.Conflict(conflicts) else SyncResult.Failed("rebase failed: ${res.status}")
                    }
                }
            } else {
                ahead = count(repo, from = null, to = head)
            }

            if (ahead > 0 || remote == null) {
                val results = g.push().setRemote("origin")
                    .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                    .setCredentialsProvider(creds).call()
                for (r in results) for (u in r.remoteUpdates) {
                    if (u.status != RemoteRefUpdate.Status.OK && u.status != RemoteRefUpdate.Status.UP_TO_DATE) {
                        return@git SyncResult.Failed("push rejected: ${u.status} ${u.message ?: ""}".trim())
                    }
                }
                val cfg = repo.config
                cfg.setString("branch", branch, "remote", "origin")
                cfg.setString("branch", branch, "merge", "refs/heads/$branch")
                cfg.save()
            }
            SyncResult.Synced(pulledCommits = behind, pushedCommits = ahead)
        } catch (e: Exception) {
            SyncResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        /**
         * Opens the repository in [dir], cloning from [settings].remoteUrl when it does not exist yet
         * and a remote is configured. Without a remote it creates a local repository that can be
         * connected later from the settings screen.
         */
        fun bootstrap(dir: File, settings: Settings): String? {
            try {
                if (File(dir, ".git").exists()) {
                    Git.open(dir).use { g ->
                        if (settings.hasRemote) {
                            val cfg = g.repository.config
                            cfg.setString("remote", "origin", "url", settings.remoteUrl)
                            cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
                            cfg.save()
                        }
                    }
                    return null
                }
                dir.mkdirs()
                if (settings.hasRemote) {
                    val creds = if (settings.token.isBlank()) null else UsernamePasswordCredentialsProvider(settings.username.ifBlank { "pantry" }, settings.token)
                    Git.cloneRepository().setURI(settings.remoteUrl).setDirectory(dir).setCredentialsProvider(creds).call().close()
                } else {
                    Git.init().setDirectory(dir).setInitialBranch("main").call().close()
                }
                return null
            } catch (e: Exception) {
                return e.message ?: e.javaClass.simpleName
            }
        }

        /** Makes the first commit on a repository that has none, so discard and revert have a HEAD to work with. */
        fun ensureInitialCommit(dir: File, settings: Settings) {
            Git.open(dir).use { g ->
                if (g.repository.resolve("HEAD") != null) return
                val who = PersonIdent("Pantry (${settings.deviceId})", "pantry@localhost")
                g.add().addFilepattern(".").call()
                g.commit().setMessage("Initialize pantry").setAuthor(who).setCommitter(who).call()
            }
        }
    }
}

package ir.mahditavakoli.mia.notify

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ir.mahditavakoli.mia.data.model.RepoIssue
import ir.mahditavakoli.mia.data.repository.GitHubRepository
import ir.mahditavakoli.mia.data.repository.ProjectRepository
import ir.mahditavakoli.mia.network.NetworkModule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Tells the user, on their phone, when the TEC agent finishes an issue.
 *
 * The gap it fills: MIA hands work to an agent that runs on GitHub for minutes or hours, and
 * until now the only way to learn it was done was to open the app and look. This closes that
 * without a push service — a periodic sweep of each project's repo for issues that reached the
 * finish line since the last look.
 *
 * Everything is best-effort. A repo that answers with an error is skipped and retried on the next
 * period, with its watermark left where it was so nothing is missed; a repo that answers is
 * marked checked whether or not it had anything to say. The worker always reports success:
 * retrying a timer that fires again in fifteen minutes anyway would only spend battery.
 */
class AgentCompletionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val gitHub = NetworkModule.gitHubRepository
        // No token means there is no repo to watch. The scheduler already avoids enqueuing in
        // that case; this is the guard for a token removed after the work was scheduled.
        if (!gitHub.isConfigured) return Result.success()
        // A notification that cannot be shown is not worth the requests it would take to build.
        if (!AgentNotifications.canPost(applicationContext)) return Result.success()

        val store = AgentWatchStore(applicationContext)
        val projects = ProjectRepository(NetworkModule.supabaseApi).getProjects().getOrElse { error ->
            Log.w(TAG, "project list unavailable, skipping this sweep", error)
            return Result.success()
        }

        for (project in projects) {
            val repo = GitHubRepository.repoNameFor(project.name)
            val since = store.lastCheckedAt(repo)
            if (since == null) {
                // First sight of this repo: adopt "now" as the watermark and say nothing. The
                // alternative is announcing every issue the agent ever finished, all at once,
                // the first time the app runs a sweep.
                store.setLastCheckedAt(repo, nowIso())
                continue
            }
            val sweptAt = nowIso()
            val issues = gitHub.issuesUpdatedSince(project.name, since).getOrElse { error ->
                // Leave the watermark alone so the next sweep covers this window too.
                Log.w(TAG, "could not read issues of $repo", error)
                continue
            }
            val finished = issues.filter { it.isFinished && !store.hasNotified(repo, it.number) }
            val announced = finished.filter {
                AgentNotifications.notifyFinished(applicationContext, project.name, it)
            }
            store.markNotified(repo, announced.map { it.number })
            store.setLastCheckedAt(repo, sweptAt)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "MIA_AgentWatch"
        private const val WORK_NAME = "agent-completion-watch"

        /**
         * Starts (or leaves running) the sweep. Called on every launch: `KEEP` means an existing
         * schedule keeps its own timing rather than being reset to zero each time the app opens,
         * which would let a frequently-opened app never actually reach a run.
         *
         * Does nothing without a GitHub token — there are no repos to watch, so the work would
         * wake the device only to return immediately.
         */
        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            if (!NetworkModule.isGitHubConfigured) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<AgentCompletionWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * What "the agent finished this" looks like from the outside.
         *
         * Two signals, because the workflow can end either way: it attaches `done` when it
         * merges, and GitHub itself closes an issue whose merged PR said "Closes #n" — in which
         * case the issue simply comes back closed, with no label to read. An `agent-failed` issue
         * is closed-but-not-finished and must never be announced as work done.
         */
        internal val RepoIssue.isFinished: Boolean
            get() = !labels.contains(FAILED_LABEL) &&
                (labels.contains(GitHubRepository.DONE_LABEL) || !isOpen)

        private const val FAILED_LABEL = "agent-failed"

        /** GitHub's `since` wants ISO-8601 UTC, and answers "updated at or after this". */
        internal fun nowIso(now: Date = Date()): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(now)
    }
}

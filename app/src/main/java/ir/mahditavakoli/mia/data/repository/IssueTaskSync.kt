package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.data.model.RepoIssue
import ir.mahditavakoli.mia.data.model.Task
import ir.mahditavakoli.mia.network.supabase.SupabaseApi
import ir.mahditavakoli.mia.network.supabase.UpdateTaskBody
import ir.mahditavakoli.mia.text.PersianText

/**
 * The return leg of the loop: work the agent finished on GitHub shows up as done in MIA.
 *
 * MIA writes tasks → issues on the way out ([IntentExecutionRepository]), but until now nothing
 * came back, so an issue the TEC agent closed left its task sitting open forever. This closes
 * that gap without adding a single network read: the project list already pulls every issue of
 * every project once, with `state=all`, to build the counts on the cards
 * ([GitHubRepository.issuesFor]) — this rides on that same answer.
 *
 * Two rules make it safe to run on every refresh:
 *
 *  - **It only ever closes.** A closed issue closes its task; an *open* issue never reopens one.
 *    Otherwise a task the user deliberately ticked off in the app would be un-ticked by the next
 *    refresh, and the user would have no way to make it stick.
 *  - **It is a convenience, not a source of truth.** Every failure is returned rather than
 *    thrown, so the caller can drop it: a sync that cannot run must never blank out the project
 *    list it is decorating.
 */
class IssueTaskSync(private val api: SupabaseApi) {

    /**
     * Closes every task of [project] whose issue is closed, in one PATCH.
     *
     * @return the ids of the tasks that were closed, so the caller can reflect them in the list
     *         it is already showing instead of re-reading the whole thing.
     */
    suspend fun closeTasksForClosedIssues(
        project: Project,
        issues: List<RepoIssue>
    ): Result<Set<String>> = runCatching {
        val ids = tasksToClose(project.tasks, issues)
        if (ids.isEmpty()) return@runCatching emptySet()
        api.updateTasksByIds("in.(${ids.joinToString(",")})", UpdateTaskBody(isDone = true))
        ids
    }

    companion object {
        /**
         * Which of [tasks] a closed issue in [issues] says is finished.
         *
         * Matching is by [PersianText.fold]ed title — the same key project lookups use — because
         * that is the only thing a task and its issue share: `createIssueForTask` opens the issue
         * with the task's title and stores no id on either side. Titles that fold to the same key
         * are treated as the same work, which is what makes a title retyped with an Arabic ی
         * still match.
         *
         * Already-done tasks are skipped so a repeated refresh writes nothing, and tasks with no
         * id are skipped because there is nothing to PATCH.
         */
        fun tasksToClose(tasks: List<Task>, issues: List<RepoIssue>): Set<String> {
            if (tasks.isEmpty() || issues.isEmpty()) return emptySet()
            val closedTitles = issues.asSequence()
                .filter { !it.isOpen }
                .map { PersianText.fold(it.title) }
                .toSet()
            if (closedTitles.isEmpty()) return emptySet()
            return tasks.asSequence()
                .filter { !it.isDone && PersianText.fold(it.title) in closedTitles }
                .mapNotNull { it.id }
                .toSet()
        }
    }
}

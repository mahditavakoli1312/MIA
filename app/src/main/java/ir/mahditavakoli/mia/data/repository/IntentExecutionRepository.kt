package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.ActionType
import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.data.model.ProjectType
import ir.mahditavakoli.mia.data.model.RepoIssue
import ir.mahditavakoli.mia.data.model.Task
import ir.mahditavakoli.mia.data.model.TokenUsage
import ir.mahditavakoli.mia.data.model.VoiceCommandIntent
import ir.mahditavakoli.mia.network.supabase.CreateProjectBody
import ir.mahditavakoli.mia.network.supabase.CreateTaskBody
import ir.mahditavakoli.mia.network.supabase.SupabaseApi
import ir.mahditavakoli.mia.network.supabase.UpdateTaskBody
import ir.mahditavakoli.mia.text.PersianText

/**
 * Executes a parsed [VoiceCommandIntent] against the Supabase REST (PostgREST) backend.
 * Project lookups happen by name because the LLM only ever gives us names, not ids.
 *
 * Creating a project also creates a matching GitHub repository, and adding a task also
 * opens a GitHub issue in that repository; closing or reopening a task does the same to that
 * issue. These GitHub side-effects are best-effort: a failure (or no token configured) never
 * fails the underlying Supabase action — it just changes the confirmation message.
 */
class IntentExecutionRepository(
    private val api: SupabaseApi,
    private val gitHub: GitHubRepository
) {

    /**
     * What a batch did: the confirmation text, and the GitHub issues it opened.
     *
     * [issues] exists for the command timeline, which follows an issue's labels and comments long
     * after this call has returned. Parsing an issue number back out of [message] would work right
     * up to the day someone rewords a Persian sentence.
     */
    data class Outcome(
        val message: String,
        val issues: List<OpenedIssue> = emptyList()
    )

    /** One issue this batch opened, and the project whose repo it lives in. */
    data class OpenedIssue(
        val projectName: String,
        val issue: RepoIssue,
        /** True when it was opened `by-agent`, i.e. something is going to happen to it. */
        val agentHandled: Boolean
    )

    /**
     * Executes a batch of intents (as the model may split one complex command into several) in
     * order, so any prerequisite like create_project runs before the add_task objects that depend
     * on it. Each intent's outcome is independent: one failing (e.g. a project not found) does not
     * abort the rest, and the returned message summarizes every line.
     */
    suspend fun executeAll(
        intents: List<VoiceCommandIntent>,
        agentHandled: Boolean,
        usage: TokenUsage? = null
    ): Result<Outcome> = runCatching {
        require(intents.isNotEmpty()) { "دستوری برای اجرا یافت نشد" }
        // One model call produced this whole batch, so every issue it opens reports the same
        // spend as shared rather than each claiming the full amount.
        val issueCount = intents.count { it.actionType == ActionType.ADD_TASK }
        val opened = mutableListOf<OpenedIssue>()
        if (intents.size == 1) {
            return@runCatching Outcome(
                message = runIntent(intents.first(), agentHandled, usage, issueCount, opened),
                issues = opened
            )
        }
        // Explicit loop, not joinToString { }, because runIntent is a suspend function and
        // joinToString's transform lambda isn't an inline/suspend-preserving context.
        val lines = ArrayList<String>(intents.size)
        for (intent in intents) {
            lines += runCatching { runIntent(intent, agentHandled, usage, issueCount, opened) }.fold(
                onSuccess = { "• $it" },
                onFailure = { "• ⚠️ ${it.message}" }
            )
        }
        Outcome(message = lines.joinToString("\n"), issues = opened)
    }

    /**
     * @param agentHandled whether a created task should be opened as an agent issue
     *        (labeled `by-agent` so the OpenCode CI workflow runs). Ignored by non-task actions.
     * @param usage what the voice→intent call cost, recorded on the issue a task opens.
     * @param usageSharedBy how many issues that one call is paying for.
     */
    suspend fun execute(
        intent: VoiceCommandIntent,
        agentHandled: Boolean,
        usage: TokenUsage? = null,
        usageSharedBy: Int = 1
    ): Result<String> = runCatching {
        runIntent(intent, agentHandled, usage, usageSharedBy, mutableListOf())
    }

    /**
     * The one implementation both entry points share.
     *
     * [opened] is appended to rather than returned because only [addTask] ever has anything to add
     * and every other branch would otherwise have to carry an empty list through.
     */
    private suspend fun runIntent(
        intent: VoiceCommandIntent,
        agentHandled: Boolean,
        usage: TokenUsage?,
        usageSharedBy: Int,
        opened: MutableList<OpenedIssue>
    ): String = when (intent.actionType) {
        ActionType.CREATE_PROJECT -> createProject(intent)
        ActionType.DELETE_PROJECT -> deleteProject(intent)
        ActionType.ADD_TASK -> addTask(intent, agentHandled, usage, usageSharedBy, opened)
        ActionType.REMOVE_TASK -> removeTask(intent)
        ActionType.COMPLETE_TASK -> setTaskDone(intent, done = true)
        ActionType.REOPEN_TASK -> setTaskDone(intent, done = false)
        ActionType.SET_DUE_DATE -> setDueDate(intent)
    }

    private suspend fun createProject(intent: VoiceCommandIntent): String {
        api.createProject(CreateProjectBody(name = intent.projectName))
        val base = "پروژه «${intent.projectName}» ساخته شد"
        if (!gitHub.isConfigured) return base
        // A command that did not say what kind of project this is has already been through the
        // confirmation sheet, where the user picked one; DEFAULT is only ever reached by a caller
        // that bypasses the sheet entirely.
        return gitHub.createRepoForProject(
            projectName = intent.projectName,
            projectType = intent.projectType ?: ProjectType.DEFAULT
        ).fold(
            onSuccess = { result ->
                val created = "$base و ریپازیتوری «${result.repo.name}» در گیت‌هاب ایجاد شد"
                // Repo exists, but some agent-wiring step (workflow/label/secret) may have failed.
                if (result.warnings.isEmpty()) created
                else "$created (هشدار پیکربندی ایجنت: ${result.warnings.joinToString("؛ ")})"
            },
            onFailure = { "$base (ساخت ریپازیتوری گیت‌هاب ناموفق بود: ${it.message})" }
        )
    }

    private suspend fun deleteProject(intent: VoiceCommandIntent): String {
        val project = findProjectOrThrow(intent.projectName)
        api.deleteProjectById("eq.${project.id}")
        return "پروژه «${intent.projectName}» حذف شد"
    }

    private suspend fun addTask(
        intent: VoiceCommandIntent,
        agentHandled: Boolean,
        usage: TokenUsage?,
        usageSharedBy: Int,
        opened: MutableList<OpenedIssue>
    ): String {
        val taskTitle = requireNotNull(intent.taskTitle) { "عنوان تسک مشخص نشده است" }
        val project = findProjectOrThrow(intent.projectName)
        api.createTask(CreateTaskBody(projectId = requireNotNull(project.id), title = taskTitle, dueDate = intent.dueDate))
        val base = "تسک «$taskTitle» به پروژه «${intent.projectName}» اضافه شد"
        if (!gitHub.isConfigured) return base
        // Use the canonical stored project name so the repo name matches the one created
        // with the project (the LLM's spoken name may differ by Persian script variants).
        return gitHub.createIssueForTask(
            projectName = project.name,
            taskTitle = taskTitle,
            description = intent.taskDescription,
            dueDate = intent.dueDate,
            agentHandled = agentHandled,
            usage = usage,
            usageSharedBy = usageSharedBy
        ).fold(
            onSuccess = { issue ->
                opened += OpenedIssue(
                    projectName = project.name,
                    issue = issue,
                    agentHandled = agentHandled
                )
                val suffix = if (agentHandled) " و به ایجنت سپرده شد" else ""
                "$base و ایشو #${issue.number} در گیت‌هاب ثبت شد$suffix"
            },
            onFailure = { "$base (ثبت ایشو گیت‌هاب ناموفق بود: ${it.message})" }
        )
    }

    private suspend fun removeTask(intent: VoiceCommandIntent): String {
        val taskTitle = requireNotNull(intent.taskTitle) { "عنوان تسک مشخص نشده است" }
        val project = findProjectOrThrow(intent.projectName)
        val task = findTaskOrThrow(project, intent.projectName, taskTitle)
        api.deleteTaskById("eq.${task.id}")
        return "تسک «$taskTitle» حذف شد"
    }

    /**
     * Closes ([done] = true) or reopens a task, and mirrors that onto the issue the task opened.
     *
     * The GitHub half is the inverse of the Supabase half — a done task is a closed issue — and
     * is best-effort in the same way [addTask]'s issue creation is: the task's state is already
     * committed by the time it runs, so a GitHub failure is reported in the confirmation line and
     * nowhere else.
     */
    private suspend fun setTaskDone(intent: VoiceCommandIntent, done: Boolean): String {
        val taskTitle = requireNotNull(intent.taskTitle) { "عنوان تسک مشخص نشده است" }
        val project = findProjectOrThrow(intent.projectName)
        val task = findTaskOrThrow(project, intent.projectName, taskTitle)
        api.updateTaskById("eq.${task.id}", UpdateTaskBody(isDone = done))
        val base = if (done) "تسک «$taskTitle» انجام‌شده علامت خورد" else "تسک «$taskTitle» دوباره باز شد"
        if (!gitHub.isConfigured) return base
        return gitHub.setIssueStateForTask(project.name, taskTitle, open = !done).fold(
            onSuccess = { issue ->
                // Null means the repo has no issue with this title — a task added before GitHub
                // was configured. Nothing failed, so nothing is reported.
                if (issue == null) base
                else "$base و ایشو #${issue.number} در گیت‌هاب ${if (done) "بسته" else "باز"} شد"
            },
            onFailure = { "$base (به‌روزرسانی ایشو گیت‌هاب ناموفق بود: ${it.message})" }
        )
    }

    private suspend fun setDueDate(intent: VoiceCommandIntent): String {
        val taskTitle = requireNotNull(intent.taskTitle) { "عنوان تسک مشخص نشده است" }
        // A set_due_date with no date is the one shape of this intent that means nothing; the
        // prompt forbids it, but a model that emits it anyway must not silently clear the column.
        val dueDate = requireNotNull(intent.dueDate?.takeIf { it.isNotBlank() }) {
            "مهلت جدید مشخص نشده است"
        }
        val project = findProjectOrThrow(intent.projectName)
        val task = findTaskOrThrow(project, intent.projectName, taskTitle)
        api.updateTaskById("eq.${task.id}", UpdateTaskBody(dueDate = dueDate))
        return "مهلت تسک «$taskTitle» روی $dueDate تنظیم شد"
    }

    /**
     * The one task lookup every task action shares: exact title match inside the project.
     *
     * Deliberately not folded the way [findProjectOrThrow] falls back — the model is handed the
     * project's real task titles as context (see IntentPromptCore.projectContext) and is told to
     * echo them verbatim, so a mismatch here means the user meant a task that does not exist.
     *
     * @param spokenName the project name as the user said it, for an error the user recognises.
     */
    private suspend fun findTaskOrThrow(project: Project, spokenName: String, taskTitle: String): Task =
        api.findTasksByTitle(
            projectIdFilter = "eq.${project.id}",
            titleFilter = "eq.$taskTitle"
        ).firstOrNull() ?: error("تسکی با عنوان «$taskTitle» در پروژه «$spokenName» پیدا نشد")

    private suspend fun findProjectOrThrow(name: String): Project {
        // Fast path: exact match (what the LLM should normally return now that it gets the
        // real project list as context).
        api.findProjectsByName("eq.$name").firstOrNull()?.let { return it }
        // Fallback: the model, the transcription, or the user's typing may still differ from the
        // stored name by Persian script variants (ی/ي, ک/ك), ZWNJ, or spacing. Compare the folded
        // forms client-side — the same key the typed-command pipeline normalizes against.
        val target = PersianText.fold(name)
        return api.getProjects().firstOrNull { PersianText.fold(it.name) == target }
            ?: error("پروژه‌ای با نام «$name» پیدا نشد")
    }
}

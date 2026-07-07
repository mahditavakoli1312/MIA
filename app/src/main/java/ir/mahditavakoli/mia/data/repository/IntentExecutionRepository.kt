package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.ActionType
import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.data.model.VoiceCommandIntent
import ir.mahditavakoli.mia.network.supabase.CreateProjectBody
import ir.mahditavakoli.mia.network.supabase.CreateTaskBody
import ir.mahditavakoli.mia.network.supabase.SupabaseApi

/**
 * Executes a parsed [VoiceCommandIntent] against the Supabase REST (PostgREST) backend.
 * Project lookups happen by name because the LLM only ever gives us names, not ids.
 *
 * Creating a project also creates a matching GitHub repository, and adding a task also
 * opens a GitHub issue in that repository. These GitHub side-effects are best-effort: a
 * failure (or no token configured) never fails the underlying Supabase action — it just
 * changes the confirmation message.
 */
class IntentExecutionRepository(
    private val api: SupabaseApi,
    private val gitHub: GitHubRepository
) {

    /**
     * Executes a batch of intents (as the model may split one complex command into several) in
     * order, so any prerequisite like create_project runs before the add_task objects that depend
     * on it. Each intent's outcome is independent: one failing (e.g. a project not found) does not
     * abort the rest, and the returned message summarizes every line. A single-intent batch behaves
     * exactly like [execute] — same message, same failure propagation.
     */
    suspend fun executeAll(intents: List<VoiceCommandIntent>, agentHandled: Boolean): Result<String> = runCatching {
        require(intents.isNotEmpty()) { "دستوری برای اجرا یافت نشد" }
        if (intents.size == 1) return@runCatching execute(intents.first(), agentHandled).getOrThrow()
        // Explicit loop, not joinToString { }, because execute() is a suspend function and
        // joinToString's transform lambda isn't an inline/suspend-preserving context.
        val lines = ArrayList<String>(intents.size)
        for (intent in intents) {
            lines += execute(intent, agentHandled).fold(
                onSuccess = { "• $it" },
                onFailure = { "• ⚠️ ${it.message}" }
            )
        }
        lines.joinToString("\n")
    }

    /**
     * @param agentHandled whether a created task should be opened as an agent issue
     *        (labeled `by-agent` so the OpenCode CI workflow runs). Ignored by non-task actions.
     */
    suspend fun execute(intent: VoiceCommandIntent, agentHandled: Boolean): Result<String> = runCatching {
        when (intent.actionType) {
            ActionType.CREATE_PROJECT -> createProject(intent)
            ActionType.DELETE_PROJECT -> deleteProject(intent)
            ActionType.ADD_TASK -> addTask(intent, agentHandled)
            ActionType.REMOVE_TASK -> removeTask(intent)
        }
    }

    private suspend fun createProject(intent: VoiceCommandIntent): String {
        api.createProject(CreateProjectBody(name = intent.projectName))
        val base = "پروژه «${intent.projectName}» ساخته شد"
        if (!gitHub.isConfigured) return base
        return gitHub.createRepoForProject(intent.projectName).fold(
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

    private suspend fun addTask(intent: VoiceCommandIntent, agentHandled: Boolean): String {
        val taskTitle = requireNotNull(intent.taskTitle) { "عنوان تسک مشخص نشده است" }
        val project = findProjectOrThrow(intent.projectName)
        api.createTask(CreateTaskBody(projectId = requireNotNull(project.id), title = taskTitle, dueDate = intent.dueDate))
        val base = "تسک «$taskTitle» به پروژه «${intent.projectName}» اضافه شد"
        if (!gitHub.isConfigured) return base
        // Use the canonical stored project name so the repo name matches the one created
        // with the project (the LLM's spoken name may differ by Persian script variants).
        return gitHub.createIssueForTask(project.name, taskTitle, intent.taskDescription, intent.dueDate, agentHandled).fold(
            onSuccess = { issue ->
                val suffix = if (agentHandled) " و به ایجنت سپرده شد" else ""
                "$base و ایشو #${issue.number} در گیت‌هاب ثبت شد$suffix"
            },
            onFailure = { "$base (ثبت ایشو گیت‌هاب ناموفق بود: ${it.message})" }
        )
    }

    private suspend fun removeTask(intent: VoiceCommandIntent): String {
        val taskTitle = requireNotNull(intent.taskTitle) { "عنوان تسک مشخص نشده است" }
        val project = findProjectOrThrow(intent.projectName)
        val task = api.findTasksByTitle(
            projectIdFilter = "eq.${project.id}",
            titleFilter = "eq.$taskTitle"
        ).firstOrNull() ?: error("تسکی با عنوان «$taskTitle» در پروژه «${intent.projectName}» پیدا نشد")
        api.deleteTaskById("eq.${task.id}")
        return "تسک «$taskTitle» حذف شد"
    }

    private suspend fun findProjectOrThrow(name: String): Project {
        // Fast path: exact match (what the LLM should normally return now that it gets the
        // real project list as context).
        api.findProjectsByName("eq.$name").firstOrNull()?.let { return it }
        // Fallback: the model/transcription may still differ from the stored name by Persian
        // script variants (ی/ي, ک/ك), ZWNJ, or spacing. Compare normalized forms client-side.
        val target = normalizePersian(name)
        return api.getProjects().firstOrNull { normalizePersian(it.name) == target }
            ?: error("پروژه‌ای با نام «$name» پیدا نشد")
    }

    private fun normalizePersian(value: String): String = value
        .replace('ي', 'ی') // Arabic Yeh -> Persian Yeh
        .replace('ك', 'ک') // Arabic Kaf -> Persian Keheh
        .replace("‌", "")        // ZWNJ / نیم‌فاصله
        .replace(Regex("\\s+"), "")    // ignore all spacing differences
        .trim()
        .lowercase()
}

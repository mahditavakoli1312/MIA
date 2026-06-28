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
 */
class IntentExecutionRepository(private val api: SupabaseApi) {

    suspend fun execute(intent: VoiceCommandIntent): Result<String> = runCatching {
        when (intent.actionType) {
            ActionType.CREATE_PROJECT -> createProject(intent)
            ActionType.DELETE_PROJECT -> deleteProject(intent)
            ActionType.ADD_TASK -> addTask(intent)
            ActionType.REMOVE_TASK -> removeTask(intent)
        }
    }

    private suspend fun createProject(intent: VoiceCommandIntent): String {
        api.createProject(CreateProjectBody(name = intent.projectName))
        return "پروژه «${intent.projectName}» ساخته شد"
    }

    private suspend fun deleteProject(intent: VoiceCommandIntent): String {
        val project = findProjectOrThrow(intent.projectName)
        api.deleteProjectById("eq.${project.id}")
        return "پروژه «${intent.projectName}» حذف شد"
    }

    private suspend fun addTask(intent: VoiceCommandIntent): String {
        val taskTitle = requireNotNull(intent.taskTitle) { "عنوان تسک مشخص نشده است" }
        val project = findProjectOrThrow(intent.projectName)
        api.createTask(CreateTaskBody(projectId = requireNotNull(project.id), title = taskTitle, dueDate = intent.dueDate))
        return "تسک «$taskTitle» به پروژه «${intent.projectName}» اضافه شد"
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

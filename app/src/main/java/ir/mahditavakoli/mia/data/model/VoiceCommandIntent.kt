package ir.mahditavakoli.mia.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ActionType {
    @SerialName("create_project") CREATE_PROJECT,
    @SerialName("delete_project") DELETE_PROJECT,
    @SerialName("add_task") ADD_TASK,
    @SerialName("remove_task") REMOVE_TASK
}

/**
 * Mirrors the strict JSON schema the LLM is instructed to return.
 * See [ir.mahditavakoli.mia.network.gemini.GeminiIntentPrompt].
 */
@Serializable
data class VoiceCommandIntent(
    @SerialName("action_type") val actionType: ActionType,
    @SerialName("project_name") val projectName: String,
    @SerialName("task_title") val taskTitle: String? = null,
    // A comprehensive, self-contained Markdown brief (detailed description + technical
    // specifications + UI/UX guidelines), used as the GitHub issue body so the Gemini coding
    // agent can implement without follow-up. Null for non-task actions. See GeminiIntentPrompt.
    @SerialName("task_description") val taskDescription: String? = null,
    @SerialName("due_date") val dueDate: String? = null
)

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
 * See [ir.mahditavakoli.mia.network.openrouter.IntentClassifierPrompt].
 */
@Serializable
data class VoiceCommandIntent(
    @SerialName("action_type") val actionType: ActionType,
    @SerialName("project_name") val projectName: String,
    @SerialName("task_title") val taskTitle: String? = null,
    @SerialName("due_date") val dueDate: String? = null
)

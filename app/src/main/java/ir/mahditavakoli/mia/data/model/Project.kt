package ir.mahditavakoli.mia.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String? = null,
    val name: String,
    @SerialName("created_at") val createdAt: String? = null,
    val tasks: List<Task> = emptyList()
)

@Serializable
data class Task(
    val id: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    val title: String,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("is_done") val isDone: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null
)

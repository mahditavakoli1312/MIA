package ir.mahditavakoli.mia.network.supabase

import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.data.model.Task
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

@Serializable
data class CreateProjectBody(val name: String)

@Serializable
data class CreateTaskBody(
    @SerialName("project_id") val projectId: String,
    val title: String,
    @SerialName("due_date") val dueDate: String? = null
)

/**
 * Thin PostgREST client for a Supabase project with two tables:
 *   projects(id uuid pk, name text, created_at timestamptz)
 *   tasks(id uuid pk, project_id uuid fk -> projects.id, title text, due_date date, is_done bool, created_at timestamptz)
 * Auth (apikey / Authorization headers) is added globally by an OkHttp interceptor, see NetworkModule.
 */
interface SupabaseApi {

    @GET("projects")
    suspend fun getProjects(
        @Query("select") select: String = "*,tasks(*)",
        @Query("order") order: String = "created_at.asc"
    ): List<Project>

    @GET("projects")
    suspend fun findProjectsByName(
        @Query("name") nameFilter: String, // e.g. "eq.وبسایت"
        @Query("select") select: String = "id,name"
    ): List<Project>

    @POST("projects")
    suspend fun createProject(@Body body: CreateProjectBody): List<Project>

    // Returns the raw Response (not a parsed body) since the global "Prefer:
    // return=representation" header makes Supabase reply with a JSON array on
    // delete too, which a bare `Unit` return type can't be deserialized from.
    @DELETE("projects")
    suspend fun deleteProjectById(@Query("id") idFilter: String): Response<Unit> // e.g. "eq.<uuid>"

    @GET("tasks")
    suspend fun findTasksByTitle(
        @Query("project_id") projectIdFilter: String,
        @Query("title") titleFilter: String,
        @Query("select") select: String = "id,title"
    ): List<Task>

    @POST("tasks")
    suspend fun createTask(@Body body: CreateTaskBody): List<Task>

    @DELETE("tasks")
    suspend fun deleteTaskById(@Query("id") idFilter: String): Response<Unit>
}

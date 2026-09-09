package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.data.model.Task
import ir.mahditavakoli.mia.network.supabase.CreateProjectBody
import ir.mahditavakoli.mia.network.supabase.CreateTaskBody
import ir.mahditavakoli.mia.network.supabase.SupabaseApi
import ir.mahditavakoli.mia.network.supabase.UpdateTaskBody
import retrofit2.Response

/**
 * In-memory PostgREST stand-in, in the same spirit as [FakeGitHubApi]: real rows, real filters,
 * no HTTP.
 *
 * It parses the `eq.<value>` filter strings rather than ignoring them, because the filters *are*
 * the behaviour under test — [IntentExecutionRepository] looks a task up by exact title inside
 * one project, and a fake that matched everything would pass whether or not the project filter
 * was ever applied.
 */
class FakeSupabaseApi : SupabaseApi {

    val projects = mutableListOf<Project>()
    val tasks = mutableListOf<Task>()

    /** Every PATCH, in order: the raw `id` filter as sent, and the body sent with it. */
    val taskUpdates = mutableListOf<Pair<String, UpdateTaskBody>>()

    /** Ids passed to deleteTaskById, in order. */
    val deletedTaskIds = mutableListOf<String>()

    fun addProject(id: String, name: String): Project =
        Project(id = id, name = name).also { projects += it }

    fun addTask(
        id: String,
        projectId: String,
        title: String,
        dueDate: String? = null,
        isDone: Boolean = false
    ): Task = Task(id = id, projectId = projectId, title = title, dueDate = dueDate, isDone = isDone)
        .also { tasks += it }

    fun task(id: String): Task = tasks.first { it.id == id }

    override suspend fun getProjects(select: String, order: String): List<Project> =
        projects.map { project -> project.copy(tasks = tasks.filter { it.projectId == project.id }) }

    override suspend fun findProjectsByName(nameFilter: String, select: String): List<Project> =
        projects.filter { it.name == eqValue(nameFilter) }

    override suspend fun createProject(body: CreateProjectBody): List<Project> {
        val created = Project(id = "project-${projects.size + 1}", name = body.name)
        projects += created
        return listOf(created)
    }

    override suspend fun deleteProjectById(idFilter: String): Response<Unit> {
        projects.removeAll { it.id == eqValue(idFilter) }
        return Response.success(Unit)
    }

    override suspend fun findTasksByTitle(
        projectIdFilter: String,
        titleFilter: String,
        select: String
    ): List<Task> = tasks.filter {
        it.projectId == eqValue(projectIdFilter) && it.title == eqValue(titleFilter)
    }

    override suspend fun createTask(body: CreateTaskBody): List<Task> {
        val created = Task(
            id = "task-${tasks.size + 1}",
            projectId = body.projectId,
            title = body.title,
            dueDate = body.dueDate
        )
        tasks += created
        return listOf(created)
    }

    override suspend fun updateTaskById(idFilter: String, body: UpdateTaskBody): Response<Unit> =
        patchTasks(idFilter, setOf(eqValue(idFilter)), body)

    override suspend fun updateTasksByIds(idFilter: String, body: UpdateTaskBody): Response<Unit> =
        patchTasks(idFilter, inValues(idFilter), body)

    private fun patchTasks(
        rawFilter: String,
        ids: Set<String>,
        body: UpdateTaskBody
    ): Response<Unit> {
        taskUpdates += rawFilter to body
        for (id in ids) {
            val index = tasks.indexOfFirst { it.id == id }
            if (index < 0) continue
            // A null field means "not sent", exactly as the serializer omits it — so it must
            // leave the stored column alone rather than overwrite it.
            tasks[index] = tasks[index].copy(
                isDone = body.isDone ?: tasks[index].isDone,
                dueDate = body.dueDate ?: tasks[index].dueDate
            )
        }
        return Response.success(Unit)
    }

    override suspend fun deleteTaskById(idFilter: String): Response<Unit> {
        val id = eqValue(idFilter)
        deletedTaskIds += id
        tasks.removeAll { it.id == id }
        return Response.success(Unit)
    }

    /** "eq.وبسایت" → "وبسایت". Any other operator is a bug in the caller, not a row that matches. */
    private fun eqValue(filter: String): String =
        filter.removePrefix("eq.").also { require(it != filter) { "unsupported filter: $filter" } }

    /** "in.(a,b)" → {"a", "b"}. */
    private fun inValues(filter: String): Set<String> {
        require(filter.startsWith("in.(") && filter.endsWith(")")) { "unsupported filter: $filter" }
        return filter.removeSurrounding("in.(", ")").split(',').filter { it.isNotBlank() }.toSet()
    }
}

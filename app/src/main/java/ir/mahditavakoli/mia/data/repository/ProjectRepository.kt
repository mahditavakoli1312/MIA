package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.network.supabase.SupabaseApi

/** Read-only access to the projects/tasks list shown on the main screen. */
class ProjectRepository(private val api: SupabaseApi) {
    suspend fun getProjects(): Result<List<Project>> = runCatching { api.getProjects() }
}

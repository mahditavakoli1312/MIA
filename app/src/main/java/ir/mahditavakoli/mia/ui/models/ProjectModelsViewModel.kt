package ir.mahditavakoli.mia.ui.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.mahditavakoli.mia.data.repository.AgentModelMigrator
import ir.mahditavakoli.mia.network.NetworkModule
import ir.mahditavakoli.mia.network.openrouter.AgentProvider
import ir.mahditavakoli.mia.network.openrouter.AgentRole
import ir.mahditavakoli.mia.network.openrouter.agentModelOrNull
import ir.mahditavakoli.mia.network.openrouter.providerFor
import ir.mahditavakoli.mia.network.toPersianMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * @param saved what the repo's files say each role is on, as last read from GitHub.
 * @param draft what the user has picked since. Only the roles that differ from [saved] are ever
 *        written, which is what keeps opening the screen and leaving it a genuine no-op rather
 *        than four commits.
 * @param appModel the device-wide in-app model. It is on this screen because a user asking
 *        "which model does each part of this project use" means it too — but it is saved the
 *        instant it is tapped and never travels to GitHub, and the screen says so.
 */
data class ProjectModelsUiState(
    val projectName: String = "",
    val isLoading: Boolean = true,
    val isApplying: Boolean = false,
    val saved: Map<AgentRole, String> = emptyMap(),
    val draft: Map<AgentRole, String> = emptyMap(),
    val appModel: String = "",
    val expandedRole: AgentRole? = null,
    val errorMessage: String? = null,
    /** Set after a successful apply, describing what actually landed on GitHub. */
    val resultMessage: String? = null
) {
    /** The model shown for [role]: the pending pick if there is one, else what the repo says. */
    fun modelFor(role: AgentRole): String? =
        if (role == AgentRole.APP) appModel else draft[role] ?: saved[role]

    fun isDirty(role: AgentRole): Boolean =
        role != AgentRole.APP && draft[role] != null && draft[role] != saved[role]

    /** Exactly what an apply would write — empty means the button has nothing to do. */
    val pendingChanges: Map<AgentRole, String>
        get() = draft.filterKeys { it != AgentRole.APP }
            .filter { (role, model) -> saved[role] != model }

    val canApply: Boolean get() = !isLoading && !isApplying && pendingChanges.isNotEmpty()

    /**
     * True when something the user is about to write runs on the user's own MiniMax account.
     * Worth saying before the commit rather than in the failure afterwards, because it is both
     * a cost and a key the repo may not hold yet.
     */
    val pendingSpendsMiniMax: Boolean
        get() = pendingChanges.values.any { providerFor(it) == AgentProvider.MINIMAX }
}

/**
 * Backs the per-project model screen: which model each seat of one project's AI team sits on.
 *
 * The models are read from the repo's own workflow files rather than remembered locally, for the
 * reason [ir.mahditavakoli.mia.data.repository.GitHubRepository.agentModelsFor] gives — the files
 * are the truth, and they can be edited on GitHub without MIA seeing it. So this screen always
 * loads before it lets anything be changed, and reloads after it writes.
 */
class ProjectModelsViewModel(application: Application) : AndroidViewModel(application) {

    private val gitHubRepository = NetworkModule.gitHubRepository
    private val secretStore = NetworkModule.secretStore

    private val _uiState = MutableStateFlow(
        ProjectModelsUiState(appModel = secretStore.textModelId)
    )
    val uiState: StateFlow<ProjectModelsUiState> = _uiState.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun load(projectName: String) {
        _uiState.update {
            it.copy(projectName = projectName, isLoading = true, errorMessage = null)
        }
        viewModelScope.launch {
            gitHubRepository.agentModelsFor(projectName).fold(
                onSuccess = { models ->
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            saved = models,
                            // Any pick that is now what the repo says is no longer a change.
                            draft = state.draft.filter { (role, id) -> models[role] != id },
                            appModel = secretStore.textModelId
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.toPersianMessage("خواندن مدل‌های این پروژه ناموفق بود")
                        )
                    }
                }
            )
        }
    }

    fun onToggleExpanded(role: AgentRole) {
        // One open card at a time: two open lists of seven models each is a screen nobody can
        // compare anything on.
        _uiState.update { it.copy(expandedRole = if (it.expandedRole == role) null else role) }
    }

    fun onModelSelected(role: AgentRole, modelId: String) {
        if (role == AgentRole.APP) {
            // No repo side, so nothing to stage: this one is saved the moment it is tapped.
            secretStore.textModelId = modelId
            _uiState.update { it.copy(appModel = secretStore.textModelId, expandedRole = null) }
            val label = agentModelOrNull(modelId)?.label ?: modelId
            _events.trySend("مدل دستورهای اپ: $label")
            return
        }
        _uiState.update {
            it.copy(draft = it.draft + (role to modelId), expandedRole = null, resultMessage = null)
        }
    }

    /** Throws away every pending pick, back to what the repo actually says. */
    fun onDiscardChanges() {
        _uiState.update { it.copy(draft = emptyMap(), resultMessage = null) }
    }

    /**
     * Commits every pending role change to the repo's workflow files, then re-reads them.
     *
     * The re-read is not paranoia: a file MIA could not write is reported per file rather than
     * as a thrown error, so the only way to show the user what the repo is *now* on — as opposed
     * to what was asked for — is to look again.
     */
    fun onApply() {
        val state = _uiState.value
        val changes = state.pendingChanges
        if (changes.isEmpty() || state.isApplying) return

        _uiState.update { it.copy(isApplying = true, resultMessage = null) }
        viewModelScope.launch {
            gitHubRepository.setAgentModels(state.projectName, changes).fold(
                onSuccess = { outcome ->
                    _uiState.update {
                        it.copy(isApplying = false, draft = emptyMap(), resultMessage = outcome.asPersianMessage())
                    }
                    _events.trySend(outcome.asPersianMessage())
                    load(state.projectName)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isApplying = false) }
                    _events.trySend(error.toPersianMessage("تغییر مدل نقش‌ها ناموفق بود"))
                }
            )
        }
    }
}

/**
 * What a migration actually did, in one line the user can act on.
 *
 * Ordered by how much the reader has to do about it: an outright failure first, then the two
 * half-successes that need the repo's files refreshed, then the ordinary "done".
 */
internal fun AgentModelMigrator.Outcome.asPersianMessage(): String {
    val roles = models.keys.joinToString("، ") { it.label }
    return when {
        isNotAnOpenRouterRepo ->
            "این مخزن فایل‌های تیم AI را ندارد؛ مدلی برای تغییر پیدا نشد."

        failed.isNotEmpty() && updated.isEmpty() ->
            "هیچ فایلی تغییر نکرد: ${failed.joinToString("، ") { "${it.first} (${it.second})" }}"

        failed.isNotEmpty() ->
            "${updated.size} فایل به‌روزرسانی شد، اما ${failed.size} فایل ناموفق بود: " +
                failed.joinToString("، ") { "${it.first} (${it.second})" }

        blockedByShared.isNotEmpty() ->
            "فایل‌های این مخزن قدیمی‌اند و یک مدل مشترک دارند؛ تغییر این نقش‌ها بدون جابه‌جا " +
                "کردن نقش‌های دیگر ممکن نبود، پس ${blockedByShared.size} فایل دست‌نخورده ماند. " +
                "فایل‌های ورک‌فلو را از docs/github به‌روز کنید تا هر نقش مدل خودش را داشته باشد."

        rolesAreNotSeparable ->
            "مدل‌ها اعمال شد، اما فایل‌های این مخزن قدیمی‌اند و فقط یک مدل مشترک دارند؛ " +
                "همهٔ نقش‌ها روی یک مدل اجرا می‌شوند. برای مدل جداگانه به ازای هر نقش، " +
                "فایل‌های ورک‌فلو را از docs/github به‌روز کنید."

        needsProviderAwareFiles ->
            "مدل اعمال شد، اما فایل‌های این مخزن AGENT_PROVIDER ندارند و همچنان اوپن‌روتر را " +
                "صدا می‌زنند؛ برای اجرای مدل MiniMax باید فایل‌های ورک‌فلو را به‌روز کنید."

        didChange -> "$roles: ${updated.size} فایل روی گیت‌هاب به‌روزرسانی شد."

        else -> "$roles از قبل روی همین مدل بود؛ چیزی تغییر نکرد."
    }
}

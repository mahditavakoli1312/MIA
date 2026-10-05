package ir.mahditavakoli.mia.ui.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import ir.mahditavakoli.mia.network.NetworkModule
import ir.mahditavakoli.mia.network.openrouter.AgentRole
import ir.mahditavakoli.mia.network.openrouter.agentModelOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

data class DefaultModelsUiState(
    val models: Map<AgentRole, String> = emptyMap(),
    val expandedRole: AgentRole? = null
)

/**
 * Backs the defaults screen: the model each role gets in the **next** project MIA creates.
 *
 * There is no apply button and no network here on purpose. Every value is a device-local
 * preference, saved the moment it is tapped, and the only thing that ever reads it is
 * [ir.mahditavakoli.mia.network.NetworkModule.readBootstrapFiles], which writes it into the
 * workflow files as they are uploaded into a new repo. Nothing here reaches a project that
 * already exists — those are changed from their own model screen, which commits to GitHub.
 */
class DefaultModelsViewModel(application: Application) : AndroidViewModel(application) {

    private val secretStore = NetworkModule.secretStore

    private val _uiState = MutableStateFlow(DefaultModelsUiState(models = secretStore.defaultModels()))
    val uiState: StateFlow<DefaultModelsUiState> = _uiState.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onToggleExpanded(role: AgentRole) {
        _uiState.update { it.copy(expandedRole = if (it.expandedRole == role) null else role) }
    }

    fun onModelSelected(role: AgentRole, modelId: String) {
        secretStore.setDefaultModelFor(role, modelId)
        _uiState.update {
            it.copy(models = secretStore.defaultModels(), expandedRole = null)
        }
        val label = agentModelOrNull(modelId)?.label ?: modelId
        _events.trySend(
            if (role == AgentRole.APP) {
                // The one role whose change is live rather than "from the next project on".
                "مدل دستورهای اپ: $label"
            } else {
                "${role.label} در پروژه‌های جدید: $label"
            }
        )
    }

    /** Puts every role back on MIA's own default — one tap out of a set nobody wants to unpick. */
    fun onResetAll() {
        for (role in AgentRole.entries) {
            secretStore.setDefaultModelFor(role, ir.mahditavakoli.mia.network.openrouter.DEFAULT_TEXT_MODEL)
        }
        _uiState.update { it.copy(models = secretStore.defaultModels(), expandedRole = null) }
        _events.trySend("همهٔ نقش‌ها به مدل پیش‌فرض MIA برگشتند.")
    }
}

package ir.mahditavakoli.mia.ui.spend

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.mahditavakoli.mia.data.model.SpendReport
import ir.mahditavakoli.mia.data.repository.LocalSpendStore
import ir.mahditavakoli.mia.data.repository.ProjectRepository
import ir.mahditavakoli.mia.data.repository.SpendRepository
import ir.mahditavakoli.mia.network.NetworkModule
import ir.mahditavakoli.mia.network.toPersianMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SpendUiState(
    val isLoading: Boolean = true,
    val report: SpendReport = SpendReport(),
    val errorMessage: String? = null,
    /** 0 means no budget is set, which hides the bar rather than showing a full one. */
    val monthlyBudget: Int = 0,
    /** What the budget field holds while the user is typing, before it is committed. */
    val budgetDraft: String = "",
    /** Frozen when the report loads, so every bar and every elapsed number agrees. */
    val now: Long = System.currentTimeMillis()
) {
    val isEmpty: Boolean get() = !isLoading && errorMessage == null && report.isEmpty

    /** 0f..1f+ — can exceed 1 on purpose: a budget that is blown should look blown. */
    val budgetFraction: Float? get() = report.budgetFraction(monthlyBudget, now)

    val tokensThisMonth: Int get() = report.tokensThisMonth(now)
}

/**
 * Backs the spend screen: what the whole AI team has cost, from the records it left behind.
 *
 * The read is deliberately not automatic anywhere else in the app — it is several requests per
 * project — so it happens when this screen is opened and when the user pulls it again, and never in
 * the background.
 */
class SpendViewModel(application: Application) : AndroidViewModel(application) {

    private val secretStore = NetworkModule.secretStore
    private val projectRepository = ProjectRepository(NetworkModule.supabaseApi)
    private val localSpendStore = LocalSpendStore(application)
    private val spendRepository = SpendRepository(
        api = NetworkModule.gitHubApi,
        gitHub = NetworkModule.gitHubRepository,
        localEntries = localSpendStore::entries
    )

    private val _uiState = MutableStateFlow(
        SpendUiState(
            monthlyBudget = secretStore.monthlyTokenBudget,
            budgetDraft = secretStore.monthlyTokenBudget.takeIf { it > 0 }?.toString().orEmpty()
        )
    )
    val uiState: StateFlow<SpendUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            // The project list is the input to the whole read: one repo per project. Failing to get
            // it is the one error worth showing instead of an empty screen.
            projectRepository.getProjects().fold(
                onSuccess = { projects ->
                    spendRepository.report(projects).fold(
                        onSuccess = { report ->
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    report = report,
                                    now = System.currentTimeMillis()
                                )
                            }
                        },
                        onFailure = { error -> fail(error) }
                    )
                },
                onFailure = { error -> fail(error) }
            )
        }
    }

    private fun fail(error: Throwable) {
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = error.toPersianMessage("خواندن هزینه‌ها ناموفق بود")
            )
        }
    }

    fun onBudgetDraftChange(value: String) {
        // Digits only, and short enough to be a token count rather than a phone number.
        _uiState.update { it.copy(budgetDraft = value.filter { ch -> ch.isDigit() }.take(9)) }
    }

    /** Commits the typed budget. An empty field means "no budget", which is a valid choice. */
    fun onBudgetSave() {
        val value = _uiState.value.budgetDraft.toIntOrNull() ?: 0
        secretStore.monthlyTokenBudget = value
        _uiState.update { it.copy(monthlyBudget = value) }
    }
}

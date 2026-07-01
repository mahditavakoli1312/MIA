package ir.mahditavakoli.mia.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.mahditavakoli.mia.data.repository.AuthRepository
import ir.mahditavakoli.mia.network.NetworkModule
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val username: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val authRepository = AuthRepository(NetworkModule.supabaseAuthApi, NetworkModule.sessionManager)

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onUsernameChange(value: String) = _uiState.update { it.copy(username = value) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value) }

    fun login() = submit { username, password ->
        authRepository.login(username, password).fold(
            onSuccess = { /* SessionManager flips isLoggedIn -> MainActivity swaps the screen */ },
            onFailure = { error -> _events.trySend(error.message ?: "ورود ناموفق بود") }
        )
    }

    fun signUp() = submit { username, password ->
        authRepository.signUp(username, password).fold(
            onSuccess = { loggedIn ->
                if (!loggedIn) _events.trySend("حساب ساخته شد. حالا وارد شوید.")
            },
            onFailure = { error -> _events.trySend(error.message ?: "ساخت حساب ناموفق بود") }
        )
    }

    private fun submit(action: suspend (username: String, password: String) -> Unit) {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _events.trySend("نام کاربری و رمز عبور را وارد کنید")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }
            action(state.username, state.password)
            _uiState.update { it.copy(isSubmitting = false) }
        }
    }
}

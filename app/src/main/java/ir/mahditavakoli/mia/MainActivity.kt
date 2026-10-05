package ir.mahditavakoli.mia

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import ir.mahditavakoli.mia.network.NetworkModule
import ir.mahditavakoli.mia.notify.AgentNotifications
import ir.mahditavakoli.mia.ui.IssueDeepLink
import ir.mahditavakoli.mia.ui.MiaApp
import ir.mahditavakoli.mia.ui.auth.LoginScreen
import ir.mahditavakoli.mia.ui.theme.MIATheme

class MainActivity : ComponentActivity() {

    /**
     * The issue a tapped notification asked for, or null.
     *
     * Held as activity state rather than read from `intent` inside the composition because the
     * activity is `singleTop`: a second tap arrives at [onNewIntent] on the instance that is
     * already composed, and only a snapshot state makes that recompose.
     */
    private val deepLink = mutableStateOf<IssueDeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLink.value = intent.toIssueDeepLink()
        setContent {
            MIATheme {
                val isLoggedIn by NetworkModule.sessionManager.isLoggedIn.collectAsState()
                if (isLoggedIn) {
                    MiaApp(
                        onLogout = { NetworkModule.sessionManager.clear() },
                        deepLink = deepLink.value,
                        onDeepLinkHandled = { deepLink.value = null }
                    )
                } else {
                    // The link is kept, not dropped: a tap that lands on the login screen should
                    // still open that issue once the user is through it.
                    LoginScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent() in step, so a later configuration change doesn't resurrect the old one.
        setIntent(intent)
        deepLink.value = intent.toIssueDeepLink()
    }
}

private fun Intent.toIssueDeepLink(): IssueDeepLink? {
    val project = getStringExtra(AgentNotifications.EXTRA_PROJECT) ?: return null
    val number = getIntExtra(AgentNotifications.EXTRA_ISSUE_NUMBER, -1)
    return if (number > 0) IssueDeepLink(projectName = project, issueNumber = number) else null
}

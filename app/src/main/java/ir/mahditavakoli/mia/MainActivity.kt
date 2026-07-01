package ir.mahditavakoli.mia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ir.mahditavakoli.mia.network.NetworkModule
import ir.mahditavakoli.mia.ui.auth.LoginScreen
import ir.mahditavakoli.mia.ui.main.MainScreen
import ir.mahditavakoli.mia.ui.theme.MIATheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MIATheme {
                val isLoggedIn by NetworkModule.sessionManager.isLoggedIn.collectAsState()
                if (isLoggedIn) {
                    MainScreen(onLogout = { NetworkModule.sessionManager.clear() })
                } else {
                    LoginScreen()
                }
            }
        }
    }
}
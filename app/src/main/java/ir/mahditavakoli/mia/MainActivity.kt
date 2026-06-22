package ir.mahditavakoli.mia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ir.mahditavakoli.mia.ui.main.MainScreen
import ir.mahditavakoli.mia.ui.theme.MIATheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MIATheme {
                MainScreen()
            }
        }
    }
}
package io.ethan.pushgo

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import io.ethan.pushgo.data.model.ThemeMode
import io.ethan.pushgo.ui.PushGoAppRoot
import io.ethan.pushgo.ui.theme.PushGoTheme

class MainActivity : AppCompatActivity() {
    private var latestIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        latestIntent = intent
        val container = (application as PushGoApp).container
        setContent {
            val themeMode by container.settingsRepository.themeModeFlow.collectAsState(initial = ThemeMode.SYSTEM)
            val useDarkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            PushGoTheme(useDarkTheme = useDarkTheme) {
                PushGoAppRoot(
                    container = container,
                    startIntent = latestIntent,
                    useDarkTheme = useDarkTheme,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        latestIntent = intent
    }
}

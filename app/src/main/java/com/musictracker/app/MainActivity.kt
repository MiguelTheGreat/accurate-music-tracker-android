package com.musictracker.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musictracker.app.data.preferences.ThemeMode
import com.musictracker.app.ui.blend.BlendCalculator
import com.musictracker.app.ui.navigation.MusicTrackerNavHost
import com.musictracker.app.ui.theme.MusicTrackerTheme

class MainActivity : ComponentActivity() {
    private val pendingBlendCode = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        val appContainer = (application as MusicTrackerApplication).appContainer

        setContent {
            val reduceTransparency by appContainer.accessibilityPreferencesStore
                .reduceTransparencyEnabled
                .collectAsStateWithLifecycle(initialValue = false)
            val themeMode by appContainer.themePreferencesStore
                .themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val isDarkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK, ThemeMode.AMOLED -> true
            }
            val isAmoledBlack = themeMode == ThemeMode.AMOLED

            DisposableEffect(isDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    }
                )
                onDispose {}
            }

            MusicTrackerTheme(
                darkTheme = isDarkTheme,
                amoledBlack = isAmoledBlack,
                reduceTransparency = reduceTransparency
            ) {
                MusicTrackerNavHost(
                    appContainer = appContainer,
                    incomingBlendCode = pendingBlendCode.value,
                    onConsumeIncomingBlendCode = { pendingBlendCode.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val data = intent?.dataString?.trim() ?: return
        if (data.startsWith(BlendCalculator.PREFIX, ignoreCase = true)) {
            pendingBlendCode.value = data
        }
    }
}

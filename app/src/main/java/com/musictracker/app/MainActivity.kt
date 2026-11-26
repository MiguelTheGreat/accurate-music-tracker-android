package com.musictracker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musictracker.app.ui.navigation.MusicTrackerNavHost
import com.musictracker.app.ui.theme.MusicTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as MusicTrackerApplication).appContainer

        setContent {
            val reduceTransparency by appContainer.accessibilityPreferencesStore
                .reduceTransparencyEnabled
                .collectAsStateWithLifecycle(initialValue = false)

            MusicTrackerTheme(reduceTransparency = reduceTransparency) {
                MusicTrackerNavHost(appContainer = appContainer)
            }
        }
    }
}

package com.musictracker.app

import android.app.Application
import com.musictracker.app.di.AppContainer

class MusicTrackerApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}

package dev.minios.ocremote

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * OpenCode Android Application
 * Entry point for Hilt dependency injection
 */
@HiltAndroidApp
class OpenCodeApp : Application() {
    
    override fun onCreate() {
        super.onCreate()
    }
}

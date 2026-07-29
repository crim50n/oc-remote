package dev.minios.ocremote

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.minios.ocremote.data.repository.DiagnosticLogRepository
import dev.minios.ocremote.logging.AppLogger
import javax.inject.Inject

/**
 * OC Remote Application
 * Entry point for Hilt dependency injection
 */
@HiltAndroidApp
class OpenCodeApp : Application() {
    @Inject lateinit var diagnosticLogRepository: DiagnosticLogRepository

    override fun onCreate() {
        super.onCreate()
        AppLogger.initialize(diagnosticLogRepository)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                AppLogger.recordCrash(thread, error)
            } finally {
                previousHandler?.uncaughtException(thread, error)
            }
        }
    }
}

package dev.minios.ocremote.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.minios.ocremote.data.repository.SyncRepository
import dev.minios.ocremote.data.repository.SyncBackend
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(
            applicationContext,
            SyncWorkerEntryPoint::class.java,
        ).syncRepository()
        val state = repository.state.first()
        if (!state.config.autoSync || state.config.primaryBackend == dev.minios.ocremote.data.repository.SyncBackend.NONE) {
            return Result.success()
        }
        return runCatching { repository.syncNow() }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { if (it is SyncPermissionException) Result.success() else Result.retry() },
            )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncWorkerEntryPoint {
    fun syncRepository(): SyncRepository
}

object SyncWorkScheduler {
    private const val WORK_NAME = "settings_sync"

    fun update(context: Context, enabled: Boolean, backend: SyncBackend = SyncBackend.NONE) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(requiredNetworkType(backend)).build())
            .build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

internal fun requiredNetworkType(backend: SyncBackend): NetworkType =
    if (backend == SyncBackend.DOCUMENT) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED

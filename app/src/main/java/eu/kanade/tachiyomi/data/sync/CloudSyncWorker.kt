package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.accounts.Account
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import timber.log.Timber
import java.util.concurrent.TimeUnit

class CloudSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = syncMutex.withLock { withContext(Dispatchers.IO) {
        val config = CloudSyncConfig(applicationContext)
        if ((!config.enabled && !inputData.getBoolean(INPUT_MANUAL, false)) || config.accountId == null) {
            return@withContext Result.success()
        }
        try {
            reportProgress(CloudSyncStage.AUTHORIZING)
            val builder = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(CloudSyncConfig.DRIVE_SCOPE)))
            config.accountEmail?.let { builder.setAccount(Account(it, "com.google")) }
            val request = builder.build()
            val result = Tasks.await(Identity.getAuthorizationClient(applicationContext).authorize(request), 40, TimeUnit.SECONDS)
            if (result.hasResolution() || result.accessToken == null) {
                config.lastError = applicationContext.getString(R.string.cloud_sync_reauthorization_required)
                return@withContext Result.failure()
            }
            val drive = GoogleDriveSyncClient(result.accessToken!!)
            reportProgress(CloudSyncStage.CHECKING_ACCOUNT)
            val account = drive.account()
            if (account.permissionId != config.accountId) {
                config.enabled = false
                config.lastError = applicationContext.getString(R.string.cloud_sync_account_changed)
                return@withContext Result.failure()
            }
            CloudSyncEngine(applicationContext, drive).sync { stage, completed, total ->
                coroutineContext.ensureActive()
                reportProgress(stage, completed, total)
            }
            config.lastSuccess = System.currentTimeMillis()
            config.lastError = null
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.e(error, "Cloud sync failed")
            config.lastError = error.message?.take(300) ?: "Cloud sync failed"
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    } }

    private suspend fun reportProgress(stage: CloudSyncStage, completed: Int = 0, total: Int = 0) {
        setProgress(workDataOf(PROGRESS_STAGE to stage.name, PROGRESS_COMPLETED to completed, PROGRESS_TOTAL to total))
    }

    companion object {
        private val syncMutex = Mutex()
        private const val PERIODIC = "tachiyomi-cloud-sync-periodic"
        private const val ONCE = "tachiyomi-cloud-sync-once"
        private const val INPUT_MANUAL = "manual"
        private const val PROGRESS_STAGE = "stage"
        private const val PROGRESS_COMPLETED = "completed"
        private const val PROGRESS_TOTAL = "total"

        fun observeStatus(context: Context) = WorkManager.getInstance(context).let { wm ->
            combine(
                wm.getWorkInfosForUniqueWorkLiveData(ONCE).asFlow(),
                wm.getWorkInfosForUniqueWorkLiveData(PERIODIC).asFlow(),
            ) { once, periodic ->
                fun WorkInfo.snapshot(isPeriodic: Boolean) = CloudSyncStatus.Work(
                    running = state == WorkInfo.State.RUNNING,
                    enqueued = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED,
                    periodic = isPeriodic,
                    attempts = runAttemptCount,
                    stage = CloudSyncStage.entries.firstOrNull { it.name == progress.getString(PROGRESS_STAGE) },
                    completed = progress.getInt(PROGRESS_COMPLETED, 0),
                    total = progress.getInt(PROGRESS_TOTAL, 0),
                )
                CloudSyncStatus.from(once.map { it.snapshot(false) } + periodic.map { it.snapshot(true) })
            }
        }

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!CloudSyncConfig(context).enabled) {
                wm.cancelUniqueWork(PERIODIC)
                wm.cancelUniqueWork(ONCE)
                return
            }
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val work = PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setConstraints(constraints).build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, work)
        }

        fun runNow(context: Context, manual: Boolean = false) {
            val config = CloudSyncConfig(context)
            if (config.accountId == null || (!manual && !config.enabled)) return
            val work = OneTimeWorkRequestBuilder<CloudSyncWorker>()
                .setInputData(workDataOf(INPUT_MANUAL to manual))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE, if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work,
            )
        }
    }
}

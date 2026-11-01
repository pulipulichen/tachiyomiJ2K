package eu.kanade.tachiyomi.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

class CloudSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = syncMutex.withLock { withContext(Dispatchers.IO) {
        val config = CloudSyncConfig(applicationContext)
        if (!config.enabled || config.accountId == null) return@withContext Result.success()
        try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(CloudSyncConfig.DRIVE_SCOPE)))
                .build()
            val result = Tasks.await(Identity.getAuthorizationClient(applicationContext).authorize(request), 40, TimeUnit.SECONDS)
            if (result.hasResolution() || result.accessToken == null) {
                config.lastError = "Google Drive authorization needs confirmation. Open cloud sync settings to reconnect."
                return@withContext Result.failure()
            }
            val drive = GoogleDriveSyncClient(result.accessToken!!)
            val account = drive.account()
            if (account.permissionId != config.accountId) {
                config.enabled = false
                config.lastError = "Cloud account changed. Sync has been disabled to protect your library."
                return@withContext Result.failure()
            }
            CloudSyncEngine(applicationContext, drive).sync()
            config.lastSuccess = System.currentTimeMillis()
            config.lastError = null
            Result.success()
        } catch (error: Exception) {
            Timber.e(error, "Cloud sync failed")
            config.lastError = error.message?.take(300) ?: "Cloud sync failed"
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    } }

    companion object {
        private val syncMutex = Mutex()
        private const val PERIODIC = "tachiyomi-cloud-sync-periodic"
        private const val ONCE = "tachiyomi-cloud-sync-once"

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!CloudSyncConfig(context).enabled) {
                wm.cancelUniqueWork(PERIODIC)
                wm.cancelUniqueWork(ONCE)
                return
            }
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val work = PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints).build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, work)
        }

        fun runNow(context: Context) {
            if (!CloudSyncConfig(context).enabled) return
            val work = OneTimeWorkRequestBuilder<CloudSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, work)
        }
    }
}

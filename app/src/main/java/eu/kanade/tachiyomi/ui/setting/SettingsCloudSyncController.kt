package eu.kanade.tachiyomi.ui.setting

import android.content.Intent
import android.text.format.DateFormat
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.sync.CloudSyncAuthActivity
import eu.kanade.tachiyomi.data.sync.CloudSyncConfig
import eu.kanade.tachiyomi.data.sync.CloudSyncStage
import eu.kanade.tachiyomi.data.sync.CloudSyncStatus
import eu.kanade.tachiyomi.data.sync.CloudSyncWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Date

/** Cloud synchronization is opt-in. A local library is never cleared on sign-in. */
class SettingsCloudSyncController : SettingsController() {
    private var statusJob: Job? = null
    private var status = CloudSyncStatus()

    override fun onAttach(view: View) {
        super.onAttach(view)
        statusJob?.cancel()
        val config = CloudSyncConfig(view.context)
        refresh(config)
        statusJob = viewScope.launch {
            combine(config.changes(), CloudSyncWorker.observeStatus(view.context)) { _, work -> work }
                .collect { work ->
                    status = work
                    refresh(config)
                }
        }
    }

    override fun onDetach(view: View) {
        statusJob?.cancel()
        statusJob = null
        super.onDetach(view)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen): PreferenceScreen = screen.apply {
        titleRes = R.string.cloud_sync_title
        val config = CloudSyncConfig(context)
        preference {
            key = "cloud_sync_connect"
            onClick { startActivity(Intent(context, CloudSyncAuthActivity::class.java)) }
        }
        preference {
            key = "cloud_sync_status"
            titleRes = R.string.cloud_sync_status
            isSelectable = false
        }
        preference {
            key = "cloud_sync_error"
            titleRes = R.string.cloud_sync_error
            isSelectable = false
        }
        switchPreference {
            key = "cloud_sync_toggle"
            titleRes = R.string.cloud_sync_automatic
            summaryRes = R.string.cloud_sync_automatic_summary
            isPersistent = false
            onChange { value ->
                config.enabled = value == true
                CloudSyncWorker.schedule(context)
                if (config.enabled) CloudSyncWorker.runNow(context)
                true
            }
        }
        preference {
            key = "cloud_sync_now"
            onClick {
                CloudSyncWorker.runNow(context, manual = true)
                status = CloudSyncStatus(CloudSyncStatus.State.QUEUED)
                refresh(config)
            }
        }
        preference {
            key = "cloud_sync_last_success"
            titleRes = R.string.cloud_sync_last_success
            isSelectable = false
        }
        preference {
            key = "cloud_sync_disconnect"
            titleRes = R.string.cloud_sync_disconnect
            summaryRes = R.string.cloud_sync_disconnect_summary
            onClick {
                AlertDialog.Builder(context)
                    .setTitle(R.string.cloud_sync_disconnect_title)
                    .setMessage(R.string.cloud_sync_disconnect_message)
                    .setPositiveButton(R.string.cloud_sync_disconnect) { _, _ ->
                        startActivity(
                            Intent(context, CloudSyncAuthActivity::class.java)
                                .putExtra(CloudSyncAuthActivity.EXTRA_DISCONNECT, true),
                        )
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        preference {
            key = "cloud_sync_safety"
            titleRes = R.string.cloud_sync_safety
            summaryRes = R.string.cloud_sync_safety_summary
            isSelectable = false
        }
        refresh(config)
    }

    private fun refresh(config: CloudSyncConfig) {
        val screen = preferenceScreen ?: return
        val context = screen.context
        val connected = config.accountId != null
        screen.findPreference<Preference>("cloud_sync_connect")?.apply {
            titleRes = if (connected) R.string.cloud_sync_reconnect else R.string.cloud_sync_connect
            summary = if (connected) {
                context.getString(R.string.cloud_sync_connected, config.accountEmail ?: config.accountId)
            } else {
                context.getString(R.string.cloud_sync_connect_summary)
            }
        }
        screen.findPreference<SwitchPreferenceCompat>("cloud_sync_toggle")?.apply {
            isEnabled = connected
            isChecked = config.enabled
        }
        screen.findPreference<Preference>("cloud_sync_now")?.apply {
            // Manual sync remains available when automatic sync is switched off.
            isEnabled = connected && !status.busy
            titleRes = if (status.state == CloudSyncStatus.State.RUNNING) R.string.cloud_sync_running else R.string.cloud_sync_now
            summary = context.getString(
                if (!connected) R.string.cloud_sync_connect_first else R.string.cloud_sync_now_summary,
            )
        }
        screen.findPreference<Preference>("cloud_sync_disconnect")?.isEnabled = connected
        screen.findPreference<Preference>("cloud_sync_status")?.summary = when {
            !connected -> context.getString(R.string.cloud_sync_not_connected)
            status.state == CloudSyncStatus.State.RUNNING -> {
                val stage = context.getString(
                    when (status.stage) {
                        CloudSyncStage.AUTHORIZING -> R.string.cloud_sync_authorizing
                        CloudSyncStage.CHECKING_ACCOUNT -> R.string.cloud_sync_checking_account
                        CloudSyncStage.DOWNLOADING -> R.string.cloud_sync_downloading
                        CloudSyncStage.PREPARING -> R.string.cloud_sync_preparing
                        CloudSyncStage.UPLOADING -> R.string.cloud_sync_uploading
                        CloudSyncStage.MERGING -> R.string.cloud_sync_merging
                        null -> R.string.cloud_sync_running
                    },
                )
                if (status.total > 0) {
                    context.getString(R.string.cloud_sync_file_progress, stage, status.completed, status.total)
                } else {
                    stage
                }
            }
            status.state == CloudSyncStatus.State.RETRYING -> context.getString(R.string.cloud_sync_retrying)
            status.state == CloudSyncStatus.State.QUEUED -> context.getString(R.string.cloud_sync_queued)
            config.lastError != null -> context.getString(R.string.cloud_sync_failed)
            config.lastSuccess > 0 -> context.getString(R.string.cloud_sync_complete)
            else -> context.getString(R.string.cloud_sync_ready)
        }
        screen.findPreference<Preference>("cloud_sync_error")?.apply {
            isVisible = config.lastError != null
            summary = config.lastError
        }
        screen.findPreference<Preference>("cloud_sync_last_success")?.summary = if (config.lastSuccess > 0) {
            DateFormat.getMediumDateFormat(context).format(Date(config.lastSuccess)) +
                " " + DateFormat.getTimeFormat(context).format(Date(config.lastSuccess))
        } else {
            context.getString(R.string.cloud_sync_never)
        }
    }
}

package eu.kanade.tachiyomi.ui.setting

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import android.text.format.DateFormat
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.data.sync.CloudSyncAuthActivity
import eu.kanade.tachiyomi.data.sync.CloudSyncConfig
import eu.kanade.tachiyomi.data.sync.CloudSyncWorker
import java.util.Date

/** Cloud synchronization is opt-in. A local library is never cleared on sign-in. */
class SettingsCloudSyncController : SettingsController() {
    override fun setupPreferenceScreen(screen: PreferenceScreen): PreferenceScreen = screen.apply {
        title = "Google Drive sync"
        val config = CloudSyncConfig(context)
        preference {
            key = "cloud_sync_connect"
            title = if (config.accountId == null) "Connect Google account" else "Reconnect Google account"
            summary = config.accountEmail ?: "Authorize access to Drive app data only"
            onClick {
                startActivity(Intent(context, CloudSyncAuthActivity::class.java))
            }
        }
        switchPreference {
            key = "cloud_sync_toggle"
            title = "Automatic two-way sync"
            summary = "Sync library, custom titles, categories, and reading progress. Downloads are not uploaded."
            isPersistent = false
            isChecked = config.enabled
            isEnabled = config.accountId != null
            onChange { value ->
                config.enabled = value == true
                CloudSyncWorker.schedule(context)
                if (config.enabled) CloudSyncWorker.runNow(context)
                true
            }
        }
        preference {
            key = "cloud_sync_now"
            title = "Sync now"
            isEnabled = config.enabled
            summary = "Queue a background synchronization"
            onClick { CloudSyncWorker.runNow(context) }
        }
        preference {
            key = "cloud_sync_status"
            title = "Last successful sync"
            summary = when {
                config.lastError != null -> "Last error: ${config.lastError}"
                config.lastSuccess > 0 -> DateFormat.getMediumDateFormat(context).format(Date(config.lastSuccess)) +
                    " " + DateFormat.getTimeFormat(context).format(Date(config.lastSuccess))
                else -> "Not synchronized yet"
            }
            isSelectable = false
        }
        preference {
            key = "cloud_sync_disconnect"
            title = "Disconnect Google account"
            isEnabled = config.accountId != null
            summary = "Revoke permission and erase this device's sync journal; local manga stay. Cloud files remain in Google Drive."
            onClick {
                AlertDialog.Builder(context)
                    .setTitle("Disconnect Google Drive?")
                    .setMessage("Your local library will be kept. The local sync journal is reset. Previously uploaded Google Drive app data is not deleted.")
                    .setPositiveButton("Disconnect") { _, _ ->
                        startActivity(Intent(context, CloudSyncAuthActivity::class.java)
                            .putExtra(CloudSyncAuthActivity.EXTRA_DISCONNECT, true))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        preference {
            key = "cloud_sync_safety"
            title = "Sync safety"
            summary = "First synchronization merges existing libraries. Keep an independent backup. When access expires, reconnect the Google account."
            isSelectable = false
        }
    }
}

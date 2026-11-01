package eu.kanade.tachiyomi.ui.setting

import android.content.Intent
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
            key = "cloud_sync_safety"
            title = "Sync safety"
            summary = "First synchronization merges existing libraries. Keep an independent backup. When access expires, reconnect the Google account."
            isSelectable = false
        }
    }
}

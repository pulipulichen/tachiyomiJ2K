package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/**
 * Only non-secret account identity and sync status are persisted.
 * OAuth access tokens are always requested from Google Play services on demand.
 */
class CloudSyncConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cloud_sync_v1", Context.MODE_PRIVATE)

    var accountId: String?
        get() = prefs.getString("account_id", null)
        set(value) { prefs.edit().putString("account_id", value).apply() }

    var accountEmail: String?
        get() = prefs.getString("account_email", null)
        set(value) { prefs.edit().putString("account_email", value).apply() }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    var lastSuccess: Long
        get() = prefs.getLong("last_success", 0L)
        set(value) { prefs.edit().putLong("last_success", value).apply() }

    var lastError: String?
        get() = prefs.getString("last_error", null)
        set(value) { prefs.edit().putString("last_error", value).apply() }

    /** Emit immediately and after authorization or a background worker changes state. */
    fun changes() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun connect(id: String, email: String?) {
        prefs.edit()
            .putString("account_id", id)
            .putString("account_email", email)
            .putBoolean("enabled", true)
            .remove("last_error")
            .apply()
    }

    companion object {
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val ANDROID_OAUTH_CLIENT_ID =
            "1096471251358-9p9rmf0m7gur6meut4qnkhhrb9l4jlkt.apps.googleusercontent.com"
    }
}

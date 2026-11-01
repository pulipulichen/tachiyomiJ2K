package eu.kanade.tachiyomi.data.sync

import android.os.Bundle
import android.accounts.Account
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Interactive consent is intentionally isolated in an Activity.
 * Background workers can renew authorization silently but must never open consent UI.
 */
class CloudSyncAuthActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_DISCONNECT = "cloud_sync_disconnect"
    }
    private val authorizationClient by lazy { Identity.getAuthorizationClient(this) }
    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            showError("Google Drive authorization cancelled")
            return@registerForActivityResult
        }
        try {
            complete(authorizationClient.getAuthorizationResultFromIntent(result.data!!))
        } catch (error: Exception) {
            showError(error.message ?: "Authorization failed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            if (intent.getBooleanExtra(EXTRA_DISCONNECT, false)) disconnect() else authorize()
        }
    }

    private fun authorize() {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(CloudSyncConfig.DRIVE_SCOPE)))
            .build()
        authorizationClient.authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val sender = result.pendingIntent?.intentSender
                    if (sender == null) showError("Missing Google consent request")
                    else consent.launch(IntentSenderRequest.Builder(sender).build())
                } else {
                    complete(result)
                }
            }.addOnFailureListener { showError(it.message ?: "Google authorization failed") }
    }

    private fun complete(result: AuthorizationResult) {
        val token = result.accessToken ?: run {
            showError("Google Drive returned no access token")
            return
        }
        lifecycleScope.launch {
            try {
                val account = withContext(Dispatchers.IO) { GoogleDriveSyncClient(token).account() }
                val id = account.permissionId ?: error("Google Drive account has no stable ID")
                val config = CloudSyncConfig(this@CloudSyncAuthActivity)
                if (config.accountId != null && config.accountId != id) {
                    throw IllegalStateException("A different Google account is already paired. Disable sync and migrate its data before switching accounts.")
                }
                config.accountId = id
                config.accountEmail = account.emailAddress
                config.lastError = null
                config.enabled = true
                CloudSyncWorker.schedule(this@CloudSyncAuthActivity)
                CloudSyncWorker.runNow(this@CloudSyncAuthActivity)
                Toast.makeText(this@CloudSyncAuthActivity, "Google Drive connected; sync queued", Toast.LENGTH_LONG).show()
                finish()
            } catch (error: Exception) {
                showError(error.message ?: "Could not connect to Google Drive")
            }
        }
    }

    private fun disconnect() {
        val config = CloudSyncConfig(this)
        val email = config.accountEmail
        if (email.isNullOrEmpty()) {
            showError("No Google account email is available to revoke authorization")
            return
        }
        val request = RevokeAccessRequest.builder()
            .setAccount(Account(email, "com.google"))
            .setScopes(listOf(Scope(CloudSyncConfig.DRIVE_SCOPE)))
            .build()
        authorizationClient.revokeAccess(request)
            .addOnSuccessListener {
                config.enabled = false
                CloudSyncWorker.schedule(this)
                // Detaching the account removes its local sync journal, but
                // intentionally leaves the user's local manga library alone.
                filesDir.resolve("cloud-sync-journal-v1.json").delete()
                filesDir.resolve("cloud-sync-journal-v1.json.bak").delete()
                config.accountId = null
                config.accountEmail = null
                config.lastError = null
                config.lastSuccess = 0L
                Toast.makeText(this, "Google Drive disconnected; library preserved", Toast.LENGTH_LONG).show()
                finish()
            }.addOnFailureListener { showError(it.message ?: "Could not revoke Google Drive access") }
    }

    private fun showError(message: String) {
        CloudSyncConfig(this).lastError = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}

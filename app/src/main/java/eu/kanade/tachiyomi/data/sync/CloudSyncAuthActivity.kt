package eu.kanade.tachiyomi.data.sync

import android.os.Bundle
import android.accounts.Account
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.Gravity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.CancellationException
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
        private const val STATE_WAITING_FOR_CONSENT = "waiting_for_google_consent"
    }
    private var waitingForConsent = false
    private lateinit var statusView: TextView
    private val authorizationClient by lazy { Identity.getAuthorizationClient(this) }
    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        waitingForConsent = false
        if (result.resultCode != RESULT_OK || result.data == null) {
            showError(getString(R.string.cloud_sync_auth_cancelled))
            return@registerForActivityResult
        }
        try {
            complete(authorizationClient.getAuthorizationResultFromIntent(result.data!!))
        } catch (error: Exception) {
            showError(error.message ?: getString(R.string.cloud_sync_auth_failed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            setText(if (intent.getBooleanExtra(EXTRA_DISCONNECT, false)) R.string.cloud_sync_disconnecting else R.string.cloud_sync_authorizing)
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ProgressBar(this@CloudSyncAuthActivity))
            addView(statusView)
        })
        waitingForConsent = savedInstanceState?.getBoolean(STATE_WAITING_FOR_CONSENT) ?: false
        // Restored activity-result launchers deliver pending consent results.
        // If rotation interrupted account verification, silently request a fresh token.
        if (!waitingForConsent) {
            if (intent.getBooleanExtra(EXTRA_DISCONNECT, false)) disconnect() else authorize()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_WAITING_FOR_CONSENT, waitingForConsent)
        super.onSaveInstanceState(outState)
    }

    private fun authorize() {
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(CloudSyncConfig.DRIVE_SCOPE)))
        CloudSyncConfig(this).accountEmail?.let { builder.setAccount(Account(it, "com.google")) }
        val request = builder.build()
        authorizationClient.authorize(request)
            .addOnSuccessListener { result ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                if (result.hasResolution()) {
                    val sender = result.pendingIntent?.intentSender
                    if (sender == null) {
                        showError(getString(R.string.cloud_sync_missing_consent))
                    } else {
                        waitingForConsent = true
                        consent.launch(IntentSenderRequest.Builder(sender).build())
                    }
                } else {
                    complete(result)
                }
            }.addOnFailureListener { showError(it.message ?: getString(R.string.cloud_sync_auth_failed)) }
    }

    private fun complete(result: AuthorizationResult) {
        val token = result.accessToken ?: run {
            showError(getString(R.string.cloud_sync_no_token))
            return
        }
        statusView.setText(R.string.cloud_sync_checking_account)
        lifecycleScope.launch {
            try {
                val account = withContext(Dispatchers.IO) { GoogleDriveSyncClient(token).account() }
                val id = account.permissionId ?: error(getString(R.string.cloud_sync_no_account_id))
                val config = CloudSyncConfig(this@CloudSyncAuthActivity)
                if (config.accountId != null && config.accountId != id) {
                    throw IllegalStateException(getString(R.string.cloud_sync_account_mismatch))
                }
                config.connect(id, account.emailAddress)
                CloudSyncWorker.schedule(this@CloudSyncAuthActivity)
                CloudSyncWorker.runNow(this@CloudSyncAuthActivity)
                Toast.makeText(this@CloudSyncAuthActivity, R.string.cloud_sync_connected_queued, Toast.LENGTH_LONG).show()
                finish()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(error.message ?: getString(R.string.cloud_sync_auth_failed))
            }
        }
    }

    private fun disconnect() {
        val config = CloudSyncConfig(this)
        val email = config.accountEmail
        if (email.isNullOrEmpty()) {
            showError(getString(R.string.cloud_sync_no_account_email))
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
                Toast.makeText(this, R.string.cloud_sync_disconnected, Toast.LENGTH_LONG).show()
                finish()
            }.addOnFailureListener { showError(it.message ?: getString(R.string.cloud_sync_revoke_failed)) }
    }

    private fun showError(message: String) {
        if (isFinishing || isDestroyed) return
        CloudSyncConfig(this).lastError = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}

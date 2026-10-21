package app.lumen.player.data.drive

import android.content.Context
import android.content.Intent
import app.lumen.player.diag.RingLog
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one way to a Drive access token. Play Services Identity's AuthorizationClient holds the
 * grant for the `drive.file` scope on the device: after the owner has consented once, every later
 * `authorize` call returns a fresh short-lived token without any UI, so there is no weekly
 * re-login. Only when the grant is gone (revoked, account removed) does the result carry a
 * resolution, which MainActivity shows through the Google sign-in sheet; from a background
 * caller it just flags "Sign in to Drive again" for Settings.
 *
 * API, verified 2026-10-21 on developer.android.com/identity/authorization and the Play
 * services release notes (play-services-auth 22.0.0, 26 August 2026):
 * `Identity.getAuthorizationClient(context).authorize(AuthorizationRequest)`,
 * `AuthorizationResult.hasResolution()`, `.pendingIntent`, `.accessToken`,
 * `getAuthorizationResultFromIntent(Intent)`, `clearToken(ClearTokenRequest)`.
 */
class DriveAuth(private val context: Context) {

    companion object {
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

        /** Access tokens live about an hour; one is reused for 45 minutes, then fetched again. */
        private const val TOKEN_REUSE_MS = 45L * 60 * 1000
        private const val AWAIT_SECONDS = 20L
    }

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedAt: Long = 0L

    private val scopes = listOf(Scope(SCOPE_DRIVE_FILE))

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(scopes).build()

    /** The Task MainActivity drives interactively; `hasResolution()` means the sheet must be shown. */
    fun authorizeTask(): Task<AuthorizationResult> = Identity.getAuthorizationClient(context).authorize(request())

    /** The result handed back by the sign-in sheet's IntentSender. */
    fun resultFromIntent(data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)

    /** Remembers a token that an interactive flow produced. */
    fun accept(result: AuthorizationResult): String? {
        val token = result.accessToken?.takeIf { it.isNotBlank() }
        if (token != null) {
            cachedToken = token
            cachedAt = System.currentTimeMillis()
        }
        return token
    }

    /**
     * A usable token, or null when the user has to sign in again (no UI is shown here). Never
     * call on the main thread: it blocks on the Play Services task for up to 20 s.
     */
    fun tokenBlocking(forceRefresh: Boolean = false): String? {
        val cached = cachedToken
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cachedAt < TOKEN_REUSE_MS) return cached
        return try {
            val result = Tasks.await(authorizeTask(), AWAIT_SECONDS, TimeUnit.SECONDS)
            if (result.hasResolution()) {
                RingLog.w("drive", "authorisation needs the user; silent token refused")
                null
            } else {
                accept(result)
            }
        } catch (t: Throwable) {
            RingLog.w("drive", "silent authorisation failed: $t")
            null
        }
    }

    suspend fun token(forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) { tokenBlocking(forceRefresh) }

    /** Drops a token Drive refused (401) so the next request asks Play Services for a fresh one. */
    fun invalidate(token: String?) {
        if (token != null && token == cachedToken) cachedToken = null
        cachedAt = 0L
        if (token == null) return
        try {
            Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build())
        } catch (t: Throwable) {
            RingLog.w("drive", "clearToken failed: $t")
        }
    }

    fun forget() {
        cachedToken = null
        cachedAt = 0L
    }
}

package app.lumen.player.data.drive

import android.content.Context
import android.content.Intent
import android.os.Looper
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
 * grant on the device: after the owner has consented once, every later `authorize` call returns
 * a fresh short-lived token without any UI, so there is no weekly re-login. Only when the grant
 * is gone (revoked, account removed) or a scope is missing does the result carry a resolution,
 * which MainActivity shows through the Google sign-in sheet; from a background caller it just
 * flags a one-line notice for Settings.
 *
 * Scopes (0.3.3, owner's ruling D2): `drive.readonly` to list and play the files inside the
 * chosen folder and its subfolders (drive.file only ever saw the folder itself, so the scan
 * found 0 files), and `drive.file` for the app's own "Lumen" backup folder and its uploads.
 *
 * A grant made by 0.3.0 to 0.3.2 holds drive.file alone. The silent request for both scopes then
 * needs the user, so [tokenBlocking] asks again for drive.file alone: that token still serves
 * backups and kept copies, and [hasReadonly] stays false so the repository shows "Reconnect
 * Drive to see the files in your folder" instead of listing with a token that cannot see inside.
 *
 * API, verified 2026-10-21 on developer.android.com/identity/authorization and the Play
 * services release notes (play-services-auth 22.0.0, 26 August 2026):
 * `Identity.getAuthorizationClient(context).authorize(AuthorizationRequest)`,
 * `AuthorizationResult.hasResolution()`, `.pendingIntent`, `.accessToken`, `.grantedScopes`,
 * `getAuthorizationResultFromIntent(Intent)`, `clearToken(ClearTokenRequest)`.
 */
class DriveAuth(private val context: Context) {

    companion object {
        const val SCOPE_DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

        /** Access tokens live about an hour; one is reused for 45 minutes, then fetched again. */
        private const val TOKEN_REUSE_MS = 45L * 60 * 1000
        private const val AWAIT_SECONDS = 20L
        private const val CLEAR_SECONDS = 5L
    }

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedAt: Long = 0L

    /**
     * Whether the last token obtained carries drive.readonly. False until a token has been seen,
     * and false for the drive.file-only fallback token.
     */
    @Volatile
    var hasReadonly: Boolean = false
        private set

    private val fullScopes = listOf(Scope(SCOPE_DRIVE_READONLY), Scope(SCOPE_DRIVE_FILE))
    private val fileOnlyScopes = listOf(Scope(SCOPE_DRIVE_FILE))

    private fun request(scopes: List<Scope>): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(scopes).build()

    /**
     * The Task MainActivity drives interactively, always for both scopes; `hasResolution()` means
     * the sheet must be shown (no grant yet, or a 0.3.0 grant without drive.readonly).
     */
    fun authorizeTask(): Task<AuthorizationResult> = Identity.getAuthorizationClient(context).authorize(request(fullScopes))

    private fun fileOnlyTask(): Task<AuthorizationResult> = Identity.getAuthorizationClient(context).authorize(request(fileOnlyScopes))

    /** The result handed back by the sign-in sheet's IntentSender. */
    fun resultFromIntent(data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)

    /**
     * Remembers a token from a request for both scopes (the interactive flow or the silent one).
     * drive.readonly counts as held when the result lists it among the granted scopes; an empty
     * list (nothing reported) is read as "granted as requested". Google's consent screen lets the
     * user untick a scope, which is the case the list catches.
     */
    fun accept(result: AuthorizationResult): String? = cacheFrom(result, requestedReadonly = true)

    private fun cacheFrom(result: AuthorizationResult, requestedReadonly: Boolean): String? {
        val token = result.accessToken?.takeIf { it.isNotBlank() }
        if (token != null) {
            val granted = try {
                result.grantedScopes.orEmpty()
            } catch (t: Throwable) {
                emptyList()
            }
            cachedToken = token
            cachedAt = System.currentTimeMillis()
            hasReadonly = requestedReadonly && (granted.isEmpty() || granted.contains(SCOPE_DRIVE_READONLY))
            if (requestedReadonly && !hasReadonly) RingLog.w("drive", "grant lacks drive.readonly; granted ${granted.joinToString()}")
        }
        return token
    }

    /**
     * A usable token, or null when the user has to sign in again (no UI is shown here). When the
     * grant for both scopes needs the user, a drive.file-only token is tried silently and returned
     * with [hasReadonly] false. Never call on the main thread: it blocks on the Play Services task
     * for up to 20 s per request.
     */
    fun tokenBlocking(forceRefresh: Boolean = false): String? {
        val cached = cachedToken
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cachedAt < TOKEN_REUSE_MS) return cached
        return try {
            val result = Tasks.await(authorizeTask(), AWAIT_SECONDS, TimeUnit.SECONDS)
            if (!result.hasResolution()) {
                cacheFrom(result, requestedReadonly = true)
            } else {
                RingLog.w("drive", "authorisation for drive.readonly needs the user; trying drive.file alone")
                val fallback = Tasks.await(fileOnlyTask(), AWAIT_SECONDS, TimeUnit.SECONDS)
                if (fallback.hasResolution()) {
                    RingLog.w("drive", "authorisation needs the user; silent token refused")
                    hasReadonly = false
                    null
                } else {
                    cacheFrom(fallback, requestedReadonly = false)
                }
            }
        } catch (t: Throwable) {
            RingLog.w("drive", "silent authorisation failed: $t")
            null
        }
    }

    suspend fun token(forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) { tokenBlocking(forceRefresh) }

    /**
     * Drops a token Drive refused (401) so the next request asks Play Services for a fresh one.
     * Play Services caches the token it handed out, so the clear is awaited (off the main
     * thread) before anyone asks again; otherwise the forced refresh can return the same token.
     */
    fun invalidate(token: String?) {
        if (token == null || token == cachedToken) cachedToken = null
        cachedAt = 0L
        if (token == null) return
        try {
            val task = Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build())
            if (Looper.myLooper() != Looper.getMainLooper()) Tasks.await(task, CLEAR_SECONDS, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            RingLog.w("drive", "clearToken failed: $t")
        }
    }

    fun forget() {
        cachedToken = null
        cachedAt = 0L
        hasReadonly = false
    }
}

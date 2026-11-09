package app.lumen.player.remote

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.lumen.player.data.drive.DriveAuth
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.common.api.Scope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class YoutubeAuthorizationTest {
    @Test fun `YouTube and Drive both opt out of each others existing grants`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val youtube = YoutubeApi(context)
        val youtubeRequest = YoutubeApi::class.java.getDeclaredMethod("request", Boolean::class.javaPrimitiveType).let {
            it.isAccessible = true
            it.invoke(youtube, false) as AuthorizationRequest
        }
        val driveRequest = DriveAuth::class.java.getDeclaredMethod("request", List::class.java).let {
            it.isAccessible = true
            it.invoke(DriveAuth(context), listOf(Scope(DriveAuth.SCOPE_DRIVE_FILE))) as AuthorizationRequest
        }
        assertTrue(youtubeRequest.optOutIncludingGrantedScopes)
        assertTrue(driveRequest.optOutIncludingGrantedScopes)
        assertEquals(listOf(YoutubeApi.SCOPE), youtubeRequest.requestedScopes.map { it.scopeUri })
        assertEquals(listOf(DriveAuth.SCOPE_DRIVE_FILE), driveRequest.requestedScopes.map { it.scopeUri })
    }

    @Test fun `a Drive-only token cannot be accepted as a YouTube connection`() {
        val api = YoutubeApi(ApplicationProvider.getApplicationContext<Application>())
        val result = AuthorizationResult(null, null, "synthetic-test-token", listOf(DriveAuth.SCOPE_DRIVE_FILE), null, null)
        assertThrows(YoutubeApi.NeedsSignIn::class.java) { api.accept(result) }
    }

    @Test fun `combined scope errors explain the next action`() {
        val message = YoutubeApi.connectionError(IllegalStateException("scopes cannot be requested together: youtube.readonly, drive.file"))
        assertTrue(message.contains("Google Play services"))
        assertTrue(message.contains("Drive connection is kept"))
    }
}

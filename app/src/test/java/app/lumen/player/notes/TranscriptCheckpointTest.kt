package app.lumen.player.notes

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.lumen.player.data.SegmentEntity
import app.lumen.player.remote.YtmLink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TranscriptCheckpointTest {
    @Test fun `only the same recording and exact saved transcript can reuse completion`() {
        val media = YtmLink("aaaaaaaaaaa", false).toMediaEntity("Fixture", "", 1).copy(uri = "drive://fixture", sizeBytes = 100)
        val checkpoint = TranscriptCheckpoint(ApplicationProvider.getApplicationContext())
        val lines = listOf(SegmentEntity(mediaUri = media.uri, startMs = 0, text = "First"))
        checkpoint.save(media, listOf(0L to "First"), true)
        assertTrue(checkpoint.read(media, lines)!!.complete)
        assertTrue(checkpoint.read(media.copy(durationMs = 3_600_000, fingerprint = "duration-learned-on-playback"), lines)!!.complete)
        assertNull(checkpoint.read(media.copy(modifiedAt = 2), lines))
        assertFalse(checkpoint.read(media, lines + SegmentEntity(mediaUri = media.uri, startMs = 1000, text = "Another"))!!.complete)
    }
}

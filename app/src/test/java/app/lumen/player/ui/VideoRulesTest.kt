package app.lumen.player.ui

import android.app.Application
import android.util.Rational
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 0.10.0: the full-screen video player's rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VideoRulesTest {
    @Test fun `only a wide picture turns the screen sideways`() {
        assertTrue(VideoRules.isWide(1920, 1080))
        assertFalse(VideoRules.isWide(1080, 1920))
        assertFalse(VideoRules.isWide(0, 0))
    }

    @Test fun `picture in picture keeps the video's shape within Android's limits`() {
        assertEquals(Rational(1920, 1080), VideoRules.pipRatio(1920, 1080))
        assertEquals(Rational(239, 100), VideoRules.pipRatio(4000, 1000))
        assertEquals(Rational(100, 239), VideoRules.pipRatio(500, 2000))
        assertEquals(Rational(16, 9), VideoRules.pipRatio(0, 0))
    }

    @Test fun `subtitle tracks are named by label or language`() {
        fun group(format: Format, selected: Boolean) =
            Tracks.Group(TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
        val tracks = Tracks(listOf(
            group(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build(), true),
            group(Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).setLanguage("fr").build(), false),
            group(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setLabel("English (SDH)").setLanguage("en").build(), true),
            group(Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).setLanguage("und").build(), false),
        ))
        val text = VideoRules.textTracks(tracks)
        assertEquals(listOf("French", "English (SDH)", "Subtitles 3"), text.map { it.label })
        assertEquals(listOf(false, true, false), text.map { it.selected })
    }
}

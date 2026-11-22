package app.lumen.player.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 0.10.1: tap to skip on a cover, a sermon's title or a video. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w430dp-h932dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TapSkipTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `each tap adds a step, kept inside the item`() {
        assertEquals(70_000L, TapSkip.target(40_000, 200_000, 1, 3, 10))
        assertEquals(10_000L, TapSkip.target(40_000, 200_000, -1, 3, 10))
        assertEquals(0L, TapSkip.target(5_000, 200_000, -1, 2, 10))
        assertEquals(199_000L, TapSkip.target(190_000, 200_000, 1, 5, 10))
    }

    @Test fun `a running loop keeps tap skips inside it`() {
        assertEquals(42_000L, TapSkip.target(30_000, 200_000, 1, 3, 10, 12_000L..42_000L))
        assertEquals(12_000L, TapSkip.target(20_000, 200_000, -1, 2, 10, 12_000L..42_000L))
    }

    @Test fun `the total reads in seconds or minutes and the side follows the half tapped`() {
        assertEquals("+30 s", TapSkip.amount(1, 3, 10))
        assertEquals("-1 min 10 s", TapSkip.amount(-1, 7, 10))
        assertEquals("+1 min", TapSkip.amount(1, 2, 30))
        assertEquals(-1, TapSkip.sideOf(10f, 400))
        assertEquals(1, TapSkip.sideOf(300f, 400))
    }

    @Test fun `double-tap then two more taps on the right makes one seek of 30 s forward`() {
        val seeks = mutableListOf<Long>()
        val singles = mutableListOf<Unit>()
        compose.setContent {
            LumenTheme(dark = true) {
                ProvideTapSkip {
                    TapSkipZone(
                        enabled = true, stepSeconds = 10, position = { 60_000L }, duration = { 600_000L },
                        onSeek = { seeks += it }, onSingleTap = { singles += Unit },
                        modifier = Modifier.size(300.dp).testTag("cover"),
                    ) { Box(Modifier.size(300.dp)) }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        val zone = compose.onNodeWithTag("cover")
        repeat(4) {
            zone.performTouchInput { down(centerRight.copy(x = width * 0.8f)); up() }
            compose.mainClock.advanceTimeBy(120)
        }
        // The badge fades in, so its presence is checked, with where the skip lands.
        compose.onNodeWithText("+30 s").assertExists()
        compose.onNodeWithText("to 01:30").assertExists()
        assertTrue("No seek before the tapping stops", seeks.isEmpty())
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle {
            assertEquals(listOf(90_000L), seeks)
            assertTrue("A double-tap is no single tap", singles.isEmpty())
        }
    }

    @Test fun `one tap alone does the single-tap action and never seeks`() {
        val seeks = mutableListOf<Long>()
        var singles = 0
        compose.setContent {
            LumenTheme(dark = true) {
                TapSkipZone(
                    enabled = true, stepSeconds = 10, position = { 60_000L }, duration = { 600_000L },
                    onSeek = { seeks += it }, onSingleTap = { singles++ },
                    modifier = Modifier.size(300.dp).testTag("video"),
                ) { Box(Modifier.size(300.dp)) }
            }
        }
        compose.onNodeWithTag("video").performTouchInput { down(centerLeft.copy(x = width * 0.2f)); up() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle {
            assertEquals(1, singles)
            assertTrue(seeks.isEmpty())
        }
    }
}

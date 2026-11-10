package app.lumen.player.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w430dp-h932dp-xhdpi")
class PlayerDismissTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `scrolling content back towards its top does not dismiss player`() {
        var dismissed = 0
        compose.setContent {
            LumenTheme(dark = true) {
                Box(Modifier.fillMaxSize().dismissPlayerOnPull(true) { dismissed++ }) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        repeat(50) { Text("Transcript line $it", Modifier.height(50.dp)) }
                    }
                }
            }
        }
        compose.onRoot().performTouchInput { swipeUp(durationMillis = 600) }
        compose.onRoot().performTouchInput { swipe(Offset(center.x, 300f), Offset(center.x, 550f), 600) }
        compose.runOnIdle { assertEquals(0, dismissed) }
    }

    @Test fun `horizontal seeker drag does not dismiss`() {
        var dismissed = 0
        var seeks = 0
        compose.setContent {
            LumenTheme(dark = true) {
                Column(Modifier.fillMaxSize().dismissPlayerOnPull(true) { dismissed++ }) {
                    Scrubber(20_000, 100_000, true, true, onSeek = { seeks++ })
                }
            }
        }
        compose.onNodeWithContentDescription("Playback position").performTouchInput { swipe(centerLeft, centerRight, 600) }
        compose.runOnIdle { assertEquals(0, dismissed); assertEquals(1, seeks) }
    }
}

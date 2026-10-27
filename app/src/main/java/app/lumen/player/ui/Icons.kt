package app.lumen.player.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The icon set from DESIGN.md section 4: 24 dp grid, 1.75 dp stroke, round caps and joins,
 * outline except play, pause, skip and the saved bookmark. Drawn here so no icon library ships.
 * The black used below is a placeholder that Icon() replaces with its tint.
 */
object LumenIcons {

    private const val STROKE = 1.75f

    private fun icon(name: String, build: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply(build)
            .build()

    private fun ImageVector.Builder.stroke(block: PathBuilder.() -> Unit) {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    private fun ImageVector.Builder.fill(block: PathBuilder.() -> Unit) {
        path(fill = SolidColor(Color.Black), pathBuilder = block)
    }

    val Home: ImageVector by lazy {
        icon("home") {
            stroke { moveTo(4f, 10.5f); lineTo(12f, 4f); lineTo(20f, 10.5f) }
            stroke { moveTo(6f, 9.5f); lineTo(6f, 20f); lineTo(18f, 20f); lineTo(18f, 9.5f) }
            stroke { moveTo(10f, 20f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 20f) }
        }
    }

    val Library: ImageVector by lazy {
        icon("library") {
            stroke { moveTo(4f, 5f); lineTo(4f, 19f) }
            stroke { moveTo(9f, 5f); lineTo(9f, 19f) }
            stroke { moveTo(13.5f, 5.5f); lineTo(19f, 18.5f) }
        }
    }

    val Gear: ImageVector by lazy {
        icon("gear") {
            stroke {
                moveTo(12f, 9f)
                arcTo(3f, 3f, 0f, true, true, 11.99f, 9f)
            }
            stroke {
                moveTo(10.3f, 3.5f); lineTo(13.7f, 3.5f); lineTo(14.3f, 5.9f); lineTo(16.4f, 7.1f); lineTo(18.8f, 6.4f)
                lineTo(20.5f, 9.4f); lineTo(18.7f, 11.1f); lineTo(18.7f, 12.9f); lineTo(20.5f, 14.6f); lineTo(18.8f, 17.6f)
                lineTo(16.4f, 16.9f); lineTo(14.3f, 18.1f); lineTo(13.7f, 20.5f); lineTo(10.3f, 20.5f); lineTo(9.7f, 18.1f)
                lineTo(7.6f, 16.9f); lineTo(5.2f, 17.6f); lineTo(3.5f, 14.6f); lineTo(5.3f, 12.9f); lineTo(5.3f, 11.1f)
                lineTo(3.5f, 9.4f); lineTo(5.2f, 6.4f); lineTo(7.6f, 7.1f); lineTo(9.7f, 5.9f); close()
            }
        }
    }

    val ChevronDown: ImageVector by lazy { icon("chevron-down") { stroke { moveTo(6f, 9.5f); lineTo(12f, 15.5f); lineTo(18f, 9.5f) } } }
    val ChevronRight: ImageVector by lazy { icon("chevron-right") { stroke { moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f) } } }
    val ChevronLeft: ImageVector by lazy { icon("chevron-left") { stroke { moveTo(14.5f, 6f); lineTo(8.5f, 12f); lineTo(14.5f, 18f) } } }
    val Close: ImageVector by lazy { icon("close") { stroke { moveTo(6f, 6f); lineTo(18f, 18f) }; stroke { moveTo(18f, 6f); lineTo(6f, 18f) } } }
    val Check: ImageVector by lazy { icon("check") { stroke { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) } } }
    val Plus: ImageVector by lazy { icon("plus") { stroke { moveTo(12f, 5f); lineTo(12f, 19f) }; stroke { moveTo(5f, 12f); lineTo(19f, 12f) } } }

    val Search: ImageVector by lazy {
        icon("search") {
            stroke { moveTo(10.5f, 4f); arcTo(6.5f, 6.5f, 0f, true, true, 10.49f, 4f) }
            stroke { moveTo(15.5f, 15.5f); lineTo(20f, 20f) }
        }
    }

    val Folder: ImageVector by lazy {
        icon("folder") {
            stroke {
                moveTo(3.5f, 7f); lineTo(3.5f, 18.5f); lineTo(20.5f, 18.5f); lineTo(20.5f, 8.5f); lineTo(12f, 8.5f)
                lineTo(10f, 5.5f); lineTo(3.5f, 5.5f); close()
            }
        }
    }

    /** A MediaStore bucket in the Library: the phone itself. */
    val Phone: ImageVector by lazy {
        icon("phone") {
            stroke {
                moveTo(7.5f, 3.5f); lineTo(16.5f, 3.5f); arcTo(1.5f, 1.5f, 0f, false, true, 18f, 5f)
                lineTo(18f, 19f); arcTo(1.5f, 1.5f, 0f, false, true, 16.5f, 20.5f); lineTo(7.5f, 20.5f)
                arcTo(1.5f, 1.5f, 0f, false, true, 6f, 19f); lineTo(6f, 5f); arcTo(1.5f, 1.5f, 0f, false, true, 7.5f, 3.5f); close()
            }
            stroke { moveTo(10.5f, 17.5f); lineTo(13.5f, 17.5f) }
        }
    }

    /** Speech: a microphone. */
    val Speech: ImageVector by lazy {
        icon("speech") {
            stroke { moveTo(12f, 3.5f); arcTo(3f, 3f, 0f, false, true, 15f, 6.5f); lineTo(15f, 12f); arcTo(3f, 3f, 0f, true, true, 9f, 12f); lineTo(9f, 6.5f); arcTo(3f, 3f, 0f, false, true, 12f, 3.5f); close() }
            stroke { moveTo(6f, 11.5f); arcTo(6f, 6f, 0f, false, false, 18f, 11.5f) }
            stroke { moveTo(12f, 17.5f); lineTo(12f, 20.5f) }
            stroke { moveTo(9f, 20.5f); lineTo(15f, 20.5f) }
        }
    }

    /** Music: a note. */
    val Music: ImageVector by lazy {
        icon("music") {
            stroke { moveTo(9f, 17.5f); lineTo(9f, 5.5f); lineTo(19f, 3.5f); lineTo(19f, 15.5f) }
            stroke { moveTo(9f, 17.5f); arcTo(2.5f, 2.5f, 0f, true, true, 8.99f, 17.5f) }
            stroke { moveTo(19f, 15.5f); arcTo(2.5f, 2.5f, 0f, true, true, 18.99f, 15.5f) }
        }
    }

    val Video: ImageVector by lazy {
        icon("video") {
            stroke { moveTo(3.5f, 7f); lineTo(3.5f, 17f); lineTo(15.5f, 17f); lineTo(15.5f, 7f); close() }
            stroke { moveTo(15.5f, 10.5f); lineTo(20.5f, 8f); lineTo(20.5f, 16f); lineTo(15.5f, 13.5f) }
        }
    }

    val Bookmark: ImageVector by lazy {
        icon("bookmark") { stroke { moveTo(6.5f, 4f); lineTo(17.5f, 4f); lineTo(17.5f, 20f); lineTo(12f, 15.5f); lineTo(6.5f, 20f); close() } }
    }

    val BookmarkFilled: ImageVector by lazy {
        icon("bookmark-filled") { fill { moveTo(6.5f, 4f); lineTo(17.5f, 4f); lineTo(17.5f, 20f); lineTo(12f, 15.5f); lineTo(6.5f, 20f); close() } }
    }

    val Lock: ImageVector by lazy {
        icon("lock") {
            stroke { moveTo(5.5f, 10.5f); lineTo(18.5f, 10.5f); lineTo(18.5f, 20f); lineTo(5.5f, 20f); close() }
            stroke { moveTo(8.5f, 10.5f); lineTo(8.5f, 7.5f); arcTo(3.5f, 3.5f, 0f, false, true, 15.5f, 7.5f); lineTo(15.5f, 10.5f) }
        }
    }

    val Moon: ImageVector by lazy {
        icon("moon") {
            stroke { moveTo(14f, 3.5f); arcTo(8.5f, 8.5f, 0f, true, false, 20.5f, 13f); arcTo(6.5f, 6.5f, 0f, false, true, 14f, 3.5f); close() }
        }
    }

    val Shuffle: ImageVector by lazy {
        icon("shuffle") {
            stroke { moveTo(3.5f, 7f); lineTo(7f, 7f); lineTo(16f, 17f); lineTo(20.5f, 17f) }
            stroke { moveTo(3.5f, 17f); lineTo(7f, 17f); lineTo(16f, 7f); lineTo(20.5f, 7f) }
            stroke { moveTo(18f, 4.5f); lineTo(20.5f, 7f); lineTo(18f, 9.5f) }
            stroke { moveTo(18f, 14.5f); lineTo(20.5f, 17f); lineTo(18f, 19.5f) }
        }
    }

    val Repeat: ImageVector by lazy {
        icon("repeat") {
            stroke { moveTo(4f, 11f); lineTo(4f, 9f); arcTo(2f, 2f, 0f, false, true, 6f, 7f); lineTo(20f, 7f) }
            stroke { moveTo(17.5f, 4.5f); lineTo(20f, 7f); lineTo(17.5f, 9.5f) }
            stroke { moveTo(20f, 13f); lineTo(20f, 15f); arcTo(2f, 2f, 0f, false, true, 18f, 17f); lineTo(4f, 17f) }
            stroke { moveTo(6.5f, 14.5f); lineTo(4f, 17f); lineTo(6.5f, 19.5f) }
        }
    }

    val SkipPrevious: ImageVector by lazy {
        icon("skip-previous") {
            fill { moveTo(6f, 5f); lineTo(8.5f, 5f); lineTo(8.5f, 19f); lineTo(6f, 19f); close() }
            fill { moveTo(19f, 5f); lineTo(19f, 19f); lineTo(9.5f, 12f); close() }
        }
    }

    val SkipNext: ImageVector by lazy {
        icon("skip-next") {
            fill { moveTo(15.5f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(15.5f, 19f); close() }
            fill { moveTo(5f, 5f); lineTo(14.5f, 12f); lineTo(5f, 19f); close() }
        }
    }

    val Play: ImageVector by lazy { icon("play") { fill { moveTo(7f, 4f); lineTo(20f, 12f); lineTo(7f, 20f); close() } } }

    val Pause: ImageVector by lazy {
        icon("pause") {
            fill { moveTo(6f, 4f); lineTo(10f, 4f); lineTo(10f, 20f); lineTo(6f, 20f); close() }
            fill { moveTo(14f, 4f); lineTo(18f, 4f); lineTo(18f, 20f); lineTo(14f, 20f); close() }
        }
    }

    val Trash: ImageVector by lazy {
        icon("trash") {
            stroke { moveTo(4.5f, 6.5f); lineTo(19.5f, 6.5f) }
            stroke { moveTo(9.5f, 6.5f); lineTo(9.5f, 4f); lineTo(14.5f, 4f); lineTo(14.5f, 6.5f) }
            stroke { moveTo(6.5f, 6.5f); lineTo(7.5f, 20f); lineTo(16.5f, 20f); lineTo(17.5f, 6.5f) }
        }
    }

    /** The Drive folder in the Library: a cloud. */
    val Cloud: ImageVector by lazy {
        icon("cloud") {
            stroke {
                moveTo(7f, 18.5f); lineTo(17.5f, 18.5f); arcTo(4f, 4f, 0f, false, false, 17.5f, 10.5f)
                lineTo(16.9f, 10.5f); arcTo(5.5f, 5.5f, 0f, false, false, 6.2f, 11.7f)
                arcTo(3.4f, 3.4f, 0f, false, false, 7f, 18.5f); close()
            }
        }
    }

    /** "Keep offline": an arrow into a tray. */
    val Download: ImageVector by lazy {
        icon("download") {
            stroke { moveTo(12f, 4f); lineTo(12f, 15f) }
            stroke { moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f) }
            stroke { moveTo(4.5f, 19.5f); lineTo(19.5f, 19.5f) }
        }
    }

    /** A kept copy: the tray with a tick. */
    val Downloaded: ImageVector by lazy {
        icon("downloaded") {
            stroke { moveTo(6.5f, 10.5f); lineTo(10.5f, 14.5f); lineTo(17.5f, 6.5f) }
            stroke { moveTo(4.5f, 19.5f); lineTo(19.5f, 19.5f) }
        }
    }

    val Label: ImageVector by lazy {
        icon("label") {
            stroke { moveTo(4f, 4f); lineTo(11f, 4f); lineTo(20f, 13f); lineTo(13f, 20f); lineTo(4f, 11f); close() }
            fill { moveTo(8f, 6.75f); arcTo(1.25f, 1.25f, 0f, true, true, 7.99f, 6.75f); close() }
        }
    }

    /** Overflow: three filled dots, vertical, for the chapter list's menu. */
    val More: ImageVector by lazy {
        icon("more") {
            for (y in listOf(5.5f, 12f, 18.5f)) {
                fill { moveTo(10.5f, y); arcTo(1.5f, 1.5f, 0f, true, true, 13.5f, y); arcTo(1.5f, 1.5f, 0f, true, true, 10.5f, y); close() }
            }
        }
    }
}

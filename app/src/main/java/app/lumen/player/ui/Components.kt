package app.lumen.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Layout tokens from DESIGN.md section 4.
val Gutter = 20.dp
val CardRadius = 14.dp
val HeroRadius = 22.dp
val RowHeight = 64.dp
val TouchTarget = 48.dp

/** Mono section label such as "CONTINUE" or "RECENT 8". */
@Composable
fun SectionLabel(text: String, count: String? = null, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val c = Lumen.colours
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = Lumen.type.label, color = c.text2)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(count, style = Lumen.type.label, color = c.text3)
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** White card with a 1 dp line on light screens; a surface step on black. */
@Composable
fun ListCard(modifier: Modifier = Modifier, radius: Dp = CardRadius, content: @Composable () -> Unit) {
    val c = Lumen.colours
    val shape = RoundedCornerShape(radius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, if (c.isDark) c.surface else c.line, shape),
    ) {
        content()
    }
}

@Composable
fun HueDot(hue: Int, size: Dp = 6.dp, colour: Color = hueColour(hue)) {
    Box(Modifier.size(size).background(colour, CircleShape))
}

/**
 * A label as a text chip with its 6 dp hue dot. Selected: a charcoal chip with cyan text on
 * light (the dot takes its light tint so it reads on charcoal); a cyan chip with black text on
 * black (the dot takes its deep hue, the better of the two on cyan: 2.93:1 for amber to 4.10:1
 * for blue-slate, against 1.09 to 1.30:1 for the light tints).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LabelChip(name: String, hue: Int, selected: Boolean = false, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val c = Lumen.colours
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .height(TouchTarget)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(shape)
                .background(if (selected) c.chipSelected else if (c.isDark) Color.Transparent else c.surface)
                .border(1.dp, if (selected) c.chipSelectedEdge else c.line, shape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The selected fill inverts the ground: charcoal on light, cyan on black.
            HueDot(hue, colour = hueColour(hue, onDarkGround = if (selected) !c.isDark else c.isDark))
            Text(name.uppercase(), style = Lumen.type.label, color = if (selected) c.onChipSelected else c.text, maxLines = 1)
        }
    }
}

/**
 * Speed, preset, sleep, bookmark: rest, active, disabled with a reason. Active is secondary
 * state: charcoal with cyan on light, an ice border and ice text on black. [onLongClick] (0.4.0)
 * gives the chip a second action, such as a chapter on the bookmark chip.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatusChip(
    text: String,
    icon: ImageVector? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = Lumen.colours
    val shape = RoundedCornerShape(50)
    val tint = when {
        !enabled -> c.text3
        active -> c.onChipActive
        else -> c.text
    }
    // A disabled chip on light drops to the rest look (text3 on charcoal would not read); on
    // black an active chip keeps its ice border even when disabled.
    val activeLook = active && (enabled || c.isDark)
    Box(
        modifier = Modifier
            .height(TouchTarget)
            .clip(shape)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(shape)
                .background(if (activeLook) c.chipActive else if (c.isDark) Color.Transparent else c.surface)
                .border(1.dp, if (activeLook) c.chipActiveEdge else c.line, shape)
                .padding(horizontal = if (icon != null) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(text.uppercase(), style = Lumen.type.label, color = tint, maxLines = 1)
        }
    }
}

/**
 * 64 dp row: 48 dp thumbnail, title, meta, right-aligned mono time. [dimmed] greys a row that
 * cannot be reached (a Drive file while offline); [progress] draws a 1 dp line under the row
 * while an offline copy is downloading.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaRow(
    title: String,
    meta: String,
    time: String,
    icon: ImageVector,
    playing: Boolean,
    copies: Int = 1,
    hues: List<Int> = emptyList(),
    divider: Boolean = true,
    dimmed: Boolean = false,
    progress: Float? = null,
    /** 0.5.0: a small source chip after the meta, such as "via YouTube Music". */
    badge: String? = null,
    /** 0.8.2: how far through a started item is (a thin bar under the meta); null hides it. */
    listened: Float? = null,
    /** 0.8.2: listened to the end: a tick on the thumbnail. */
    finished: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val c = Lumen.colours
    val titleColour = if (dimmed) c.text3 else c.text
    val metaColour = if (dimmed) c.text3 else c.text2
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(RowHeight)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(48.dp).background(c.thumb, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (finished) LumenIcons.Check else icon, contentDescription = if (finished) "Played" else null, tint = if (finished) c.accentInk else metaColour, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playing) {
                        Box(Modifier.size(6.dp).background(c.accentInk, CircleShape))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(title, style = Lumen.type.bodyStrong, color = titleColour, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    hues.take(3).forEach { HueDot(it) }
                    if (badge != null) {
                        Text(
                            badge.uppercase(),
                            style = Lumen.type.labelS,
                            color = metaColour,
                            maxLines = 1,
                            modifier = Modifier
                                .border(1.dp, c.line, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(meta, style = Lumen.type.bodyS, color = metaColour, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (copies > 1) {
                        Text(
                            "x$copies",
                            style = Lumen.type.labelS,
                            color = metaColour,
                            modifier = Modifier
                                .border(1.dp, c.line, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                if (listened != null) {
                    Spacer(Modifier.height(4.dp))
                    ProgressLine(fraction = listened, thickness = 2.dp, modifier = Modifier.fillMaxWidth(0.6f))
                }
            }
            Text(time, style = Lumen.type.timeS, color = if (playing) c.accentInk else metaColour)
        }
        if (progress != null) {
            ProgressLine(fraction = progress, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
        }
        if (divider) HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
    }
}

/**
 * 52 dp pill: charcoal with a cyan glyph and label on light screens, cyan with a black glyph and
 * label on black. Disabled on light, the label drops to text2 so a grey pill never carries the
 * cyan light (which reads as live); on black the same text2 label sits on the line-grey fill.
 */
@Composable
fun PrimaryPill(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Lumen.colours
    val fg = if (!enabled) c.text2 else c.onAccentFill
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) c.accentFill else c.line)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = Lumen.type.bodyStrong, color = fg)
    }
}

/** 52 dp outlined pill beside a PrimaryPill: text colour, 1 dp line, no fill. */
@Composable
fun SecondaryPill(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Lumen.colours
    val shape = RoundedCornerShape(50)
    val tint = if (enabled) c.text else c.text3
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .border(1.dp, c.line, shape)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = Lumen.type.bodyStrong, color = tint)
    }
}

/**
 * Lets a long list live as separate lazy items while still reading as one ListCard: each row
 * paints the surface, the first and last rows round their outer corners, and the 1 dp card line
 * is drawn per row (sides for every row, top or bottom edge only where the card starts or ends).
 */
@Composable
fun Modifier.cardSegment(first: Boolean, last: Boolean): Modifier {
    val c = Lumen.colours
    val radius = CardRadius
    val shape = when {
        first && last -> RoundedCornerShape(radius)
        first -> RoundedCornerShape(topStart = radius, topEnd = radius)
        last -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
        else -> RectangleShape
    }
    val lineColour = if (c.isDark) c.surface else c.line
    return this
        .clip(shape)
        .background(c.surface)
        .drawBehind {
            val strokeWidth = 1.dp.toPx()
            val r = radius.toPx()
            val inset = strokeWidth / 2f
            // A rounded rectangle taller than the row, placed so only the wanted edges fall
            // inside the clip: the top one for the first row, the bottom one for the last,
            // neither for a row in the middle (its sides still show).
            val top = if (first) inset else -size.height
            val bottom = if (last) size.height - inset else size.height * 2f
            drawRoundRect(
                color = lineColour,
                topLeft = Offset(inset, top),
                size = Size(size.width - strokeWidth, bottom - top),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = strokeWidth),
            )
        }
}

/** Stop point on the Night Home Continue line: an amber dot inside a black ring (DARK-OPTIONS C3). */
private val StopDot = 10.dp
private val StopRing = 3.dp

/**
 * 2 dp progress line used on the Continue card: ink fill on the divider track. [emberStop] ends
 * the fill with the amber stop point, set apart from the cyan by a ring of the base colour; the
 * caller shows it only on black and only while Lumen is holding a speech item's place. The dot
 * is drawn over the line and overhangs it, so it takes no layout space.
 */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, thickness: Dp = 2.dp, emberStop: Boolean = false) {
    val c = Lumen.colours
    val f = fraction.coerceIn(0f, 1f)
    val stopModifier = if (emberStop) {
        Modifier.drawWithContent {
            drawContent()
            val centre = Offset(size.width * f, size.height / 2f)
            drawCircle(color = c.base, radius = (StopDot / 2 + StopRing).toPx(), center = centre)
            drawCircle(color = c.ember, radius = (StopDot / 2).toPx(), center = centre)
        }
    } else {
        Modifier
    }
    Box(modifier.fillMaxWidth().height(thickness).then(stopModifier).background(c.divider, RoundedCornerShape(1.dp))) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .height(thickness)
                .background(c.accentInk, RoundedCornerShape(1.dp)),
        )
    }
}

/** The held head on a paused speech item: a 16 dp amber dot in a 3 dp black ring (DESIGN.md round 5, C). */
private val HeadRest = 14.dp
private val HeadHeld = 16.dp
private val HeadDragged = 22.dp
private val HeadRing = 3.dp

/**
 * The scrubber: a Material Slider with a thin track (6 dp speech, 3 dp music), ink fill and a
 * white handle that grows from 14 to 22 dp on touch. The seek fires on release, never during.
 * [holdingPlace] (a paused speech item) turns the head into the ember: 16 dp amber inside a 3 dp
 * ring of the base colour, back to white within 120 ms of play.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    thin: Boolean,
    modifier: Modifier = Modifier,
    holdingPlace: Boolean = false,
    onSeek: (Long) -> Unit,
) {
    val c = Lumen.colours
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = durationMs.coerceAtLeast(0L)
    val shown = if (dragging) dragValue else positionMs.toFloat()
    val range = 0f..(if (duration > 0) duration.toFloat() else 1f)
    val interaction = remember { MutableInteractionSource() }
    val isDragged by interaction.collectIsDraggedAsState()
    val ember = holdingPlace && enabled
    val thumbSize by animateDpAsState(targetValue = if (isDragged) HeadDragged else if (ember) HeadHeld else HeadRest, animationSpec = tween(120), label = "thumb")
    val headColour by animateColorAsState(targetValue = if (!enabled) c.line else if (ember) c.ember else c.text, animationSpec = tween(120), label = "head")
    val ring by animateDpAsState(targetValue = if (ember) HeadRing else 0.dp, animationSpec = tween(120), label = "ring")
    val trackHeight = if (thin) 3.dp else 6.dp

    Column(modifier.fillMaxWidth()) {
        Slider(
            value = shown.coerceIn(range),
            onValueChange = { v ->
                dragging = true
                dragValue = v
            },
            onValueChangeFinished = {
                dragging = false
                onSeek(dragValue.toLong())
            },
            enabled = enabled && duration > 0,
            interactionSource = interaction,
            valueRange = range,
            thumb = {
                Box(
                    Modifier
                        .size(HeadDragged),
                    contentAlignment = Alignment.Center,
                ) {
                    // The ring may overhang the 22 dp slot while dragging, so it ignores the
                    // slot's constraints rather than squeezing the dot.
                    Box(
                        Modifier
                            .requiredSize(thumbSize + ring * 2)
                            .then(if (ring > 0.dp) Modifier.background(c.base, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(thumbSize).background(headColour, CircleShape))
                    }
                }
            },
            track = { state: SliderState ->
                val span = (state.valueRange.endInclusive - state.valueRange.start).takeIf { it > 0f } ?: 1f
                val fraction = ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(trackHeight).background(c.line, RoundedCornerShape(3.dp))) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(trackHeight)
                            .background(if (enabled) c.accentInk else c.line, RoundedCornerShape(3.dp)),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().height(TouchTarget),
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(formatTime(shown.toLong()), style = if (thin) Lumen.type.timeS else Lumen.type.timeL, color = if (thin) c.text2 else c.text)
            Spacer(Modifier.weight(1f))
            Text(if (duration > 0) formatTime(duration) else "--:--", style = Lumen.type.timeS, color = c.text3)
            Spacer(Modifier.weight(1f))
            Text(
                if (duration > 0) "-" + formatTime((duration - shown.toLong()).coerceAtLeast(0L)) else "--:--",
                style = if (thin) Lumen.type.timeS else Lumen.type.timeL,
                color = if (thin) c.text2 else c.text,
            )
        }
    }
}

/** Filled circle in the accent fill with the play or pause glyph on it (cyan and black on black). */
@Composable
fun PlayButton(isPlaying: Boolean, enabled: Boolean, size: Dp = 72.dp, onClick: () -> Unit) {
    val c = Lumen.colours
    val description = if (isPlaying) "Pause" else "Play"
    Box(
        modifier = Modifier
            .size(size)
            .background(if (enabled) c.accentFill else c.line, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (isPlaying) LumenIcons.Pause else LumenIcons.Play, contentDescription = null, tint = c.onAccentFill, modifier = Modifier.size(32.dp))
    }
}

/** Ring button for back and forward with the number of seconds inside. */
@Composable
fun SkipButton(seconds: Int, forward: Boolean, enabled: Boolean, size: Dp = 64.dp, onClick: () -> Unit) {
    val c = Lumen.colours
    val tint = if (enabled) c.text else c.text3
    val description = if (forward) "Forward $seconds seconds" else "Back $seconds seconds"
    Box(
        modifier = Modifier
            .size(size)
            .border(1.dp, c.line, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (forward) LumenIcons.ChevronRight else LumenIcons.ChevronLeft, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(seconds.toString(), style = Lumen.type.timeS, color = tint)
        }
    }
}

/** Icon button with a 48 dp touch target. */
@Composable
fun IconTap(icon: ImageVector, description: String, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(TouchTarget)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size))
    }
}

/** Two-item bottom bar: Home, Library. Never animates. */
@Composable
fun BottomBar(current: Screen, onSelect: (Screen) -> Unit) {
    val c = Lumen.colours
    Column(Modifier.fillMaxWidth().background(if (c.isDark) c.base else c.surface)) {
        HorizontalDivider(color = c.line, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp)
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarItem("Home", LumenIcons.Home, current == Screen.Home) { onSelect(Screen.Home) }
            BarItem("Library", LumenIcons.Library, current == Screen.Library) { onSelect(Screen.Library) }
            BarItem("Playlists", LumenIcons.Playlist, current == Screen.Playlists) { onSelect(Screen.Playlists) }
        }
    }
}

/**
 * One tab. The icon sits in a 56 by 30 dp capsule when selected (both tabs carry the capsule box
 * so the icons line up): charcoal with a cyan icon on light, cyan with a black icon on black.
 */
@Composable
private fun BarItem(text: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val c = Lumen.colours
    val iconTint = if (selected) c.onNavSelected else c.text3
    val labelTint = if (selected) c.navSelectedLabel else c.text3
    Column(
        modifier = Modifier
            .size(width = 96.dp, height = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 30.dp)
                .background(if (selected) c.navSelected else Color.Transparent, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = text, tint = iconTint)
        }
        Spacer(Modifier.height(4.dp))
        Text(text.uppercase(), style = Lumen.type.labelS, color = labelTint)
    }
}

/**
 * Mini bar above the bottom bar: current item, mono time line, play or pause. On light it is a
 * charcoal strip with an off-white title, the position [time] in cyan and a cyan play disc; on
 * black it is the elevated surface with the time in cyan and a cyan disc with a black glyph.
 * [meta] follows the time.
 */
@Composable
fun MiniBar(title: String, time: String, meta: String, isPlaying: Boolean, onOpen: () -> Unit, onPlayPause: () -> Unit) {
    val c = Lumen.colours
    val shape = RoundedCornerShape(CardRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(RowHeight)
            .clip(shape)
            .background(c.miniBar)
            .border(1.dp, c.miniBarEdge, shape)
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Lumen.type.bodyStrong, color = c.onMiniBar, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.miniBarTime)) { append(time) }
                    append(" · ")
                    append(meta)
                },
                style = Lumen.type.timeS,
                color = c.onMiniBar2,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(TouchTarget)
                .clip(CircleShape)
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(40.dp).background(c.miniPlay, CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    if (isPlaying) LumenIcons.Pause else LumenIcons.Play,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = c.onMiniPlay,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Empty state: what happened and what to do next. One primary action, an optional outlined second. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
    secondaryAction: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    notice: (@Composable () -> Unit)? = null,
) {
    val c = Lumen.colours
    ListCard(radius = HeroRadius) {
        Column(Modifier.padding(Gutter)) {
            Text(title, style = Lumen.type.displayS, color = c.text)
            Spacer(Modifier.height(6.dp))
            Text(body, style = Lumen.type.bodyS, color = c.text2)
            Spacer(Modifier.height(16.dp))
            PrimaryPill(action, enabled = actionEnabled, onClick = onAction)
            if (secondaryAction != null && onSecondaryAction != null) {
                Spacer(Modifier.height(10.dp))
                SecondaryPill(secondaryAction, onClick = onSecondaryAction)
            }
            if (notice != null) {
                Spacer(Modifier.height(12.dp))
                notice()
            }
        }
    }
}

/** The one quiet line shown after Android refused the media permission, with the way to fix it. */
@Composable
fun PermissionNotice(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val c = Lumen.colours
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Lumen needs access to audio and video files to scan the phone",
            style = Lumen.type.bodyS,
            color = c.text2,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextAction("Open settings", onClick = onOpenSettings)
    }
}

/** One settings or sheet toggle with a one-line explanation. */
@Composable
fun ToggleRow(title: String, explanation: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = Lumen.colours
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Lumen.type.body, color = if (enabled) c.text else c.text3)
            if (explanation != null) Text(explanation, style = Lumen.type.bodyS, color = c.text2)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccentFill,
                checkedTrackColor = c.accentFill,
                checkedBorderColor = c.accentFill,
                uncheckedThumbColor = c.switchOffThumb,
                uncheckedTrackColor = c.surface,
                uncheckedBorderColor = c.switchOffEdge,
            ),
        )
    }
}

/**
 * 48 dp search field with the search glyph; filters as you type. On light, focus draws a 1.5 dp
 * ink border; on black the field keeps its 0.3.0 look (no border).
 */
@Composable
fun SearchField(value: String, placeholder: String, onChange: (String) -> Unit) {
    val c = Lumen.colours
    val shape = RoundedCornerShape(CardRadius)
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TouchTarget)
            .clip(shape)
            .background(c.surface)
            .then(
                when {
                    c.isDark -> Modifier.border(1.dp, c.surface, shape)
                    focused -> Modifier.border(1.5.dp, c.accentInk, shape)
                    else -> Modifier.border(1.dp, c.line, shape)
                },
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(LumenIcons.Search, contentDescription = null, tint = c.text3, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = Lumen.type.body, color = c.text3)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Lumen.type.body.copy(color = c.text),
                cursorBrush = SolidColor(c.accentInk),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        if (value.isNotEmpty()) {
            IconTap(LumenIcons.Close, "Clear search", c.text2, size = 18.dp) { onChange("") }
        }
    }
}

/** A plain text action set in body weight 600, 48 dp tall. */
@Composable
fun TextAction(text: String, modifier: Modifier = Modifier, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Lumen.colours
    Box(
        modifier = modifier
            .heightIn(min = TouchTarget)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Lumen.type.bodyStrong, color = if (!enabled) c.text3 else if (danger) c.danger else c.accentInk)
    }
}

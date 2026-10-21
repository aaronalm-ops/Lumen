package app.lumen.player.ui

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
fun HueDot(hue: Int, size: Dp = 6.dp) {
    Box(Modifier.size(size).background(hueColour(hue), CircleShape))
}

/** A label as a text chip with its 6 dp hue dot. */
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
                .background(if (c.isDark) Color.Transparent else c.surface)
                .border(1.dp, if (selected) c.silver else c.line, shape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HueDot(hue)
            Text(name.uppercase(), style = Lumen.type.label, color = if (selected) c.silver else c.text, maxLines = 1)
        }
    }
}

/** Speed, preset, sleep, bookmark: rest, active (silver), disabled with a reason. */
@Composable
fun StatusChip(
    text: String,
    icon: ImageVector? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Lumen.colours
    val shape = RoundedCornerShape(50)
    val tint = when {
        !enabled -> c.text3
        active -> c.silver
        else -> c.text
    }
    Box(
        modifier = Modifier
            .height(TouchTarget)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(shape)
                .background(if (c.isDark) Color.Transparent else c.surface)
                .border(1.dp, if (active) c.silver else c.line, shape)
                .padding(horizontal = if (icon != null) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(text.uppercase(), style = Lumen.type.label, color = tint, maxLines = 1)
        }
    }
}

/** 64 dp row: 48 dp thumbnail, title, meta, right-aligned mono time. */
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
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val c = Lumen.colours
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
                modifier = Modifier.size(48.dp).background(if (c.isDark) c.elevated else c.base, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = c.text2, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playing) {
                        Box(Modifier.size(6.dp).background(c.accent, CircleShape))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(title, style = Lumen.type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    hues.take(3).forEach { HueDot(it) }
                    Text(meta, style = Lumen.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (copies > 1) {
                        Text(
                            "x$copies",
                            style = Lumen.type.labelS,
                            color = c.text2,
                            modifier = Modifier
                                .border(1.dp, c.line, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(time, style = Lumen.type.timeS, color = if (playing) c.accent else c.text2)
        }
        if (divider) HorizontalDivider(color = c.line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
    }
}

/** 52 dp pill: amber with white text on light screens, amber with black glyph on black. */
@Composable
fun PrimaryPill(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Lumen.colours
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) c.accent else c.line)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = Lumen.type.bodyStrong, color = c.onAccent)
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

/** 2 dp progress line used on the Continue card. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, thickness: Dp = 2.dp) {
    val c = Lumen.colours
    Box(modifier.fillMaxWidth().height(thickness).background(c.line, RoundedCornerShape(1.dp))) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(thickness)
                .background(c.accent, RoundedCornerShape(1.dp)),
        )
    }
}

/**
 * The scrubber: a Material Slider with a thin track (6 dp speech, 3 dp music), amber fill and a
 * white handle that grows from 14 to 22 dp on touch. The seek fires on release, never during.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    thin: Boolean,
    modifier: Modifier = Modifier,
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
    val thumbSize by animateDpAsState(targetValue = if (isDragged) 22.dp else 14.dp, animationSpec = tween(120), label = "thumb")
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
                        .size(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(thumbSize).background(if (enabled) c.text else c.line, CircleShape))
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
                            .background(if (enabled) c.accent else c.line, RoundedCornerShape(3.dp)),
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

/** Amber filled circle with a black play or pause glyph. */
@Composable
fun PlayButton(isPlaying: Boolean, enabled: Boolean, size: Dp = 72.dp, onClick: () -> Unit) {
    val c = Lumen.colours
    val description = if (isPlaying) "Pause" else "Play"
    Box(
        modifier = Modifier
            .size(size)
            .background(if (enabled) c.accent else c.line, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (isPlaying) LumenIcons.Pause else LumenIcons.Play, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(32.dp))
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
                .padding(horizontal = 40.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarItem("Home", LumenIcons.Home, current == Screen.Home) { onSelect(Screen.Home) }
            BarItem("Library", LumenIcons.Library, current == Screen.Library) { onSelect(Screen.Library) }
        }
    }
}

@Composable
private fun BarItem(text: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val c = Lumen.colours
    val tint = if (selected) c.text else c.text3
    Column(
        modifier = Modifier
            .size(width = 96.dp, height = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = text, tint = tint)
        Spacer(Modifier.height(4.dp))
        Text(text.uppercase(), style = Lumen.type.labelS, color = tint)
    }
}

/** Mini bar above the bottom bar: current item, mono time line, play or pause. */
@Composable
fun MiniBar(title: String, meta: String, isPlaying: Boolean, onOpen: () -> Unit, onPlayPause: () -> Unit) {
    val c = Lumen.colours
    val shape = RoundedCornerShape(CardRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(RowHeight)
            .clip(shape)
            .background(c.elevated)
            .border(1.dp, if (c.isDark) c.elevated else c.line, shape)
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Lumen.type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = Lumen.type.timeS, color = c.text2, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(TouchTarget)
                .clip(CircleShape)
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(40.dp).background(c.inverseBg, CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    if (isPlaying) LumenIcons.Pause else LumenIcons.Play,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = c.inverseFg,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Empty state: what happened and what to do next. One amber action, an optional outlined second. */
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
                checkedThumbColor = c.onAccent,
                checkedTrackColor = c.accent,
                checkedBorderColor = c.accent,
                uncheckedThumbColor = c.text2,
                uncheckedTrackColor = c.surface,
                uncheckedBorderColor = c.line,
            ),
        )
    }
}

/** 48 dp search field with the search glyph; filters as you type. */
@Composable
fun SearchField(value: String, placeholder: String, onChange: (String) -> Unit) {
    val c = Lumen.colours
    val shape = RoundedCornerShape(CardRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TouchTarget)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, if (c.isDark) c.surface else c.line, shape)
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
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
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
        Text(text, style = Lumen.type.bodyStrong, color = if (!enabled) c.text3 else if (danger) c.danger else c.accent)
    }
}

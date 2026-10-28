package app.lumen.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.lumen.player.data.NotesEntity
import app.lumen.player.data.SegmentEntity
import app.lumen.player.notes.NotesRepository
import app.lumen.player.notes.SermonNotes
import app.lumen.player.notes.Timed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0.6.0: a sermon's notes and transcript. The notes follow the template Aaron chose (Study notes
 * and Journal mixed, nothing added): sections in the speaker's order, each with its time, the
 * scriptures cited, what was said as paragraphs, the points with their times and the lines said
 * word for word; announcements last. Every time is a tap that plays from there. The transcript tab
 * has its own search, and the line now playing carries the amber time (the place Lumen holds).
 */
@Composable
fun NotesScreen(
    item: LibraryItem,
    notes: NotesEntity?,
    segments: List<SegmentEntity>,
    /** Why notes cannot be written right now, or null. */
    blocker: String?,
    /** Where this item is playing, or null when another item is loaded. */
    playingMs: Long?,
    initialQuery: String,
    onClose: () -> Unit,
    onWrite: () -> Unit,
    onPlayAt: (Long) -> Unit,
    /** 0.6.1: straight to Settings when the Gemini key is missing. */
    onSetUp: () -> Unit = {},
) {
    val c = Lumen.colours
    BackHandler(onBack = onClose)
    val parsed = remember(notes?.json) { notes?.json?.takeIf { it.isNotBlank() }?.let { SermonNotes.parse(it) } }
    var tab by rememberSaveable(item.uri) { mutableStateOf(if (initialQuery.isNotBlank() && segments.isNotEmpty()) 1 else 0) }
    var query by rememberSaveable(item.uri) { mutableStateOf(initialQuery) }
    // The transcript arrives a moment after the screen opens; a search from the Library lands on it.
    LaunchedEffect(segments.isNotEmpty()) {
        if (initialQuery.isNotBlank() && segments.isNotEmpty()) tab = 1
    }

    // pointerInput holds every touch on this screen, so nothing reaches the screen underneath.
    Column(Modifier.fillMaxSize().background(c.base).pointerInput(Unit) {}.statusBarsPadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTap(LumenIcons.ChevronLeft, "Back", c.text, onClick = onClose)
            Spacer(Modifier.width(4.dp))
            Text(
                item.media.title,
                style = Lumen.type.bodyStrong,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (notes?.status == NotesRepository.STATUS_WORKING) ProgressLine(fraction = 1f, thickness = 1.dp)
        if (segments.isNotEmpty() || parsed != null) {
            Row(Modifier.padding(horizontal = Gutter - 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TabChip("Notes", selected = tab == 0) { tab = 0 }
                TabChip("Transcript", selected = tab == 1) { tab = 1 }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (tab == 1 && segments.isNotEmpty()) {
                TranscriptView(segments, query, playingMs, onQuery = { query = it }, onPlayAt = onPlayAt)
            } else {
                NotesView(item, notes, parsed, blocker, onWrite, onPlayAt, onSetUp)
            }
        }
    }
}

@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Lumen.colours
    val shape = RoundedCornerShape(50)
    Box(Modifier.height(TouchTarget).clip(shape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .height(32.dp)
                .clip(shape)
                .background(if (selected) c.chipSelected else if (c.isDark) androidx.compose.ui.graphics.Color.Transparent else c.surface)
                .border(1.dp, if (selected) c.chipSelectedEdge else c.line, shape)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text.uppercase(), style = Lumen.type.label, color = if (selected) c.onChipSelected else c.text)
        }
    }
}

/** A time that plays from itself. */
@Composable
private fun TimeTap(ms: Long, onPlayAt: (Long) -> Unit, here: Boolean = false) {
    val c = Lumen.colours
    if (ms < 0) return
    Text(
        formatTime(ms),
        style = Lumen.type.timeS.copy(fontWeight = FontWeight.W500),
        color = if (here) c.ember else c.accentInk,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onPlayAt(ms) }.padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotesView(
    item: LibraryItem,
    notes: NotesEntity?,
    parsed: SermonNotes?,
    blocker: String?,
    onWrite: () -> Unit,
    onPlayAt: (Long) -> Unit,
    onSetUp: () -> Unit,
) {
    val c = Lumen.colours
    val needsKey = blocker?.contains("Gemini key") == true
    LazyColumn(
        modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
        contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val working = notes?.status == NotesRepository.STATUS_WORKING
        val failed = notes?.status == NotesRepository.STATUS_FAILED
        if (parsed == null) {
            item {
                when {
                    working -> StatusCard("Writing notes", notes?.step.orEmpty().ifBlank { "Starting" } +
                        ". You can leave Lumen; the notes appear here when they are ready.")
                    failed -> EmptyState(
                        title = "Notes were not written",
                        body = notes?.step.orEmpty(),
                        action = if (needsKey) "Set up notes" else "Try again",
                        onAction = if (needsKey) onSetUp else onWrite,
                        actionEnabled = blocker == null || needsKey,
                        notice = if (blocker != null) ({ Text(blocker, style = Lumen.type.bodyS, color = c.text2) }) else null,
                    )
                    else -> EmptyState(
                        title = "Notes for this recording",
                        body = "On Wi-Fi, Lumen sends the recording to Google's Gemini for a timed transcript, then writes " +
                            "notes from the transcript holding only what was said: every point, scripture, story, " +
                            "quote and announcement, each with its time. It takes a few minutes.",
                        action = if (needsKey) "Set up notes" else "Write notes",
                        onAction = if (needsKey) onSetUp else onWrite,
                        actionEnabled = blocker == null || needsKey,
                        notice = if (blocker != null) ({ Text(blocker, style = Lumen.type.bodyS, color = c.text2) }) else null,
                    )
                }
            }
            return@LazyColumn
        }

        if (working) item { StatusCard("Writing the notes again", notes?.step.orEmpty()) }
        if (failed) item { StatusCard("The last try did not finish", notes?.step.orEmpty()) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(parsed.title.ifBlank { item.media.title }, style = Lumen.type.displayS, color = c.text)
                val meta = buildList {
                    if (parsed.speaker.isNotBlank()) add(parsed.speaker)
                    if (item.media.dateAdded > 0) add(SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date(item.media.dateAdded)))
                    if (item.media.durationMs > 0) add(formatTime(item.media.durationMs))
                }
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = Lumen.type.mono, color = c.text2)
            }
        }
        if (parsed.readings.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Read aloud")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        parsed.readings.forEach { ScriptureChip(it, onPlayAt) }
                    }
                }
            }
        }
        items(parsed.sections) { section ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        section.heading,
                        style = Lumen.type.bodyStrong.copy(fontSize = Lumen.type.displayS.fontSize * 0.8f, lineHeight = Lumen.type.displayS.lineHeight),
                        color = c.text,
                        modifier = Modifier.weight(1f),
                    )
                    TimeTap(section.ms, onPlayAt)
                }
                if (section.scriptures.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        section.scriptures.forEach { ScriptureChip(it, onPlayAt) }
                    }
                }
                section.prose.forEach { paragraph ->
                    Text(paragraph, style = Lumen.type.body.copy(lineHeight = Lumen.type.body.lineHeight * 1.15f), color = c.text)
                }
                if (section.points.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        section.points.forEach { point ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text("•", style = Lumen.type.body, color = c.text2, modifier = Modifier.padding(top = 6.dp, end = 8.dp))
                                Text(point.text, style = Lumen.type.body, color = c.text, modifier = Modifier.weight(1f).padding(top = 6.dp))
                                TimeTap(point.ms, onPlayAt)
                            }
                        }
                    }
                }
                section.quotes.forEach { quote ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.thumb).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text("“${quote.text}”", style = Lumen.type.bodyStrong, color = c.text, modifier = Modifier.weight(1f).padding(top = 4.dp))
                        TimeTap(quote.ms, onPlayAt)
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (parsed.announcements.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Announcements")
                    ListCard {
                        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
                            parsed.announcements.forEach { a ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Text(a.text, style = Lumen.type.body, color = c.text, modifier = Modifier.weight(1f).padding(top = 6.dp))
                                    TimeTap(a.ms, onPlayAt)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Written by ${notes?.model.orEmpty().ifBlank { "Gemini" }} from the transcript" +
                        (notes?.updatedAt?.takeIf { it > 0 }?.let { ", ${relativeTime(it)}" } ?: "") +
                        ". Times can be a few seconds out; tap one to hear the moment.",
                    style = Lumen.type.bodyS,
                    color = c.text3,
                )
                if (!working) TextAction("Write again", enabled = blocker == null, onClick = onWrite)
            }
        }
    }
}

@Composable
private fun ScriptureChip(ref: Timed, onPlayAt: (Long) -> Unit) {
    val c = Lumen.colours
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .border(1.dp, c.line, shape)
            .clickable(enabled = ref.ms >= 0) { onPlayAt(ref.ms) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(ref.text, style = Lumen.type.bodyS.copy(fontWeight = FontWeight.W600), color = c.text)
        if (ref.ms >= 0) Text(formatTime(ref.ms), style = Lumen.type.timeS, color = c.accentInk)
    }
}

@Composable
private fun StatusCard(title: String, body: String) {
    val c = Lumen.colours
    ListCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = Lumen.type.bodyStrong, color = c.text)
            if (body.isNotBlank()) Text(body, style = Lumen.type.bodyS, color = c.text2)
        }
    }
}

@Composable
private fun TranscriptView(
    segments: List<SegmentEntity>,
    query: String,
    playingMs: Long?,
    onQuery: (String) -> Unit,
    onPlayAt: (Long) -> Unit,
) {
    val c = Lumen.colours
    val q = query.trim()
    val shown = remember(segments, q) { if (q.length < 2) segments else segments.filter { it.text.contains(q, ignoreCase = true) } }
    val nowIndex = playingMs?.let { ms -> segments.indexOfLast { it.startMs <= ms } } ?: -1
    val nowId = segments.getOrNull(nowIndex)?.id
    val listState = rememberLazyListState()
    // Opens at the line now playing, when this item is the one playing and nothing is searched.
    LaunchedEffect(segments.size) {
        if (q.isEmpty() && nowIndex > 2) listState.scrollToItem((nowIndex - 2).coerceAtLeast(0) + 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
        contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 8.dp, bottom = 32.dp),
    ) {
        item {
            SearchField(value = query, placeholder = "Search what was said", onChange = onQuery)
            Text(
                if (q.length >= 2) "${shown.size} lines with \"$q\"" else "${segments.size} lines. Tap a time to play from it",
                style = Lumen.type.bodyS,
                color = c.text3,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }
        items(shown, key = { it.id }) { seg ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.width(72.dp)) { TimeTap(seg.startMs, onPlayAt, here = seg.id == nowId) }
                Text(
                    highlight(seg.text, q, c.accentInk),
                    style = Lumen.type.body,
                    color = if (seg.text.startsWith("[")) c.text3 else c.text,
                    modifier = Modifier.weight(1f).padding(top = 5.dp),
                )
            }
        }
    }
}

/** [text] with every match of [q] in weight 600 and the ink colour. */
fun highlight(text: String, q: String, ink: androidx.compose.ui.graphics.Color): AnnotatedString {
    if (q.length < 2) return AnnotatedString(text)
    return buildAnnotatedString {
        var from = 0
        val lower = text.lowercase()
        val needle = q.lowercase()
        while (true) {
            val at = lower.indexOf(needle, from)
            if (at < 0) {
                append(text.substring(from))
                break
            }
            append(text.substring(from, at))
            withStyle(SpanStyle(fontWeight = FontWeight.W600, color = ink)) { append(text.substring(at, at + needle.length)) }
            from = at + needle.length
        }
    }
}

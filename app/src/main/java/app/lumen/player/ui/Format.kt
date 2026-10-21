package app.lumen.player.ui

import java.util.Locale

/** "47:12" under an hour, "1:09:32" over. Always set in tabular mono where shown. */
fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.UK, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.UK, "%02d:%02d", m, s)
}

/** "1.0x", "1.25x", "1.5x". */
fun speedLabel(speed: Float): String {
    val raw = String.format(Locale.UK, "%.2f", speed).trimEnd('0')
    val text = if (raw.endsWith(".")) raw + "0" else raw
    return text + "x"
}

/** "just now", "12 min ago", "3 h ago", "2 days ago" for the scan row; "never" for 0. */
fun relativeTime(atMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (atMs <= 0L) return "never"
    val minutes = ((nowMs - atMs).coerceAtLeast(0L)) / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        minutes < 60 * 48 -> "1 day ago"
        else -> "${minutes / (60 * 24)} days ago"
    }
}

/** "1,240" with a thin separator for file counts. */
fun formatCount(n: Int): String = String.format(Locale.UK, "%,d", n)

/** "22 min left" for the Continue eyebrow. */
fun minutesLeft(positionMs: Long, durationMs: Long): String {
    if (durationMs <= 0) return ""
    val minutes = ((durationMs - positionMs).coerceAtLeast(0L) + 30_000L) / 60_000L
    return if (minutes < 1) "under a minute left" else "$minutes min left"
}

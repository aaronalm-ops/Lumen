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

/** "22 min left" for the Continue eyebrow. */
fun minutesLeft(positionMs: Long, durationMs: Long): String {
    if (durationMs <= 0) return ""
    val minutes = ((durationMs - positionMs).coerceAtLeast(0L) + 30_000L) / 60_000L
    return if (minutes < 1) "under a minute left" else "$minutes min left"
}

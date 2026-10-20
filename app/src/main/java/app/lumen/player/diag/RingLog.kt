package app.lumen.player.diag

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-process ring buffer of the last [CAPACITY] log lines. Everything the app logs goes through
 * here so the diagnostics zip can include it without reading logcat.
 */
object RingLog {
    private const val CAPACITY = 2000
    private const val TAG = "Lumen"

    private val lines = ArrayDeque<String>(CAPACITY)
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.UK)

    fun i(scope: String, message: String) = add("I", scope, message)

    fun w(scope: String, message: String) = add("W", scope, message)

    fun e(scope: String, message: String, error: Throwable? = null) {
        add("E", scope, if (error == null) message else "$message: $error")
    }

    private fun add(level: String, scope: String, message: String) {
        val stamp = synchronized(format) { format.format(Date()) }
        val line = "$stamp $level/$scope: $message"
        synchronized(lines) {
            if (lines.size >= CAPACITY) lines.removeFirst()
            lines.addLast(line)
        }
        when (level) {
            "E" -> Log.e(TAG, "$scope: $message")
            "W" -> Log.w(TAG, "$scope: $message")
            else -> Log.i(TAG, "$scope: $message")
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
}

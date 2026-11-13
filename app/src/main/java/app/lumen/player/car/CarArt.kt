package app.lumen.player.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import app.lumen.player.R
import app.lumen.player.diag.RingLog
import java.io.File
import java.security.MessageDigest

/**
 * 0.9.0: Lumen's own tiles for Android Auto (tabs, playlists, sermons without a cover), drawn
 * once and served by [CarArtProvider] so the car's process can read them. Fixed Lumen palette:
 * near-black ground, the light-trail mark, Manrope label, cyan or amber accent. Never derived
 * from cover art.
 */
object CarArt {
    const val CYAN = 0xFF28D9E8.toInt()
    const val AMBER = 0xFFF2A93B.toInt()
    private const val GROUND_TOP = 0xFF17171C.toInt()
    private const val GROUND_BOTTOM = 0xFF0A0A0D.toInt()
    private const val SIZE = 320
    private const val VERSION = 1

    enum class Accent(val colour: Int) { CYAN(CarArt.CYAN), AMBER(CarArt.AMBER) }

    private fun authority(context: Context) = "${context.packageName}.art"

    /** A content:// uri for a tile with [label] (two lines at most), drawn if not cached yet. */
    fun tile(context: Context, label: String, accent: Accent): Uri? {
        val name = "t${VERSION}_" + hash("$label|${accent.name}") + ".png"
        val file = File(dir(context), name)
        if (!file.exists()) {
            try {
                val bitmap = draw(context, label, accent)
                val temp = File(dir(context), "$name.tmp")
                temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                if (!temp.renameTo(file)) temp.delete()
            } catch (t: Throwable) {
                RingLog.w("car", "tile not drawn: $t")
                return null
            }
        }
        return Uri.Builder().scheme("content").authority(authority(context)).appendPath("tile").appendPath(name).build()
    }

    /** A content:// uri for a cached cover file (files/art), or null when it is not one. */
    fun cover(context: Context, artPath: String?): Uri? {
        val file = artPath?.let { File(it) } ?: return null
        if (!file.exists() || file.parentFile?.canonicalPath != File(context.filesDir, "art").canonicalPath) return null
        if (!CarArtProvider.isSafeName(file.name)) return null
        return Uri.Builder().scheme("content").authority(authority(context)).appendPath("cover").appendPath(file.name).build()
    }

    internal fun dir(context: Context): File = File(context.filesDir, "car-art").apply { mkdirs() }

    private fun draw(context: Context, label: String, accent: Accent): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val ground = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, SIZE.toFloat(), GROUND_TOP, GROUND_BOTTOM, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), ground)
        ResourcesCompat.getDrawable(context.resources, R.drawable.lumen_lockup, context.theme)?.mutate()?.let { mark ->
            mark.setBounds(24, 26, 24 + 92, 26 + 74)
            mark.alpha = 230
            mark.draw(canvas)
        }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent.colour; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND }
        canvas.drawLine(28f, 214f, 92f, 214f, accentPaint)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(245, 245, 245)
            typeface = ResourcesCompat.getFont(context, R.font.manrope)
            isFakeBoldText = true
        }
        val lines = wrap(label.trim(), text)
        var y = 262f
        if (lines.size == 2) y = 248f
        lines.forEachIndexed { i, line -> canvas.drawText(line, 26f, y + i * (text.textSize + 6f), text) }
        return bitmap
    }

    /** One or two lines that fit the tile; long labels end with an ellipsis. */
    private fun wrap(label: String, paint: TextPaint): List<String> {
        val width = SIZE - 52f
        for (size in listOf(44f, 38f, 32f)) {
            paint.textSize = size
            if (paint.measureText(label) <= width) return listOf(label)
        }
        paint.textSize = 32f
        val words = label.split(Regex("\\s+"))
        var first = ""
        var i = 0
        while (i < words.size && paint.measureText((first + " " + words[i]).trim()) <= width) {
            first = (first + " " + words[i]).trim()
            i++
        }
        if (first.isEmpty()) return listOf(ellipsize(label, paint, width))
        val rest = words.drop(i).joinToString(" ")
        return if (rest.isEmpty()) listOf(first) else listOf(first, ellipsize(rest, paint, width))
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        var cut = text
        while (cut.isNotEmpty() && paint.measureText("$cut…") > width) cut = cut.dropLast(1)
        return "${cut.trimEnd()}…"
    }

    private fun hash(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).take(10).joinToString("") { "%02x".format(it) }
}

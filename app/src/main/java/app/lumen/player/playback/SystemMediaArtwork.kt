package app.lumen.player.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import app.lumen.player.R
import java.io.ByteArrayOutputStream

/** Fixed Lumen artwork for system controls. Actual covers remain in the app's artwork block. */
@UnstableApi
internal class SystemMediaArtwork(context: Context) {
    val bitmap: Bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also { image ->
        val canvas = Canvas(image)
        canvas.drawColor(Color.BLACK)
        ResourcesCompat.getDrawable(context.resources, R.drawable.lumen_lockup, context.theme)?.mutate()?.let { mark ->
            mark.setBounds(136, 130, 376, 322)
            mark.draw(canvas)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF5F5F5.toInt()
            textSize = 38f
            textAlign = Paint.Align.CENTER
            typeface = ResourcesCompat.getFont(context, R.font.manrope)
        }
        canvas.drawText("LUMEN", 256f, 376f, paint)
        paint.color = 0xFF28D9E8.toInt()
        paint.strokeWidth = 4f
        canvas.drawLine(200f, 402f, 312f, 402f, paint)
    }
    private val bytes = ByteArrayOutputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        output.toByteArray()
    }
    private var previous: MediaMetadata? = null
    private var branded: MediaMetadata? = null

    fun metadata(source: MediaMetadata): MediaMetadata {
        if (source != previous) {
            previous = source
            branded = source.buildUpon().setArtworkUri(null)
                .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()
        }
        return branded!!
    }
}

package app.lumen.player.cast

import android.net.Uri
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import app.lumen.player.diag.RingLog
import app.lumen.player.playback.MediaItems
import com.google.android.gms.cast.MediaQueueItem

/**
 * 0.5.0: hands the Cast receiver a URL it can fetch. A Lumen queue item carries `content://`,
 * `file://` or `drive://`; the receiver gets `http://<phone>:<port>/m/<token>` from
 * [CastMediaServer] instead. The media id (the Lumen uri, which is also the Cast content id) is
 * kept, so RemoteCastPlayer's timeline maps every receiver item back to the original MediaItem
 * (CastTimelineTracker looks items up by content id) and the position is saved under the same key
 * as on the phone. Artwork on the phone is served the same way; a video item is sent as a movie so
 * the Default Media Receiver shows the picture.
 */
@UnstableApi
class LumenCastConverter(private val server: CastMediaServer) : MediaItemConverter {

    private val delegate = DefaultMediaItemConverter()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val original = MediaItems.uriOf(mediaItem)
        // 0.6.1: the receiver needs a type. Items built outside the library (Continue after a
        // restart, Open with) carry none, so it is worked out from the file name.
        val mime = mediaItem.localConfiguration?.mimeType?.takeIf { it.isNotBlank() } ?: guessMime(original, MediaItems.isVideo(mediaItem))
        val url = server.urlFor(original, mime, MediaItems.sizeBytes(mediaItem))
        if (url == null) RingLog.w("cast", "no address to serve $original from; the receiver will fail to load it")
        val metadata = mediaItem.mediaMetadata.buildUpon()
        val art = mediaItem.mediaMetadata.artworkUri
        if (art != null && art.scheme != "http" && art.scheme != "https") {
            metadata.setArtworkUri(server.urlFor(art.toString(), "image/jpeg", 0L)?.let { Uri.parse(it) })
        }
        if (MediaItems.isVideo(mediaItem)) metadata.setMediaType(MediaMetadata.MEDIA_TYPE_MOVIE)
        val forReceiver = mediaItem.buildUpon()
            .setMediaId(mediaItem.mediaId.ifBlank { original })
            .setUri(Uri.parse(url ?: original))
            .setMimeType(mime)
            .setMediaMetadata(metadata.build())
            .build()
        return delegate.toMediaQueueItem(forReceiver)
    }

    private fun guessMime(uri: String, video: Boolean): String {
        val ext = Uri.parse(uri).lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return when (ext) {
            "m4a", "aac" -> "audio/mp4"
            "opus", "ogg" -> "audio/ogg"
            else -> if (video) "video/mp4" else "audio/mpeg"
        }
    }

    /**
     * Used only for a receiver item the timeline has not seen (a session joined from elsewhere):
     * the media id is put back as the uri, so a Lumen item never carries the phone's LAN URL.
     */
    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val item = delegate.toMediaItem(mediaQueueItem)
        val id = item.mediaId
        val scheme = Uri.parse(id).scheme
        return if (scheme == "content" || scheme == "file" || scheme == "drive") {
            item.buildUpon().setUri(Uri.parse(id)).build()
        } else {
            item
        }
    }
}

package app.lumen.player.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.lumen.player.data.MediaEntity
import java.io.File

/** Builds the MediaItems the UI, the widget and Android Auto hand to the session. */
object MediaItems {
    const val EXTRA_IS_VIDEO = "lumen.isVideo"
    const val EXTRA_LABEL_IDS = "lumen.labelIds"
    const val EXTRA_SPEECH = "lumen.speech"
    const val EXTRA_DURATION_MS = "lumen.durationMs"
    const val EXTRA_FINGERPRINT = "lumen.fingerprint"

    /** 0.4.0: whether the item resumes at its saved place; differs from speech for long voice notes. */
    const val EXTRA_RESUME = "lumen.resume"

    /** 0.4.0: a WhatsApp voice note; Smooth transitions never fade into or out of one. */
    const val EXTRA_VOICE_NOTE = "lumen.voiceNote"

    fun forMedia(
        media: MediaEntity,
        labelIds: List<Long>,
        speech: Boolean,
        browsable: Boolean = false,
        resume: Boolean = speech,
        voiceNote: Boolean = false,
    ): MediaItem {
        val uri = Uri.parse(media.uri)
        val extras = Bundle().apply {
            putBoolean(EXTRA_IS_VIDEO, media.isVideo)
            putLongArray(EXTRA_LABEL_IDS, labelIds.toLongArray())
            putBoolean(EXTRA_SPEECH, speech)
            putLong(EXTRA_DURATION_MS, media.durationMs)
            putString(EXTRA_FINGERPRINT, media.fingerprint)
            putBoolean(EXTRA_RESUME, resume)
            putBoolean(EXTRA_VOICE_NOTE, voiceNote)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(media.title)
            .setArtist(media.artist.ifBlank { null })
            .setIsPlayable(true)
            .setIsBrowsable(browsable)
            .setMediaType(if (speech) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
        if (media.durationMs > 0) metadata.setDurationMs(media.durationMs)
        media.artPath?.let { path ->
            val file = File(path)
            if (file.exists()) metadata.setArtworkUri(Uri.fromFile(file))
        }
        return MediaItem.Builder()
            .setMediaId(media.uri)
            .setUri(uri)
            .setMimeType(media.mime.ifBlank { null })
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(metadata.build())
            .build()
    }

    fun folder(id: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

    fun isVideo(item: MediaItem?): Boolean = item?.mediaMetadata?.extras?.getBoolean(EXTRA_IS_VIDEO, false) ?: false

    fun speechOrNull(item: MediaItem?): Boolean? {
        val extras = item?.mediaMetadata?.extras ?: return null
        return if (extras.containsKey(EXTRA_SPEECH)) extras.getBoolean(EXTRA_SPEECH) else null
    }

    /** Null for an item built before 0.4.0 or outside the library; callers then fall back to speech. */
    fun resumeOrNull(item: MediaItem?): Boolean? {
        val extras = item?.mediaMetadata?.extras ?: return null
        return if (extras.containsKey(EXTRA_RESUME)) extras.getBoolean(EXTRA_RESUME) else null
    }

    fun isVoiceNote(item: MediaItem?): Boolean = item?.mediaMetadata?.extras?.getBoolean(EXTRA_VOICE_NOTE, false) ?: false

    fun labelIds(item: MediaItem?): List<Long> = item?.mediaMetadata?.extras?.getLongArray(EXTRA_LABEL_IDS)?.toList() ?: emptyList()

    fun fingerprint(item: MediaItem?): String? = item?.mediaMetadata?.extras?.getString(EXTRA_FINGERPRINT)

    fun uriOf(item: MediaItem): String =
        item.localConfiguration?.uri?.toString()
            ?: item.requestMetadata.mediaUri?.toString()
            ?: item.mediaId
}

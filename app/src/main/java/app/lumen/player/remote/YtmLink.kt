package app.lumen.player.remote

import android.net.Uri
import app.lumen.player.data.FolderEntity
import app.lumen.player.data.MediaEntity

/** True for the one Library folder that holds the shared YouTube Music items (`ytm:`). */
val FolderEntity.isYtm: Boolean get() = uri == YtmLink.FOLDER_URI

/** True for a YouTube Music row (`ytm://<videoId>` or `ytm-list://<listId>`). */
val MediaEntity.isYtm: Boolean get() = YtmLink.isYtm(uri)

/**
 * 0.5.0: a song or playlist in YouTube Music, as Lumen stores it. Lumen never plays YouTube audio
 * itself; a YouTube Music row in a queue opens the official app and Lumen controls it from there
 * (see [RemoteController]).
 *
 * Shared links Lumen takes (the YouTube Music share sheet, or a YouTube link):
 * - `https://music.youtube.com/watch?v=<id>` (any `&list=` or `&si=` is ignored: the song wins),
 *   `https://www.youtube.com/watch?v=<id>`, `https://m.youtube.com/watch?v=<id>`,
 *   `https://youtu.be/<id>` become `ytm://<id>`;
 * - `https://music.youtube.com/playlist?list=<id>` (albums share this way too) becomes
 *   `ytm-list://<id>`.
 */
data class YtmLink(val id: String, val isList: Boolean) {

    /** The Lumen uri: the media row key, the queue item's uri and its media id. */
    val uri: String get() = (if (isList) LIST_PREFIX else VIDEO_PREFIX) + id

    /** What YouTube Music opens. A playlist starts playing from its first song. */
    val watchUrl: String
        get() = if (isList) "https://music.youtube.com/watch?list=$id" else "https://music.youtube.com/watch?v=$id"

    /** The Library row for this link. Duration 0 (music layout), no art, never a video. */
    fun toMediaEntity(title: String, artist: String, now: Long): MediaEntity = MediaEntity(
        uri = uri,
        folderUri = FOLDER_URI,
        title = title.ifBlank { if (isList) "YouTube Music playlist" else "YouTube Music song" },
        artist = artist,
        durationMs = 0L,
        sizeBytes = 0L,
        dateAdded = now,
        mime = "",
        isVideo = false,
        // Unique per link, so two shared songs with one title never collapse into one row.
        fingerprint = "ytm|$uri",
        relPath = "",
        // "Looked, none": the art comes from YouTube Music's own session while it plays.
        artPath = "",
        tint = null,
        modifiedAt = now,
    )

    /** What the share sheet handed over: the link, and the title and artist when the text had them. */
    data class Shared(val link: YtmLink, val title: String, val artist: String)

    companion object {
        const val VIDEO_PREFIX = "ytm://"
        const val LIST_PREFIX = "ytm-list://"
        const val FOLDER_URI = "ytm:"
        const val FOLDER_NAME = "YouTube Music"

        /** The source chip on Library rows and Now Playing. */
        const val SOURCE_LABEL = "via YouTube Music"

        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private val LIST_ID = Regex("[A-Za-z0-9_-]{2,64}")
        private val URL = Regex("https?://\\S+")
        private val HOSTS = setOf("music.youtube.com", "www.youtube.com", "youtube.com", "m.youtube.com", "youtu.be")

        fun isYtm(uri: String): Boolean = uri.startsWith(VIDEO_PREFIX) || uri.startsWith(LIST_PREFIX)

        fun fromUri(uri: String): YtmLink? = when {
            uri.startsWith(VIDEO_PREFIX) -> uri.removePrefix(VIDEO_PREFIX).takeIf { VIDEO_ID.matches(it) }?.let { YtmLink(it, isList = false) }
            uri.startsWith(LIST_PREFIX) -> uri.removePrefix(LIST_PREFIX).takeIf { LIST_ID.matches(it) }?.let { YtmLink(it, isList = true) }
            else -> null
        }

        /** A YouTube or YouTube Music URL to a link; null for anything else (a channel, an album browse page). */
        fun fromUrl(url: String): YtmLink? {
            val parsed = try {
                Uri.parse(url.trim())
            } catch (t: Throwable) {
                return null
            }
            val host = parsed.host?.lowercase() ?: return null
            if (host !in HOSTS) return null
            if (host == "youtu.be") {
                val id = parsed.pathSegments.firstOrNull() ?: return null
                return if (VIDEO_ID.matches(id)) YtmLink(id, isList = false) else null
            }
            val video = queryParameter(parsed, "v")
            if (video != null && VIDEO_ID.matches(video)) return YtmLink(video, isList = false)
            val list = queryParameter(parsed, "list")
            if (list != null && LIST_ID.matches(list)) return YtmLink(list, isList = true)
            return null
        }

        /**
         * Reads an ACTION_SEND text/plain share. YouTube Music puts the link in the text, and the
         * title (often "Title - Artist") in the subject or in the text before the link; whatever is
         * there is used, the rest is left blank.
         */
        fun fromShare(text: String?, subject: String?): Shared? {
            val body = text.orEmpty()
            val url = URL.find(body)?.value?.trimEnd('.', ',', ')', ']', '"', '\'') ?: return null
            val link = fromUrl(url) ?: return null
            val rest = body.replace(url, " ").trim()
            val label = listOf(subject.orEmpty().trim(), rest)
                .map { cleanTitle(it) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            val title: String
            val artist: String
            if (label.contains(" - ")) {
                title = label.substringBeforeLast(" - ").trim()
                artist = label.substringAfterLast(" - ").trim()
            } else {
                title = label
                artist = ""
            }
            return Shared(link, title, artist)
        }

        private fun cleanTitle(raw: String): String = raw
            .replace(Regex("\\s+"), " ")
            .removeSuffix(" - YouTube Music")
            .removeSuffix(" - YouTube")
            .trim()
            .trim('"', '“', '”')
            .trim()

        private fun queryParameter(uri: Uri, name: String): String? = try {
            if (uri.isHierarchical) uri.getQueryParameter(name) else null
        } catch (t: Throwable) {
            null
        }
    }
}

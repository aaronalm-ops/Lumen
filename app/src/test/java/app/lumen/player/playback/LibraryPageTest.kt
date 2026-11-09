package app.lumen.player.playback

import org.junit.Assert.*
import org.junit.Test

class LibraryPageTest {
    @Test fun `browser pages keep user order with no overlap`() {
        val ordered = listOf("sermon", "song", "voice note", "music")
        assertEquals(listOf("sermon", "song"), LibraryPage.slice(ordered, 0, 2))
        assertEquals(listOf("voice note", "music"), LibraryPage.slice(ordered, 1, 2))
        assertEquals(emptyList<String>(), LibraryPage.slice(ordered, 2, 2))
    }

    @Test fun `oversized and invalid requests cannot wrap into the first page`() {
        assertEquals(emptyList<String>(), LibraryPage.slice(listOf("one"), Int.MAX_VALUE, Int.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { LibraryPage.slice(listOf("one"), -1, 10) }
        assertThrows(IllegalArgumentException::class.java) { LibraryPage.slice(listOf("one"), 0, 0) }
    }
}

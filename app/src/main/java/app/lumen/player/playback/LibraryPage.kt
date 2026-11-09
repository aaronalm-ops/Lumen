package app.lumen.player.playback

internal object LibraryPage {
    /** Long arithmetic prevents oversized browser requests from wrapping back to the first page. */
    fun <T> slice(items: List<T>, page: Int, pageSize: Int): List<T> {
        require(page >= 0 && pageSize > 0)
        val start = page.toLong() * pageSize
        if (start >= items.size) return emptyList()
        val end = (start + pageSize).coerceAtMost(items.size.toLong())
        return items.subList(start.toInt(), end.toInt())
    }
}

package app.lumen.player.ui

/** 0.10.0: the queue screen's rules, kept pure so they are unit tested without a player. */
internal object QueueOrder {
    /** Where Play next puts an item: right after the current one. */
    fun nextIndex(current: Int, count: Int): Int = (current + 1).coerceIn(0, count)

    /** The list after the row at [from] is dragged to [to], as the player's moveMediaItem does. */
    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Which row is current after a move: the current item travels with its row. */
    fun currentAfterMove(current: Int, from: Int, to: Int): Int = when {
        from == current -> to
        from < current && to >= current -> current - 1
        from > current && to <= current -> current + 1
        else -> current
    }
}

package dev.drecorder.audio.edit

/**
 * The order the sources of a merge are spliced in.
 *
 * A merge is not a set: "A then B" and "B then A" are two different recordings,
 * and the user is the only one who knows which one they meant. The library can
 * only hand over *which* recordings were picked, so the order has to be
 * something the editor shows and the user changes (DESIGN §12.2).
 *
 * The plan is one ordered list of clips, so moving a source is moving a slice
 * of that list — no other part of the pipeline has to know about it.
 *
 * Pure and Android-free, so it is unit-tested off-device.
 */
object EditOrder {

    /**
     * [items] with the element at [from] moved [delta] places, or unchanged when
     * the move would leave the list.
     *
     * A move that falls off either end is refused rather than clamped: the user
     * pressed "move earlier" on the first item, and silently doing nothing is
     * the honest answer — clamping would swap two recordings they did not ask
     * to swap.
     */
    fun <T> move(items: List<T>, from: Int, delta: Int): List<T> {
        if (from !in items.indices || delta == 0) return items
        val to = from + delta
        if (to !in items.indices) return items
        return items.toMutableList().also {
            val moved = it.removeAt(from)
            it.add(to, moved)
        }
    }

    /**
     * Where [from] ends up after [move], so the caller can keep pointing at the
     * same recording after the list was reordered.
     */
    fun destinationOf(items: List<*>, from: Int, delta: Int): Int {
        if (from !in items.indices || delta == 0) return from
        val to = from + delta
        return if (to in items.indices) to else from
    }
}

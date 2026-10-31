package app.kassa.money.ui.common

/**
 * The index maths behind drag-to-reorder lists, plain Kotlin so it has fast JVM tests. A list is a column of items with
 * [heights] (px) and the same [gap] between each. Offsets are px from where the dragged item started, down positive.
 */
object ReorderMath {

    /** The top edge of each item. */
    fun tops(heights: List<Int>, gap: Int): List<Int> {
        var y = 0
        return heights.map { h -> y.also { y += h + gap } }
    }

    /**
     * Where item [from] belongs when dragged [offset]: after every other item whose middle is above the dragged item's
     * middle. Judged on the original layout, so the answer doesn't flicker while the others slide aside.
     */
    fun target(heights: List<Int>, gap: Int, from: Int, offset: Float): Int {
        if (from !in heights.indices) return from
        val tops = tops(heights, gap)
        val middle = tops[from] + heights[from] / 2f + offset
        return heights.indices.count { it != from && tops[it] + heights[it] / 2f < middle }
    }

    /** Keeps a drag inside the list: the item can't go above the first slot or below the last. */
    fun clampOffset(heights: List<Int>, gap: Int, from: Int, offset: Float): Float {
        if (from !in heights.indices) return 0f
        val tops = tops(heights, gap)
        val min = 0f - tops[from]
        val max = (tops.last() + heights.last() - tops[from] - heights[from]).toFloat()
        return offset.coerceIn(min, max)
    }

    /** How far item [index] slides while item [from] is held over slot [to]: it makes room by one item's height. */
    fun shift(heights: List<Int>, gap: Int, from: Int, to: Int, index: Int): Float {
        if (index == from || from !in heights.indices) return 0f
        val room = (heights[from] + gap).toFloat()
        return when {
            from < to && index in (from + 1)..to -> -room
            from > to && index in to until from -> room
            else -> 0f
        }
    }

    /** The offset at which item [from] sits exactly in slot [to], where a drop settles before the order changes. */
    fun snapOffset(heights: List<Int>, gap: Int, from: Int, to: Int): Float {
        if (from !in heights.indices || to !in heights.indices || from == to) return 0f
        val tops = tops(heights, gap)
        return if (to > from) (tops[to] + heights[to] - heights[from] - tops[from]).toFloat() else (tops[to] - tops[from]).toFloat()
    }

    /** [list] with the item at [from] taken out and put at [to]. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    /**
     * Puts [reordered] (the members of [within], in their new order) back into the slots [within] took in [all]; the
     * others keep their places. This is how a group moves inside its own column of a two-column layout.
     */
    fun <T> placeInSlots(all: List<T>, within: List<T>, reordered: List<T>): List<T> {
        val slots = all.indices.filter { all[it] in within }
        if (slots.size != reordered.size) return all
        val next = all.toMutableList()
        slots.forEachIndexed { n, slot -> next[slot] = reordered[n] }
        return next
    }

    /**
     * How far to scroll this frame while a dragged item spans [top] to [bottom] inside a view from [viewTop] to
     * [viewBottom]: negative scrolls up, positive down, 0 when it's clear of both edges. It speeds up toward [maxStep]
     * the nearer the item gets to the edge, within [margin] of it.
     */
    fun edgeScroll(top: Float, bottom: Float, viewTop: Float, viewBottom: Float, margin: Float, maxStep: Float): Float {
        if (margin <= 0f) return 0f
        val up = viewTop + margin - top
        val down = bottom - (viewBottom - margin)
        return when {
            up > 0f -> -maxStep * (up / margin).coerceAtMost(1f)
            down > 0f -> maxStep * (down / margin).coerceAtMost(1f)
            else -> 0f
        }
    }
}

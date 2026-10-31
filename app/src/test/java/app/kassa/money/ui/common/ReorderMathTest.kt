package app.kassa.money.ui.common

import org.junit.Test
import kotlin.test.assertEquals

class ReorderMathTest {

    // Three items, 100px tall with 10px between: tops 0, 110, 220.
    private val h = listOf(100, 100, 100)

    @Test
    fun topsAddTheGaps() {
        assertEquals(listOf(0, 110, 220), ReorderMath.tops(h, 10))
    }

    @Test
    fun anItemStaysPutUntilItsMiddlePassesTheNeighboursMiddle() {
        // Item 0's middle is at 50; item 1's is at 160, so it takes a drag of more than 110.
        assertEquals(0, ReorderMath.target(h, 10, 0, 0f))
        assertEquals(0, ReorderMath.target(h, 10, 0, 109f))
        assertEquals(1, ReorderMath.target(h, 10, 0, 111f))
        assertEquals(2, ReorderMath.target(h, 10, 0, 230f))
    }

    @Test
    fun draggingUpWorksTheSameWay() {
        assertEquals(2, ReorderMath.target(h, 10, 2, -109f))
        assertEquals(1, ReorderMath.target(h, 10, 2, -111f))
        assertEquals(0, ReorderMath.target(h, 10, 2, -230f))
    }

    @Test
    fun unevenHeightsUseEachItemsOwnMiddle() {
        val uneven = listOf(40, 200, 40)
        // Item 0 middle 20, item 1 middle 50+100=150: needs more than 130.
        assertEquals(0, ReorderMath.target(uneven, 10, 0, 129f))
        assertEquals(1, ReorderMath.target(uneven, 10, 0, 131f))
    }

    @Test
    fun aBadIndexStaysWhereItIs() {
        assertEquals(5, ReorderMath.target(h, 10, 5, 50f))
        assertEquals(0f, ReorderMath.clampOffset(h, 10, 5, 50f))
    }

    @Test
    fun aDragStopsAtTheFirstAndLastSlots() {
        assertEquals(0f, ReorderMath.clampOffset(h, 10, 0, -50f))
        assertEquals(220f, ReorderMath.clampOffset(h, 10, 0, 999f))
        assertEquals(-220f, ReorderMath.clampOffset(h, 10, 2, -999f))
        assertEquals(30f, ReorderMath.clampOffset(h, 10, 1, 30f))
    }

    @Test
    fun othersSlideByOneItemsHeightPlusGap() {
        // Item 0 held over slot 2: items 1 and 2 move up.
        assertEquals(0f, ReorderMath.shift(h, 10, 0, 2, 0))
        assertEquals(-110f, ReorderMath.shift(h, 10, 0, 2, 1))
        assertEquals(-110f, ReorderMath.shift(h, 10, 0, 2, 2))
        // Item 2 held over slot 1: item 1 moves down, item 0 stays.
        assertEquals(0f, ReorderMath.shift(h, 10, 2, 1, 0))
        assertEquals(110f, ReorderMath.shift(h, 10, 2, 1, 1))
        // Not moved: nothing slides.
        assertEquals(0f, ReorderMath.shift(h, 10, 1, 1, 0))
    }

    @Test
    fun snapOffsetPutsTheItemInTheTargetSlot() {
        assertEquals(0f, ReorderMath.snapOffset(h, 10, 1, 1))
        assertEquals(110f, ReorderMath.snapOffset(h, 10, 0, 1))
        assertEquals(220f, ReorderMath.snapOffset(h, 10, 0, 2))
        assertEquals(-110f, ReorderMath.snapOffset(h, 10, 2, 1))
        // A tall item moving past a short one: the short one goes to the top, so the tall one sits 50px lower.
        assertEquals(50f, ReorderMath.snapOffset(listOf(100, 40), 10, 0, 1))
    }

    @Test
    fun moveTakesOneOutAndPutsItBack() {
        assertEquals(listOf("b", "c", "a"), ReorderMath.move(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), ReorderMath.move(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("a", "b"), ReorderMath.move(listOf("a", "b"), 1, 1))
        assertEquals(listOf("a", "b"), ReorderMath.move(listOf("a", "b"), 5, 0))
    }

    @Test
    fun aColumnsGroupsGoBackIntoTheirOwnSlots() {
        val all = listOf("CASH", "INV", "CARDS", "LOANS")
        assertEquals(listOf("INV", "CASH", "CARDS", "LOANS"), ReorderMath.placeInSlots(all, listOf("CASH", "INV"), listOf("INV", "CASH")))
        assertEquals(listOf("CASH", "INV", "LOANS", "CARDS"), ReorderMath.placeInSlots(all, listOf("CARDS", "LOANS"), listOf("LOANS", "CARDS")))
        // A reordered list of the wrong size changes nothing.
        assertEquals(all, ReorderMath.placeInSlots(all, listOf("CASH", "INV"), listOf("INV")))
    }

    @Test
    fun edgeScrollIsZeroInTheMiddleAndGrowsTowardTheEdges() {
        assertEquals(0f, ReorderMath.edgeScroll(300f, 400f, 0f, 1000f, 100f, 20f))
        assertEquals(-10f, ReorderMath.edgeScroll(50f, 150f, 0f, 1000f, 100f, 20f))
        assertEquals(-20f, ReorderMath.edgeScroll(-30f, 70f, 0f, 1000f, 100f, 20f))
        assertEquals(10f, ReorderMath.edgeScroll(800f, 950f, 0f, 1000f, 100f, 20f))
        assertEquals(20f, ReorderMath.edgeScroll(900f, 1100f, 0f, 1000f, 100f, 20f))
        assertEquals(0f, ReorderMath.edgeScroll(0f, 10f, 0f, 1000f, 0f, 20f))
    }
}

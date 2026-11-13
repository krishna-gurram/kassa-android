package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverviewTilesTest {

    @Test
    fun aTileWeighsItsShareOfTheThreeInPartsPerThousand() {
        // 5000 of 10000 is 500; 2000 is 200, lifted to the floor.
        assertEquals(listOf(500, 300, 300), OverviewTiles.weights(listOf(5_000, 3_000, 2_000), false))
        assertEquals(listOf(400, 350, 300), OverviewTiles.weights(listOf(400, 350, 250), false))
    }

    @Test
    fun theFloorKeepsASmallTileWideEnoughForItsIcon() {
        // 180000 / 243543 = 739 per thousand; 44320 and 19223 are 181 and 78, lifted to the floor.
        assertEquals(listOf(739, 300, 300), OverviewTiles.weights(listOf(180_000, 44_320, 19_223), false))
        // Shares round down: 1 of 3 is 333.
        assertEquals(listOf(333, 333, 333), OverviewTiles.weights(listOf(1, 1, 1), false))
    }

    @Test
    fun oneOrTwoCategoriesAndEmptyListsWork() {
        assertEquals(listOf(1000), OverviewTiles.weights(listOf(12_345), false))
        assertEquals(listOf(500, 500), OverviewTiles.weights(listOf(100, 100), false))
        assertEquals(emptyList(), OverviewTiles.weights(emptyList(), false))
        // Nothing spent at all, or a negative figure: no division by zero, the floor holds.
        assertEquals(listOf(300, 300), OverviewTiles.weights(listOf(0, 0), false))
        assertEquals(listOf(1000, 300), OverviewTiles.weights(listOf(500, -200), false))
    }

    @Test
    fun whileHiddenEveryTileIsTheSameWhateverTheFigures() {
        assertEquals(listOf(1000, 1000, 1000), OverviewTiles.weights(listOf(180_000, 44_320, 19_223), true))
        assertEquals(listOf(1000), OverviewTiles.weights(listOf(1), true))
        assertTrue(OverviewTiles.weights(emptyList(), true).isEmpty())
    }
}

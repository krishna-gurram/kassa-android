package app.kassa.money.data.jobs

import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupNamesTest {
    private fun day(n: Int) = BackupNames.nameFor(LocalDateTime.of(2026, 1, 1, 23, 0, 5).plusDays(n.toLong()))

    @Test
    fun theNameIsTheMoneyManagerStyleStamp() {
        assertEquals("Kassa_20261031_090507.kassa", BackupNames.nameFor(LocalDateTime.of(2026, 10, 31, 9, 5, 7)))
        assertTrue(BackupNames.isOurs("Kassa_20261031_090507.kassa"))
    }

    @Test
    fun onlyExactlyKassasOwnNamesMatch() {
        for (not in listOf(
            "Kassa_20261031_090507_protected.kassa", "Kassa_20261031_090507 (1).kassa", "kassa_20261031_090507.kassa", "Kassa_20261031_090507.kassa.bak",
            "Kassa_2026103_090507.kassa", "Kassa_20261031_09050.kassa", "Kassa_20261331_090507.kassa", "Kassa_20260230_090507.kassa", "Kassa_20261031_250507.kassa",
            "Kassa_.kassa", "Kassa.kassa", "notes.txt", "Photos", "Money Manager_20261031.mmbak", "", "Kassa_20261031_090507.kassa\n",
        )) assertFalse(BackupNames.isOurs(not), not)
    }

    @Test
    fun thirtyFilesAreKeptAndOlderOnesGo() {
        val names = (0 until 35).map(::day)
        val gone = BackupNames.toDelete(names)
        assertEquals(5, gone.size)
        // The five oldest.
        assertEquals((0 until 5).map(::day).toSet(), gone.toSet())
        assertEquals(names.drop(5), (names - gone.toSet()))
    }

    @Test
    fun nothingIsDeletedUntilThereAreMoreThanThirty() {
        assertTrue(BackupNames.toDelete((0 until 30).map(::day)).isEmpty())
        assertTrue(BackupNames.toDelete(emptyList()).isEmpty())
        assertEquals(1, BackupNames.toDelete((0 until 31).map(::day)).size)
    }

    @Test
    fun filesThatDontMatchAreNeverDeletedAndDontCountTowardTheThirty() {
        val ours = (0 until 33).map(::day)
        val others = listOf(
            "Kassa_20200101_000000_protected.kassa", "Kassa_20200101_000000 (1).kassa", "holiday.jpg", "Kassa_notes.kassa", "old.mmbak", "Kassa_20200101_000000.kassa.tmp",
        )
        val gone = BackupNames.toDelete(others + ours)
        assertEquals(3, gone.size)
        assertTrue(gone.all { it in ours })
        assertTrue(others.none { it in gone })
    }

    @Test
    fun theOrderIsByTheTimeInTheNameNotTheOrderListed() {
        val ours = (0 until 33).map(::day).shuffled(java.util.Random(7))
        assertEquals((0 until 3).map(::day).toSet(), BackupNames.toDelete(ours).toSet())
        // A year boundary and different months still sort by time.
        val across = listOf("Kassa_20261231_235959.kassa", "Kassa_20270101_000001.kassa", "Kassa_20260101_000000.kassa")
        assertEquals(listOf("Kassa_20260101_000000.kassa"), BackupNames.toDelete(across, keep = 2))
    }

    @Test
    fun theSameNameTwiceCountsOnce() {
        val a = day(1)
        assertTrue(BackupNames.toDelete(List(40) { a }).isEmpty())
    }

    @Test
    fun keepingFewerThanOneIsNotPossible() {
        assertEquals(1, BackupNames.toDelete(listOf(day(1), day(2)), keep = 0).size)
    }
}

package app.kassa.money.domain

import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoTest {
    private val today = LocalDate.parse("2026-10-24")
    private val demo = Demo.data(today, ZoneOffset.UTC)

    @Test
    fun sameSeedSameData() {
        assertEquals(demo, Demo.data(today, ZoneOffset.UTC))
    }

    @Test
    fun everyEntryPointsAtSomethingThatExists() {
        val cats = demo.categories.map { it.id }.toSet()
        val accounts = demo.accounts.map { it.id }.toSet()
        assertTrue(demo.transactions.size > 100)
        demo.transactions.forEach { t ->
            t.categoryId?.let { assertTrue(it in cats) }
            t.accountId?.let { assertTrue(it in accounts) }
            t.toAccountId?.let { assertTrue(it in accounts) }
        }
        assertEquals(demo.transactions.size, demo.transactions.map { it.id }.toSet().size)
    }

    @Test
    fun namesFitTheLimits() {
        demo.categories.forEach { assertTrue(it.name.length <= InputRules.CATEGORY_NAME_MAX) }
        demo.accounts.forEach { assertTrue(it.name.length <= InputRules.NAME_MAX) }
        demo.transactions.forEach { assertTrue(it.note.length <= InputRules.NOTE_MAX) }
    }

    @Test
    fun repeatingChargesShowUp() {
        val repeats = Repeats.find(demo.transactions, today, ZoneOffset.UTC).map { it.name }
        assertTrue("Streaming" in repeats && "Salary" in repeats && "Electricity" in repeats, repeats.toString())
    }
}

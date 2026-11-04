package app.kassa.money.shared

import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupJson
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/** The demo backup's Accounts and Activity figures from the shared rules equal the ones `tools/make_demo_backup.py` worked out from the entries alone. */
class DemoBackupTest {
    private val dir = "../app/src/test/resources/backup-demo/"
    private fun demo(): BackupData {
        val bytes = File(dir + "demo.kassa").readBytes()
        return BackupJson.parse(GZIPInputStream(ByteArrayInputStream(bytes.copyOfRange(6, bytes.size))).readBytes().decodeToString())
    }
    private fun num(json: String, key: String): Long = Regex("\"$key\": (-?\\d+)").find(json)!!.groupValues[1].toLong()
    private fun usd(cents: Long): String {
        val a = abs(cents)
        val whole = (a / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        return (if (cents < 0) "-" else "") + "$" + whole + "." + (a % 100).toString().padStart(2, '0')
    }

    @Test
    fun accountsMatchTheWorkedOutFigures() {
        KassaCore.installDefaults()
        val json = File(dir + "expected.json").readText()
        val a = LedgerView(demo(), null).accounts(false, num(json, "todayEpochDay").toInt(), "UTC")
        assertEquals(usd(num(json, "assets")), a.assetsText)
        assertEquals(usd(num(json, "liabilities")), a.liabilitiesText)
        assertEquals(usd(num(json, "netWorth")), a.netWorthText)
        val rows = a.groups.flatMap { it.rows }
        assertEquals(usd(num(json, "1")), rows.first { it.name == "Everyday checking" }.amountText)
        assertEquals(false, rows.any { it.name == "Old savings" && it.dueKind.name != "Closed" })
    }

    private val mask = "\u2022\u2022\u2022\u2022"

    /** 2026-10-31, the last day of the month `expected.json` works out (its own "today" is 3 November). */
    private val lastOfOctober = 20757

    @Test
    fun overviewFiguresOfOctober() {
        KassaCore.installDefaults()
        val json = File(dir + "expected.json").readText()
        val o = LedgerView(demo(), null).overview(false, lastOfOctober, "UTC")
        assertEquals(2026, o.year)
        assertEquals(10, o.month)
        // The months of expected.json: "2026-10": spending 276329, income 520286.
        assertEquals(usd(276_329), o.spending.totalText)
        assertEquals(usd(520_286), o.income.totalText)
        assertEquals("Green", o.income.tone.name)
        assertEquals("Ink", o.spending.tone.name)
        assertEquals(listOf("Home", "Groceries", "Dining"), o.spending.top.map { it.name })
        assertEquals(listOf(usd(180_000), usd(44_320), usd(19_223)), o.spending.top.map { it.amountText })
        assertEquals(listOf("Salary", "Interest"), o.income.top.map { it.name })
        assertEquals("symbol", o.spending.top[0].iconKind)
        assertEquals("home", o.spending.top[0].iconValue)
        // The figures of the Accounts tab are the same ones the pins show.
        val accounts = LedgerView(demo(), null).accounts(false, num(json, "todayEpochDay").toInt(), "UTC")
        val checking = accounts.groups.flatMap { it.rows }.first { it.name == "Everyday checking" }
        val pin = LedgerView(demo(), null).overview(false, num(json, "todayEpochDay").toInt(), "UTC").pins.first { it.name == "Everyday checking" }
        assertEquals(checking.amountText, pin.amountText)
    }

    @Test
    fun overviewTodayIsTheCurrentMonthUpToToday() {
        KassaCore.installDefaults()
        val json = File(dir + "expected.json").readText()
        val o = LedgerView(demo(), null).overview(false, num(json, "todayEpochDay").toInt(), "UTC")
        assertEquals(11, o.month)
        assertEquals(usd(181_599), o.spending.totalText)
        assertEquals(usd(260_000), o.income.totalText)
        assertEquals(listOf("Home", "Subscriptions"), o.spending.top.map { it.name })
    }

    @Test
    fun overviewPinsFollowAccountsOrderAndEndWithPeople() {
        KassaCore.installDefaults()
        val o = LedgerView(demo(), null).overview(false, lastOfOctober, "UTC")
        // Open accounts A to Z (Old savings is closed), then People.
        assertEquals(
            listOf("Brokerage", "Car loan", "Everyday checking", "Rainy day savings", "Travel card", "Wallet", ""),
            o.pins.map { it.name },
        )
        assertEquals(true, o.pins.last().isPeople)
        assertEquals(false, o.pins.first().isPeople)
        assertEquals(true, o.pins.size <= LedgerView.MAX_PINS)
        // A loan shows what is owed, with no minus.
        val loan = o.pins.first { it.name == "Car loan" }
        assertEquals(false, loan.amountText.contains("-"))
    }

    @Test
    fun overviewHiddenMasksEveryAmountAndColour() {
        KassaCore.installDefaults()
        val o = LedgerView(demo(), null).overview(true, lastOfOctober, "UTC")
        assertEquals(mask, o.spending.totalText)
        assertEquals(mask, o.income.totalText)
        assertEquals("Ink", o.income.tone.name)
        (o.spending.top + o.income.top).forEach { assertEquals(mask, it.amountText) }
        o.pins.forEach {
            assertEquals(mask, it.amountText)
            assertEquals("Ink", it.amountTone.name)
            if (it.dueKind.name != "Nothing") assertEquals(mask, it.dueAmountText)
        }
    }
}

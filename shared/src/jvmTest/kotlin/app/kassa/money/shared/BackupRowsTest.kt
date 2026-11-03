package app.kassa.money.shared

import app.kassa.money.backup.BackupAccount
import app.kassa.money.backup.BackupBill
import app.kassa.money.backup.BackupBudget
import app.kassa.money.backup.BackupCategory
import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupGroup
import app.kassa.money.backup.BackupHolding
import app.kassa.money.backup.BackupInvestmentTransfer
import app.kassa.money.backup.BackupJson
import app.kassa.money.backup.BackupLoanChange
import app.kassa.money.backup.BackupLoanEntry
import app.kassa.money.backup.BackupLoanPayment
import app.kassa.money.backup.BackupLoanTerms
import app.kassa.money.backup.BackupPerson
import app.kassa.money.backup.BackupTrade
import app.kassa.money.backup.BackupTransaction
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The flat cell API the iOS database code uses: data to cells and back through [BackupBuilder] gives the same data. */
class BackupRowsTest {

    private fun golden(): BackupData {
        val bytes = File("../app/src/test/resources/backup-golden/plain.kassa").readBytes()
        assertEquals("KASSA0", bytes.copyOfRange(0, 6).decodeToString())
        val json = GZIPInputStream(ByteArrayInputStream(bytes.copyOfRange(6, bytes.size))).readBytes().decodeToString()
        return BackupJson.parse(json)
    }

    /** What the Swift side does: walk every table cell by cell and feed a builder. */
    private fun roundTrip(data: BackupData): BackupData {
        val builder = BackupBuilder(data.createdAt, data.monthStartDay, data.currency, data.accountArrangement)
        for (table in BackupRows.tableNames()) {
            val columns = BackupRows.columns(table).size
            for (row in 0 until BackupRows.rowCount(data, table)) {
                for (column in 0 until columns) builder.setCell(table, column, BackupRows.cell(data, table, row, column))
                builder.endRow(table)
            }
        }
        for (name in BackupRows.iconNames(data)) builder.addIcon(name, BackupRows.icon(data, name)!!)
        return builder.build()
    }

    private val everything = BackupData(
        createdAt = 9, monthStartDay = 15, currency = "GBP", accountArrangement = "G:a",
        groups = listOf(BackupGroup(1, "g", 2)),
        accounts = listOf(
            BackupAccount(1, "a", "LOAN", 1, -5, 3, 4, 2, 6, true, 1, "ext", "1234", 7, 8),
            BackupAccount(2, "b", "CASH", openingBalance = 0, archived = false, sortOrder = 0),
        ),
        categories = listOf(BackupCategory(1, "c", "EXPENSE", 2, "i", -3, true, true, 4, "x"), BackupCategory(2, "d", "INCOME", null, "", 0, false, false, 0)),
        transactions = listOf(
            BackupTransaction(1, "TRANSFER", 5, 1, 2, 3, 4, 6, "n ☕ \"q\" \n", 7, "e"),
            BackupTransaction(2, "INCOME", Long.MAX_VALUE, null, null, null, null, Long.MIN_VALUE, ""),
        ),
        budgets = listOf(BackupBudget(1, 0, 300)),
        people = listOf(BackupPerson(1, "p", true)),
        loans = listOf(BackupLoanEntry(1, 2, -3, 4, "n", 5)),
        bills = listOf(BackupBill(1, "b", 2, 3, 28, 4_00, true, 202610), BackupBill(2, "c", 2, 3, 1, null, false, 0)),
        holdings = listOf(BackupHolding(1, 2, "VTI", 1_500_000, 25_000, 99, 24_000)),
        trades = listOf(BackupTrade(1, 2, "VTI", -3, 4)),
        loanTerms = listOf(BackupLoanTerms(1, "FLAT", true, 61250, 2, 60, 3, 4, 5, 6, 7, 8, 9, 10, 11, true, 12)),
        loanChanges = listOf(BackupLoanChange(1, 2, 3, 4, 5, 6, "REDUCING"), BackupLoanChange(2, 2, 3)),
        loanPayments = listOf(BackupLoanPayment(1, 2, 3, 4, 5, 6, 7, 8, 9, true, 10, false, true, 11), BackupLoanPayment(2, 2, 3, 4, null, null, null, 0, 0, false, 0, true, null, null)),
        investmentTransfers = listOf(BackupInvestmentTransfer(1, 2, 3, 4, 5)),
        icons = mapOf("b.png" to byteArrayOf(1, 2, 3, -1), "a.png" to ByteArray(0)),
    )

    @Test fun theGoldenFileSurvivesDataToCellsToData() {
        val data = golden()
        val back = roundTrip(data)
        assertEquals(data, back)
        assertEquals(BackupJson.write(data), BackupJson.write(back))
    }

    @Test fun everyTableAndOptionalFieldSurvives() {
        val back = roundTrip(everything)
        assertEquals(everything, back)
        assertEquals(BackupJson.write(everything).length, BackupJson.write(back).length)
    }

    @Test fun anEmptyBackupSurvives() {
        val empty = BackupData(createdAt = 0, monthStartDay = 1)
        assertEquals(empty, roundTrip(empty))
    }

    @Test fun cellsAreTextAndNullsStayNull() {
        val data = golden()
        val accounts = BackupRows.columns("accounts")
        assertEquals(accounts.size, BackupRows.columnKinds("accounts").size)
        assertEquals("Checking", BackupRows.cell(data, "accounts", 0, accounts.indexOf("name")))
        assertEquals("20500", BackupRows.cell(data, "accounts", 2, accounts.indexOf("closedDay")))
        assertNull(BackupRows.cell(data, "accounts", 0, accounts.indexOf("closedDay")))
        assertEquals("0", BackupRows.cell(data, "accounts", 0, accounts.indexOf("archived")))
        assertEquals("1", BackupRows.cell(everything, "accounts", 0, accounts.indexOf("archived")))
        assertEquals("99999999", BackupRows.cell(data, "transactions", 1, BackupRows.columns("transactions").indexOf("amount")))
    }

    @Test fun outOfRangeAndUnknownThingsAreNullOrEmpty() {
        val data = golden()
        assertNull(BackupRows.cell(data, "accounts", 99, 0))
        assertNull(BackupRows.cell(data, "accounts", -1, 0))
        assertNull(BackupRows.cell(data, "accounts", 0, 99))
        assertNull(BackupRows.cell(data, "nope", 0, 0))
        assertEquals(0, BackupRows.rowCount(data, "nope"))
        assertTrue(BackupRows.columns("nope").isEmpty() && BackupRows.columnKinds("nope").isEmpty())
        assertNull(BackupRows.icon(data, "nope"))
    }

    @Test fun kindsAreIntOrText() {
        for (table in BackupRows.tableNames()) {
            assertTrue(BackupRows.columnKinds(table).all { it == "int" || it == "text" }, table)
            assertEquals(BackupRows.columns(table).size, BackupRows.columnKinds(table).size, table)
        }
        assertEquals(14, BackupRows.tableNames().size)
    }

    private fun builder() = BackupBuilder(1, 1, null, null)

    @Test fun aBuilderRefusesWhatDoesNotFit() {
        val b = builder()
        assertFailsWith<IllegalArgumentException> { b.setCell("nope", 0, "1") }
        assertFailsWith<IllegalArgumentException> { b.setCell("people", 3, "1") }
        assertFailsWith<IllegalArgumentException> { b.endRow("people") } // nothing set
        b.setCell("people", 0, "1")
        b.setCell("people", 1, "x")
        assertFailsWith<IllegalArgumentException> { b.endRow("people") } // archived not set
        b.setCell("people", 2, "yes")
        assertFailsWith<IllegalArgumentException> { b.endRow("people") } // not 0 or 1
        b.setCell("people", 2, "1")
        b.endRow("people")
        assertEquals(listOf(BackupPerson(1, "x", true)), b.build().people)

        b.setCell("people", 0, "1.5")
        b.setCell("people", 1, "x")
        b.setCell("people", 2, "0")
        assertFailsWith<IllegalArgumentException> { b.endRow("people") } // not an integer
        b.setCell("people", 0, null)
        assertFailsWith<IllegalArgumentException> { b.endRow("people") } // required but null
        assertFailsWith<IllegalArgumentException> { b.addIcon("a.png", "***") }
        assertEquals(1, b.build().people.size)
    }

    @Test fun aBuilderKeepsDuplicatesForTheDatabaseToRefuse() {
        val b = builder()
        repeat(2) {
            b.setCell("account_groups", 0, "1"); b.setCell("account_groups", 1, "G"); b.setCell("account_groups", 2, "0")
            b.endRow("account_groups")
        }
        assertEquals(2, b.build().groups.size)
    }

    @Test fun emptyCurrencyAndArrangementReadAsNull() {
        val d = BackupBuilder(1, 1, "", "").build()
        assertNull(d.currency)
        assertNull(d.accountArrangement)
    }

    /** Swift's Schema.swift must list the same tables and columns, in the same order, as the cell API. */
    @Test fun schemaSwiftMatchesTheCellApi() {
        val text = File("../ios/Kassa/Storage/Schema.swift").readText()
        val creates = Regex("\"CREATE TABLE IF NOT EXISTS `(\\w+)` \\((.*)\\)\",").findAll(text).toList()
        assertEquals(BackupRows.tableNames(), creates.map { it.groupValues[1] })
        for (m in creates) {
            val table = m.groupValues[1]
            val defs = Regex("`(\\w+)` (INTEGER|TEXT)").findAll(m.groupValues[2]).toList()
            assertEquals(BackupRows.columns(table), defs.map { it.groupValues[1] }, table)
            assertEquals(BackupRows.columnKinds(table), defs.map { if (it.groupValues[2] == "INTEGER") "int" else "text" }, table)
        }
        assertEquals("static let version = 10", Regex("static let version = \\d+").find(text)!!.value)
    }
}

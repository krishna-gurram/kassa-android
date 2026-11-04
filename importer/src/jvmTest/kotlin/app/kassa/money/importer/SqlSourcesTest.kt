package app.kassa.money.importer

import app.kassa.money.domain.AccountType
import kotlinx.datetime.TimeZone
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The platform-neutral readers the iOS app feeds with SQL answers (`SqlResults`), the file-kind rules and the Cashew questions.
 * All data is synthetic, shaped like the real tables.
 */
class SqlSourcesTest {

    /** Pours rows into [SqlResults] the way Swift does: columns first, then each cell by its SQLite type. */
    private fun SqlResults.table(name: String, columns: List<String>, vararg rows: List<Any?>) {
        begin(name)
        columns.forEach { addColumn(it) }
        for (row in rows) {
            for (cell in row) when (cell) {
                null -> addNull()
                is Long -> addInteger(cell)
                is Int -> addInteger(cell.toLong())
                is Double -> addReal(cell)
                else -> addText(cell.toString())
            }
            endRow()
        }
        end()
    }

    private fun mmResults(): SqlResults {
        val r = SqlResults()
        r.table("groups", listOf("uid", "name", "type", "order"), listOf("g-bank", "Bank", 1, 0), listOf("g-card", "Cards", 2, 1))
        r.table(
            "assets", listOf("uid", "name", "group", "order", "fin", "pay", "start"),
            listOf("a-check", "Checking", "g-bank", 0, null, null, "1000.00"),
            listOf("a-card", "Card", "g-card", 1, 5, 25, "0"),
        )
        r.table("categories", listOf("uid", "name", "type", "p", "del", "order"), listOf("c-food", "Food", 1, null, 0, 0), listOf("c-pay", "Salary", 0, null, 0, 1))
        r.table(
            "transactions", listOf("uid", "type", "asset", "to", "cat", "money", "date", "content", "pair"),
            listOf("t1", 1, "a-check", null, "c-food", "12.50", "1760000000000", "Lunch", null),
            listOf("t2", 0, "a-check", null, "c-pay", 2000.0, "1760100000000", "Pay", null),
            listOf("t3", 3, "a-check", "a-card", null, "100", "1760200000000", "Card payment", "p1"),
            listOf("t4", 4, "a-card", "a-check", null, "100", "1760200000000", "Card payment", "p1"),
        )
        r.table("budgets", listOf("target", "period", "amount", "uid"), listOf("c-food", 0, 300.0, "b1"))
        r.table("n.photos", listOf("n"), listOf(2L))
        r.table("mainCurrency", listOf("iso"), listOf("EUR"))
        return r
    }

    @Test
    fun theMoneyManagerReaderMapsRowsLikeAndroidsCursor() {
        val source = MoneyManagerReader(mmResults(), iphone = false)
        assertEquals(listOf("Bank", "Cards"), source.groups().map { it.name })
        val assets = source.assets()
        assertEquals("1000.00", assets[0].startingAmount)
        assertEquals(5, assets[1].statementDay)
        assertEquals(25, assets[1].dueDay)
        // A float amount becomes plain decimal text; text stays as it is.
        val tx = source.transactions()
        assertEquals("12.50", tx[0].amount)
        assertEquals("2000.0", tx[1].amount)
        assertEquals(1_760_000_000_000L, tx[0].epochMillis)
        assertEquals("p1", tx[2].pairKey)
        assertEquals(2, source.extras().photos)
        assertEquals(0, source.extras().repeating)
        assertEquals("EUR", source.mainCurrency())
        assertEquals(listOf(300.0), source.budgets().map { it.amount })
    }

    @Test
    fun aMoneyManagerPlanFromSqlAnswersMatchesTheSourceBalances() {
        val plan = MoneyManagerImporter(TimeZone.UTC).plan(MoneyManagerReader(mmResults(), iphone = false))
        assertTrue(plan.reconcile.allMatch)
        assertEquals(2, plan.accounts.size)
        assertEquals(3, plan.transactions.size)
        // The transfer is one entry, from the out half.
        assertEquals(1, plan.transactions.count { it.toAccountId != null })
        assertEquals(1, plan.budgets.size)
    }

    @Test
    fun theIphoneLayoutReadsCoreDataDatesAndAmounts() {
        val r = SqlResults()
        r.table("groups", listOf("a", "b", "c", "d"), listOf("g", "Bank", 1, 0))
        r.table("assets", listOf("a", "b", "c", "d", "e", "f", "g"), listOf("a1", "Main", "g", 0, null, null, "5"))
        r.table("assetDeleted", listOf("u", "d"), listOf("a1", 1))
        r.table("categories", listOf("a", "b", "c", "d", "e", "f"))
        r.table("transactions", listOf("a", "b", "c", "d", "e", "f", "g", "h", "i"), listOf("t", 1, "a1", null, null, 7.25, 0.0, null, null))
        r.table("budgets", listOf("a", "b", "c", "d"))
        val source = MoneyManagerReader(r, iphone = true)
        assertTrue(source.assets()[0].deleted)
        assertEquals("7.25", source.transactions()[0].amount)
        assertEquals(978_307_200_000L, source.transactions()[0].epochMillis)
    }

    @Test
    fun aMissingRequiredAnswerIsAnErrorAndAMissingOptionalOneIsEmpty() {
        val r = SqlResults()
        assertTrue(runCatching { MoneyManagerReader(r, false).groups() }.isFailure)
        r.table("assets", listOf("a", "b", "c", "d", "e", "f", "g"))
        assertEquals(0, MoneyManagerReader(r, false).extras().photos)
        assertNull(MoneyManagerReader(r, false).mainCurrency())
        r.skip("assets")
        assertFalse(r.has("assets"))
    }

    @Test
    fun theQueriesAreNamedAndRequiredOnesAreMarked() {
        for (iphone in listOf(false, true)) {
            val list = MoneyManagerSql.queries(iphone)
            assertEquals(list.size, list.map { it.name }.toSet().size)
            assertEquals(setOf("groups", "assets", "categories", "transactions", "budgets"), list.filter { !it.optional }.map { it.name }.toSet())
        }
        assertEquals(setOf("wallets", "categories", "transactions"), CashewSql.queries().filter { !it.optional }.map { it.name }.toSet())
    }

    @Test
    fun decimalTextWritesFloatsWithoutExponents() {
        assertEquals("12.5", DecimalText.plain(12.5))
        assertEquals("100.0", DecimalText.plain(100.0))
        assertEquals("0.000010", DecimalText.plain(1.0E-5))
        assertEquals("12345678.0", DecimalText.plain(1.2345678E7))
        assertEquals("-0.5", DecimalText.plain(-0.5))
        assertEquals("0", DecimalText.plain(Double.NaN))
    }

    @Test
    fun fileKindsComeFromTheFirstBytesAndTheTables() {
        assertEquals("EMPTY", PickedFileRules.kindOfHead("", 0))
        assertEquals("KASSA", PickedFileRules.kindOfHead("KASSA0\u001f", 50))
        assertEquals("KASSA_PROTECTED", PickedFileRules.kindOfHead("KASSA1xxxxxxxxxx", 50))
        assertEquals("SQLITE", PickedFileRules.kindOfHead("SQLite format 3\u0000", 4096))
        assertEquals("TEXT", PickedFileRules.kindOfHead("date,amount,notes,more", 500))
        assertEquals("OTHER", PickedFileRules.kindOfHead("%PDF\u0000\u0001\u0002\u0003", 500))
        assertTrue(PickedFileRules.refusal("TEXT").startsWith("This looks like a spreadsheet or text file."))
        assertEquals("", PickedFileRules.refusal("SQLITE"))
        assertEquals("MONEY_MANAGER", PickedFileRules.kindOfTables(listOf("INOUTCOME", "ASSETS", "ZCATEGORY", "x")))
        assertEquals("MONEY_MANAGER", PickedFileRules.kindOfTables(listOf("ZINOUTCOME", "ZASSET", "ZASSETGROUP", "ZCATEGORY")))
        assertEquals("CASHEW", PickedFileRules.kindOfTables(listOf("wallets", "transactions", "categories")))
        assertEquals("UNKNOWN", PickedFileRules.kindOfTables(listOf("wallets")))
        assertTrue(MoneyManagerSql.isIphoneLayout(listOf("ZINOUTCOME", "ZASSET", "ZASSETGROUP", "ZCATEGORY")))
        assertFalse(MoneyManagerSql.isIphoneLayout(listOf("INOUTCOME", "ASSETS", "ZCATEGORY", "ZINOUTCOME", "ZASSET", "ZASSETGROUP")))
    }

    // ---------- Cashew ----------

    private fun cashewResults(): SqlResults {
        val r = SqlResults()
        r.table("wallets", listOf("wallet_pk", "name", "currency", "order"), listOf("w1", "Everyday", "usd", 0), listOf("w2", "Euro stash", "eur", 1))
        r.table(
            "categories", listOf("category_pk", "name", "income", "order", "main_category_pk"),
            listOf("0", "Balance Correction", 0, 0, null), listOf("food", "Food", 0, 1, null), listOf("pay", "Salary", 1, 2, null),
        )
        r.table(
            "transactions", listOf("transaction_pk", "name", "amount", "category_fk", "wallet_fk", "date_created", "paid", "note"),
            listOf("t1", "Lunch", -12.5, "food", "w1", 1_760_000_000L, 1, ""),
            listOf("t2", "Pay", "2000", "pay", "w1", 1_760_100_000L, "true", ""),
            listOf("t3", "Cafe", -4.0, "food", "w2", 1_760_200_000L, 1, ""),
        )
        r.table("settings", listOf("settings_j_s_o_n"), listOf("{\"selectedWalletPk\": \"w1\"}"))
        return r
    }

    @Test
    fun theCashewReaderReadsByColumnNameWithDefaults() {
        val source = CashewReader(cashewResults())
        assertNull(CashewSql.refusal(cashewResults()))
        assertEquals(listOf("usd", "eur"), source.wallets().map { it.currency })
        val rows = source.transactions()
        assertEquals("-12.5", rows[0].amount)
        assertEquals("2000", rows[1].amount)
        assertEquals(1_760_000_000_000L, rows[0].epochMillis)
        assertTrue(rows[1].paid)
        assertEquals(emptyList(), source.objectives())
        assertEquals("w1", source.primaryWalletPk())
    }

    @Test
    fun aCashewBackupMissingARequiredColumnIsRefused() {
        val r = cashewResults()
        r.table("wallets", listOf("wallet_pk"), listOf("w1"))
        assertEquals(CashewSql.OLD_VERSION, CashewSql.refusal(r))
        r.skip("wallets")
        assertEquals(CashewSql.OLD_VERSION, CashewSql.refusal(r))
    }

    @Test
    fun theCashewQuestionsGoInOrderAndThePlanMatchesBalances() {
        val source = CashewReader(cashewResults())
        val importer = CashewImporter(TimeZone.UTC)
        val interview = CashewInterview(importer.scan(source), "USD")
        assertEquals(CashewInterview.Step.FOUND, interview.step)
        assertTrue(interview.canGoOn())
        assertFalse(interview.next())
        assertEquals(CashewInterview.Step.ACCOUNTS, interview.step)
        assertFalse(interview.canGoOn())
        interview.setAccountType("w1", AccountType.CHECKING)
        assertFalse(interview.canGoOn())
        interview.setAccountType("w2", AccountType.SAVINGS)
        assertTrue(interview.canGoOn())
        // No loans, but one account is in euros: that question comes next.
        assertFalse(interview.next())
        assertEquals(CashewInterview.Step.CURRENCY, interview.step)
        assertEquals(listOf("Euro stash"), interview.otherCurrency.map { it.name })
        assertTrue(interview.back().not())
        assertEquals(CashewInterview.Step.ACCOUNTS, interview.step)
        interview.next()
        assertTrue(interview.next())
        val plan = interview.plan(source, importer)
        assertTrue(plan.reconcile.allMatch)
        assertEquals(3, plan.transactions.size)
        assertTrue(plan.issues.any { it is ImportIssue.OtherCurrency })
        // Leaving the euro account out drops its entry and the note.
        interview.setLeaveOut("w2", true)
        val without = interview.plan(source, importer)
        assertEquals(2, without.transactions.size)
        assertFalse(without.issues.any { it is ImportIssue.OtherCurrency })
    }

    @Test
    fun backFromTheFirstQuestionLeavesTheInterview() {
        val interview = CashewInterview(CashewImporter(TimeZone.UTC).scan(CashewReader(cashewResults())), "USD")
        assertTrue(interview.back())
    }
}

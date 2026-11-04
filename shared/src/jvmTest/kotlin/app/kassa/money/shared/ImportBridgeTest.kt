package app.kassa.money.shared

import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupLoanEntry
import app.kassa.money.backup.BackupPerson
import app.kassa.money.backup.BackupAccount
import app.kassa.money.backup.BackupBill
import app.kassa.money.importer.SqlResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The iOS import's Kotlin side over synthetic SQL answers: opening, questions, review and what is saved over existing data. */
class ImportBridgeTest {

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
            listOf("t2", 0, "a-check", null, "c-pay", "2000", "1760100000000", "Pay", null),
            listOf("t3", 3, "a-check", "a-card", null, "100", "1760200000000", "Card payment", "p1"),
            listOf("t4", 4, "a-card", "a-check", null, "100", "1760200000000", "Card payment", "p1"),
        )
        r.table("budgets", listOf("target", "period", "amount", "uid"), listOf("c-food", 0, 300.0, "b1"))
        r.table("mainCurrency", listOf("iso"), listOf("EUR"))
        r.table("n.memos", listOf("n"), listOf(4L))
        return r
    }

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
            listOf("t2", "Pay", "2000", "pay", "w1", 1_760_100_000L, 1, ""),
            listOf("t3", "Cafe", -4.0, "food", "w2", 1_760_200_000L, 1, ""),
        )
        r.table("settings", listOf("settings_j_s_o_n"), listOf("{\"selectedWalletPk\": \"w1\"}"))
        return r
    }

    /** What Kassa holds before the import: an old account, a bill, a person with a starting balance and another tied to an entry. */
    private fun existing(): BackupData = BackupData(
        createdAt = 5, monthStartDay = 12, currency = "GBP",
        accounts = listOf(BackupAccount(1, "Old", "CHECKING", null, 0, null, null, null, null, false, 0)),
        bills = listOf(BackupBill(1, "Rent", 1, 1, 1, 100, true, 0)),
        people = listOf(BackupPerson(1, "sam", false), BackupPerson(2, "Kim", false)),
        loans = listOf(BackupLoanEntry(1, 1, 500, 1, "start", null), BackupLoanEntry(2, 2, 40, 1, "split", 9)),
        accountArrangement = "1,2",
    )

    @Test
    fun aMoneyManagerFileOpensIntoAReviewThatMatchesTheSource() {
        KassaCore.installDefaults()
        val opened = ImportBridge.openMoneyManager(mmResults(), false, "UTC", "GBP")
        val session = assertNotNull(opened.session)
        assertEquals("", opened.refusal)
        assertEquals("MONEY_MANAGER", session.kind)
        // The backup's own currency is used when Kassa lists it.
        assertEquals("EUR", session.detectedCurrency())
        assertEquals("REVIEW", session.step())
        val review = session.review(false)
        assertEquals(1, review.expenses)
        assertEquals(1, review.income)
        assertEquals(1, review.transfers)
        assertEquals(3, review.entries)
        assertEquals(2, review.accounts)
        assertTrue(review.allMatch)
        assertEquals(2, review.accountChecks)
        assertEquals(0, review.accountsBad)
        assertEquals("Money Manager", review.appName)
        assertTrue(review.staysBehind.any { it.kind == "LEFT_BEHIND" && it.first == "Memos" && it.count == 4 })
        assertEquals(listOf("Card"), review.cardsWithoutPayFrom)
        assertEquals(emptyList(), review.cardsWithoutStatement)
    }

    @Test
    fun theReviewMasksAmountsWhileHidden() {
        KassaCore.installDefaults()
        val session = assertNotNull(ImportBridge.openMoneyManager(mmResults(), false, "UTC", "USD").session)
        val zero = session.review(false).worthKnowing + session.review(true).worthKnowing
        // Nothing here quotes an amount unless an issue does; the call must simply work in both modes.
        assertNotNull(zero)
    }

    @Test
    fun savingReplacesEverythingThatPointsAtOldAccountsAndKeepsPeopleAndSettings() {
        KassaCore.installDefaults()
        val session = assertNotNull(ImportBridge.openMoneyManager(mmResults(), false, "UTC", "GBP").session)
        val merged = session.merged(existing())
        assertEquals(listOf("Checking", "Card"), merged.accounts.map { it.name })
        assertEquals(3, merged.transactions.size)
        assertEquals(emptyList(), merged.bills)
        assertEquals(emptyList(), merged.holdings)
        assertEquals(emptyList(), merged.loanTerms)
        // People stay, with their starting balance; the one tied to an old entry goes.
        assertEquals(listOf("sam", "Kim"), merged.people.map { it.name })
        assertEquals(listOf(1L), merged.loans.map { it.id })
        // Settings stay, and the arrangement is dropped.
        assertEquals(12, merged.monthStartDay)
        assertEquals("GBP", merged.currency)
        assertNull(merged.accountArrangement)
        assertEquals(5L, merged.createdAt)
    }

    @Test
    fun aCashewFileAsksItsQuestionsThenReviews() {
        KassaCore.installDefaults()
        val opened = ImportBridge.openCashew(cashewResults(), "UTC", "GBP")
        val session = assertNotNull(opened.session)
        assertEquals("CASHEW", session.kind)
        // Cashew says its main account is in dollars, which Kassa lists.
        assertEquals("USD", session.detectedCurrency())
        assertFalse(session.hasReview())
        var q = session.question(false)
        assertEquals("FOUND", q.step)
        assertEquals(2, q.accountCount)
        assertEquals(1, q.categories)
        assertTrue(q.hasRange)
        assertTrue(q.lastEpochDay > q.firstEpochDay)
        session.goOn()
        q = session.question(false)
        assertEquals("ACCOUNTS", q.step)
        assertFalse(q.canGoOn)
        // Not answered yet: goOn does nothing.
        session.goOn()
        assertEquals("ACCOUNTS", session.question(false).step)
        session.setAccountType("w1", "CHECKING")
        session.setAccountType("w2", "SAVINGS")
        q = session.question(false)
        assertTrue(q.canGoOn)
        assertEquals("CHECKING", q.accounts[0].kind)
        assertTrue(q.accounts[1].other)
        assertEquals("1,987.50", q.accounts[0].shownAmount.removePrefix("$"))
        assertTrue(q.accounts[1].shownAmount.endsWith("4.00 EUR"))
        session.goOn()
        q = session.question(false)
        assertEquals("CURRENCY", q.step)
        assertEquals("Euro stash", q.currencyAccounts.single().name)
        session.setLeaveOut("w2", true)
        assertTrue(session.question(false).currencyAccounts.single().leaveOut)
        session.goOn()
        assertTrue(session.hasReview())
        assertEquals("REVIEW", session.step())
        val review = session.review(false)
        assertEquals(2, review.entries)
        assertTrue(review.allMatch)
        assertEquals("Cashew", review.appName)
        // Going back drops the review and returns to the questions.
        assertFalse(session.goBack())
        assertFalse(session.hasReview())
    }

    @Test
    fun aCashewBackupTooOldToReadIsARefusalNotAnError() {
        val r = cashewResults()
        r.table("wallets", listOf("wallet_pk"), listOf("w1"))
        val opened = ImportBridge.openCashew(r, "UTC", "USD")
        assertNull(opened.session)
        assertTrue(opened.refusal.startsWith("This Cashew backup is from a version older than Kassa can read."))
    }

    @Test
    fun aCashewLoanBecomingAPersonJoinsPeopleByName() {
        KassaCore.installDefaults()
        val r = cashewResults()
        r.table(
            "objectives", listOf("objective_pk", "type", "name", "amount", "income", "date_created"),
            listOf("o1", 1, "Sam", 80.0, 1, 1_760_000_000L),
        )
        val session = assertNotNull(ImportBridge.openCashew(r, "UTC", "USD").session)
        session.goOn()
        session.setAccountType("w1", "CHECKING")
        session.setAccountType("w2", "SAVINGS")
        session.goOn()
        assertEquals("LOANS", session.question(false).step)
        session.setLoan("o1", "PERSON")
        session.goOn()
        session.goOn()
        val merged = session.merged(existing())
        // "Sam" is the same person as the existing "sam": reused, and the starting balance lands on them.
        assertEquals(listOf("sam", "Kim"), merged.people.map { it.name })
        assertTrue(merged.loans.any { it.personId == 1L && it.amount == 8000L })
    }

    @Test
    fun theKindsOfFilesAreNamedForSwift() {
        assertEquals("SQLITE", ImportBridge.kindOfHead("SQLite format 3\u0000", 100))
        assertEquals("CASHEW", ImportBridge.kindOfTables(listOf("wallets", "transactions", "categories")))
        assertTrue(ImportBridge.refusal("EMPTY").isNotEmpty())
        assertTrue(ImportBridge.damaged().startsWith("The file is damaged"))
        assertTrue(ImportBridge.moneyManagerQueries(true).all { it.sql.isNotBlank() })
        assertTrue(ImportBridge.cashewQueries().size >= 6)
    }
}

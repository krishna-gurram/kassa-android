package app.kassa.money.importer

import app.kassa.money.domain.AccountType
import app.kassa.money.domain.CategoryIcon
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.TxKind
import app.kassa.money.domain.balanceOf
import app.kassa.money.domain.personBalance
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic Cashew backups shaped like Cashew's real tables. No real data. */
class CashewImporterTest {
    private val zone = ZoneOffset.UTC
    private val importer = CashewImporter(zone)

    private fun day(d: Int, month: Int = 10) = LocalDate.of(2026, month, d).atStartOfDay(zone).toInstant().toEpochMilli()

    private class Fake(
        val wallets: List<CwWallet> = listOf(CwWallet("w1", "Everyday", null, 0)),
        val categories: List<CwCategory> = listOf(
            CwCategory("0", "Balance Correction", null, null, null, 0, false, null),
            CwCategory("food", "Food", "0xff3f9a5e", "cutlery.png", null, 1, false, null),
            CwCategory("coffee", "Coffee", null, "coffee-cup.png", null, 2, false, "food"),
            CwCategory("pay", "Salary", "0xff4570d6", "coin.png", null, 3, true, null),
        ),
        val transactions: List<CwTransaction> = emptyList(),
        val objectives: List<CwObjective> = emptyList(),
        val budgets: List<CwBudget> = emptyList(),
        val limits: List<CwCategoryLimit> = emptyList(),
        val primary: String? = "w1",
    ) : CashewSource {
        override fun wallets() = wallets
        override fun categories() = categories
        override fun transactions() = transactions
        override fun objectives() = objectives
        override fun budgets() = budgets
        override fun categoryLimits() = limits
        override fun primaryWalletPk() = primary
    }

    private var n = 0
    private fun tx(
        amount: String, wallet: String? = "w1", category: String? = "food", at: Long = day(1), title: String = "",
        note: String = "", sub: String? = null, paired: String? = null, paid: Boolean = true, skip: Boolean = false,
        type: Int? = null, re: Int? = null, period: Int? = null, loan: String? = null, pk: String = "t${++n}",
    ) = CwTransaction(pk, paired, title, amount, note, category, sub, wallet, at, !amount.startsWith("-"), type, paid, skip, re, period, null, loan)

    private val allTypes = CashewChoices(accountTypes = mapOf("w1" to AccountType.CHECKING, "w2" to AccountType.SAVINGS, "w3" to AccountType.CASH))

    private fun ImportPlan.balance(name: String) = accounts.first { it.name == name }.let { balanceOf(it, transactions) }

    @Test fun everydayEntriesComeInWithCategoriesAndMatchingBalances() {
        val plan = importer.plan(Fake(transactions = listOf(
            tx("-12.50", title = "Lunch"), tx("-4.25", category = "food", sub = "coffee", title = "Latte", note = "oat milk"),
            tx("2480", category = "pay", title = "Payroll"),
        )), allTypes)
        assertEquals(3, plan.transactions.size)
        val coffee = plan.categories.first { it.name == "Coffee" }
        assertEquals(plan.categories.first { it.name == "Food" }.id, coffee.parentId)
        assertEquals("Latte · oat milk", plan.transactions.first { it.categoryId == coffee.id }.note)
        assertEquals(TxKind.INCOME, plan.transactions.first { it.note == "Payroll" }.kind)
        assertEquals(246325L, plan.balance("Everyday"))
        assertTrue(plan.reconcile.allMatch)
        assertTrue(plan.categories.none { it.name == "Balance Correction" })
        assertEquals(AccountType.CHECKING, plan.accounts.single().type)
    }

    @Test fun linkedBalanceCorrectionsAreTransfers() {
        val w = listOf(CwWallet("w1", "Everyday", null, 0), CwWallet("w2", "Savings", null, 1))
        val plan = importer.plan(Fake(wallets = w, transactions = listOf(
            tx("-300", category = "0", pk = "a", paired = null, title = "Transfer"),
            tx("300", wallet = "w2", category = "0", pk = "b", paired = "a", title = "Transfer"),
        )), allTypes)
        val t = plan.transactions.single()
        assertEquals(TxKind.TRANSFER, t.kind)
        assertEquals(30000L, t.amount)
        assertEquals(plan.accounts.first { it.name == "Everyday" }.id, t.accountId)
        assertEquals(plan.accounts.first { it.name == "Savings" }.id, t.toAccountId)
        assertTrue(plan.reconcile.allMatch)
    }

    @Test fun olderUnlinkedTransfersPairWithinAMinute() {
        val w = listOf(CwWallet("w1", "Everyday", null, 0), CwWallet("w2", "Savings", null, 1))
        val plan = importer.plan(Fake(wallets = w, transactions = listOf(
            tx("-50", category = "0", at = day(3)), tx("50", wallet = "w2", category = "0", at = day(3) + 20_000),
            // An hour apart: not a transfer, two corrections.
            tx("-20", category = "0", at = day(4)), tx("20", wallet = "w2", category = "0", at = day(4) + 3_600_000),
        )), allTypes)
        assertEquals(1, plan.transactions.count { it.kind == TxKind.TRANSFER })
        assertEquals(-2000L, plan.accounts.first { it.name == "Everyday" }.openingBalance)
        assertEquals(2000L, plan.accounts.first { it.name == "Savings" }.openingBalance)
        assertTrue(plan.issues.contains(ImportIssue.BalanceCorrections(2)))
        assertTrue(plan.reconcile.allMatch)
    }

    @Test fun aLoneCorrectionBecomesTheStartingBalance() {
        val plan = importer.plan(Fake(transactions = listOf(tx("1000.10", category = "0"), tx("-10", title = "Snack"))), allTypes)
        assertEquals(100010L, plan.accounts.single().openingBalance)
        assertEquals(1, plan.transactions.size)
        assertTrue(plan.reconcile.allMatch)
    }

    @Test fun unpaidAndSkippedEntriesStayBehind() {
        val plan = importer.plan(Fake(transactions = listOf(
            tx("-10"), tx("-99", paid = false, type = 0), tx("-15", paid = false, skip = true, type = 1, re = 3, period = 1),
            tx("-20", paid = true, skip = true),
        )), allTypes)
        assertEquals(1, plan.transactions.size)
        assertTrue(plan.issues.any { it is ImportIssue.LeftBehind && it.count == 1 && it.what.startsWith("upcoming") })
        assertTrue(plan.reconcile.allMatch)
    }

    @Test fun monthlySubscriptionsBecomeBillsAndOthersStay() {
        val plan = importer.plan(Fake(transactions = listOf(
            tx("-15.49", title = "Streaming", paid = false, type = 1, re = 3, period = 1, at = day(14, 11)),
            tx("-15.49", title = "Streaming", paid = false, type = 1, re = 3, period = 1, at = day(14, 12)),
            tx("-99", title = "Yearly app", paid = false, type = 1, re = 4, period = 1, at = day(2, 11)),
            tx("-40", title = "Every 2 months", paid = false, type = 2, re = 3, period = 2, at = day(2, 11)),
            tx("2480", category = "pay", title = "Payroll", paid = false, type = 2, re = 3, period = 1, at = day(1, 11)),
        )), allTypes)
        val bill = plan.bills.single()
        assertEquals("Streaming", bill.name)
        assertEquals(14, bill.dayOfMonth)
        assertEquals(1549L, bill.fixedAmount)
        assertTrue(plan.issues.any { it is ImportIssue.LeftBehind && it.count == 3 && it.what.startsWith("repeating") })
    }

    @Test fun aLoanToAFriendBecomesAPersonWhoOwesTheRest() {
        val loan = CwObjective("L1", 1, "Sam", "600", income = true, epochMillis = day(1), archived = false)
        val plan = importer.plan(Fake(objectives = listOf(loan), transactions = listOf(
            tx("1000", category = "pay", title = "Payroll", at = day(1)),
            tx("-600", title = "Loan to Sam", loan = "L1", at = day(2)),
            tx("200", category = "pay", title = "Sam paid back", loan = "L1", at = day(9)),
        )), allTypes.copy(loans = mapOf("L1" to LoanChoice.PERSON)))
        val sam = plan.people.single()
        assertEquals("Sam", sam.name)
        assertEquals(40000L, personBalance(sam.id, plan.personLoans, plan.transactions))
        assertTrue(plan.personLoans.isEmpty())
        assertEquals(60000L, plan.balance("Everyday"))
        assertTrue(plan.reconcile.allMatch)
    }

    @Test fun aPaymentsOnlyLoanStartsWithTheLoanAmount() {
        val loan = CwObjective("L1", 1, "Dad", "4000", income = false, epochMillis = day(1), archived = false)
        val plan = importer.plan(Fake(objectives = listOf(loan), transactions = listOf(
            tx("5000", category = "pay"), tx("-500", loan = "L1", title = "Paid Dad"),
        )), allTypes.copy(loans = mapOf("L1" to LoanChoice.PERSON)))
        val dad = plan.people.single()
        assertEquals(-350000L, personBalance(dad.id, plan.personLoans, plan.transactions))
        assertTrue(plan.reconcile.allMatch)
    }

    @Test fun aMortgageBecomesALoanAccountPaidDownByTransfers() {
        val loan = CwObjective("M", 1, "Mortgage", "312000", income = false, epochMillis = day(1, 1), archived = false)
        val plan = importer.plan(Fake(objectives = listOf(loan), transactions = listOf(
            tx("10000", category = "pay"), tx("-1850", loan = "M", at = day(1, 2)), tx("-1850", loan = "M", at = day(1, 3)),
        )), allTypes.copy(loans = mapOf("M" to LoanChoice.ACCOUNT)))
        val mortgage = plan.accounts.first { it.name == "Mortgage" }
        assertEquals(AccountType.LOAN, mortgage.type)
        assertEquals(-31200000L, mortgage.openingBalance)
        assertEquals(-30830000L, balanceOf(mortgage, plan.transactions))
        assertEquals(2, plan.transactions.count { it.kind == TxKind.TRANSFER && it.toAccountId == mortgage.id })
        assertEquals(630000L, plan.balance("Everyday"))
        assertTrue(plan.reconcile.allMatch)
    }

    @Test fun aLoanLeftAsEntriesIsJustSpendingAndIncome() {
        val loan = CwObjective("L1", 1, "Sam", "600", income = true, epochMillis = day(1), archived = false)
        val plan = importer.plan(Fake(objectives = listOf(loan), transactions = listOf(tx("-600", loan = "L1"))), allTypes.copy(loans = mapOf("L1" to LoanChoice.ENTRIES)))
        assertEquals(TxKind.EXPENSE, plan.transactions.single().kind)
        assertTrue(plan.people.isEmpty())
    }

    @Test fun leavingAnAccountOutKeepsTheOtherBalancesRight() {
        val w = listOf(CwWallet("w1", "Everyday", null, 0), CwWallet("w3", "Europe trip", "eur", 1))
        val plan = importer.plan(Fake(wallets = w, transactions = listOf(
            tx("1000", category = "pay"),
            tx("-400", category = "0", pk = "a", title = "Card top-up"), tx("370", wallet = "w3", category = "0", pk = "b", paired = "a"),
            tx("-30", wallet = "w3", title = "Croissant"),
        )), allTypes.copy(leaveOut = setOf("w3")))
        assertEquals(listOf("Everyday"), plan.accounts.map { it.name })
        val kept = plan.transactions.first { it.note.contains("Transfer with") }
        assertEquals(TxKind.EXPENSE, kept.kind)
        assertEquals("Card top-up · Transfer with Europe trip", kept.note)
        assertEquals(60000L, plan.balance("Everyday"))
        assertTrue(plan.issues.contains(ImportIssue.TransfersAsEntries(1)))
        assertTrue(plan.reconcile.allMatch)
        assertTrue(plan.transactions.none { it.note == "Croissant" })
    }

    @Test fun transfersAcrossCurrenciesKeepBothBalances() {
        val w = listOf(CwWallet("w1", "Everyday", "usd", 0), CwWallet("w3", "Europe trip", "eur", 1))
        val plan = importer.plan(Fake(wallets = w, transactions = listOf(
            tx("-400", category = "0", pk = "a"), tx("370", wallet = "w3", category = "0", pk = "b", paired = "a"),
        )), allTypes)
        assertTrue(plan.issues.contains(ImportIssue.MismatchedTransfers(1)))
        assertEquals(37000L, plan.balance("Europe trip"))
        assertEquals(-40000L, plan.balance("Everyday"))
        assertTrue(plan.reconcile.allMatch)
    }

    @Test fun refundsComeInAsIncomeInTheirCategory() {
        val plan = importer.plan(Fake(transactions = listOf(tx("-80", title = "Shoes"), tx("80", title = "Shoes returned"))), allTypes)
        val refund = plan.transactions.first { it.note == "Shoes returned" }
        assertEquals(TxKind.INCOME, refund.kind)
        assertEquals(plan.categories.first { it.name == "Food" }.id, refund.categoryId)
        assertTrue(plan.issues.contains(ImportIssue.RefundsAsIncome(1)))
    }

    @Test fun categoryLimitsFromMonthlyBudgetsBecomeBudgets() {
        val plan = importer.plan(Fake(
            budgets = listOf(
                CwBudget("b1", "Monthly", "2000", 3, 1, archived = false, absoluteLimits = true, order = 0),
                CwBudget("b2", "Weekly", "200", 2, 1, archived = false, absoluteLimits = true, order = 1),
                CwBudget("b3", "Shares", "1000", 3, 1, archived = false, absoluteLimits = false, order = 2),
            ),
            categories = Fake().categories + CwCategory("fun", "Fun", null, null, "🎉", 5, false, null),
            limits = listOf(
                CwCategoryLimit("food", "b1", "450"), CwCategoryLimit("food", "b2", "100"),
                CwCategoryLimit("coffee", "b1", "50"), CwCategoryLimit("fun", "b3", "25"),
            ),
        ), allTypes)
        val food = plan.categories.first { it.name == "Food" }.id
        val fun_ = plan.categories.first { it.name == "Fun" }
        assertEquals(45000L, plan.budgets.first { it.categoryId == food }.amount)
        assertEquals(25000L, plan.budgets.first { it.categoryId == fun_.id }.amount)
        assertEquals(2, plan.budgets.size)
        assertEquals(CategoryIcon.Emoji("🎉"), fun_.icon)
        assertTrue(plan.issues.any { it is ImportIssue.LeftBehind && it.count == 2 && it.what.startsWith("budget limits") })
    }

    @Test fun namesColoursAndIconsAreCleanedUp() {
        val plan = importer.plan(Fake(
            wallets = listOf(CwWallet("w1", "Card", null, 0), CwWallet("w2", "card", null, 1)),
            categories = listOf(
                CwCategory("a", "Groceries", "0xff00ff00", "groceries.png", null, 0, false, null),
                CwCategory("b", "groceries", null, null, null, 1, false, null),
                CwCategory("c", "A very long category name that goes on and on", null, "unknown-thing.png", null, 2, false, null),
            ),
        ), allTypes)
        assertEquals(listOf("Card", "card 2"), plan.accounts.map { it.name })
        val groceries = plan.categories.filter { it.name.equals("Groceries", true) }
        assertEquals(1, groceries.size)
        assertEquals(0xFF00FF00.toInt(), groceries.single().color)
        assertEquals(CategoryIcon.Symbol("grocery"), groceries.single().icon)
        assertTrue(plan.categories.all { it.name.length <= 30 })
        assertEquals(CategoryIcon.DEFAULT, plan.categories.first { it.name.startsWith("A very") }.icon)
        assertTrue(plan.issues.any { it is ImportIssue.MergedCategories })
    }

    @Test fun brokenRowsAreCountedNotImported() {
        val plan = importer.plan(Fake(transactions = listOf(
            tx("-5"), tx("-5", wallet = "gone"), tx("abc"), tx("-123456789012"), tx("0"),
        )), allTypes)
        assertEquals(2, plan.transactions.size)
        assertTrue(plan.issues.contains(ImportIssue.SkippedRows(1, "entries whose account no longer exists in Cashew")))
        assertTrue(plan.issues.contains(ImportIssue.SkippedRows(1, "entries with an unreadable amount")))
        assertTrue(plan.issues.contains(ImportIssue.SkippedRows(1, "entries above the largest amount Kassa allows")))
        assertTrue(plan.issues.contains(ImportIssue.ZeroAmountEntries(1)))
    }

    @Test fun scanCountsWhatTheQuestionsNeed() {
        val w = listOf(CwWallet("w1", "Everyday", "usd", 0), CwWallet("w3", "Europe trip", "eur", 1))
        val loans = listOf(
            CwObjective("L1", 1, "Sam", "600", income = true, epochMillis = day(1), archived = false),
            CwObjective("M", 1, "Mortgage", "312000", income = false, epochMillis = day(1), archived = false),
            CwObjective("G", 0, "Holiday", "2000", income = false, epochMillis = day(1), archived = false),
        )
        val scan = importer.scan(Fake(wallets = w, objectives = loans, transactions = listOf(
            tx("-600", loan = "L1"), tx("200", category = "pay", loan = "L1"), tx("-1850", loan = "M"),
            tx("-400", category = "0", pk = "a"), tx("370", wallet = "w3", category = "0", pk = "b", paired = "a"),
            tx("-9", paid = false),
        )))
        assertEquals("USD", scan.mainCurrency)
        assertEquals(listOf("Europe trip"), scan.otherCurrency("USD").map { it.name })
        assertEquals(1, scan.transfers)
        assertEquals(3, scan.entries)
        assertEquals(1, scan.accounts.first { it.name == "Everyday" }.transfers)
        val sam = scan.loans.first { it.name == "Sam" }
        assertEquals(60000L, sam.amount); assertEquals(1, sam.payments); assertTrue(sam.lent)
        val mortgage = scan.loans.first { it.name == "Mortgage" }
        assertEquals(31200000L, mortgage.amount); assertEquals(1, mortgage.payments)
        assertEquals(2, scan.loans.size)
        assertEquals(2, scan.categories); assertEquals(1, scan.subcategories)
    }

    @Test fun anAccountWithoutAKindIsNeverGuessed() {
        val plan = importer.plan(Fake(), CashewChoices())
        assertEquals(AccountType.OTHER_ASSET, plan.accounts.single().type)
    }

    @Test fun coloursParse() {
        assertEquals(0xFF123456.toInt(), CashewImporter.colorOf("0xff123456"))
        assertEquals(0xFF123456.toInt(), CashewImporter.colorOf("#123456"))
        assertNull(CashewImporter.colorOf("blue"))
        assertNull(CashewImporter.colorOf(null))
    }

    @Test fun centsKeepTheirSign() {
        assertEquals(-1250L, CashewImporter.cents("-12.5"))
        assertEquals(1250L, CashewImporter.cents("12.50"))
        assertEquals(1L, CashewImporter.cents("0.005"))
        assertNull(CashewImporter.cents(""))
    }

    /** Whatever the mix of entries, transfers, corrections, loans and left-out accounts, every kept balance matches. */
    @Test fun randomBackupsAlwaysBalance() {
        repeat(300) { seed ->
            val rnd = Random(seed)
            val wallets = (1..rnd.nextInt(1, 5)).map { CwWallet("w$it", "Account $it", if (rnd.nextInt(5) == 0) "eur" else null, it) }
            val loans = (1..rnd.nextInt(0, 3)).map { CwObjective("L$it", 1, "Loan $it", "${rnd.nextInt(100, 5000)}", rnd.nextBoolean(), day(1), false) }
            val rows = mutableListOf<CwTransaction>()
            repeat(rnd.nextInt(1, 60)) { i ->
                val w = wallets.random(rnd).pk
                val cents = rnd.nextInt(1, 500_000)
                val amt = (if (rnd.nextBoolean()) "-" else "") + "${cents / 100}.${"%02d".format(cents % 100)}"
                val at = day(1 + rnd.nextInt(27), 1 + rnd.nextInt(12))
                when (rnd.nextInt(6)) {
                    0 -> if (wallets.size > 1) {
                        val other = wallets.filter { it.pk != w }.random(rnd).pk
                        rows += tx("-$cents", wallet = w, category = "0", at = at, pk = "x$i")
                        rows += tx("${cents + if (rnd.nextInt(4) == 0) 7 else 0}", wallet = other, category = "0", at = at, pk = "y$i", paired = "x$i")
                    }
                    1 -> rows += tx(amt, wallet = w, category = "0", at = at)
                    2 -> if (loans.isNotEmpty()) rows += tx(amt, wallet = w, loan = loans.random(rnd).pk, at = at)
                    3 -> rows += tx(amt, wallet = w, paid = rnd.nextBoolean(), type = rnd.nextInt(0, 3), re = 3, period = 1, at = at)
                    else -> rows += tx(amt, wallet = w, category = listOf("food", "pay", null).random(rnd), sub = if (rnd.nextBoolean()) "coffee" else null, at = at)
                }
            }
            val choices = CashewChoices(
                accountTypes = wallets.associate { it.pk to AccountType.entries.random(rnd) },
                leaveOut = wallets.filter { rnd.nextInt(4) == 0 }.map { it.pk }.toSet().let { if (it.size == wallets.size) emptySet() else it },
                loans = loans.associate { it.pk to LoanChoice.entries.random(rnd) },
            )
            val plan = importer.plan(Fake(wallets = wallets, objectives = loans, transactions = rows, primary = null), choices)
            assertTrue(plan.reconcile.allMatch, "seed $seed: ${plan.reconcile.accounts.filter { !it.matches }}")
            assertTrue(plan.transactions.all { it.amount >= 0 })
            assertEquals(plan.transactions.size, plan.transactions.map { it.id }.toSet().size)
            assertTrue(plan.transactions.all { t -> plan.accounts.any { it.id == t.accountId } })
            assertTrue(plan.categories.filter { it.parentId != null }.all { c -> plan.categories.any { it.id == c.parentId && it.kind == c.kind } })
        }
    }

    @Test fun incomeAndExpenseCategoriesStaySeparate() {
        val plan = importer.plan(Fake(), allTypes)
        assertEquals(CategoryKind.INCOME, plan.categories.first { it.name == "Salary" }.kind)
        assertEquals(CategoryKind.EXPENSE, plan.categories.first { it.name == "Food" }.kind)
    }
}

package app.kassa.money.shared

import app.kassa.money.backup.BackupAccount
import app.kassa.money.backup.BackupCategory
import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupHolding
import app.kassa.money.backup.BackupJson
import app.kassa.money.backup.BackupLoanEntry
import app.kassa.money.backup.BackupLoanPayment
import app.kassa.money.backup.BackupLoanTerms
import app.kassa.money.backup.BackupPerson
import app.kassa.money.backup.BackupTransaction
import app.kassa.money.domain.Money
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The figures the iOS screens show, from the golden backup and from a richer ledger whose numbers are worked out by hand below. */
class LedgerViewTest {

    private fun golden(): BackupData {
        val bytes = File("../app/src/test/resources/backup-golden/plain.kassa").readBytes()
        val json = GZIPInputStream(ByteArrayInputStream(bytes.copyOfRange(6, bytes.size))).readBytes().decodeToString()
        return BackupJson.parse(json)
    }

    private fun group(a: AccountsData, key: String): AccountsGroupData = a.groups.first { it.key == key }
    private fun row(a: AccountsData, name: String): AccountRowData = a.groups.flatMap { it.rows }.first { it.name == name }
    private fun noZone() = "UTC"

    // ---------- the golden backup ----------

    // 2026-01-01 is epoch day 20454; the golden entries are on 29, 30 and 31 December 2025.
    private val jan1 = 20_454

    @Test
    fun goldenAccounts() {
        KassaCore.installDefaults()
        val view = LedgerView(golden(), null)
        assertEquals("USD", view.currencyCode())
        val a = view.accounts(false, jan1, noZone())
        // Checking: 150000 + 99999999 - 450 - 25000 = 100,124,549. The card is at zero; the loan is closed, so it is out of every total.
        assertEquals("$1,001,245.49", a.netWorthText)
        assertEquals(Tone.Green, a.netWorthTone)
        assertEquals("$1,001,245.49", a.assetsText)
        assertEquals("$0.00", a.liabilitiesText)
        assertEquals(listOf("CASH_AND_BANK", "CREDIT_CARDS", "PEOPLE", "CLOSED"), a.groups.map { it.key })
        assertEquals("$1,001,245.49", row(a, "Checking").amountText)
        val card = row(a, "Card")
        assertEquals("$0.00", card.amountText)
        assertEquals(RowNote.NothingToPay, card.note)
        assertEquals("0000", card.last4)
        val sam = row(a, "Sam")
        assertEquals(PersonSide.Settled, sam.side)
        val loan = row(a, "Car loan")
        assertEquals(DueKind.Closed, loan.dueKind)
        assertEquals(20_500, loan.dueDay)
        assertEquals("", loan.amountText)
    }

    @Test
    fun goldenActivity() {
        KassaCore.installDefaults()
        val view = LedgerView(golden(), null)
        val m = view.activity(2025, 12, false, jan1, noZone())
        assertEquals("$4.50", m.spendingText)
        assertEquals("$999,999.99", m.incomeText)
        assertEquals(3, m.entryCount)
        assertFalse(m.isCurrentMonth)
        assertEquals(listOf(20_453, 20_452, 20_451), m.days.map { it.epochDay })
        assertEquals(listOf("$4.50", "", ""), m.days.map { it.spentText })
        val latte = m.days[0].entries.single()
        assertEquals(EntryKind.Expense, latte.kind)
        assertEquals("Latte ₹1,00,000", latte.note)
        assertEquals("Café ☕", latte.categoryName)
        assertEquals("$4.50", latte.amountText)
        assertEquals("Checking", latte.accountName)
        assertEquals("+$999,999.99", m.days[1].entries.single().amountText)
        assertEquals(Tone.Green, m.days[1].entries.single().amountTone)
        val transfer = m.days[2].entries.single()
        assertEquals(EntryKind.Transfer, transfer.kind)
        assertEquals("Car loan", transfer.toAccountName)
        assertTrue(transfer.amountMuted)
        assertTrue(view.activity(2026, 1, false, jan1, noZone()).isCurrentMonth)
        assertEquals(0, view.activity(2026, 1, false, jan1, noZone()).days.size)
    }

    @Test
    fun hiddenGivesTheMaskForEveryAmountAndNoColour() {
        KassaCore.installDefaults()
        val view = LedgerView(golden(), null)
        val a = view.accounts(true, jan1, noZone())
        assertEquals(Money.MASK, a.netWorthText)
        assertEquals(Tone.Ink, a.netWorthTone)
        for (text in listOf(a.assetsText, a.liabilitiesText, a.receivableText, a.payableText)) assertEquals(Money.MASK, text)
        for (g in a.groups) {
            if (g.key != "CLOSED") assertEquals(Money.MASK, g.totalText, g.key)
            assertEquals(Tone.Ink, g.totalTone)
            for (r in g.rows) {
                if (r.dueKind != DueKind.Closed) assertEquals(Money.MASK, r.amountText, r.name)
                assertEquals(Tone.Ink, r.amountTone)
                assertEquals(RowNote.Plain, r.note)
            }
        }
        assertEquals(PersonSide.Hidden, row(a, "Sam").side)
        val m = view.activity(2025, 12, true, jan1, noZone())
        assertEquals(Money.MASK, m.spendingText)
        assertEquals(Money.MASK, m.incomeText)
        assertEquals(Tone.Ink, m.incomeTone)
        // A day with no spending still shows the mask, so a zero never says which days had none.
        assertEquals(listOf(Money.MASK, Money.MASK, Money.MASK), m.days.map { it.spentText })
        for (day in m.days) for (e in day.entries) assertEquals(Money.MASK, e.amountText)
        // The call put the global switch back.
        assertEquals("$1.00", Money.format(100))
    }

    // ---------- a richer ledger ----------

    private val noon = 43_200_000L
    private fun at(day: Int) = day * 86_400_000L + noon

    // Today is 2026-01-20 (epoch day 20473). Epoch days: 12 Jan 20465, 14 Jan 20467, 15 Jan 20468, 16 Jan 20469, 17 Jan 20470, 19 Jan 20472.
    private val today = 20_473

    private fun acct(id: Long, name: String, type: String, opening: Long, statement: Int? = null, due: Int? = null, maturity: Int? = null, closed: Int? = null, last4: String? = null) =
        BackupAccount(id, name, type, null, opening, statement, due, null, null, false, id.toInt(), null, last4, maturity, closed)

    private fun tx(id: Long, kind: String, amount: Long, day: Int, from: Long?, to: Long? = null, cat: Long? = null, note: String = "") =
        BackupTransaction(id, kind, amount, from, to, cat, null, at(day), note)

    private fun terms(counts: Boolean) = BackupLoanTerms(
        accountId = 4, type = "REDUCING", profit = false, rate = 60_000, payment = 100_000, termMonths = 120, originalAmount = 1_000_000,
        startDay = 20_000, nextPaymentDay = today, dueDay = 17, escrow = 0, fee = 0, earlyCharge = 0, countAllAsSpending = counts,
    )

    private fun rich(counts: Boolean = false, arrangement: String? = null) = BackupData(
        createdAt = 0, monthStartDay = 1, currency = "USD", accountArrangement = arrangement,
        accounts = listOf(
            acct(1, "Checking", "CHECKING", 100_000),
            acct(2, "Savings", "SAVINGS", 500_000),
            acct(3, "Visa", "CREDIT_CARD", 0, statement = 10, due = 28, last4 = "1234"),
            acct(4, "Mortgage", "LOAN", -1_000_000),
            acct(5, "Old savings", "SAVINGS", 0, closed = 20_400),
            acct(6, "Brokerage", "INVESTMENT", 20_000),
            acct(7, "Term deposit", "FIXED_DEPOSIT", 100_000, maturity = 20_600),
        ),
        categories = listOf(
            BackupCategory(1, "Groceries", "EXPENSE", null, "emoji:🛒", -16733696, true, false, 0),
            BackupCategory(2, "Salary", "INCOME", null, "emoji:💼", -16776961, false, false, 1),
            BackupCategory(3, "Loan interest", "EXPENSE", null, "symbol:percent", -65536, false, false, 2),
        ),
        transactions = listOf(
            tx(1, "EXPENSE", 20_000, 20_390, 3, cat = 1),               // 28 Dec on the card, before the statement
            tx(2, "EXPENSE", 3_000, 20_465, 3, cat = 1, note = "Fuel"), // 12 Jan on the card, after it
            tx(3, "TRANSFER", 10_000, 20_467, 1, 2),
            tx(4, "TRANSFER", 95_000, 20_470, 1, 4),                    // the loan's principal
            tx(5, "EXPENSE", 5_000, 20_470, 1, cat = 3),                // its interest
            tx(6, "INCOME", 300_000, 20_468, 1, cat = 2),
            tx(7, "TRANSFER", 5_000, 20_468, 1, 3),                     // a card payment
            tx(8, "EXPENSE", 4_500, 20_469, 1, cat = 1, note = "Market"),
            tx(9, "ADJUST_OUT", 1_500, 20_472, 2),
        ),
        people = listOf(BackupPerson(1, "Sam", false), BackupPerson(2, "Ann", false)),
        loans = listOf(BackupLoanEntry(1, 1, 2_500, at(20_460), "lunch"), BackupLoanEntry(2, 2, -7_000, at(20_460), "tickets")),
        holdings = listOf(BackupHolding(1, 6, "X", 2_500_000, 4_000, at(20_470))),
        loanTerms = listOf(terms(counts)),
        loanPayments = listOf(BackupLoanPayment(1, 4, 20_470, 100_000, 4, 5, null, 0, 0, false, 5_000, true)),
    )

    @Test
    fun richAccountFigures() {
        KassaCore.installDefaults()
        val view = LedgerView(rich(), null)
        val a = view.accounts(false, today, noZone())
        // Checking 100000 +300000 -4500 -5000 -95000 -5000 -10000 = 280,500. Savings 500000 +10000 -1500 = 508,500.
        assertEquals("$2,805.00", row(a, "Checking").amountText)
        assertEquals("$5,085.00", row(a, "Savings").amountText)
        // Brokerage: 20000 cash plus 2.5 shares at 40.00.
        assertEquals("$300.00", row(a, "Brokerage").amountText)
        assertEquals("$300.00", group(a, "INVESTMENTS").totalText)
        // Cash and bank adds the term deposit (1,000.00); the closed account is out.
        assertEquals("$8,890.00", group(a, "CASH_AND_BANK").totalText)
        assertEquals(listOf("Checking", "Savings", "Term deposit"), group(a, "CASH_AND_BANK").rows.map { it.name })
        val deposit = row(a, "Term deposit")
        assertEquals(DueKind.Matures, deposit.dueKind)
        assertEquals(20_600, deposit.dueDay)
        assertEquals(listOf("CASH_AND_BANK", "INVESTMENTS", "CREDIT_CARDS", "LOANS", "PEOPLE", "CLOSED"), a.groups.map { it.key })
        assertEquals(listOf("Old savings"), group(a, "CLOSED").rows.map { it.name })
    }

    @Test
    fun richCard() {
        KassaCore.installDefaults()
        val a = LedgerView(rich(), null).accounts(false, today, noZone())
        // Balance -18000: statement 20000 (up to 9 Jan), 5000 paid since, 3000 charged since. Payable 15000, total balance 18000.
        val visa = row(a, "Visa")
        assertEquals("$150.00", visa.amountText)
        assertEquals(Tone.Orange, visa.amountTone)
        assertEquals(RowNote.TotalBalance, visa.note)
        assertEquals("$180.00", visa.noteAmountText)
        assertEquals(DueKind.Due, visa.dueKind)
        assertEquals(20_481, visa.dueDay)          // 28 January
        assertEquals(Tone.Ink, visa.dueTone)       // not due yet
        assertEquals("1234", visa.last4)
        assertEquals("$180.00", group(a, "CREDIT_CARDS").totalText)
    }

    @Test
    fun richLoan() {
        KassaCore.installDefaults()
        val a = LedgerView(rich(), null).accounts(false, today, noZone())
        // Owed 1,000,000 - 95,000 = 905,000. The payment is 1,000.00; the next one is due today, so its line is orange.
        val loan = row(a, "Mortgage")
        assertEquals("$1,000.00", loan.amountText)
        assertEquals(Tone.Orange, loan.amountTone)
        assertEquals(RowNote.Outstanding, loan.note)
        assertEquals("$9,050.00", loan.noteAmountText)
        assertEquals(DueKind.Due, loan.dueKind)
        assertEquals(today, loan.dueDay)
        assertEquals(Tone.Orange, loan.dueTone)
        assertEquals("$9,050.00", group(a, "LOANS").totalText)
    }

    @Test
    fun richPeopleAndTotals() {
        KassaCore.installDefaults()
        val a = LedgerView(rich(), null).accounts(false, today, noZone())
        // Sam owes 25.00, you owe Ann 70.00.
        assertEquals("$25.00", a.receivableText)
        assertEquals("$70.00", a.payableText)
        val people = group(a, "PEOPLE")
        assertEquals(listOf("Ann", "Sam"), people.rows.map { it.name })
        assertEquals(PersonSide.YouOwe, people.rows[0].side)
        assertEquals("$70.00", people.rows[0].amountText)
        assertEquals(Tone.Orange, people.rows[0].amountTone)
        assertEquals(PersonSide.OwesYou, people.rows[1].side)
        assertEquals(Tone.Green, people.rows[1].amountTone)
        assertEquals("−$45.00", people.totalText)
        // Assets: 8890.00 + 300.00 + 25.00 = 9,215.00. Liabilities: 180.00 + 9,050.00 + 70.00 = 9,300.00. Net worth -85.00.
        assertEquals("$9,215.00", a.assetsText)
        assertEquals("$9,300.00", a.liabilitiesText)
        assertEquals("−$85.00", a.netWorthText)
        assertEquals(Tone.Orange, a.netWorthTone)
    }

    @Test
    fun hiddenRichLedgerHasNoSizeOrColour() {
        KassaCore.installDefaults()
        val a = LedgerView(rich(), null).accounts(true, today, noZone())
        assertEquals(Money.MASK, a.netWorthText)
        assertEquals(Tone.Ink, a.netWorthTone)
        val visa = row(a, "Visa")
        assertEquals(Money.MASK, visa.amountText)
        assertEquals(Money.MASK, visa.noteAmountText)
        assertEquals(Tone.Ink, visa.amountTone)
        assertEquals(Tone.Ink, row(a, "Mortgage").dueTone)
        assertEquals(Money.MASK, group(a, "PEOPLE").totalText)
    }

    @Test
    fun yourOrderWinsOverAToZ() {
        KassaCore.installDefaults()
        val view = LedgerView(rich(arrangement = "LOANS,CASH_AND_BANK|CASH_AND_BANK=2,1"), null)
        val a = view.accounts(false, today, noZone())
        assertEquals(listOf("LOANS", "CASH_AND_BANK", "CREDIT_CARDS", "INVESTMENTS", "PEOPLE", "CLOSED"), a.groups.map { it.key })
        assertEquals(listOf("Savings", "Checking", "Term deposit"), group(a, "CASH_AND_BANK").rows.map { it.name })
    }

    @Test
    fun overviewWeekStartsOnThePhonesFirstDayAndNeverCountsACorrection() {
        KassaCore.installDefaults()
        val v = LedgerView(rich(), null)
        // Today is Tuesday 20 January 2026. Spending in January: 12th 30.00, 16th 45.00, 17th 50.00 (loan interest); a 15.00
        // balance correction on the 19th is in no spending figure.
        val monday = v.overview(false, today, noZone(), 2)
        assertEquals(20_472, monday.weekStartDay)
        assertEquals("$0.00", monday.weekSpending.totalText)
        assertEquals("$125.00", monday.spending.totalText)
        assertEquals(20_471, v.overview(false, today, noZone(), 1).weekStartDay)
        val saturday = v.overview(false, today, noZone(), 7)
        assertEquals(20_470, saturday.weekStartDay)
        assertEquals("$50.00", saturday.weekSpending.totalText)
        assertEquals(listOf("Loan interest"), saturday.weekSpending.top.map { it.name })
        val friday = v.overview(false, today, noZone(), 6)
        assertEquals(20_469, friday.weekStartDay)
        assertEquals("$95.00", friday.weekSpending.totalText)
        assertEquals(listOf("Loan interest", "Groceries"), friday.weekSpending.top.map { it.name })
        // The month is the same whichever day the week starts on.
        assertEquals("$125.00", friday.spending.totalText)
        assertEquals("$0.00", friday.weekIncome.totalText)
    }

    @Test
    fun aCategoryFromThisWeekOpensTheMonthTheWeekStartsIn() {
        KassaCore.installDefaults()
        // Friday 2 January 2026 (epoch day 20455): a week starting Monday 29 December began in the month before.
        val early = LedgerView(BackupData(createdAt = 0, monthStartDay = 1), null)
        assertEquals(1, early.overview(false, 20_455, noZone(), 2).weekCategoryBack)
        // A week that starts inside the month opens this month.
        assertEquals(0, early.overview(false, 20_459, noZone(), 2).weekCategoryBack)
        // With a month that starts on the 15th, the same week still belongs to the period that began on 15 December.
        val mid = LedgerView(BackupData(createdAt = 0, monthStartDay = 15), null)
        assertEquals(0, mid.overview(false, 20_455, noZone(), 2).weekCategoryBack)
        // Today 16 January with the 15th start: a week from Monday 12 January began in the period before.
        assertEquals(1, mid.overview(false, 20_469, noZone(), 2).weekCategoryBack)
        // It is the number the spending page of this week gives its categories.
        val page = early.spendingPage(false, 20_455, noZone(), 2, false, false, 0)
        assertEquals(page.categoryBack, early.overview(false, 20_455, noZone(), 2).weekCategoryBack)
    }

    @Test
    fun overviewWeekEqualsTheSpendingPageOfThisWeek() {
        KassaCore.installDefaults()
        val v = LedgerView(rich(), null)
        for (first in listOf(1, 2, 6, 7)) {
            val o = v.overview(false, today, noZone(), first)
            val spending = v.spendingPage(false, today, noZone(), first, false, false, 0)
            assertEquals(spending.totalText, o.weekSpending.totalText)
            assertEquals(spending.fromDay, o.weekStartDay)
            assertEquals(v.spendingPage(false, today, noZone(), first, false, true, 0).totalText, o.weekIncome.totalText)
        }
    }

    @Test
    fun overviewWeekMayStartBeforeTheMonthDoes() {
        KassaCore.installDefaults()
        val data = rich().let { it.copy(transactions = it.transactions + tx(20, "EXPENSE", 1_000, 20_452, 1, cat = 1)) }
        // Saturday 3 January 2026: the Monday-first week began on 29 December, so it holds 30 December; the month does not.
        val v = LedgerView(data, null).overview(false, 20_456, noZone(), 2)
        assertEquals(20_451, v.weekStartDay)
        assertEquals("$10.00", v.weekSpending.totalText)
        assertEquals("$0.00", v.spending.totalText)
        assertEquals(1, v.month)
    }

    @Test
    fun overviewWeekIsMaskedWhileHidden() {
        KassaCore.installDefaults()
        val v = LedgerView(rich(), null).overview(true, today, noZone(), 6)
        assertEquals(Money.MASK, v.weekSpending.totalText)
        assertEquals(Money.MASK, v.weekIncome.totalText)
        assertEquals(Tone.Ink, v.weekIncome.tone)
        v.weekSpending.top.forEach { assertEquals(Money.MASK, it.amountText) }
    }

    @Test
    fun richActivity() {
        KassaCore.installDefaults()
        val m = LedgerView(rich(), null).activity(2026, 1, false, today, noZone())
        assertTrue(m.isCurrentMonth)
        // Spending: fuel 30.00 + market 45.00 + loan interest 50.00; the principal is a transfer. Income 3,000.00.
        assertEquals("$125.00", m.spendingText)
        assertEquals("$3,000.00", m.incomeText)
        assertEquals(8, m.entryCount)
        assertEquals(listOf(20_472, 20_470, 20_469, 20_468, 20_467, 20_465), m.days.map { it.epochDay })
        assertEquals(listOf("", "$50.00", "$45.00", "", "", "$30.00"), m.days.map { it.spentText })

        val correction = m.days[0].entries.single()
        assertEquals(EntryKind.Correction, correction.kind)
        assertEquals("↓ $15.00", correction.amountText)
        assertEquals("Savings", correction.accountName)

        // 17 January: the interest (newer id first), then the principal.
        val loanDay = m.days[1].entries
        assertEquals(listOf(5L, 4L), loanDay.map { it.id })
        assertEquals("interest", loanDay[0].tag)
        assertEquals("Loan interest", loanDay[0].categoryName)
        assertEquals("principal", loanDay[1].tag)
        assertEquals(EntryKind.Transfer, loanDay[1].kind)
        assertFalse(loanDay[1].countsAsSpending)
        assertEquals("$950.00", loanDay[1].amountText)

        // 15 January: the card payment is a green credit, then the salary.
        val payDay = m.days[3].entries
        assertEquals(listOf(7L, 6L), payDay.map { it.id })
        assertEquals(EntryKind.CardPayment, payDay[0].kind)
        assertEquals("+$50.00", payDay[0].amountText)
        assertEquals(Tone.Green, payDay[0].amountTone)
        assertEquals("Visa", payDay[0].toAccountName)
        assertEquals(EntryKind.Income, payDay[1].kind)
        assertEquals("Salary", payDay[1].categoryName)
        assertEquals("💼", payDay[1].badgeText)

        val market = m.days[2].entries.single()
        assertEquals("Market", market.note)
        assertEquals("🛒", market.badgeText)
        assertEquals(-16733696, market.badgeColor)
        assertEquals(Tone.Ink, market.amountTone)
        assertFalse(market.amountMuted)
    }

    @Test
    fun aLoanPrincipalThatCountsIsSpending() {
        KassaCore.installDefaults()
        val m = LedgerView(rich(counts = true), null).activity(2026, 1, false, today, noZone())
        // The 950.00 principal now counts, in the category its interest went to.
        assertEquals("$1,075.00", m.spendingText)
        assertEquals("$1,000.00", m.days[1].spentText)
        val principal = m.days[1].entries.first { it.id == 4L }
        assertEquals(EntryKind.Expense, principal.kind)
        assertEquals("Loan interest", principal.categoryName)
        assertTrue(principal.countsAsSpending)
        assertEquals("principal", principal.tag)
        // Balances never change.
        val a = LedgerView(rich(counts = true), null).accounts(false, today, noZone())
        assertEquals("$9,050.00", row(a, "Mortgage").noteAmountText)
    }

    @Test
    fun theMonthFollowsThePhonesZone() {
        KassaCore.installDefaults()
        // 31 Dec 2025 23:00 UTC is already 1 January in Auckland, so it belongs to January there.
        val late = rich().copy(transactions = listOf(BackupTransaction(1, "EXPENSE", 1_000, 1, null, null, null, 1_767_222_000_000L, "late")))
        val view = LedgerView(late, null)
        assertEquals(1, view.activity(2025, 12, false, today, "UTC").entryCount)
        assertEquals(0, view.activity(2025, 12, false, today, "Pacific/Auckland").entryCount)
        assertEquals(1, view.activity(2026, 1, false, today, "Pacific/Auckland").entryCount)
    }

    @Test
    fun theCurrencyComesFromTheBackupThenThePhone() {
        KassaCore.installDefaults()
        assertEquals("USD", LedgerView(rich(), "INR").currencyCode())
        assertEquals("INR", LedgerView(rich().copy(currency = null), "inr").currencyCode())
        assertEquals("USD", LedgerView(rich().copy(currency = null), "XYZ").currencyCode())
        val inr = LedgerView(rich().copy(currency = "INR"), null).accounts(false, today, noZone())
        assertEquals("₹2,805.00", row(inr, "Checking").amountText)
        // The switch is put back after each call.
        assertEquals("$1.00", Money.format(100))
    }

    @Test
    fun numberStyleAndCollationComeFromThePhone() {
        KassaCore.useNumberStyle(",", ".", "0", false)
        try {
            val a = LedgerView(rich(), null).accounts(false, today, noZone())
            assertEquals("$2.805,00", row(a, "Checking").amountText)
            KassaCore.useNumberStyle(".", ",", "0", true)
            val big = LedgerView(golden(), null).accounts(false, jan1, noZone())
            assertEquals("$10,01,245.49", big.netWorthText)
        } finally {
            KassaCore.installDefaults()
        }
    }

    @Test
    fun anEmptyBackupIsEmpty() {
        KassaCore.installDefaults()
        val view = LedgerView(BackupData(createdAt = 0, monthStartDay = 1), null)
        assertTrue(view.isEmpty())
        val a = view.accounts(false, today, noZone())
        assertEquals("$0.00", a.netWorthText)
        assertEquals(listOf("PEOPLE"), a.groups.map { it.key })
        assertEquals(0, view.activity(2026, 1, false, today, noZone()).days.size)
        assertFalse(LedgerView(rich(), null).isEmpty())
    }
}

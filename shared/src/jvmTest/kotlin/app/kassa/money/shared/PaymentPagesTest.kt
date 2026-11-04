package app.kassa.money.shared

import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupJson
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The card, account and person pages and the pay-card and person-payment rules, against the demo backup. The card's figures are
 * worked out here again from the file's entries with plain Kotlin and java.time (not the code under test): the statement of 5 October
 * is what was owed on the 4th, the payments since come off it, and the charges since are outstanding.
 */
class PaymentPagesTest {
    private val dir = "../app/src/test/resources/backup-demo/"
    private val mask = "••••"
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
    private val expected get() = File(dir + "expected.json").readText()
    private val today get() = num(expected, "todayEpochDay").toInt()
    private fun view(): LedgerView { KassaCore.installDefaults(); return LedgerView(demo(), null) }
    private fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    private val travelCard = 4L
    private val checking = 1L
    private val sam = 1L

    /** The Travel card's figures for the statement closed on [close], from the entries alone: payable, outstanding. */
    private fun worked(close: LocalDate, until: LocalDate): Triple<Long, Long, Long> {
        var owedAtClose = 0L
        var paid = 0L
        var charges = 0L
        for (t in demo().transactions) {
            val effect = when {
                t.kind == "EXPENSE" && t.accountId == travelCard -> -t.amount
                t.kind == "TRANSFER" && t.toAccountId == travelCard -> t.amount
                t.kind == "TRANSFER" && t.accountId == travelCard -> -t.amount
                else -> 0L
            }
            if (effect == 0L) continue
            val day = dayOf(t.epochMillis)
            if (day.isBefore(close)) owedAtClose -= effect
            else if (!day.isAfter(until)) if (effect > 0) paid += effect else charges += -effect
        }
        return Triple(owedAtClose - paid, charges, owedAtClose)
    }

    // ---------- the card page ----------

    @Test
    fun theCardPageFiguresEqualTheOnesWorkedOutFromTheEntries() {
        val v = view()
        val close = LocalDate.of(2026, 10, 5)
        val (payable, outstanding, _) = worked(close, LocalDate.ofEpochDay(today.toLong()))
        // The file's own total: expected.json lists what the card owes.
        assertEquals(num(expected, "cardOwed"), payable + outstanding)
        val c = v.cardPage(travelCard, false, today, "UTC", 0)
        assertTrue(c.found)
        assertEquals("Travel card", c.name)
        assertEquals("4242", c.last4)
        assertEquals(usd(payable), c.payableText)
        assertEquals(usd(outstanding), c.outstandingText)
        assertEquals(usd(payable + outstanding), c.totalText)
        assertEquals(usd(payable), c.payText)
        assertTrue(c.hasPayable)
        assertTrue(c.canPay)
        assertEquals(close.toEpochDay().toInt(), c.lastCloseDay)
        assertEquals(LocalDate.of(2026, 11, 5).toEpochDay().toInt(), c.nextCloseDay)
        // Statement on the 5th, due on the 25th: the 25th of October, nine days before the demo's today.
        assertEquals(LocalDate.of(2026, 10, 25).toEpochDay().toInt(), c.dueDay)
        assertEquals("DaysLate", c.status.name)
        assertEquals(9, c.statusDays)
        assertEquals("Orange", c.statusTone.name)
        assertEquals("Orange", c.payableTone.name)
        assertEquals("Ink", c.totalTone.name)
    }

    @Test
    fun theCurrentStretchListsTheEntriesSinceTheStatementDate() {
        val close = LocalDate.of(2026, 10, 5)
        val c = view().cardPage(travelCard, false, today, "UTC", 0)
        val since = demo().transactions.filter {
            (it.accountId == travelCard || it.toAccountId == travelCard) &&
                !dayOf(it.epochMillis).isBefore(close) && !dayOf(it.epochMillis).isAfter(LocalDate.ofEpochDay(today.toLong()))
        }
        assertEquals(since.size, c.entryCount)
        assertEquals(since.size, c.days.sumOf { it.entries.size })
        assertEquals(0, c.back)
        assertFalse(c.canForward)
        assertTrue(c.canBack)
        assertEquals(close.toEpochDay().toInt(), c.fromDay)
        // The payment since the statement date shows as a payment, and a green credit on the row.
        assertTrue(c.paymentsText.isNotEmpty())
        assertEquals(usd(worked(close, LocalDate.ofEpochDay(today.toLong())).second), c.chargesText)
        val rows = c.days.flatMap { it.entries }
        assertTrue(rows.any { it.kind.name == "CardPayment" })
        assertTrue(rows.all { it.accountName == "Travel card" || it.toAccountName == "Travel card" })
    }

    @Test
    fun theLastStatementIsWhatWasOwedWhenItClosed() {
        val v = view()
        val close = LocalDate.of(2026, 10, 5)
        val (payable, _, owedAtClose) = worked(close, LocalDate.ofEpochDay(today.toLong()))
        val last = v.cardPage(travelCard, false, today, "UTC", 1)
        assertEquals(1, last.back)
        assertTrue(last.canForward)
        assertEquals(LocalDate.of(2026, 9, 5).toEpochDay().toInt(), last.fromDay)
        assertEquals(close.toEpochDay().toInt(), last.toDay)
        // Charges on the statement are the card's expenses from 5 Sep to 4 Oct.
        val charged = demo().transactions.filter {
            it.kind == "EXPENSE" && it.accountId == travelCard &&
                !dayOf(it.epochMillis).isBefore(LocalDate.of(2026, 9, 5)) && dayOf(it.epochMillis).isBefore(close)
        }.sumOf { it.amount }
        assertEquals(usd(charged), last.chargesText)
        assertEquals("LeftToPay", last.left.name)
        assertEquals(usd(payable), last.leftText)
        assertTrue(owedAtClose > payable, "a payment since the statement came off it")
        // Nothing is entered before the card's first entry, so going back ends.
        var back = 0
        while (v.cardPage(travelCard, false, today, "UTC", back).canBack) back++
        assertTrue(back >= 2)
        assertEquals(back, demo().transactions.filter { it.accountId == travelCard || it.toAccountId == travelCard }.minOf { it.epochMillis }
            .let { first -> (0..60).first { b -> !dayOf(first).isBefore(windowFrom(b)) } })
    }

    /** The first day of statement [back] by the calendar: the 5th, [back] months before the one that closed on 5 Oct. */
    private fun windowFrom(back: Int): LocalDate = if (back == 0) LocalDate.of(2026, 10, 5) else LocalDate.of(2026, 10, 5).minusMonths(back.toLong())

    @Test
    fun hiddenMasksEveryFigureAndGivesNoColourOrSize() {
        val c = view().cardPage(travelCard, true, today, "UTC", 1)
        for (text in listOf(c.payableText, c.totalText, c.outstandingText, c.payText, c.chargesText)) assertEquals(mask, text)
        for (tone in listOf(c.payableTone, c.totalTone, c.statusTone, c.dueTone)) assertEquals("Ink", tone.name)
        assertEquals("DueOn", c.status.name, "no days late, no paid in full")
        assertEquals(0, c.statusDays)
        assertEquals("", c.statusAmountText)
        assertEquals("None", c.left.name, "no left to pay line while hidden")
        if (c.paymentsText.isNotEmpty()) assertEquals(mask, c.paymentsText)
    }

    @Test
    fun onlyAnOpenCardWithStatementDaysHasACardPage() {
        val v = view()
        assertFalse(v.cardPage(checking, false, today, "UTC", 0).found)
        assertFalse(v.cardPage(999, false, today, "UTC", 0).found)
        assertFalse(v.cardPayData(checking, false, today, "UTC").found)
        assertTrue(v.accountPage(travelCard, false, today, "UTC", 0).hasCardPage)
        assertFalse(v.accountPage(checking, false, today, "UTC", 0).hasCardPage)
    }

    // ---------- pay card ----------

    @Test
    fun thePaySheetOffersPayableAndTotalAndStartsOnThePayableFromTheCardsOwnAccount() {
        val v = view()
        val (payable, outstanding, _) = worked(LocalDate.of(2026, 10, 5), LocalDate.ofEpochDay(today.toLong()))
        val p = v.cardPayData(travelCard, false, today, "UTC")
        assertTrue(p.found)
        assertEquals(listOf("PAYABLE", "TOTAL", "OTHER"), p.options.map { it.choice })
        assertEquals(listOf(payable, payable + outstanding, 0L), p.options.map { it.cents })
        assertEquals(listOf(usd(payable), usd(payable + outstanding), ""), p.options.map { it.amountText })
        assertEquals(LocalDate.of(2026, 10, 25).toEpochDay().toInt(), p.options[0].dueDay)
        assertEquals(usd(outstanding), p.options[1].outstandingText)
        assertEquals("PAYABLE", p.defaultChoice)
        // The demo card has its own pay-from account (Everyday checking): the only account ever preselected.
        assertEquals(checking, p.defaultPayFromId)
    }

    @Test
    fun payFromChoicesAreOpenCheckingSavingsAndCashAndNeverACard() {
        val names = view().payFromPick("").groups.flatMap { it.accounts }.map { it.name }
        assertEquals(listOf("Everyday checking", "Rainy day savings", "Wallet"), names)
    }

    @Test
    fun aCardPaymentIsAnOrdinaryTransferAndItsProblemsAreAndroidsSentences() {
        KassaCore.installDefaults()
        assertEquals("Card payment", CardPaymentBridge.note())
        assertNull(CardPaymentBridge.problem(1599, checking, "USD"))
        assertEquals(FieldProblemData("AMOUNT", "Enter an amount."), CardPaymentBridge.problem(0, checking, "USD"))
        assertEquals(FieldProblemData("ACCOUNT", "Pick the account it's paid from."), CardPaymentBridge.problem(100, 0, "USD"))
        assertEquals("AMOUNT", CardPaymentBridge.problem(10_000_000_000, checking, "USD")?.field)
        // As a transfer into the card it is a card payment on Activity, and the entry page may edit it (as on Android).
        val input = EntryInput(0, "TRANSFER", 1599, checking, travelCard, 0, 1_767_268_800_000L, CardPaymentBridge.note())
        assertNull(EntryBridge.problem(input, "USD"))
        assertTrue(EntryBridge.isEditable("TRANSFER", "CREDIT_CARD", false, false, false))
        assertEquals(listOf(checking.toString(), travelCard.toString()), EntryBridge.mustBeOpen(input, 0, 0))
    }

    @Test
    fun aCardPaymentRowOnActivityOpensTheEntryPage() {
        val v = view()
        val december = v.activity(2025, 12, false, today, "UTC")
        val row = december.days.flatMap { it.entries }.first { it.id == 51L }
        assertEquals("CardPayment", row.kind.name)
        assertTrue(row.editable)
        assertFalse(row.personPayment)
        assertNotNull(v.entryEdit(51))
    }

    // ---------- people ----------

    @Test
    fun samOwesYouAndTheHistoryRunsNewestFirst() {
        val v = view()
        val p = v.personPage(sam, false, "UTC")
        assertTrue(p.found)
        assertEquals("Sam", p.name)
        assertEquals(usd(num(expected, "samOwesYou")), p.balanceText)
        assertEquals(11_500, num(expected, "samOwesYou"))
        assertEquals("OwesYou", p.side.name)
        assertEquals("Green", p.balanceTone.name)
        assertEquals("PERSON_IN", p.defaultKind, "they owe you: they pay")
        assertEquals(11_500, p.suggestedCents)
        assertEquals(listOf("Paid back some", "Lent for a deposit", "Dinner split"), p.lines.map { it.note })
        assertEquals(listOf("$30.00", "$100.00", "+$45.00"), p.lines.map { it.amountText })
        assertEquals(listOf(true, true, false), p.lines.map { it.payment })
        assertEquals(listOf(330L, 329L, 0L), p.lines.map { it.paymentId })
        // Open since the first line that left zero: the dinner split on 10 January 2026.
        assertEquals(LocalDate.of(2026, 1, 10).toEpochDay().toInt(), p.sinceDay)
        assertEquals("Ink", p.lines[0].amountTone.name, "a payment only settles up: no colour")
        assertEquals("Green", p.lines[2].amountTone.name)
    }

    @Test
    fun hiddenPersonPageGivesNoSizeSideOrDate() {
        val p = view().personPage(sam, true, "UTC")
        assertEquals(mask, p.balanceText)
        assertEquals("Hidden", p.side.name)
        assertEquals("Ink", p.balanceTone.name)
        assertEquals(0, p.sinceDay)
        p.lines.forEach { assertEquals(mask, it.amountText); assertEquals("Ink", it.amountTone.name) }
    }

    @Test
    fun aPersonPaymentRowOpensItsOwnSheetAndNotTheEntryPage() {
        val v = view()
        // Entry 329 is "Lent for a deposit" on 12 March 2026.
        val march = v.activity(2026, 3, false, today, "UTC")
        val row = march.days.flatMap { it.entries }.first { it.id == 329L }
        assertEquals("PersonOut", row.kind.name)
        assertTrue(row.personPayment)
        assertFalse(row.editable)
        assertNull(v.entryEdit(329))
        val edit = assertNotNull(v.personPaymentEdit(329))
        assertEquals(PersonPaymentEditData(329, sam, "Sam", "PERSON_OUT", 10_000, checking, edit.epochMillis, "Lent for a deposit"), edit)
        assertNull(v.personPaymentEdit(51), "a card payment is not a person payment")
        assertNull(v.personPaymentEdit(3), "nor is an expense")
        assertNull(v.personPaymentEdit(99_999))
    }

    @Test
    fun theSheetOffersBankCashPrepaidAndCardAccountsAndKeepsTheEditedOne() {
        val v = view()
        fun names(keep: Long) = v.personPaymentPick("", keep).groups.flatMap { it.accounts }.map { it.name }
        assertEquals(setOf("Everyday checking", "Rainy day savings", "Wallet", "Travel card"), names(0).toSet())
        assertFalse(names(0).contains("Old savings"), "closed")
        assertFalse(names(0).contains("Car loan"))
        assertFalse(names(0).contains("Brokerage"))
        assertTrue(names(7).contains("Old savings"), "an edited payment keeps its account")
        assertEquals(listOf("Travel card"), v.personPaymentPick("trav", 0).groups.flatMap { it.accounts }.map { it.name })
    }

    @Test
    fun personPaymentRowsAreBuiltAsTheStoreWritesThem() {
        KassaCore.installDefaults()
        val input = PersonPaymentInput(0, sam, "PERSON_IN", 11_500, checking, 1_767_268_800_000L, "  Sam   paid you ")
        assertNull(PersonPaymentBridge.problem(input, "USD"))
        val cells = BackupRows.columns("transactions").indices.associate { BackupRows.columns("transactions")[it] to PersonPaymentBridge.cell(input, it) }
        assertEquals("PERSON_IN", cells["kind"])
        assertEquals("11500", cells["amount"])
        assertEquals(checking.toString(), cells["accountId"])
        assertEquals(sam.toString(), cells["personId"])
        assertEquals("Sam paid you", cells["note"], "the note is cleaned")
        assertNull(cells["toAccountId"])
        assertNull(cells["categoryId"])
        assertNull(cells["id"])
        val owned = BackupRows.columns("transactions").indices.filter { PersonPaymentBridge.ownsColumn(it) }.map { BackupRows.columns("transactions")[it] }
        assertEquals(setOf("kind", "amount", "accountId", "epochMillis", "note"), owned.toSet())
        assertEquals(listOf(checking.toString()), PersonPaymentBridge.mustBeOpen(input, 0))
        assertEquals(emptyList(), PersonPaymentBridge.mustBeOpen(input, checking))
    }

    @Test
    fun personPaymentProblemsAreAndroidsSentences() {
        KassaCore.installDefaults()
        fun input(kind: String = "PERSON_OUT", amount: Long = 500, account: Long = checking) =
            PersonPaymentInput(0, sam, kind, amount, account, 0, "")
        assertEquals(FieldProblemData("AMOUNT", "Enter an amount."), PersonPaymentBridge.problem(input(amount = 0), "USD"))
        assertEquals(FieldProblemData("ACCOUNT", "Pick an account."), PersonPaymentBridge.problem(input(account = 0), "USD"))
        assertEquals("AMOUNT", PersonPaymentBridge.problem(input(kind = "EXPENSE"), "USD")?.field)
        assertEquals("AMOUNT", PersonPaymentBridge.problem(input(kind = "nonsense"), "USD")?.field)
        assertTrue(PersonPaymentBridge.isPaymentKind("PERSON_IN"))
        assertFalse(PersonPaymentBridge.isPaymentKind("TRANSFER"))
        assertEquals("Sam paid you", PersonPaymentBridge.note("PERSON_IN", "Sam", "", ""))
        assertEquals("You paid Sam", PersonPaymentBridge.note("PERSON_OUT", "Sam", "PERSON_IN", "Sam paid you"))
        assertEquals("Lent for a deposit", PersonPaymentBridge.note("PERSON_OUT", "Sam", "PERSON_OUT", "Lent for a deposit"))
    }

    // ---------- account page and closing ----------

    @Test
    fun anAccountPageShowsItsBalanceAndItsMonthsEntries() {
        val v = view()
        // The last day of October: the page of that month shows that month's end balance, newest entries first.
        val p = v.accountPage(checking, false, 20757, "UTC", 0)
        assertEquals("Everyday checking", p.name)
        assertEquals("Balance", p.balanceKind.name)
        assertEquals(2026, p.year)
        assertEquals(10, p.month)
        assertTrue(p.entryCount > 0)
        assertEquals(p.entryCount, p.days.sumOf { it.entries.size })
        val newestFirst = p.days.map { it.epochDay }
        assertEquals(newestFirst.sortedDescending(), newestFirst)
        assertTrue(p.hasEarlier)
        // On the demo's own today the balance is the one expected.json lists.
        val now = v.accountPage(checking, false, today, "UTC", 0)
        assertEquals(usd(num(expected, "1")), now.balanceText)
        // Going back a month ends at the balance then: today's less what moved since the end of that month.
        val older = v.accountPage(checking, false, today, "UTC", 1)
        assertFalse(older.isCurrentMonth)
        val txs = demo().transactions
        val endOfOctober = LocalDate.of(2026, 10, 31)
        var balance = 250_000L
        for (t in txs) {
            if (dayOf(t.epochMillis).isAfter(endOfOctober)) continue
            if (t.accountId == checking) balance += if (t.kind == "INCOME" || t.kind == "PERSON_IN") t.amount else -t.amount
            if (t.kind == "TRANSFER" && t.toAccountId == checking) balance += t.amount
        }
        assertEquals(usd(balance), older.balanceText)
        assertEquals("Orange".takeIf { balance < 0 } ?: "Ink", older.balanceTone.name)
    }

    @Test
    fun aDebtShowsWhatIsOwedWithNoMinusAndAnInvestmentItsValue() {
        val v = view()
        assertEquals("OutstandingBalance", v.accountPage(5, false, today, "UTC", 0).balanceKind.name)
        assertEquals(usd(num(expected, "loanOwed")), v.accountPage(5, false, today, "UTC", 0).balanceText)
        assertEquals("TotalValue", v.accountPage(6, false, today, "UTC", 0).balanceKind.name)
        assertEquals(usd(225_000), v.accountPage(6, false, today, "UTC", 0).balanceText)
        assertEquals("TotalBalance", v.accountPage(travelCard, false, today, "UTC", 0).balanceKind.name)
        assertEquals(usd(num(expected, "cardOwed")), v.accountPage(travelCard, false, today, "UTC", 0).balanceText)
    }

    @Test
    fun onlyASettledAccountCloses() {
        val v = view()
        val problem = "Pay off or move the balance first."
        assertEquals(problem, v.closeProblem(checking))
        assertEquals(problem, v.closeProblem(travelCard))
        assertEquals(problem, v.closeProblem(5), "the loan")
        // The brokerage has no cash but holdings worth 2,250.00: not settled.
        assertEquals(problem, v.closeProblem(6))
        // Old savings is closed and settled: nothing refuses it (reopening never needs a check).
        assertEquals("", v.closeProblem(7))
        assertEquals("", v.closeProblem(999))
        assertEquals(problem, v.accountPage(checking, false, today, "UTC", 0).closeProblem)
        val old = v.accountPage(7, false, today, "UTC", 0)
        assertEquals(20634, old.closedDay)
        assertEquals("", old.closeProblem)
    }
}

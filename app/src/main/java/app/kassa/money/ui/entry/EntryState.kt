package app.kassa.money.ui.entry

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kassa.money.data.repo.EntryDraft
import app.kassa.money.domain.Account
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.Money
import app.kassa.money.domain.Split
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/** What the entry page opens with: a blank entry, an existing one to edit, or a prefilled one (a due bill). */
data class EntryStart(
    val editing: Transaction? = null,
    val splitWith: List<Long> = emptyList(),
    /** Each person's share when the split isn't equal (or when someone else paid). */
    val shares: Map<Long, Long> = emptyMap(),
    /** Someone else paid the whole expense. */
    val paidBy: Long? = null,
    /** Open with the split panel showing, as "Split a transaction" from a person does. */
    val openSplit: Boolean = false,
    val prefill: EntryDraft? = null,
    /** Where Add was pressed: from a card's or category's page, the new entry starts with it picked. */
    val preset: EntryPreset? = null,
    /** Runs after a successful save, for example to mark the bill that opened the page as handled. */
    val afterSave: (() -> Unit)? = null,
)

/** A new entry's starting kind, account or category, taken from the page Add was pressed on. */
data class EntryPreset(val kind: TxKind = TxKind.EXPENSE, val accountId: Long? = null, val categoryId: Long? = null)

/** The input the bottom panel shows. Null means none: the panel shows the number pad and the save button. */
internal enum class Field { AMOUNT, CATEGORY, SUB, ACCOUNT, TO, DATE, NOTE, SPLIT }

/** The kinds the entry page edits. Others (payments to people, trades) open on their own pages. */
private val PAGE_KINDS = setOf(TxKind.EXPENSE, TxKind.INCOME, TxKind.TRANSFER)

/**
 * Everything on the entry page: what's typed, which panel is open, and the rules that turn it into an [EntryDraft].
 * The composables only lay this out and call its methods. Each property is Compose state, so changes redraw the page.
 */
@Stable
internal class EntryState(val start: EntryStart, private val zone: ZoneId, private val today: LocalDate) {
    val editing: Transaction? = start.editing
    private val prefill: EntryDraft? = start.prefill
    private val originalMillis: Long? = editing?.epochMillis ?: prefill?.epochMillis

    // What's typed.
    var kind by mutableStateOf(editing?.kind?.takeIf { it in PAGE_KINDS } ?: prefill?.kind?.takeIf { it in PAGE_KINDS } ?: start.preset?.kind ?: TxKind.EXPENSE)
    var amount by mutableStateOf((editing?.amount ?: prefill?.amount)?.takeIf { it > 0 }?.let(::centsToInput) ?: "")
    var categoryId by mutableStateOf(editing?.categoryId ?: prefill?.categoryId ?: start.preset?.categoryId)
    /**
     * A new entry starts with no account: you pick it, or it's the one whose page you pressed Add on.
     * Nothing guesses one from the category or merchant.
     */
    var accountId by mutableStateOf(editing?.accountId ?: prefill?.accountId ?: start.preset?.accountId)
    var toAccountId by mutableStateOf(editing?.toAccountId ?: prefill?.toAccountId)
    var date by mutableStateOf(originalMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: today)
    var note by mutableStateOf((editing?.note ?: prefill?.note ?: "").take(InputRules.NOTE_MAX))
        private set

    // The split: who shares it, who paid (null: you), and exact shares when not equal.
    var splitPeople by mutableStateOf(start.splitWith)
        private set
    var paidBy by mutableStateOf(start.paidBy)
    var exact by mutableStateOf(start.shares.isNotEmpty() && start.shares != Split.equalShares(editing?.amount ?: prefill?.amount ?: 0L, start.splitWith))
        private set
    var exactShares by mutableStateOf(start.shares)
        private set

    // What's showing.
    var field by mutableStateOf(
        when {
            start.openSplit -> Field.SPLIT
            editing != null -> null
            amount.isEmpty() -> Field.AMOUNT
            prefill != null && categoryId == null && kind != TxKind.TRANSFER -> Field.CATEGORY
            else -> null
        }
    )
    var error by mutableStateOf<String?>(null)
    /** Search in the category and account panels; cleared whenever [field] changes. While [searching], the form
     *  above steps aside on a phone so results have room. */
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var calendarMonth by mutableStateOf(YearMonth.from(date))
    /** The person whose exact share is being typed, and what's typed so far. */
    var editingShare by mutableStateOf<Long?>(null)
    var shareInput by mutableStateOf("")

    private val initial = snapshot()
    private fun snapshot() = listOf(kind, amount, categoryId, accountId, toAccountId, date, note, splitPeople, paidBy, exactShares)
    /** True once anything differs from how the page opened; leaving then asks first. */
    val dirty: Boolean get() = snapshot() != initial || start.openSplit

    val cents: Long get() = Money.parseCents(amount) ?: 0L
    val shared: Boolean get() = kind == TxKind.EXPENSE && splitPeople.isNotEmpty()
    val shares: Map<Long, Long>
        get() = when {
            !shared -> emptyMap()
            exact -> splitPeople.associateWith { exactShares[it] ?: 0L }
            else -> Split.equalShares(cents, splitPeople)
        }
    val myShare: Long get() = cents - shares.values.sum()
    val someoneElsePaid: Boolean get() = kind == TxKind.EXPENSE && paidBy != null

    fun typeNote(raw: String) { note = InputRules.typed(raw, InputRules.NOTE_MAX) }

    fun pressKey(key: String) { amount = applyKey(amount, key); error = null }

    /** The next required input still empty, in the order the tiles read. */
    fun missing(): Field? = when (kind) {
        TxKind.TRANSFER -> when { accountId == null -> Field.ACCOUNT; toAccountId == null -> Field.TO; else -> null }
        else -> when { categoryId == null -> Field.CATEGORY; accountId == null && !someoneElsePaid -> Field.ACCOUNT; else -> null }
    }

    /** After a pick: the next empty input, then the note for a new entry, then nothing (the save button). */
    fun advance() {
        error = null
        field = missing() ?: when {
            cents <= 0 -> Field.AMOUNT
            editing == null && note.isBlank() && kind != TxKind.TRANSFER -> Field.NOTE
            else -> null
        }
    }

    fun pickCategory(id: Long) { categoryId = id; error = null }

    /** A payment can't come from a card, so switching to a transfer drops a card that was picked. */
    fun switchKind(k: TxKind, accounts: Map<Long, Account>) {
        if (k == kind) return
        kind = k; categoryId = null; error = null
        if (k == TxKind.TRANSFER && accountId?.let { accounts[it]?.type?.hasStatement } == true) accountId = null
        if (k != TxKind.TRANSFER) toAccountId = null
        field = if (amount.isEmpty()) Field.AMOUNT else missing()
    }

    fun pickNote(text: String, categoryFromLastUse: Long?) {
        note = text.take(InputRules.NOTE_MAX)
        if (categoryId == null) categoryId = categoryFromLastUse
        field = missing()
    }

    fun toggleSplitWith(personId: Long) {
        error = null
        if (personId in splitPeople) {
            splitPeople = splitPeople - personId
            exactShares = exactShares - personId
            if (paidBy == personId) paidBy = null
        } else splitPeople = splitPeople + personId
    }

    fun useEqualShares() { exact = false; error = null }

    /** Switches to exact amounts, starting from the equal split and keeping any share already typed. */
    fun useExactShares() {
        if (!exact) exactShares = Split.equalShares(cents, splitPeople) + exactShares.filterKeys { it in splitPeople && (exactShares[it] ?: 0L) > 0 }
        exact = true
    }

    fun startShare(personId: Long) { editingShare = personId; shareInput = "" }

    /** Saves the share being typed (an empty pad keeps the old one) and goes back to the split. */
    fun commitShare() {
        val person = editingShare ?: return
        Money.parseCents(shareInput)?.let { exactShares = exactShares + (person to it) }
        editingShare = null; error = null
    }

    /** The first problem in reading order, with the input to open for it; null when the entry can be saved. */
    fun problem(): Pair<Field, String>? {
        InputRules.amountProblem(cents)?.let { return Field.AMOUNT to it }
        InputRules.noteProblem(note)?.let { return Field.NOTE to it }
        return when {
            kind != TxKind.TRANSFER && categoryId == null -> Field.CATEGORY to "Pick a category."
            accountId == null && !someoneElsePaid -> Field.ACCOUNT to "Pick an account."
            shared && myShare < 0 -> Field.SPLIT to "The shares add up to more than ${Money.format(cents)}."
            kind == TxKind.TRANSFER && (toAccountId == null || toAccountId == accountId) -> Field.TO to "Pick where the money went."
            else -> null
        }
    }

    /** The entry to save. Call only when [problem] is null. */
    fun toDraft(): EntryDraft = EntryDraft(
        id = editing?.id,
        kind = kind,
        amount = cents,
        accountId = if (someoneElsePaid) null else accountId,
        toAccountId = if (kind == TxKind.TRANSFER) toAccountId else null,
        categoryId = if (kind == TxKind.TRANSFER) null else categoryId,
        personId = null,
        epochMillis = savedMillis(),
        note = InputRules.clean(note),
        splitWith = if (shared) splitPeople else emptyList(),
        shares = if (shared) shares else null,
        paidBy = if (shared) paidBy else null,
    )

    /** Keeps the original time of day when editing; uses now for today and noon for other new dates. */
    private fun savedMillis(): Long {
        val time = when {
            originalMillis != null -> Instant.ofEpochMilli(originalMillis).atZone(zone).toLocalTime()
            date == today -> LocalTime.now(zone)
            else -> LocalTime.NOON
        }
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }
}

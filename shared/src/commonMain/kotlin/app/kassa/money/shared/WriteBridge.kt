package app.kassa.money.shared

import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountField
import app.kassa.money.domain.AccountForm
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.CategoryIcon
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.CurrencyStyle
import app.kassa.money.domain.Currencies
import app.kassa.money.domain.EntryField
import app.kassa.money.domain.EntryRules
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.Money
import app.kassa.money.domain.PersonField
import app.kassa.money.domain.PersonForm
import app.kassa.money.domain.StarterCategories
import app.kassa.money.domain.TxKind
import app.kassa.money.domain.dateOfMillis
import app.kassa.money.domain.localDateOfEpochDay
import kotlinx.datetime.TimeZone

/*
 * The write path of the iOS app (stage C): what Swift calls to check and build the rows it writes. The rules are :domain's
 * (`EntryRules`, `AccountForm`, `PersonForm`, `StarterCategories`, `InputRules`); this file only turns them into flat values
 * Swift can pass and read, the way `BackupRows` does for the database: every value is a String, Int, Long, Boolean, a list of
 * those or a small class, never a Kotlin enum member (Swift compares those by `.name`) or a nullable Long (0 means "none": ids
 * start at 1). Rows leave as cells in the column order of `BackupRows.columns(table)`, found by name here so a column that moves
 * can't be bound to the wrong value.
 *
 * Sentences that mention an amount ("That's more than $100,000,000.") are written in the ledger's currency and never masked
 * (typed amounts are never masked), so each call that can word one takes the currency code.
 */

/** Runs [block] with Money in the currency [code] (fallback when unknown) and never masked, then puts Money back. */
internal fun <T> withMoney(code: String?, block: () -> T): T {
    val style = Money.style
    val hide = Money.hideAmounts
    Money.style = Currencies.forCode(code) ?: Currencies.fallback
    Money.hideAmounts = { false }
    try {
        return block()
    } finally {
        Money.style = style
        Money.hideAmounts = hide
    }
}

private fun txKind(name: String): TxKind? = TxKind.entries.firstOrNull { it.name == name }

private fun accountType(name: String): AccountType? = AccountType.entries.firstOrNull { it.name == name }

private fun idOrNull(id: Long): Long? = if (id > 0) id else null

private fun text(x: Long?): String? = x?.toString()

private fun flag(b: Boolean): String = if (b) "1" else "0"

/** A problem with one input: [field] is an `EntryField`, `AccountField` or `PersonField` name. */
data class FieldProblemData(val field: String, val message: String)

// ---------- entries ----------

/** One entry as Swift hands it over: flat, 0 means "none", [kind] is a `TxKind` name, [id] 0 for a new entry. */
class EntryInput(
    val id: Long,
    val kind: String,
    val amount: Long,
    val accountId: Long,
    val toAccountId: Long,
    val categoryId: Long,
    val epochMillis: Long,
    val note: String,
) {
    internal fun draft(): EntryRules.Draft? {
        val k = txKind(kind) ?: return null
        return EntryRules.Draft(idOrNull(id), k, amount, idOrNull(accountId), idOrNull(toAccountId), idOrNull(categoryId), epochMillis, note)
    }
}

/** What the entry page needs from the rules. See `EntryRules`. */
object EntryBridge {

    /** The first problem of [input], or null when it can be saved. [currency] words amounts. */
    fun problem(input: EntryInput, currency: String): FieldProblemData? = withMoney(currency) {
        val draft = input.draft() ?: return@withMoney FieldProblemData(EntryField.AMOUNT.name, "That kind of entry can't be saved here.")
        EntryRules.problem(draft)?.let { FieldProblemData(it.field.name, it.message) }
    }

    /**
     * The input still empty, as an `EntryField` name: CATEGORY, ACCOUNT or TO; empty text when the entry has all it needs.
     * ("What's next" for the button under the pad.)
     */
    fun missing(kind: String, categoryId: Long, accountId: Long, toAccountId: Long): String {
        val k = txKind(kind) ?: return ""
        return EntryRules.missing(k, idOrNull(categoryId), idOrNull(accountId), idOrNull(toAccountId))?.name ?: ""
    }

    /** Number of cells of a row of `transactions` (the columns of `BackupRows`). */
    fun cellCount(): Int = BackupRows.columns("transactions").size

    /**
     * One cell of the `transactions` row for [input] as it is stored (see `EntryRules.normalized`): null for the id (the database
     * numbers it) and for the columns this page doesn't own (person, split share, external id), otherwise text.
     */
    fun cell(input: EntryInput, column: Int): String? {
        val d = EntryRules.normalized(input.draft() ?: return null)
        return when (BackupRows.columns("transactions").getOrNull(column)) {
            "kind" -> d.kind.name
            "amount" -> d.amount.toString()
            "accountId" -> text(d.accountId)
            "toAccountId" -> text(d.toAccountId)
            "categoryId" -> text(d.categoryId)
            "epochMillis" -> d.epochMillis.toString()
            "note" -> d.note
            else -> null
        }
    }

    /** True for the columns `cell` fills for an edit: the ones the page owns, so an edit leaves the others as they were. */
    fun ownsColumn(column: Int): Boolean =
        BackupRows.columns("transactions").getOrNull(column) in setOf("kind", "amount", "accountId", "toAccountId", "categoryId", "epochMillis", "note")

    /**
     * The accounts that must be open for this save, as decimal text: the ones it newly touches (see `EntryRules.newlyTouched`).
     * [beforeAccountId] and [beforeToAccountId] are the stored entry's accounts (0 for none; both 0 for a new entry).
     */
    fun mustBeOpen(input: EntryInput, beforeAccountId: Long, beforeToAccountId: Long): List<String> {
        val d = EntryRules.normalized(input.draft() ?: return emptyList())
        val before = if (beforeAccountId > 0 || beforeToAccountId > 0) {
            d.copy(accountId = idOrNull(beforeAccountId), toAccountId = idOrNull(beforeToAccountId))
        } else null
        return EntryRules.newlyTouched(d, before).map { it.toString() }
    }

    fun closedMessage(name: String): String = EntryRules.closedMessage(name)

    /** True for a new transfer into an investment account ([toAccountType] an `AccountType` name): it waits to be explained. */
    fun waitsForExplanation(kind: String, toAccountType: String): Boolean {
        val k = txKind(kind) ?: return false
        return EntryRules.waitsForExplanation(k, accountType(toAccountType))
    }

    /**
     * Whether the entry page may edit or delete a stored entry, from what the store knows of it ([kind] and [toAccountType] are
     * names, "" for none). See `EntryRules.isEditable`: this is the store's own check, so an edit never rewrites what the page doesn't know.
     */
    fun isEditable(kind: String, toAccountType: String, belongsToLoanPayment: Boolean, hasPerson: Boolean, isSplit: Boolean): Boolean {
        val k = txKind(kind) ?: return false
        return EntryRules.isEditable(k, accountType(toAccountType), belongsToLoanPayment, hasPerson, isSplit)
    }

    /** The moment to store for [epochDay] in the zone [zoneId]; [originalMillis] is the edited entry's (0 for a new one). See `EntryRules.savedMillis`. */
    fun savedMillis(epochDay: Int, zoneId: String, nowMillis: Long, originalMillis: Long): Long {
        val zone = try {
            TimeZone.of(zoneId)
        } catch (e: Exception) {
            TimeZone.UTC
        }
        return EntryRules.savedMillis(localDateOfEpochDay(epochDay), zone, nowMillis, if (originalMillis != 0L) originalMillis else null)
    }

    /** The calendar day of [epochMillis] in the zone [zoneId], as an epoch day. */
    fun dayOf(epochMillis: Long, zoneId: String): Int {
        val zone = try {
            TimeZone.of(zoneId)
        } catch (e: Exception) {
            TimeZone.UTC
        }
        return dateOfMillis(epochMillis, zone).toEpochDays()
    }

    // The number pad and the typed fields: what is typed is filtered as it is typed, by the same rules as Android.

    /** One pad key ("0" to "9", ".", "del") applied to [typed]. */
    fun press(typed: String, key: String): String = app.kassa.money.domain.AmountPad.press(typed, key)

    /** [typed] as the phone writes numbers; the typed text itself keeps a point. */
    fun display(typed: String): String = app.kassa.money.domain.AmountPad.display(typed)

    /** Cents as pad input (with a point). */
    fun typedOf(cents: Long): String = app.kassa.money.domain.AmountPad.typedOf(cents)

    /** What the pad has typed as cents; 0 for nothing. */
    fun cents(typed: String): Long = app.kassa.money.domain.AmountPad.cents(typed)

    /** A note as typed: no line breaks, at most the limit. */
    fun typedNote(raw: String): String = InputRules.typed(raw, InputRules.NOTE_MAX)

    /** A switch of kind: the ids that survive as "categoryId,accountId,toAccountId" in decimal text (0 for none). */
    fun switched(kind: String, to: String, categoryId: Long, accountId: Long, toAccountId: Long, accountIsCard: Boolean): String {
        val from = txKind(kind) ?: return "$categoryId,$accountId,$toAccountId"
        val target = txKind(to) ?: return "$categoryId,$accountId,$toAccountId"
        val d = EntryRules.Draft(
            kind = from, amount = 0, accountId = idOrNull(accountId), toAccountId = idOrNull(toAccountId),
            categoryId = idOrNull(categoryId), epochMillis = 0,
        )
        val next = EntryRules.switched(d, target, accountIsCard)
        return "${next.categoryId ?: 0},${next.accountId ?: 0},${next.toAccountId ?: 0}"
    }
}

// ---------- accounts and people ----------

/** One account as the Add account form has it, as typed. [type] is an `AccountType` name; [payFromId] 0 for none. */
class AccountInput(
    val type: String,
    val name: String,
    val last4: String,
    val balance: String,
    val statementDay: String,
    val dueDay: String,
    val limit: String,
    val payFromId: Long,
    /** A fixed deposit's maturity as an epoch day; 0 for none. Ignored for any other kind. */
    val maturityDay: Int,
) {
    /** The form without a maturity day (every kind but a fixed deposit). */
    constructor(
        type: String, name: String, last4: String, balance: String, statementDay: String, dueDay: String, limit: String, payFromId: Long,
    ) : this(type, name, last4, balance, statementDay, dueDay, limit, payFromId, 0)

    internal fun form(): AccountForm? {
        val t = accountType(type) ?: return null
        return AccountForm(
            type = t, name = name, last4 = last4, balance = balance, statementDay = statementDay, dueDay = dueDay,
            limit = limit, payFromId = idOrNull(payFromId), maturityDay = maturityDay.takeIf { it > 0 },
        )
    }
}

/** A kind of account in the Add account list. */
data class AccountKindData(val key: String, val hasStatement: Boolean, val isDebt: Boolean, val hasMaturity: Boolean)

/** A heading of the Add account list ("CASH_AND_BANK", "CREDIT_AND_LOANS", "OTHER") and its kinds. */
data class AccountKindGroupData(val key: String, val kinds: List<AccountKindData>)

/** What the Add account sheet needs from the rules. See `AccountForm`. */
object AccountBridge {

    private fun kind(t: AccountType) = AccountKindData(t.name, t.hasStatement, t.isDebt, t.hasMaturity)

    /**
     * The kinds the iOS Add account sheet offers. Loan or mortgage has its own form (`LoanBridge`); Fixed deposit offers an optional
     * maturity day. Left out for now, on purpose (its extra setup isn't built on iOS and must not be faked): Investment (holdings,
     * trades). An investment account from a backup is shown and works as on Android.
     */
    fun kindGroups(): List<AccountKindGroupData> = listOf(
        AccountKindGroupData("CASH_AND_BANK", listOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.PREPAID, AccountType.FIXED_DEPOSIT).map(::kind)),
        AccountKindGroupData("CREDIT_AND_LOANS", listOf(AccountType.CREDIT_CARD, AccountType.LOAN).map(::kind)),
        AccountKindGroupData("OTHER", listOf(AccountType.OTHER_ASSET, AccountType.OTHER_DEBT).map(::kind)),
    )

    /** Each field's problem (the `AccountField` name and the sentence), in field order; empty when the account can be saved. [takenNames] are the other accounts' names. */
    fun problems(input: AccountInput, takenNames: List<String>, currency: String): List<FieldProblemData> = withMoney(currency) {
        val form = input.form() ?: return@withMoney listOf(FieldProblemData(AccountField.NAME.name, "Pick a kind of account."))
        val found = form.problems(takenNames)
        AccountField.entries.mapNotNull { f -> found[f]?.let { FieldProblemData(f.name, it) } }
    }

    /**
     * One cell of the new `accounts` row (the columns of `BackupRows`): null for the id and for what the form doesn't set. Call only
     * when [problems] is empty. [sortOrder] puts the account after the others.
     */
    fun cell(input: AccountInput, column: Int, sortOrder: Int): String? = withMoney(null) {
        val form = input.form() ?: return@withMoney null
        val a: Account = form.toAccount(Account(name = "", type = form.type), currentBalance = 0).copy(sortOrder = sortOrder)
        when (BackupRows.columns("accounts").getOrNull(column)) {
            "name" -> a.name
            "type" -> a.type.name
            "openingBalance" -> a.openingBalance.toString()
            "statementDay" -> a.statementDay?.toString()
            "dueDay" -> a.dueDay?.toString()
            "payFromId" -> text(a.payFromId)
            "creditLimit" -> text(a.creditLimit)
            "archived" -> flag(a.archived)
            "sortOrder" -> a.sortOrder.toString()
            "last4" -> a.last4
            "maturityDay" -> a.maturityDay?.toString()
            else -> null
        }
    }

    /** The maturity day (epoch day) offered when the person adds one to a fixed deposit: a year after [today]. */
    fun defaultMaturityDay(today: Int): Int = AccountForm.defaultMaturityDay(today)

    /** A new account's `accounts` cells that are never null (the id is null). Swift uses it to know which columns to bind. */
    fun cellCount(): Int = BackupRows.columns("accounts").size
}

/** The Add person form. */
object PersonBridge {

    /** Each field's problem (the `PersonField` name and the sentence); empty when the person can be saved. */
    fun problems(name: String, theyOwe: Boolean, amount: String, takenNames: List<String>, currency: String): List<FieldProblemData> = withMoney(currency) {
        val found = PersonForm(name, theyOwe, amount).problems(takenNames)
        PersonField.entries.mapNotNull { f -> found[f]?.let { FieldProblemData(f.name, it) } }
    }

    /** What is already owed, in minor units (positive: they owe you). Call only when [problems] is empty. */
    fun opening(name: String, theyOwe: Boolean, amount: String): Long = withMoney(null) { PersonForm(name, theyOwe, amount).opening() }

    fun cleanName(name: String): String = InputRules.clean(name)

    /** The note of the line that holds what was owed already (the same text Android writes). */
    fun startingNote(): String = PersonForm.STARTING_NOTE
}

// ---------- first run ----------

/** A starter category as the first-run list shows it. [kind] is a `CategoryKind` name. */
data class StarterData(val name: String, val kind: String, val icon: String, val on: Boolean)

/** One currency in the first-run list: its code, English name and a sample ("$1,234.50", never masked). */
data class CurrencyOptionData(val code: String, val name: String, val sample: String)

/** What the first-run screens need from the rules. */
object StartBridge {

    /** The starter categories in list order; Swift words nothing of them but draws `icon` with its own symbols. */
    fun starters(): List<StarterData> = StarterCategories.all.map { StarterData(it.name, it.kind.name, it.icon, it.on) }

    /** How many categories [names] make. */
    fun categoryCount(names: List<String>): Int = StarterCategories.categories(names).size

    /**
     * One cell of row [row] of the `categories` rows made for the ticked [names] (the columns of `BackupRows`): null for the id.
     * [firstSortOrder] numbers the first one, the rest follow.
     */
    fun categoryCell(names: List<String>, row: Int, column: Int, firstSortOrder: Int): String? {
        val c = StarterCategories.categories(names).getOrNull(row) ?: return null
        return when (BackupRows.columns("categories").getOrNull(column)) {
            "name" -> c.name
            "kind" -> c.kind.name
            "icon" -> c.icon.encode()
            "color" -> c.color.toString()
            "countsTowardDaily" -> flag(c.countsTowardDaily)
            "hidden" -> flag(c.hidden)
            "sortOrder" -> (firstSortOrder + row).toString()
            else -> null
        }
    }

    /** [code] in capitals when Kassa lists that currency, else empty text. */
    fun listed(code: String): String = Currencies.forCode(code)?.code ?: ""

    /** The currencies Kassa lists, A to Z by name, each with a sample so the list shows how amounts will look. */
    fun currencies(): List<CurrencyOptionData> =
        Currencies.sorted().map { c: CurrencyStyle -> CurrencyOptionData(c.code, c.name, Money.sample(123_450, style = c)) }

    /**
     * The currency a phone starts with: [currencyCode] of its [regionCode] when Kassa lists it, else empty text and the first-run
     * screen asks. See `Currencies.forRegion`.
     */
    fun regionCurrency(regionCode: String, currencyCode: String): String = Currencies.forRegion(regionCode, currencyCode)?.code ?: ""
}

/** The field filters of the forms, so a field shows only what may be typed, as on Android. */
object FieldBridge {
    fun typedAmount(raw: String): String = InputRules.typedAmount(raw)
    fun typedBalance(raw: String): String = InputRules.typedBalance(raw)
    fun typedDigits(raw: String, max: Int): String = InputRules.typedDigits(raw, max)
    fun typedRate(raw: String): String = InputRules.typedRate(raw)
    fun typedName(raw: String): String = InputRules.typed(raw, InputRules.NAME_LIMIT)
}

internal fun categoryKindOf(name: String): CategoryKind? = CategoryKind.entries.firstOrNull { it.name == name }

internal fun iconParts(icon: CategoryIcon): Pair<String, String> = when (icon) {
    is CategoryIcon.Emoji -> "emoji" to icon.value
    is CategoryIcon.Symbol -> "symbol" to icon.name
    is CategoryIcon.Image -> "image" to icon.fileName
}

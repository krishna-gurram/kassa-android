package dev.krishnagurram.kassa.ui.entry

import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.CategoryKind
import dev.krishnagurram.kassa.domain.InputRules
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.ui.Ledger
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalTime

/* Plain functions behind the entry and bill pages: the number pad, and the order things are offered in. */

/** One number pad key applied to [current]: at most [InputRules.AMOUNT_DIGITS] whole digits and two decimals. */
internal fun applyKey(current: String, key: String): String = when (key) {
    "del" -> current.dropLast(1)
    "." -> if (current.contains('.')) current else current.ifEmpty { "0" } + "."
    else -> when {
        current.contains('.') && current.substringAfter('.').length >= 2 -> current
        !current.contains('.') && current.length >= InputRules.AMOUNT_DIGITS -> current
        current == "0" -> key
        else -> current + key
    }
}

/** What the pad has typed, grouped for reading: "12345.6" shows as "12,345.6" (or the currency's own grouping). */
internal fun displayAmount(typed: String): String {
    if (typed.isEmpty()) return "0"
    val whole = typed.substringBefore('.').ifEmpty { "0" }
    val grouped = whole.toLongOrNull()?.let { NumberFormat.getIntegerInstance(Money.style.locale).format(it) } ?: whole
    return if (typed.contains('.')) grouped + "." + typed.substringAfter('.') else grouped
}

/** Cents as pad input: 1250 is "12.50", 1200 is "12". */
internal fun centsToInput(cents: Long): String = BigDecimal.valueOf(cents).movePointLeft(2).toPlainString().removeSuffix(".00")

/** Top-level categories ranked by how often they're used, weighted toward entries logged near this hour. */
internal fun rankTop(ledger: Ledger, kind: CategoryKind): List<Category> {
    val hour = LocalTime.now(ledger.zone).hour
    val score = HashMap<Long, Double>()
    val wanted = if (kind == CategoryKind.INCOME) TxKind.INCOME else TxKind.EXPENSE
    for (t in ledger.recent) {
        if (t.kind != wanted) continue
        val top = t.categoryId?.let { ledger.categoryById[it] }?.let { c -> c.parentId ?: c.id } ?: continue
        val h = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).hour
        score[top] = (score[top] ?: 0.0) + 1 + 2 * maxOf(0.0, 1 - Math.abs(h - hour) / 4.0)
    }
    return ledger.categories.filter { it.kind == kind && it.parentId == null && !it.hidden }
        .sortedWith(compareByDescending<Category> { score[it.id] ?: 0.0 }.thenBy { it.sortOrder })
}

/**
 * Open accounts in the order to offer them: most used for this kind first (recent use counts more), then the types
 * that usually fit (cards and checking for expenses, bank accounts for income). It only orders; nothing is preselected.
 */
internal fun rankAccounts(ledger: Ledger, kind: TxKind, to: Boolean): List<Account> {
    val score = HashMap<Long, Double>()
    val newest = ledger.recent.maxOfOrNull { it.epochMillis } ?: 0L
    for (t in ledger.recent) {
        if (t.kind != kind) continue
        val id = (if (to) t.toAccountId else t.accountId) ?: continue
        val ageDays = (newest - t.epochMillis) / 86_400_000.0
        score[id] = (score[id] ?: 0.0) + 1 + maxOf(0.0, 1 - ageDays / 30.0)
    }
    val likely = when (kind) {
        TxKind.EXPENSE -> setOf(AccountType.CREDIT_CARD, AccountType.CHECKING, AccountType.CASH, AccountType.PREPAID)
        TxKind.INCOME -> setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH)
        else -> AccountType.entries.toSet()
    }
    return ledger.accounts.accounts.filter { !it.archived }
        .sortedWith(compareByDescending<Account> { score[it.id] ?: 0.0 }.thenByDescending { it.type in likely }.thenBy { it.sortOrder })
}

/** A category's visible subcategories, most used first. */
internal fun subsRanked(ledger: Ledger, top: Category): List<Category> {
    val uses = HashMap<Long, Int>()
    for (t in ledger.recent) t.categoryId?.let { uses[it] = (uses[it] ?: 0) + 1 }
    return ledger.categories.filter { it.parentId == top.id && !it.hidden }
        .sortedWith(compareByDescending<Category> { uses[it.id] ?: 0 }.thenBy { it.sortOrder })
}

/** Categories and subcategories whose name contains [query], best matches first; a subcategory needs a visible parent. */
internal fun searchCategories(ledger: Ledger, kind: CategoryKind, query: String): List<Category> =
    ledger.categories
        .filter { it.kind == kind && !it.hidden && it.name.contains(query, ignoreCase = true) }
        .filter { it.parentId == null || ledger.categoryById[it.parentId]?.hidden == false }
        .sortedWith(compareBy<Category> { !it.name.startsWith(query, ignoreCase = true) }.thenBy { it.parentId != null }.thenBy { it.name.lowercase() })

/**
 * Up to six notes to offer under the note field. With nothing typed and a category picked, the notes used most
 * with that category; otherwise the frequent notes ([all]) that contain what's typed.
 */
internal fun noteSuggestions(all: List<String>, typed: String, ledger: Ledger, kind: TxKind, categoryId: Long?): List<String> {
    val q = typed.trim().lowercase()
    val pool = if (categoryId != null && q.isEmpty()) {
        ledger.recent.filter { it.kind == kind && it.categoryId == categoryId && it.note.isNotBlank() }
            .groupingBy { it.note }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    } else all
    return pool.filter { q.isEmpty() || (it.lowercase().contains(q) && it.lowercase() != q) }.take(6)
}

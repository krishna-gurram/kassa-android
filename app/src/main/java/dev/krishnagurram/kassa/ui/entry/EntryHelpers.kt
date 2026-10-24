package dev.krishnagurram.kassa.ui.entry

import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.CategoryKind
import dev.krishnagurram.kassa.domain.InputRules
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Names
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.ui.Ledger
import java.math.BigDecimal
import java.text.NumberFormat

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

/** Visible top-level categories of [kind], A to Z, so each stays in the same place in the picker. */
internal fun topCategories(ledger: Ledger, kind: CategoryKind): List<Category> =
    ledger.categories.filter { it.kind == kind && it.parentId == null && !it.hidden }.sortedWith(Names.order { it.name })

/** Open accounts, A to Z. It only orders; nothing is preselected. */
internal fun openAccounts(ledger: Ledger): List<Account> =
    ledger.accounts.accounts.filter { !it.archived }.sortedWith(Names.order { it.name })

/** A category's visible subcategories, A to Z. */
internal fun subcategories(ledger: Ledger, top: Category): List<Category> =
    ledger.categories.filter { it.parentId == top.id && !it.hidden }.sortedWith(Names.order { it.name })

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

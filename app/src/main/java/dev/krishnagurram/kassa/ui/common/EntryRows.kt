package dev.krishnagurram.kassa.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.theme.Kassa

/** One entry in any list. Shared by Today, Activity, card details and people. */
@Composable
fun TransactionRow(
    t: Transaction,
    ledger: Ledger,
    showDivider: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: ((Transaction) -> Unit)? = null,
) {
    val m = if (onClick != null) modifier.clickable { onClick(t) } else modifier
    val acct = t.accountId?.let { ledger.accountById[it]?.name }
    val person = t.personId?.let { id -> ledger.people.people.firstOrNull { it.id == id }?.name }
    when (t.kind) {
        TxKind.TRANSFER -> {
            val to = ledger.accountById[t.toAccountId]
            EntryRow(
                badge = { GlyphBadge("⇄") },
                title = "To ${to?.name ?: "an account"}",
                subtitle = "From ${acct ?: "an account"}",
                amount = Money.format(t.amount), amountColor = Kassa.colors.ink2,
                note = when { to?.type?.hasStatement == true -> "Card payment"; t.note.isNotBlank() -> t.note; else -> "Transfer" },
                showDivider = showDivider, modifier = m,
            )
        }
        TxKind.PERSON_IN, TxKind.PERSON_OUT -> EntryRow(
            badge = { GlyphBadge(person?.take(1) ?: "?") },
            title = t.note.ifBlank { if (t.kind == TxKind.PERSON_IN) "${person ?: "Someone"} paid you" else "You paid ${person ?: "someone"}" },
            subtitle = acct ?: "",
            amount = Money.format(t.amount), amountColor = Kassa.colors.ink2,
            note = "Settle up", showDivider = showDivider, modifier = m,
        )
        else -> {
            val cat = t.categoryId?.let { ledger.categoryById[it] }
            val parent = cat?.parentId?.let { ledger.categoryById[it] }
            val share = t.myShare?.takeIf { it != t.amount }?.let { "your share ${Money.format(it)}" }
            EntryRow(
                badge = { CategoryBadge(parent ?: cat) },
                title = t.note.ifBlank { cat?.name ?: "Uncategorized" },
                subtitle = listOfNotNull(cat?.name ?: "Uncategorized", share).joinToString(", "),
                amount = if (t.kind == TxKind.INCOME) Money.format(t.amount, showPlus = true) else Money.format(t.amount),
                amountColor = if (t.kind == TxKind.INCOME) Kassa.colors.positive else Kassa.colors.ink,
                note = acct ?: person?.let { "Paid by $it" },
                showDivider = showDivider, modifier = m,
            )
        }
    }
}

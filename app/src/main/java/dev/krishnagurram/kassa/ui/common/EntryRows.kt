package dev.krishnagurram.kassa.ui.common

import java.time.Instant
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingUp
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
    /** Replaces the small line under the amount, for example with the running balance on an account page. */
    noteOverride: String? = null,
    onClick: ((Transaction) -> Unit)? = null,
    /** Puts the date in front of the second line, for lists that aren't grouped by day. */
    showDate: Boolean = false,
) {
    val date = if (showDate) Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).toLocalDate().let { d ->
        d.format(java.time.format.DateTimeFormatter.ofPattern(if (d.year == ledger.today.year) "MMM d" else "MMM d, yyyy")) + " \u00b7 "
    } else ""
    val m = if (onClick != null) modifier.clickable { onClick(t) } else modifier
    val acct = t.accountId?.let { ledger.accountById[it]?.name }
    val person = t.personId?.let { id -> ledger.people.people.firstOrNull { it.id == id }?.name }
    when (t.kind) {
        TxKind.TRANSFER -> {
            val to = ledger.accountById[t.toAccountId]
            EntryRow(
                badge = { IconBadge(androidx.compose.material.icons.Icons.Outlined.SwapHoriz) },
                title = "To ${to?.name ?: "an account"}",
                subtitle = date + "From ${acct ?: "an account"}",
                amount = Money.format(t.amount), amountColor = Kassa.colors.ink2,
                note = noteOverride ?: when { to?.type?.hasStatement == true -> "Card payment"; t.note.isNotBlank() -> t.note; else -> "Transfer" },
                showDivider = showDivider, modifier = m,
            )
        }
        TxKind.BUY, TxKind.SELL -> EntryRow(
            badge = { IconBadge(if (t.kind == TxKind.BUY) androidx.compose.material.icons.Icons.Outlined.TrendingUp else androidx.compose.material.icons.Icons.Outlined.TrendingDown) },
            title = (if (t.kind == TxKind.BUY) "Bought " else "Sold ") + t.note,
            subtitle = date + (acct ?: ""),
            amount = Money.format(t.amount), amountColor = Kassa.colors.ink2,
            note = noteOverride ?: "Investing", showDivider = showDivider, modifier = m,
        )
        TxKind.PERSON_IN, TxKind.PERSON_OUT -> EntryRow(
            badge = { GlyphBadge(person?.take(1) ?: "?") },
            title = t.note.ifBlank { if (t.kind == TxKind.PERSON_IN) "${person ?: "Someone"} paid you" else "You paid ${person ?: "someone"}" },
            subtitle = date + (acct ?: ""),
            amount = Money.format(t.amount), amountColor = Kassa.colors.ink2,
            note = noteOverride ?: "Settle up", showDivider = showDivider, modifier = m,
        )
        else -> {
            val cat = t.categoryId?.let { ledger.categoryById[it] }
            val parent = cat?.parentId?.let { ledger.categoryById[it] }
            val share = t.myShare?.takeIf { it != t.amount }?.let { "your share ${Money.format(it)}" }
            EntryRow(
                badge = { CategoryBadge(parent ?: cat) },
                title = t.note.ifBlank { cat?.name ?: "Uncategorized" },
                subtitle = date + listOfNotNull(cat?.name ?: "Uncategorized", share).joinToString(", "),
                amount = if (t.kind == TxKind.INCOME) Money.format(t.amount, showPlus = true) else Money.format(t.amount),
                amountColor = if (t.kind == TxKind.INCOME) Kassa.colors.positive else Kassa.colors.ink,
                note = noteOverride ?: acct ?: person?.let { "Paid by $it" },
                showDivider = showDivider, modifier = m,
            )
        }
    }
}

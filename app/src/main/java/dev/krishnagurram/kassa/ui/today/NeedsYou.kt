package dev.krishnagurram.kassa.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.domain.InvestmentTransfer
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Recurring
import dev.krishnagurram.kassa.domain.RecurringRules
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.CategoryBadge
import dev.krishnagurram.kassa.ui.common.ConfirmDialog
import dev.krishnagurram.kassa.ui.common.GlyphBadge
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.entry.Chip
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** What the review list can do. Nothing here posts by itself: each choice is the user's. */
interface NeedsYouActions {
    fun approve(r: Recurring, amount: Long)
    fun editRecurring(r: Recurring, amount: Long)
    fun skip(r: Recurring)
    fun allocate(t: InvestmentTransfer)
    /** Stop asking about a transfer, changing nothing else. */
    fun dismiss(t: InvestmentTransfer)
}

/** A ✕ on a card asks this first. */
private class Dismissal(val title: String, val body: String, val confirm: String, val run: () -> Unit)

/** Bills due today and investment transfers waiting to be explained. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NeedsYou(ledger: Ledger, actions: NeedsYouActions) {
    val transfers = ledger.transfers
    val due = ledger.dueBills()
    val count = transfers.size + due.size
    if (count == 0) return
    var asking by remember { mutableStateOf<Dismissal?>(null) }
    asking?.let { d -> ConfirmDialog(d.title, d.body, d.confirm, onConfirm = d.run, onDismiss = { asking = null }, destructive = false) }
    SectionHeader("Pending review", "$count to confirm")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
        due.forEach { r ->
            val est = RecurringRules.estimate(r, ledger.recent, ledger.today.plusDays(1), ledger.zone)
            val last = RecurringRules.recent(r, ledger.recent)
            val cat = ledger.categoryById[r.categoryId]
            val top = cat?.let { c -> c.parentId?.let { ledger.categoryById[it] } ?: c }
            val dueDate = RecurringRules.dueDate(r, YearMonth.from(ledger.today))
            Card(
                kicker = "Recurring, due ${dueDate.format(DateTimeFormatter.ofPattern("EEE, MMM d"))}" +
                    if (r.fixedAmount == null && last.isNotEmpty()) ". Last ${last.size}: ${last.joinToString { Money.format(it, wholeDollars = true) }}" else "",
                badge = { CategoryBadge(top) },
                title = r.name,
                subtitle = if (r.fixedAmount != null) "Fixed amount" else if (est != null) "Estimated, edit if the bill differs" else "No history yet, enter the amount",
                amount = est?.let { Money.format(it) } ?: "",
                onDismiss = { asking = Dismissal("Skip ${r.name} this month?", "It comes back next month. Nothing is added.", "Skip") { actions.skip(r) } },
            ) {
                if (est != null) Chip("Add ${Money.format(est)}", selected = true) { actions.approve(r, est) }
                Chip(if (est != null) "Edit amount" else "Enter amount", selected = est == null) { actions.editRecurring(r, est ?: 0) }
                Chip("Skip this month", selected = false, outlined = true) { actions.skip(r) }
            }
        }
        transfers.forEach { p ->
            val acct = ledger.accountById[p.accountId]
            Card(
                kicker = "Investment transfer on ${Instant.ofEpochMilli(p.epochMillis).atZone(ledger.zone).format(DateTimeFormatter.ofPattern("MMM d"))}",
                badge = { GlyphBadge("↗") },
                title = "Where did it go?",
                subtitle = "${Money.format(p.amount, wholeDollars = true)} moved to ${acct?.name ?: "investments"}",
                amount = Money.format(p.amount),
                onDismiss = { asking = Dismissal("Remove from pending review?", "Nothing else changes: the account's cash and holdings stay as they are.", "Remove") { actions.dismiss(p) } },
            ) {
                Chip("Answer", selected = true) { actions.allocate(p) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Card(
    kicker: String,
    badge: @Composable () -> Unit,
    title: String,
    subtitle: String,
    amount: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Kassa.colors.raised).border(1.dp, Kassa.colors.line, shape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kicker, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp).offset(x = 6.dp)) {
                Icon(Icons.Outlined.Close, "Dismiss", tint = Kassa.colors.ink2, modifier = Modifier.size(18.dp))
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            badge()
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = KassaType.small, color = Kassa.colors.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(amount, style = KassaType.bodyStrong, color = Kassa.colors.ink)
        }
        FlowRow(Modifier.padding(top = 12.dp)) { buttons() }
    }
}

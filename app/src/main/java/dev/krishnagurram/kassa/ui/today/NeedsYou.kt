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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.data.db.PendingEntity
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Recurring
import dev.krishnagurram.kassa.domain.RecurringRules
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.CategoryBadge
import dev.krishnagurram.kassa.ui.common.GlyphBadge
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.entry.Chip
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Callbacks for the things that wait on Today. */
interface NeedsYouActions {
    fun addAlert(p: PendingEntity)
    fun editAlert(p: PendingEntity)
    fun dismissAlert(p: PendingEntity)
    fun approve(r: Recurring, amount: Long)
    fun editRecurring(r: Recurring, amount: Long)
    fun skip(r: Recurring)
    fun allocate(p: PendingEntity)
}

/** Bank alerts to confirm, recurring bills to approve, investment transfers to explain. Nothing posts on its own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NeedsYou(ledger: Ledger, actions: NeedsYouActions) {
    val alerts = ledger.assist.pending.filter { it.type == "ALERT" }
    val allocs = ledger.assist.pending.filter { it.type == "ALLOCATE" }
    val due = ledger.dueRecurring()
    val count = alerts.size + allocs.size + due.size
    if (count == 0) return
    SectionHeader("Pending review", "$count to confirm")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
        alerts.forEach { p ->
            val cat = p.categoryId?.let { ledger.categoryById[it] }
            val top = cat?.let { c -> c.parentId?.let { ledger.categoryById[it] } ?: c }
            val acct = p.accountId?.let { ledger.accountById[it] }
            val time = Instant.ofEpochMilli(p.epochMillis).atZone(ledger.zone)
            Card(
                kicker = "From a bank alert, ${time.format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))}",
                badge = { CategoryBadge(top) },
                title = p.merchant ?: if (p.isCredit) "Refund" else "Card purchase",
                subtitle = listOfNotNull(cat?.name ?: "Pick a category", acct?.name ?: "pick an account").joinToString(" on "),
                amount = (if (p.isCredit) "+" else "") + Money.format(p.amount),
            ) {
                if (cat != null && acct != null) Chip("Add it", selected = true) { actions.addAlert(p) }
                Chip(if (cat != null && acct != null) "Edit" else "Finish it", selected = cat == null || acct == null) { actions.editAlert(p) }
                Chip("Dismiss", selected = false, outlined = true) { actions.dismissAlert(p) }
            }
        }
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
            ) {
                if (est != null) Chip("Add ${Money.format(est)}", selected = true) { actions.approve(r, est) }
                Chip(if (est != null) "Edit amount" else "Enter amount", selected = est == null) { actions.editRecurring(r, est ?: 0) }
                Chip("Skip this month", selected = false, outlined = true) { actions.skip(r) }
            }
        }
        allocs.forEach { p ->
            val acct = p.accountId?.let { ledger.accountById[it] }
            Card(
                kicker = "Investment transfer on ${Instant.ofEpochMilli(p.epochMillis).atZone(ledger.zone).format(DateTimeFormatter.ofPattern("MMM d"))}",
                badge = { GlyphBadge("↗") },
                title = "Where did it go?",
                subtitle = "${Money.format(p.amount, wholeDollars = true)} moved to ${acct?.name ?: "investments"}",
                amount = Money.format(p.amount),
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
    buttons: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Kassa.colors.raised).border(1.dp, Kassa.colors.line, shape).padding(16.dp)) {
        Text(kicker, style = KassaType.tiny, color = Kassa.colors.ink2)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            badge()
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = KassaType.bodyStrong, color = Kassa.colors.ink)
                Text(subtitle, style = KassaType.small, color = Kassa.colors.ink2)
            }
            Text(amount, style = KassaType.bodyStrong, color = Kassa.colors.ink)
        }
        FlowRow(Modifier.padding(top = 12.dp)) { buttons() }
    }
}

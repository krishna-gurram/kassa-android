package dev.krishnagurram.kassa.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.RecurringRules
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.importer.SecondaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType

/** Recurring bills: each asks on its day with a prefilled amount and never posts on its own. Tapping one opens the bill page. */
@Composable
fun RecurringPage(ledger: Ledger, actions: SettingsActions) {
    Text("On a bill's day, Overview asks you to confirm it.", style = KassaType.body, color = Kassa.colors.ink2)
    Gap(10)
    SecondaryButton("Add a recurring bill", { actions.openBill(null) })
    SectionHeader("Your bills")
    if (ledger.assist.recurring.isEmpty()) Text("None yet. Good candidates: rent, electricity, phone, insurance.", style = KassaType.body, color = Kassa.colors.ink2)
    ledger.assist.recurring.sortedBy { it.dayOfMonth }.forEach { r ->
        val est = RecurringRules.estimate(r, ledger.recent, ledger.today.plusDays(1), ledger.zone)
        Column(Modifier.clickable { actions.openBill(r) }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = KassaType.bodyStrong, color = if (r.active) Kassa.colors.ink else Kassa.colors.ink3)
                    Text("Day ${r.dayOfMonth} · ${ledger.accountById[r.accountId]?.name ?: "No account"}${if (!r.active) " · Paused" else ""}", style = KassaType.small, color = Kassa.colors.ink2)
                }
                Text(est?.let { (if (r.fixedAmount == null) "≈ " else "") + Money.format(it) } ?: "—", style = KassaType.bodyStrong, color = Kassa.colors.ink)
            }
            HorizontalDivider(color = Kassa.colors.line)
        }
    }
    Gap(24)
}

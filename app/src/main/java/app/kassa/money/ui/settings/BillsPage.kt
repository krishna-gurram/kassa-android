package app.kassa.money.ui.settings

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.Money
import app.kassa.money.domain.RecurringRules
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.importer.SecondaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/** Recurring bills: each asks on its day with a prefilled amount and never posts on its own. Tapping one opens the bill page. */
@Composable
internal fun BillsPage(ledger: Ledger, actions: SettingsActions) {
    Text(stringResource(R.string.settings_bills_intro), style = KassaType.body, color = Kassa.colors.ink2)
    Gap(10)
    SecondaryButton(stringResource(R.string.settings_bills_add), { actions.openBill(null) })
    SectionHeader(stringResource(R.string.settings_bills_header))
    if (ledger.bills.isEmpty()) Text(stringResource(R.string.settings_bills_empty), style = KassaType.body, color = Kassa.colors.ink2)
    ledger.bills.sortedBy { it.dayOfMonth }.forEach { r ->
        val est = RecurringRules.estimate(r, ledger.recent, ledger.today.plusDays(1), ledger.zone)
        Column(Modifier.clickable { actions.openBill(r) }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = KassaType.bodyStrong, color = if (r.active) Kassa.colors.ink else Kassa.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(if (r.active) R.string.settings_bills_detail else R.string.settings_bills_detail_paused, r.dayOfMonth, ledger.accountById[r.accountId]?.name ?: stringResource(R.string.settings_bills_no_account)), style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(modifier = Modifier.padding(start = 12.dp), text = est?.let { if (r.fixedAmount == null) stringResource(R.string.settings_bills_approx, Money.format(it)) else Money.format(it) } ?: "—", style = KassaType.bodyStrong, color = Kassa.colors.ink)
            }
            HorizontalDivider(color = Kassa.colors.line)
        }
    }
    Gap(24)
}

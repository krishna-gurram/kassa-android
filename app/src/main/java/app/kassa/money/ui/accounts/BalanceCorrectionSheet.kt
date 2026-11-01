package app.kassa.money.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.BalanceCorrection
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.Money
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.effectOn
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.cards.cardMoney
import app.kassa.money.ui.common.AmountField
import app.kassa.money.ui.common.ConfirmDialog
import app.kassa.money.ui.common.DateRow
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.momentOn
import app.kassa.money.ui.common.rememberSavedDate
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.importer.SecondaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.Instant

/**
 * Make an account show the balance its bank or lender shows, with a dated balance correction (see [BalanceCorrection]):
 * the figure typed, what Kassa shows now, and the difference as an arrow and an amount. Earlier entries stay as they
 * are. Opening an earlier correction edits or deletes it; the difference is then worked out without it. Built for any
 * account; a debt is typed and shown as what is owed.
 *
 * [onSave] gets the balance the account should show (as the account's own balance, so a debt is negative) and the
 * moment, and a `done` to call once saved so the sheet stays open on a problem.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BalanceCorrectionSheet(
    ledger: Ledger,
    account: Account,
    editing: Transaction?,
    onDismiss: () -> Unit,
    onSave: (target: Long, epochMillis: Long, done: () -> Unit) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val debt = account.type.isDebt
    val now = ledger.accounts.balances[account.id] ?: 0
    // What Kassa shows without the correction being edited: the difference is measured from this.
    val without = now - (editing?.effectOn(account.id) ?: 0)
    val shownWithout = BalanceCorrection.shownFrom(without, debt)
    // Typed input survives folding or rotating the phone.
    var text by rememberSaveable { mutableStateOf(editing?.let { Money.toInput(BalanceCorrection.shownFrom(now, debt)) } ?: "") }
    var date by rememberSavedDate { editing?.let { Instant.ofEpochMilli(it.epochMillis).atZone(ledger.zone).toLocalDate() } ?: ledger.today }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val typed = Money.parseCents(text)
    val difference = typed?.let { it - shownWithout }
    val label = if (account.type == AccountType.LOAN) stringResource(R.string.accounts_balance_lender_shows) else stringResource(R.string.accounts_balance_should_show)
    val enterBalance = stringResource(R.string.accounts_enter_balance)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding().navigationBarsPadding().widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (editing == null) stringResource(R.string.accounts_correct_balance) else BalanceCorrection.TITLE, style = KassaType.section, color = Kassa.colors.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Gap(8)
            AmountField(
                text, { v -> text = v; problem = null }, label, Modifier.fillMaxWidth(), problem = problem, signed = !debt,
            )
            Column(
                Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(Kassa.colors.ground).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Line(stringResource(R.string.accounts_balance_in_kassa), if (debt) cardMoney(shownWithout) else Money.format(shownWithout))
                Line(stringResource(R.string.accounts_difference), difference?.takeIf { it != 0L }?.let { BalanceCorrection.describe(it) } ?: "—")
            }
            Text(stringResource(R.string.accounts_date), style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            DateRow(date, ledger.today) { d -> date = d }
            Text(
                stringResource(R.string.accounts_correction_note),
                style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 8.dp),
            )
            Gap(12)
            PrimaryButton(stringResource(R.string.accounts_save_correction)) {
                val t = typed
                val change = t?.let { BalanceCorrection.change(BalanceCorrection.targetFrom(it, debt), without) }
                problem = when {
                    t == null -> enterBalance
                    else -> InputRules.balanceProblem(t) ?: change?.let { BalanceCorrection.problem(it) }
                }
                if (problem == null && t != null) onSave(BalanceCorrection.targetFrom(t, debt), momentOn(date, ledger.today, ledger.zone), onDismiss)
            }
            if (onDelete != null) SecondaryButton(stringResource(R.string.accounts_delete_correction), { confirmDelete = true }, Modifier.padding(top = 8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.accounts_cancel), color = Kassa.colors.ink2) }
            Gap(12)
        }
    }
    if (confirmDelete && onDelete != null) ConfirmDialog(
        title = stringResource(R.string.accounts_delete_correction_title),
        body = stringResource(R.string.accounts_delete_correction_body),
        confirm = stringResource(R.string.accounts_delete), onConfirm = onDelete, onDismiss = { confirmDelete = false },
    )
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.weight(1f, fill = false))
        Text(value, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
    }
}

package app.kassa.money.ui.accounts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.Account
import app.kassa.money.domain.closeProblem
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.common.ConfirmDialog
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/** What an account's page can do about closing it, handed in by the router so the page never touches the view model. */
class AccountClosing(
    /** Shows a short message ("Pay off or move the balance first."). */
    val onMessage: (String) -> Unit,
    val onClose: () -> Unit,
    val onReopen: () -> Unit,
)

/**
 * What every account page shows under its summary besides the entries: a fixed deposit's maturity date, then either
 * Close account (only a settled account closes; otherwise a message says what to do first) or, for a closed account,
 * when it closed and Reopen. Closing deletes nothing: the entries stay in Activity, Insights and reports.
 */
@Composable
fun AccountStatus(ledger: Ledger, account: Account, closing: AccountClosing?) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        account.maturityDay?.let {
            Text(stringResource(R.string.accounts_matures_on, accountDate(it)), style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp))
        }
        val closed = account.closedDay
        if (closed != null) {
            Text(stringResource(R.string.accounts_closed_on, accountDate(closed)), style = KassaType.small, color = Kassa.colors.ink2,
                modifier = Modifier.padding(top = 10.dp))
            if (closing != null) TextButton(onClick = closing.onReopen, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.accounts_reopen_account), style = KassaType.bodyStrong, color = Kassa.colors.ink)
            }
        } else if (closing != null && !account.archived) {
            TextButton(
                onClick = {
                    // An investment account is settled only when its cash and its holdings are both gone.
                    val worth = (ledger.accounts.balances[account.id] ?: 0L) + ledger.holdingsValue(account.id)
                    closeProblem(worth)?.let(closing.onMessage) ?: run { confirm = true }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.accounts_close_account), style = KassaType.bodyStrong, color = Kassa.colors.ink2) }
        }
    }
    if (confirm && closing != null) ConfirmDialog(
        title = stringResource(R.string.accounts_close_title, account.name),
        body = stringResource(R.string.accounts_close_body),
        confirm = stringResource(R.string.accounts_close_account), destructive = false,
        onConfirm = closing.onClose, onDismiss = { confirm = false },
    )
}

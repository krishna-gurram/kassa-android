package app.kassa.money.ui.accounts

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/** How the Accounts tab orders every group; stored in Settings as these names. */
internal enum class AccountSort(@StringRes val labelRes: Int) {
    NAME(R.string.accounts_sort_az), BALANCE(R.string.accounts_sort_largest), DUE(R.string.accounts_sort_due), CUSTOM(R.string.accounts_sort_custom);

    companion object {
        fun of(stored: String) = entries.firstOrNull { it.name == stored } ?: NAME
    }
}

/**
 * Order accounts: the one place the Accounts tab's order is chosen, and the order Overview's tiles follow. Name,
 * Balance and Due date each set a sort; Your order is the arrangement, started or edited by its Arrange button.
 * Reset to A to Z applies at once and the caller offers Undo, so there's no confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrderSheet(
    sort: AccountSort,
    onPick: (AccountSort) -> Unit,
    onArrange: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp).widthIn(max = 640.dp)) {
            Text(stringResource(R.string.accounts_order_accounts), style = KassaType.section, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.accounts_order_overview_note), style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            OrderOption(stringResource(R.string.accounts_order_name), stringResource(R.string.accounts_sort_az), sort == AccountSort.NAME) { onPick(AccountSort.NAME) }
            OrderOption(stringResource(R.string.accounts_order_balance), stringResource(R.string.accounts_sort_largest), sort == AccountSort.BALANCE) { onPick(AccountSort.BALANCE) }
            OrderOption(stringResource(R.string.accounts_order_due_date), stringResource(R.string.accounts_order_due_sub), sort == AccountSort.DUE) { onPick(AccountSort.DUE) }
            OrderOption(
                stringResource(R.string.accounts_sort_custom), stringResource(R.string.accounts_order_custom_sub), sort == AccountSort.CUSTOM,
                onClick = onArrange,
                action = if (sort == AccountSort.CUSTOM) stringResource(R.string.accounts_edit) else stringResource(R.string.accounts_arrange), onAction = onArrange,
            )
            HorizontalDivider(color = Kassa.colors.line)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onReset, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.accounts_reset_to_az), style = KassaType.bodyStrong, color = Kassa.colors.ink2)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.accounts_close), style = KassaType.bodyStrong, color = Kassa.colors.ink)
                }
            }
        }
    }
}

/** One radio row at least 56dp tall, with an optional pill button after it that does the same as the row. */
@Composable
private fun OrderOption(
    title: String, sub: String, selected: Boolean,
    action: String? = null, onAction: () -> Unit = {},
    onClick: () -> Unit,
) {
    HorizontalDivider(color = Kassa.colors.line)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected, onClick = null, modifier = Modifier.padding(end = 12.dp),
                colors = RadioButtonDefaults.colors(selectedColor = Kassa.colors.ink, unselectedColor = Kassa.colors.ink3),
            )
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(title, style = KassaType.bodyStrong, color = Kassa.colors.ink)
                Text(sub, style = KassaType.small, color = Kassa.colors.ink2)
            }
        }
        if (action != null) Box(
            Modifier.heightIn(min = 48.dp).padding(start = 8.dp).clickable(role = Role.Button, onClick = onAction),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                action, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1,
                modifier = Modifier.clip(CircleShape).border(1.dp, Kassa.colors.line, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

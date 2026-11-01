package app.kassa.money.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.Currencies
import app.kassa.money.domain.CurrencyStyle
import app.kassa.money.domain.Money
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/**
 * Every currency Kassa can show, A to Z, each with an example of how it reads on this phone. [selected] is a code, or
 * null before one is chosen. The one list for Settings > Currency and for the first-run screen.
 */
@Composable
internal fun CurrencyList(selected: String?, onPick: (CurrencyStyle) -> Unit) {
    Currencies.sorted().forEach { c ->
        val on = c.code == selected
        Column(Modifier.selectable(selected = on, role = Role.RadioButton, onClick = { onPick(c) })) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = KassaType.bodyStrong, color = Kassa.colors.ink)
                    Text(c.code, style = KassaType.small, color = Kassa.colors.ink2)
                }
                Text(Money.sample(123_450, style = c), style = KassaType.body, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 10.dp))
                Text(if (on) "✓" else "", style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.widthIn(min = 16.dp).clearAndSetSemantics { })
            }
            HorizontalDivider(color = Kassa.colors.line)
        }
    }
}

/** The list in a bottom sheet, for first run, where there is no Settings page yet. Picking closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CurrencySheet(selected: String?, onPick: (CurrencyStyle) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp).widthIn(max = 640.dp)) {
            Text(stringResource(R.string.settings_currency_title), style = KassaType.title, color = Kassa.colors.ink, modifier = Modifier.padding(bottom = 8.dp))
            CurrencyList(selected) { onPick(it); onDismiss() }
        }
    }
}

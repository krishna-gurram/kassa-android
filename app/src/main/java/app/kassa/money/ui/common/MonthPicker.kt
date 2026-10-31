package app.kassa.money.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.ui.activity.Chevron
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.YearMonth

/** The earliest year the picker's arrows go back to. */
private const val EARLIEST_YEAR = 1990

/**
 * Choose month: a year with arrows and the twelve months under it, as one sheet. Months after [latest] can't be
 * picked (Activity stops at the current month). The month's name comes from the phone's own calendar (`Dates.kt`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthPickerSheet(current: YearMonth, latest: YearMonth, onPick: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    var year by rememberSaveable { mutableIntStateOf(current.year) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.ground) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp).widthIn(max = 640.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Choose month", style = KassaType.section, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close", style = KassaType.bodyStrong, color = Kassa.colors.ink) }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { year-- }, enabled = year > EARLIEST_YEAR, modifier = Modifier.size(48.dp)) {
                    Icon(Chevron(left = true), "Previous year", tint = Kassa.colors.ink)
                }
                Text(yearOnly(year), style = KassaType.section, color = Kassa.colors.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { year++ }, enabled = year < latest.year, modifier = Modifier.size(48.dp)) {
                    Icon(Chevron(left = false), "Next year", tint = if (year < latest.year) Kassa.colors.ink else Kassa.colors.ink3.copy(alpha = 0.4f))
                }
            }
            (1..12).map { YearMonth.of(year, it) }.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { m ->
                        val enabled = !m.isAfter(latest)
                        val on = m == current
                        Box(
                            Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (on) Kassa.colors.ink else Kassa.colors.raised)
                                .clickable(enabled = enabled, role = Role.Button) { onPick(m) }
                                .semantics { contentDescription = monthYear(m) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                monthShort(m.atDay(1)), style = KassaType.bodyStrong, maxLines = 1,
                                color = when { on -> Kassa.colors.onInk; enabled -> Kassa.colors.ink; else -> Kassa.colors.ink3.copy(alpha = 0.5f) },
                            )
                        }
                    }
                }
            }
        }
    }
}

package app.kassa.money.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.kassa.money.ui.entry.QuickButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Today, Yesterday, or another day, as the entry page's date panel offers them, for sheets with no room for a whole
 * calendar. The third button shows the picked day and opens [MonthCalendar] in a dialog.
 */
@Composable
fun DateRow(date: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val yesterday = today.minusDays(1)
    val other = date != today && date != yesterday
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        QuickButton("Today", on = date == today, modifier = Modifier.weight(1f)) { onPick(today) }
        QuickButton("Yesterday", on = date == yesterday, modifier = Modifier.weight(1f)) { onPick(yesterday) }
        QuickButton(if (other) date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) else "Other day", on = other, modifier = Modifier.weight(1f)) {
            picking = true
        }
    }
    if (picking) Dialog(onDismissRequest = { picking = false }) {
        var month by remember { mutableStateOf(YearMonth.from(date)) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Kassa.colors.raised).padding(16.dp)) {
            Text("Date", style = KassaType.section, color = Kassa.colors.ink, modifier = Modifier.padding(bottom = 8.dp))
            MonthCalendar(month, date, today, onMonth = { month = it }, modifier = Modifier.height(300.dp)) { picked ->
                onPick(picked)
                picking = false
            }
            TextButton(onClick = { picking = false }, modifier = Modifier.align(Alignment.End)) { Text("Cancel", color = Kassa.colors.ink) }
        }
    }
}

/** When something on [date] happened: now for today, noon for any other day, so it sorts sensibly. */
fun momentOn(date: LocalDate, today: LocalDate, zone: ZoneId): Long =
    date.atTime(if (date == today) LocalTime.now(zone) else LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()

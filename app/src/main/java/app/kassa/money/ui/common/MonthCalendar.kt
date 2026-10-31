package app.kassa.money.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kassa.money.ui.activity.Chevron
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Kassa's one calendar: a month of days, starting on the phone's first day of the week. Arrows or a swipe change the
 * month. Days after [today] can't be picked, and neither can months after this one, unless [allowFuture] is set (the
 * next payment date of a loan is ahead of today). Every date choice in the app uses it, through the entry page's date
 * panel or [DateRow].
 */
@Composable
fun MonthCalendar(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    onMonth: (YearMonth) -> Unit,
    modifier: Modifier,
    allowFuture: Boolean = false,
    onPick: (LocalDate) -> Unit,
) {
    val locale = Locale.getDefault()
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    val canNext = allowFuture || month < YearMonth.from(today)
    Column(modifier.fillMaxWidth().swipeShift { d -> if (d < 0) onMonth(month.minusMonths(1)) else if (canNext) onMonth(month.plusMonths(1)) }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonth(month.minusMonths(1)) }, modifier = Modifier.size(48.dp)) {
                Icon(Chevron(left = true), "Previous month", tint = Kassa.colors.ink)
            }
            Text(monthYear(month), style = KassaType.bodyStrong, color = Kassa.colors.ink,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            IconButton(onClick = { onMonth(month.plusMonths(1)) }, enabled = canNext, modifier = Modifier.size(48.dp)) {
                Icon(Chevron(left = false), "Next month", tint = if (canNext) Kassa.colors.ink else Kassa.colors.ink3.copy(alpha = 0.4f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            (0L until 7L).map { firstDay.plus(it) }.forEach {
                Text(it.getDisplayName(TextStyle.NARROW, locale), style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = Kassa.colors.ink3, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(vertical = 2.dp))
            }
        }
        // Blank cells before the 1st, so it falls under its weekday.
        val lead = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
        val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            // Each day's cell is its own 48dp target; the row gives way only when the panel is shorter than six of them.
            Row(Modifier.fillMaxWidth().weight(1f, fill = false).height(48.dp)) {
                (0 until 7).forEach { i ->
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        week.getOrNull(i)?.let { d -> CalendarDay(d, selected = d == selected, today = today, allowFuture = allowFuture, onPick = onPick) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(d: LocalDate, selected: Boolean, today: LocalDate, allowFuture: Boolean, onPick: (LocalDate) -> Unit) {
    val future = d.isAfter(today) && !allowFuture
    Box(
        Modifier.fillMaxSize().clickable(enabled = !future, role = Role.Button) { onPick(d) }
            .semantics { contentDescription = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)); this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(if (selected) Kassa.colors.ink else Color.Transparent), contentAlignment = Alignment.Center) {
            Text(
                d.dayOfMonth.toString(),
                style = KassaType.body.copy(
                    fontWeight = if (d == today) FontWeight.Bold else FontWeight.Medium,
                    textDecoration = if (d == today && !selected) TextDecoration.Underline else null,
                ),
                color = when { selected -> Kassa.colors.onInk; future -> Kassa.colors.ink3.copy(alpha = 0.4f); else -> Kassa.colors.ink },
            )
        }
    }
}

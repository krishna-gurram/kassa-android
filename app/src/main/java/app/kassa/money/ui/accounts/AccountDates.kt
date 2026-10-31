package app.kassa.money.ui.accounts

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kassa.money.ui.common.DateRow
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import android.text.format.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Accounts keep a maturity or closing day as an epoch day (the rules know nothing of java.time); these convert at the edge. */
internal fun LocalDate.accountDay(): Int = toEpochDay().toInt()
internal fun dateOfAccountDay(day: Int): LocalDate = LocalDate.ofEpochDay(day.toLong())

/** "Nov 14" or "14 Nov": the phone's own order for a month and day. */
internal fun accountShortDate(date: LocalDate): String {
    val locale = Locale.getDefault()
    return date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale))
}

/** "Mar 18, 2027" in the phone's language and order. */
internal fun accountDate(day: Int): String = dateOfAccountDay(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/**
 * A fixed deposit's optional maturity date: a day to pick (future days allowed, like a loan's next payment) and a way
 * to leave it empty. Used by the add form and the edit page, so both ask the same way.
 */
@Composable
internal fun MaturityField(day: Int?, today: LocalDate, onChange: (Int?) -> Unit) {
    Text("Matures (optional)", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
    if (day == null) {
        TextButton(onClick = { onChange(today.plusYears(1).accountDay()) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Add the maturity date", color = Kassa.colors.ink)
        }
    } else {
        DateRow(dateOfAccountDay(day), today, allowFuture = true) { onChange(it.accountDay()) }
        TextButton(onClick = { onChange(null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear the maturity date", color = Kassa.colors.ink2) }
    }
}

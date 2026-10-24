package dev.krishnagurram.kassa.ui.common

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.krishnagurram.kassa.ui.entry.Chip
import dev.krishnagurram.kassa.ui.theme.Kassa
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Today, Yesterday, or a past day from a calendar, as chips. The third chip shows the picked day.
 * Days after [today] can't be picked: nothing in Kassa happens in the future.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DateRow(date: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val yesterday = today.minusDays(1)
    val other = date != today && date != yesterday
    FlowRow {
        Chip("Today", selected = date == today, small = true) { onPick(today) }
        Chip("Yesterday", selected = date == yesterday, small = true) { onPick(yesterday) }
        Chip(if (other) date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) else "Pick a date", selected = other, small = true) { picking = true }
    }
    if (picking) {
        // The picker works in UTC midnights; converting through UTC keeps the chosen day the same everywhere.
        val lastDay = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= lastDay
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("Done", color = Kassa.colors.ink) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel", color = Kassa.colors.ink2) } },
        ) { DatePicker(state = state) }
    }
}

/** When something on [date] happened: now for today, noon for any other day, so it sorts sensibly. */
fun momentOn(date: LocalDate, today: LocalDate, zone: ZoneId): Long =
    date.atTime(if (date == today) LocalTime.now(zone) else LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()

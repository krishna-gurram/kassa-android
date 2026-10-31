package app.kassa.money.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.ui.activity.Chevron
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/* The building blocks every Settings page uses, so they all look and behave alike. */

/** A row that opens a page: title, current value, chevron. */
@Composable
internal fun NavRow(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
            Text(value, style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, end = 6.dp))
            Icon(Chevron(left = false), null, tint = Kassa.colors.ink3)
        }
        HorizontalDivider(color = Kassa.colors.line)
    }
}

/** A row with an on/off switch, and a line under the title saying what it does. */
@Composable
internal fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = KassaType.bodyStrong, color = Kassa.colors.ink)
            Text(detail, style = KassaType.small, color = Kassa.colors.ink2)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Kassa.colors.ink, checkedThumbColor = Kassa.colors.onInk))
    }
    HorizontalDivider(color = Kassa.colors.line)
}

/** A small segmented choice for two to four options. */
@Composable
internal fun Choice(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Kassa.colors.chip).padding(3.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Kassa.colors.raised else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(key) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = if (on) Kassa.colors.ink else Kassa.colors.ink2)
            }
        }
    }
}

/** A time of day (minutes after midnight) in the phone's 12 or 24 hour style. */
internal fun timeLabel(minuteOfDay: Int): String =
    LocalTime.of(minuteOfDay / 60, minuteOfDay % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** A clock for picking a time of day. Returns minutes after midnight. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialog(minuteOfDay: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val state = rememberTimePickerState(minuteOfDay / 60, minuteOfDay % 60, is24Hour)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Kassa.colors.raised,
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("Set", color = Kassa.colors.ink) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Kassa.colors.ink2) } },
        text = { TimePicker(state) },
    )
}

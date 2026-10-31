package app.kassa.money.ui.start

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kassa.money.data.security.OpenProblem
import app.kassa.money.ui.common.ConfirmDialog
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.importer.SecondaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/**
 * Shown instead of the app when the database can't be opened (its key was lost, or the file can't be read), so a bad
 * state never becomes a crash on every start. Nothing is erased until the user confirms: "Restore from a backup" and
 * "Start over" both clear the unreadable data first ([onErase]); a restore then continues on the first-run screen,
 * which asks for the backup file. [onRetry] tries to open the data again.
 */
@Composable
fun CantOpenScreen(problem: OpenProblem, onRetry: () -> Unit, onErase: () -> Unit) {
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    var confirmStartOver by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Kassa.colors.ground).windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Kassa can't open its data", style = KassaType.title, color = Kassa.colors.ink)
            Text(
                if (problem == OpenProblem.DATA_UNREADABLE)
                    "The key that protects your data on this phone is missing or no longer works, so the data can't be read. " +
                        "This can happen after a phone reset or a restore from another phone. If you have a Kassa backup, you can restore it."
                else
                    "Something stopped Kassa from opening its data. The data may be fine, so try again first.",
                style = KassaType.body, color = Kassa.colors.ink2,
            )
            Gap(8)
            if (problem == OpenProblem.OTHER) PrimaryButton("Try again", onRetry) else SecondaryButton("Try again", onRetry)
            SecondaryButton("Restore from a backup", { confirmRestore = true })
            TextButton(onClick = { confirmStartOver = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Start over", style = KassaType.bodyStrong, color = Kassa.colors.attention)
            }
            Text("Nothing is erased until you confirm.", style = KassaType.small, color = Kassa.colors.ink2)
        }
    }
    if (confirmRestore) ConfirmDialog(
        "Erase unreadable data and restore?",
        "Kassa will erase the data on this phone that it can't read, then ask you to choose your backup file. This can't be undone.",
        "Erase and continue", onConfirm = onErase, onDismiss = { confirmRestore = false },
    )
    if (confirmStartOver) ConfirmDialog(
        "Start over?",
        "This erases all Kassa data on this phone and can't be undone. Kassa will open empty.",
        "Erase and start over", onConfirm = onErase, onDismiss = { confirmStartOver = false },
    )
}

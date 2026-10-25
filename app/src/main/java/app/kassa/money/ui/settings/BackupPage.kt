package app.kassa.money.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.kassa.money.data.backup.BackupContents
import app.kassa.money.data.backup.BackupFile
import app.kassa.money.domain.InputRules
import app.kassa.money.ui.LocalSettings
import app.kassa.money.ui.common.ConfirmDialog
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.importer.SecondaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Backups the Money Manager way: no password, one file per backup in a folder you pick, every one kept, once a day
 * at your time or right now. Restoring, and a copy with a password for sharing or storing somewhere you don't control.
 */
@Composable
internal fun BackupPage(actions: SettingsActions) {
    var status by remember { mutableStateOf<String?>(null) }
    DailyBackup(actions, onStatus = { status = it })
    SectionHeader("Restore")
    Restore(actions, onStatus = { status = it })
    SectionHeader("Copy with a password")
    ProtectedCopy(actions, onStatus = { status = it })
    status?.let { Text(it, style = KassaType.body, color = Kassa.colors.ink, modifier = Modifier.padding(top = 12.dp)) }
    Gap(24)
}

@Composable
private fun DailyBackup(actions: SettingsActions, onStatus: (String?) -> Unit) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val auto by settings.autoBackup.collectAsState()
    val folder by settings.backupFolder.collectAsState()
    val minute by settings.backupMinute.collectAsState()
    val last by settings.lastBackup.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    // What to do once a folder is picked, when picking it was the first step of something else.
    var afterFolder by remember { mutableStateOf<(() -> Unit)?>(null) }

    val backUpNow: () -> Unit = {
        busy = true; onStatus(null)
        scope.launch {
            onStatus(runCatching { actions.backupNow() }.fold({ "Saved $it." }, { it.message ?: "The backup couldn't be saved." }))
            busy = false
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Keeps access after the phone restarts, so the daily backup can write there.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            settings.setBackupFolder(uri.toString())
            actions.refreshDailyJobs()
            afterFolder?.invoke()
        }
        afterFolder = null
    }
    val pickFolderThen: (() -> Unit) -> Unit = { next -> afterFolder = next; folderPicker.launch(null) }

    SwitchRow("Back up every day", "No password. Every backup is kept.", auto) { on ->
        if (on && folder == null) pickFolderThen { settings.setAutoBackup(true); actions.refreshDailyJobs() }
        else { settings.setAutoBackup(on); actions.refreshDailyJobs() }
    }
    NavRow("Folder", folder?.let(::folderName) ?: "Choose") { folderPicker.launch(null) }
    NavRow("Time", timeLabel(minute)) { pickTime = true }
    val (lastAt, problem) = last
    Text(
        when {
            problem != null -> problem
            lastAt > 0 -> "Last backup " + Instant.ofEpochMilli(lastAt).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
            else -> "No backup yet"
        },
        style = KassaType.small, color = if (problem != null) Kassa.colors.attention else Kassa.colors.ink2, modifier = Modifier.padding(top = 10.dp),
    )
    Gap(12)
    PrimaryButton(if (busy) "Backing up..." else "Back up now") {
        when {
            busy -> Unit
            folder == null -> pickFolderThen(backUpNow)
            else -> backUpNow()
        }
    }
    if (pickTime) TimeDialog(minute, onDismiss = { pickTime = false }) { m -> settings.setBackupMinute(m); actions.refreshDailyJobs(); pickTime = false }
}

/** The folder's own name from its document tree address, like "Kassa" for Documents/Kassa. */
private fun folderName(uri: String): String =
    Uri.parse(uri).lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.ifBlank { null } ?: "Chosen folder"

@Composable
private fun Restore(actions: SettingsActions, onStatus: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<Uri?>(null) }
    var needsPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var contents by remember { mutableStateOf<BackupContents?>(null) }
    var confirm by remember { mutableStateOf(false) }

    fun read(uri: Uri, pw: CharArray?) = scope.launch {
        actions.readBackup(uri, pw)
            .onSuccess { contents = it; needsPassword = false; password = ""; onStatus(null) }
            .onFailure { e ->
                needsPassword = e is BackupFile.NeedsPassword || e is BackupFile.WrongPassword
                onStatus(if (e is BackupFile.NeedsPassword) null else e.message ?: "Couldn't open that file.")
            }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { file = uri; contents = null; needsPassword = false; read(uri, null) }
    }

    SecondaryButton("Restore from a backup", { opener.launch(arrayOf("*/*")) })
    if (needsPassword) {
        OutlinedTextField(
            password, { password = InputRules.typed(it, 128) }, singleLine = true, label = { Text("This backup's password") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        Gap(8)
        PrimaryButton("Open backup") { file?.let { read(it, password.toCharArray()) } }
    }
    contents?.let { c ->
        val created = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.ofEpochMilli(c.createdAt).atZone(ZoneId.systemDefault()))
        Gap(14)
        Text("From $created: ${c.summary}.", style = KassaType.bodyStrong, color = Kassa.colors.ink)
        Text("Restoring replaces everything in Kassa now with this backup.", style = KassaType.small, color = Kassa.colors.attention)
        Gap(8)
        PrimaryButton("Replace with this backup") { confirm = true }
        TextButton(onClick = { contents = null }) { Text("Cancel", color = Kassa.colors.ink2) }
        if (confirm) ConfirmDialog(
            title = "Replace everything in Kassa?",
            body = "All accounts, entries and settings in Kassa now are replaced by this backup. This can't be undone.",
            confirm = "Replace",
            onConfirm = { actions.restore(c); contents = null; onStatus("Restoring...") },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun ProtectedCopy(actions: SettingsActions, onStatus: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            onStatus(actions.exportTo(uri, password.toCharArray()))
            open = false; password = ""; repeat = ""
        }
    }
    if (!open) {
        TextButton(onClick = { open = true }) { Text("Save a copy with a password", color = Kassa.colors.ink) }
        return
    }
    Text("Anyone opening this copy needs the password. Kassa can't recover a lost one.", style = KassaType.small, color = Kassa.colors.ink2)
    OutlinedTextField(password, { password = InputRules.typed(it, 128) }, singleLine = true, label = { Text("Password") },
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    OutlinedTextField(repeat, { repeat = InputRules.typed(it, 128) }, singleLine = true, label = { Text("Repeat it") },
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    Gap(10)
    PrimaryButton("Save copy") {
        val problem = InputRules.passwordProblem(password, repeat)
        onStatus(problem)
        if (problem == null) saver.launch("Kassa_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}_protected.kassa")
    }
}

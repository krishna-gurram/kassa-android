package app.kassa.money.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.data.Failures
import app.kassa.money.data.Failures.Doing
import app.kassa.money.data.backup.BackupContents
import app.kassa.money.data.backup.BackupFile
import app.kassa.money.domain.InputRules
import app.kassa.money.ui.LocalSettings
import app.kassa.money.ui.common.ConfirmDialog
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.PasswordField
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.importer.SecondaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Backups the Money Manager way: no password, one file per backup in a folder you pick, the newest 30 kept, once a day
 * at your time or right now. Restoring, and a copy with a password for sharing or storing somewhere you don't control.
 */
@Composable
internal fun BackupPage(actions: SettingsActions) {
    var status by remember { mutableStateOf<String?>(null) }
    val settings = LocalSettings.current
    val noteSeen by settings.backupNoteSeen.collectAsState()
    if (!noteSeen) {
        Text(stringResource(R.string.settings_backup_note), style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(bottom = 4.dp))
        TextButton(onClick = { settings.dismissBackupNote() }) { Text(stringResource(R.string.settings_backup_got_it), color = Kassa.colors.ink) }
    }
    DailyBackup(actions, onStatus = { status = it })
    SectionHeader(stringResource(R.string.settings_backup_restore_header))
    Restore(actions, onStatus = { status = it })
    SectionHeader(stringResource(R.string.settings_backup_copy_header))
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
    var explainFolder by remember { mutableStateOf(false) }
    val noteSeen by settings.backupNoteSeen.collectAsState()
    val chosenFolder = stringResource(R.string.settings_backup_chosen_folder)

    val backUpNow: () -> Unit = {
        busy = true; onStatus(null)
        scope.launch {
            onStatus(runCatching { actions.backupNow() }.fold({ context.getString(R.string.settings_backup_saved, it) }, { e ->
                // The job's own failures already carry plain wording; anything else is mapped here, never shown raw.
                if (e is Failures.UserFacing) e.message.orEmpty() else Failures.message(e, Doing.WRITE_BACKUP)
            }))
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
    // The first time, a short note about encryption comes before the picker; it is never asked again once seen.
    val openPicker: () -> Unit = { if (noteSeen) folderPicker.launch(null) else explainFolder = true }
    val pickFolderThen: (() -> Unit) -> Unit = { next -> afterFolder = next; openPicker() }
    if (explainFolder) ConfirmDialog(
        title = stringResource(R.string.settings_backup_before_folder),
        body = stringResource(R.string.settings_backup_note),
        confirm = stringResource(R.string.settings_backup_choose_folder),
        destructive = false,
        onConfirm = { explainFolder = false; settings.dismissBackupNote(); folderPicker.launch(null) },
        onDismiss = { explainFolder = false; afterFolder = null },
    )

    SwitchRow(stringResource(R.string.settings_backup_every_day), stringResource(R.string.settings_backup_every_day_hint), auto) { on ->
        if (on && folder == null) pickFolderThen { settings.setAutoBackup(true); actions.refreshDailyJobs() }
        else { settings.setAutoBackup(on); actions.refreshDailyJobs() }
    }
    NavRow(stringResource(R.string.settings_backup_folder), folder?.let { folderName(it, chosenFolder) } ?: stringResource(R.string.settings_backup_folder_choose)) { openPicker() }
    NavRow(stringResource(R.string.settings_time), timeLabel(minute)) { pickTime = true }
    val (lastAt, problem) = last
    Text(
        when {
            problem != null -> problem
            lastAt > 0 -> stringResource(
                R.string.settings_backup_last,
                Instant.ofEpochMilli(lastAt).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)),
            )
            else -> stringResource(R.string.settings_backup_none_yet)
        },
        style = KassaType.small, color = if (problem != null) Kassa.colors.attention else Kassa.colors.ink2, modifier = Modifier.padding(top = 10.dp),
    )
    Gap(12)
    PrimaryButton(if (busy) stringResource(R.string.settings_backup_backing_up) else stringResource(R.string.settings_backup_now)) {
        when {
            busy -> Unit
            folder == null -> pickFolderThen(backUpNow)
            else -> backUpNow()
        }
    }
    if (pickTime) TimeDialog(minute, onDismiss = { pickTime = false }) { m -> settings.setBackupMinute(m); actions.refreshDailyJobs(); pickTime = false }
}

/** The folder's own name from its document tree address, like "Kassa" for Documents/Kassa. */
private fun folderName(uri: String, fallback: String): String =
    Uri.parse(uri).lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.ifBlank { null } ?: fallback

@Composable
private fun Restore(actions: SettingsActions, onStatus: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<Uri?>(null) }
    var needsPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var contents by remember { mutableStateOf<BackupContents?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    val restoringText = stringResource(R.string.settings_backup_restoring)

    fun read(uri: Uri, pw: CharArray?) = scope.launch {
        actions.readBackup(uri, pw)
            .onSuccess { contents = it; needsPassword = false; password = ""; onStatus(null) }
            .onFailure { e ->
                needsPassword = e is BackupFile.NeedsPassword || e is BackupFile.WrongPassword
                onStatus(if (e is BackupFile.NeedsPassword) null else Failures.message(e, Doing.READ_BACKUP))
            }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { file = uri; contents = null; needsPassword = false; read(uri, null) }
    }

    SecondaryButton(if (restoring) restoringText else stringResource(R.string.settings_backup_restore_button), { if (!restoring) opener.launch(arrayOf("*/*")) })
    if (needsPassword) {
        PasswordField(password, { password = it }, stringResource(R.string.settings_backup_password_label), Modifier.fillMaxWidth().padding(top = 10.dp))
        Gap(8)
        PrimaryButton(stringResource(R.string.settings_backup_open)) { file?.let { read(it, password.toCharArray()) } }
    }
    contents?.let { c ->
        Gap(14)
        BackupSummary(c)
        Text(stringResource(R.string.settings_backup_restore_warning), style = KassaType.small, color = Kassa.colors.attention)
        Gap(8)
        PrimaryButton(if (restoring) restoringText else stringResource(R.string.settings_backup_replace_with)) { if (!restoring) confirm = true }
        TextButton(onClick = { if (!restoring) contents = null }) { Text(stringResource(R.string.settings_cancel), color = Kassa.colors.ink2) }
        if (confirm) RestoreConfirm(
            onConfirm = {
                confirm = false; restoring = true; onStatus(restoringText)
                scope.launch {
                    // The result is plain wording either way, so the "Restoring..." line is always replaced.
                    val result = try { actions.restore(c) } catch (e: CancellationException) { throw e } catch (e: Exception) { Failures.message(e, Doing.RESTORE) }
                    restoring = false; contents = null; onStatus(result)
                }
            },
            onDismiss = { confirm = false },
        )
    }
}

/** When a backup was made and what's in it. Also on the Import page. */
@Composable
internal fun BackupSummary(c: BackupContents) {
    val created = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.ofEpochMilli(c.createdAt).atZone(ZoneId.systemDefault()))
    Text(stringResource(R.string.settings_backup_from, created, c.summary), style = KassaType.bodyStrong, color = Kassa.colors.ink)
}

/** Asked before a backup replaces what's in Kassa, here and on the Import page. */
@Composable
internal fun RestoreConfirm(onConfirm: () -> Unit, onDismiss: () -> Unit) = ConfirmDialog(
    title = stringResource(R.string.settings_backup_replace_title),
    body = stringResource(R.string.settings_backup_replace_body),
    confirm = stringResource(R.string.settings_backup_replace),
    onConfirm = onConfirm,
    onDismiss = onDismiss,
)

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
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.settings_backup_save_copy_link), color = Kassa.colors.ink) }
        return
    }
    Text(stringResource(R.string.settings_backup_copy_note), style = KassaType.small, color = Kassa.colors.ink2)
    PasswordField(password, { password = it }, stringResource(R.string.settings_backup_password), Modifier.fillMaxWidth().padding(top = 8.dp))
    PasswordField(repeat, { repeat = it }, stringResource(R.string.settings_backup_repeat), Modifier.fillMaxWidth().padding(top = 8.dp))
    Gap(10)
    PrimaryButton(stringResource(R.string.settings_backup_save_copy)) {
        val problem = InputRules.passwordProblem(password, repeat)
        onStatus(problem)
        if (problem == null) saver.launch("Kassa_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}_protected.kassa")
    }
}

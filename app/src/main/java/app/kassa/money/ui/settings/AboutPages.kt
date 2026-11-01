package app.kassa.money.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.kassa.money.BuildConfigInfo
import app.kassa.money.KassaApplication
import app.kassa.money.R
import app.kassa.money.domain.InputRules
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.RaisedPanel
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.importer.SecondaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.io.File

/* Settings › About: feedback, privacy and licences. */

private enum class FeedbackKind(@StringRes val label: Int, @StringRes val prompt: Int, val subject: String) {
    PROBLEM(R.string.settings_feedback_kind_problem, R.string.settings_feedback_kind_problem_prompt, "Something's wrong"),
    PRAISE(R.string.settings_feedback_kind_praise, R.string.settings_feedback_kind_praise_prompt, "I like something"),
    IDEA(R.string.settings_feedback_kind_idea, R.string.settings_feedback_kind_idea_prompt, "An idea"),
}

private const val MAX_SCREENSHOTS = 5

/**
 * Feedback goes out through the phone's email app, since Kassa has no internet access. The email carries the
 * message, the app and Android version, the phone model, any screenshots picked, and the last crash report if the
 * user leaves it ticked. Nothing from their entries is included, and they see the whole email before sending.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FeedbackPage() {
    val context = LocalContext.current
    val crash = remember { (context.applicationContext as KassaApplication).crashFile.takeIf { it.exists() } }
    var kind by remember { mutableStateOf(FeedbackKind.PROBLEM) }
    var text by remember { mutableStateOf("") }
    var shots by remember { mutableStateOf(listOf<File>()) }
    var withCrash by remember { mutableStateOf(crash != null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val writeFirst = stringResource(R.string.settings_feedback_write_first)
    val noPlay = stringResource(R.string.settings_feedback_no_play)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_SCREENSHOTS)) { uris ->
        shots = (shots + uris.mapNotNull { copyScreenshot(context, it) }).take(MAX_SCREENSHOTS)
    }

    Label(stringResource(R.string.settings_feedback_about))
    FlowRow { FeedbackKind.entries.forEach { k -> Chip(stringResource(k.label), kind == k) { kind = k } } }
    Gap(8)
    OutlinedTextField(
        text, { text = InputRules.typedLines(it, InputRules.FEEDBACK_MAX); problem = null },
        label = { Text(stringResource(kind.prompt)) }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth(),
        supportingText = if (text.length > InputRules.FEEDBACK_MAX - 200) { { Text("${text.length}/${InputRules.FEEDBACK_MAX}") } } else null,
    )

    Label(stringResource(R.string.settings_feedback_screenshots))
    if (shots.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)) {
        shots.forEach { f -> Thumbnail(f) { f.delete(); shots = shots - f } }
    }
    if (shots.size < MAX_SCREENSHOTS) SecondaryButton(if (shots.isEmpty()) stringResource(R.string.settings_feedback_add_screenshots) else stringResource(R.string.settings_feedback_add_more), {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    })

    Label(stringResource(R.string.settings_feedback_included))
    RaisedPanel {
        Text(deviceLine(context), style = KassaType.small, color = Kassa.colors.ink)
        if (crash != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { withCrash = !withCrash }) {
            Text(stringResource(R.string.settings_feedback_last_crash), style = KassaType.small, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
            Checkbox(withCrash, { withCrash = it }, colors = CheckboxDefaults.colors(checkedColor = Kassa.colors.ink, checkmarkColor = Kassa.colors.onInk))
        }
        Gap(6)
        Text(stringResource(R.string.settings_feedback_included_note), style = KassaType.tiny, color = Kassa.colors.ink2)
    }
    Gap(16)
    PrimaryButton(stringResource(R.string.settings_feedback_write_email)) {
        if (text.isBlank()) { problem = writeFirst; return@PrimaryButton }
        val report = crash?.takeIf { withCrash }
        problem = sendFeedback(context, kind, text.trim(), shots, report)
        if (problem == null) report?.delete()
    }
    problem?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention, modifier = Modifier.padding(top = 6.dp)) }

    if (kind == FeedbackKind.PRAISE) {
        Gap(16)
        RaisedPanel {
            Text(stringResource(R.string.settings_feedback_enjoying), style = KassaType.bodyStrong, color = Kassa.colors.ink)
            Text(stringResource(R.string.settings_feedback_rating_hint), style = KassaType.small, color = Kassa.colors.ink2)
            Gap(10)
            SecondaryButton(stringResource(R.string.settings_feedback_rate), {
                problem = runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}")))
                }.fold({ null }, { noPlay })
            })
        }
    }
    Gap(24)
}

/** What's in Kassa and on the phone, for a bug report. Nothing personal. */
private fun deviceLine(context: Context) =
    context.getString(R.string.settings_feedback_device, BuildConfigInfo.versionName, Build.VERSION.RELEASE, Build.MANUFACTURER, Build.MODEL)

/** Opens the email app with everything filled in. Returns a problem to show, or null when it opened. */
private fun sendFeedback(context: Context, kind: FeedbackKind, text: String, shots: List<File>, crash: File?): String? {
    val body = buildString {
        appendLine(text)
        appendLine()
        appendLine("--")
        appendLine(deviceLine(context))
        crash?.let { appendLine(); appendLine("Crash report:"); append(runCatching { it.readText() }.getOrDefault("").take(20_000)) }
    }
    val uris = ArrayList(shots.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
    val email = Intent(if (uris.isEmpty()) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = if (uris.isEmpty()) "text/plain" else "image/*"
        if (BuildConfigInfo.supportEmail.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfigInfo.supportEmail))
        putExtra(Intent.EXTRA_SUBJECT, "Kassa feedback: ${kind.subject}")
        putExtra(Intent.EXTRA_TEXT, body)
        if (uris.isNotEmpty()) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Only email apps, not every app that can share.
        selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
    }
    // Some phones refuse the selector form, so fall back to a plain mailto, then to anything that can send email.
    val subject = "Kassa feedback: ${kind.subject}"
    val attempts = buildList<Intent> {
        add(email)
        val to = BuildConfigInfo.supportEmail
        if (uris.isEmpty()) add(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to) + "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body.take(4_000)))),
        )
        add(
            Intent.createChooser(
                Intent(if (uris.isEmpty()) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "message/rfc822"
                    if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    if (uris.isNotEmpty()) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                context.getString(R.string.settings_feedback_chooser),
            ),
        )
    }
    for (intent in attempts) {
        try { context.startActivity(intent); return null } catch (e: ActivityNotFoundException) { /* try the next way */ }
    }
    return context.getString(R.string.settings_feedback_no_email, BuildConfigInfo.supportEmail.ifBlank { context.getString(R.string.settings_feedback_us) })
}

/** Copies a picked screenshot into the feedback folder, the only place Kassa shares files from. */
private fun copyScreenshot(context: Context, uri: Uri): File? = runCatching {
    val dir = File(context.cacheDir, "feedback").apply { mkdirs() }
    val ext = if (context.contentResolver.getType(uri) == "image/png") "png" else "jpg"
    val file = File(dir, "screenshot-${System.nanoTime()}.$ext")
    context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
    file
}.getOrNull()

@Composable
private fun Thumbnail(file: File, onRemove: () -> Unit) {
    val image = remember(file) {
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap()
    }
    Box(Modifier.size(64.dp)) {
        if (image != null) Image(image, stringResource(R.string.settings_feedback_screenshot), contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)))
        // A 24dp badge with a 48dp touch target, overlapping the corner.
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-12).dp).size(48.dp).clip(CircleShape).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Kassa.colors.ink), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, stringResource(R.string.settings_feedback_remove_screenshot), tint = Kassa.colors.onInk, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** The privacy policy, in the app. The same words go on the web page the Play listing links to. */
@Composable
internal fun PrivacyPage() {
    RaisedPanel {
        listOf(
            R.string.settings_privacy_local_title to R.string.settings_privacy_local_body,
            R.string.settings_privacy_encrypted_title to R.string.settings_privacy_encrypted_body,
            R.string.settings_privacy_backups_title to R.string.settings_privacy_backups_body,
            R.string.settings_privacy_crash_title to R.string.settings_privacy_crash_body,
            R.string.settings_privacy_feedback_title to R.string.settings_privacy_feedback_body,
        ).forEachIndexed { i, (title, body) ->
            if (i > 0) Gap(12)
            Text(stringResource(title), style = KassaType.bodyStrong, color = Kassa.colors.ink)
            Text(stringResource(body), style = KassaType.small, color = Kassa.colors.ink2)
        }
    }
    Gap(24)
}

/** One credited library and the bundled file with its licence text. */
internal data class Licence(val name: String, val licence: String, val file: String)

internal val licences = listOf(
    Licence("Android Jetpack libraries", "Apache 2.0", "apache-2.0.txt"),
    Licence("Kotlin and kotlinx.coroutines", "Apache 2.0", "apache-2.0.txt"),
    Licence("Material icons", "Apache 2.0", "apache-2.0.txt"),
    Licence("SQLCipher for Android", "BSD-style", "sqlcipher.txt"),
    Licence("Hanken Grotesk font", "SIL OFL 1.1", "ofl-1.1.txt"),
)

@Composable
internal fun LicencesPage(open: (Licence) -> Unit) {
    Text(stringResource(R.string.settings_licences_intro), style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(bottom = 8.dp))
    licences.forEach { l -> NavRow(l.name, l.licence) { open(l) } }
    Gap(24)
}

/** A licence's full text, read from the app itself. */
@Composable
internal fun LicenceText(licence: Licence) {
    val context = LocalContext.current
    val text = remember(licence.file) { runCatching { context.assets.open("licences/${licence.file}").bufferedReader().use { it.readText() } }.getOrDefault("") }
    Text(text, style = KassaType.small, color = Kassa.colors.ink2, overflow = TextOverflow.Clip)
    Gap(24)
}

@Composable
private fun Label(text: String) = Text(text, style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))

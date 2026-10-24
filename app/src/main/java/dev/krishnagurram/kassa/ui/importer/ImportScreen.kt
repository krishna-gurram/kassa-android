package dev.krishnagurram.kassa.ui.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.importer.ImportIssue
import dev.krishnagurram.kassa.importer.ImportPlan
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.Panel
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.text.NumberFormat

/**
 * The whole import, from picking the file to the result. Shown on first launch, and again from Accounts
 * when importing over existing data. [onFinished] is called after a successful import or on "Start fresh".
 */
@Composable
fun ImportScreen(firstRun: Boolean, onFinished: () -> Unit, onCancel: (() -> Unit)? = null, vm: ImportViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::read) }
    val pick = { picker.launch(arrayOf("*/*")) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            when (val s = state) {
                ImportState.Idle -> Intro(firstRun, pick, onFinished, onCancel)
                ImportState.Reading -> Busy("Reading your backup")
                ImportState.Saving -> Busy("Saving to the encrypted database")
                is ImportState.Failed -> {
                    Intro(firstRun, pick, onFinished, onCancel)
                    Gap(12)
                    Text(s.message, style = KassaType.body, color = Kassa.colors.attention)
                }
                is ImportState.Review -> Review(s.plan, firstRun, onImport = vm::confirm, onPickAgain = { vm.reset(); pick() })
                is ImportState.Done -> {
                    Text("Imported", style = KassaType.title, color = Kassa.colors.ink)
                    Gap(8)
                    Text("${count(s.entries)} entries are now in Kassa, on this phone only.", style = KassaType.body, color = Kassa.colors.ink2)
                    Gap(24)
                    PrimaryButton("Open Kassa") { vm.reset(); onFinished() }
                }
            }
        }
    }
}

@Composable
private fun Intro(firstRun: Boolean, pick: () -> Unit, onFinished: () -> Unit, onCancel: (() -> Unit)?) {
    Gap(if (firstRun) 48 else 8)
    Text(if (firstRun) "Kassa" else "Import from another app", style = KassaType.title, color = Kassa.colors.ink)
    Gap(8)
    Text(
        if (firstRun) "Your money, on this phone only. Kassa has no internet access, so nothing you enter can leave the device."
        else "This replaces everything in Kassa with the backup you pick. You'll see a review before anything changes.",
        style = KassaType.body, color = Kassa.colors.ink2,
    )
    Gap(28)
    Panel {
        Column {
            Text("Bring your history from another app", style = KassaType.bodyStrong, color = Kassa.colors.ink)
            Gap(4)
            Text(
                "Supported files: .mmbak. Make a backup in your old app, then pick the file here. It's read on the phone and never uploaded.",
                style = KassaType.small, color = Kassa.colors.ink2,
            )
            Gap(14)
            PrimaryButton("Choose backup file", pick)
        }
    }
    Gap(12)
    if (firstRun) {
        // The currency this phone shows; it can change later in Settings.
        val settings = dev.krishnagurram.kassa.ui.LocalSettings.current
        val code by settings.currency.collectAsState()
        Text("Currency", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow {
            dev.krishnagurram.kassa.domain.Currencies.all.forEach { c ->
                dev.krishnagurram.kassa.ui.entry.Chip(c.code, selected = c.code == code, small = true) { settings.setCurrency(c.code) }
            }
        }
    }
    if (firstRun) TextButton(onClick = onFinished, modifier = Modifier.fillMaxWidth()) { Text("Start fresh instead", color = Kassa.colors.ink2) }
    if (onCancel != null) TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = Kassa.colors.ink2) }
}

@Composable
private fun Busy(label: String) {
    Gap(80)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Kassa.colors.ink)
        Gap(16)
        Text(label, style = KassaType.body, color = Kassa.colors.ink2)
    }
}

@Composable
private fun Review(plan: ImportPlan, firstRun: Boolean, onImport: () -> Unit, onPickAgain: () -> Unit) {
    val r = plan.reconcile
    val byKind = plan.transactions.groupingBy { it.kind }.eachCount()
    Text("Review before importing", style = KassaType.title, color = Kassa.colors.ink)
    Gap(8)
    Text("Nothing is saved until you tap Import.", style = KassaType.body, color = Kassa.colors.ink2)

    SectionHeader("What's in the backup")
    Fact("Expenses", count(byKind[TxKind.EXPENSE] ?: 0))
    Fact("Income", count(byKind[TxKind.INCOME] ?: 0))
    Fact("Transfers", count(byKind[TxKind.TRANSFER] ?: 0))
    Fact("Accounts", count(plan.accounts.size))
    Fact("Categories", "${count(plan.categories.count { !it.hidden })} active, ${count(plan.categories.count { it.hidden })} retired")
    Fact("Budgets", count(plan.budgets.size))

    SectionHeader("Checks", if (r.allMatch) "All match" else "Needs a look")
    val badAccounts = r.accounts.filter { !it.matches }
    val badMonths = r.months.filter { !it.matches }
    Check(
        ok = badAccounts.isEmpty(),
        text = if (badAccounts.isEmpty()) "All ${r.accounts.size} account balances match the backup."
        else "${badAccounts.size} account balances differ: " + badAccounts.joinToString { "${it.name} (${Money.format(it.imported - it.source)})" },
    )
    Check(
        ok = badMonths.isEmpty(),
        text = if (badMonths.isEmpty()) "Spending and income match for all ${r.months.size} months."
        else "${badMonths.size} months differ, starting ${badMonths.first().month}.",
    )

    if (plan.issues.isNotEmpty()) {
        SectionHeader("Worth knowing", "You can fix these later")
        plan.issues.forEach { Issue(it) }
    }

    Gap(28)
    PrimaryButton("Import ${count(plan.transactions.size)} entries", onImport)
    if (!firstRun) {
        Gap(6)
        Text("This replaces what's in Kassa now.", style = KassaType.small, color = Kassa.colors.attention)
    }
    Gap(4)
    TextButton(onClick = onPickAgain, modifier = Modifier.fillMaxWidth()) { Text("Choose a different file", color = Kassa.colors.ink2) }
}

@Composable
private fun Issue(issue: ImportIssue) {
    val text = when (issue) {
        is ImportIssue.PossibleDuplicateAccounts -> "These look like the same account: ${issue.names.joinToString(" and ")}. You can archive one after importing."
        is ImportIssue.SplitwiseRows -> "${issue.entries} entries use Splitwise accounts or categories (${(issue.accountNames + issue.categoryNames).joinToString()}). They import as they are now; turning them into money owed by people comes with the next update."
        is ImportIssue.MismatchedTransfers -> "${issue.count} transfer's two halves didn't agree. Kassa kept the sending side; balances still match."
        is ImportIssue.UnpairedTransfers -> "${issue.count} transfers had only one half. They were kept so balances match."
        is ImportIssue.ZeroAmountEntries -> "${issue.count} entries have an amount of $0. They were kept."
        is ImportIssue.SkippedRows -> "${issue.count} skipped: ${issue.reason}."
    }
    Row(Modifier.padding(vertical = 8.dp)) {
        Text("•", style = KassaType.body, color = Kassa.colors.ink3, modifier = Modifier.padding(end = 10.dp))
        Text(text, style = KassaType.body, color = Kassa.colors.ink)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = KassaType.body, color = Kassa.colors.ink2)
        Text(value, style = KassaType.bodyStrong, color = Kassa.colors.ink)
    }
}

@Composable
private fun Check(ok: Boolean, text: String) {
    Row(Modifier.padding(vertical = 7.dp)) {
        Text(if (ok) "✓" else "!", style = KassaType.bodyStrong, color = if (ok) Kassa.colors.positive else Kassa.colors.attention, modifier = Modifier.padding(end = 10.dp))
        Text(text, style = KassaType.body, color = Kassa.colors.ink)
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Kassa.colors.ink, contentColor = Kassa.colors.onInk),
    ) { Text(label, style = KassaType.bodyStrong) }
}

/** A white (raised) filled button, for actions that add something next to a list. */
@Composable
fun RaisedButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Kassa.colors.raised, contentColor = Kassa.colors.ink),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp, pressedElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Kassa.colors.line),
    ) { Text(label, style = KassaType.bodyStrong) }
}

@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(15.dp),
    ) { Text(label, style = KassaType.bodyStrong, color = Kassa.colors.ink) }
}

private fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

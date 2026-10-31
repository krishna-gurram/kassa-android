package app.kassa.money.ui.importer

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kassa.money.data.backup.BackupContents
import app.kassa.money.domain.Money
import app.kassa.money.domain.TxKind
import app.kassa.money.importer.ImportIssue
import app.kassa.money.importer.ImportPlan
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.Panel
import app.kassa.money.ui.common.PasswordField
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.settings.BackupSummary
import app.kassa.money.ui.settings.RestoreConfirm
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.text.NumberFormat

/**
 * The whole import, from picking the file to the result. Shown on first launch, where "Start fresh" comes first
 * ([onStartFresh]), and again from Settings when importing over existing data. [onFinished] follows a successful import.
 */
@Composable
fun ImportScreen(
    firstRun: Boolean,
    onFinished: () -> Unit,
    onCancel: (() -> Unit)? = null,
    onStartFresh: () -> Unit = onFinished,
    vm: ImportViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::read) }
    val pick = { picker.launch(arrayOf("*/*")) }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (app.kassa.money.BuildConfig.DEBUG) DebugImport.take()?.let(vm::read) }

    // Each step opens at its top, not wherever the last one was scrolled to.
    val scroll = rememberScrollState()
    val stepKey = when (val st = state) { is ImportState.Cashew -> st.step; else -> st::class }
    androidx.compose.runtime.LaunchedEffect(stepKey) { scroll.scrollTo(0) }
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            when (val s = state) {
                ImportState.Idle -> Intro(firstRun, pick, onStartFresh, onCancel)
                ImportState.Reading -> Busy("Reading your backup")
                ImportState.Saving -> Busy("Saving to the encrypted database")
                is ImportState.Failed -> {
                    Intro(firstRun, pick, onStartFresh, onCancel)
                    Gap(12)
                    Text(s.message, style = KassaType.body, color = Kassa.colors.attention)
                }
                is ImportState.Cashew -> {
                    androidx.activity.compose.BackHandler(onBack = vm::back)
                    CashewSteps(s, vm)
                }
                is ImportState.KassaPassword -> KassaPassword(s.problem, onOpen = vm::openKassa, onCancel = vm::reset)
                is ImportState.KassaReady -> KassaReady(s.contents, firstRun, onRestore = vm::restoreKassa, onCancel = vm::reset)
                ImportState.Restored -> {
                    Text("Backup restored", style = KassaType.title, color = Kassa.colors.ink)
                    Gap(8)
                    Text("Everything from the backup is now in Kassa, on this phone only.", style = KassaType.body, color = Kassa.colors.ink2)
                    Gap(24)
                    PrimaryButton("Open Kassa") { vm.reset(); onFinished() }
                }
                is ImportState.Review -> Review(s.plan, firstRun, onImport = vm::confirm, onPickAgain = { vm.reset(); pick() })
                is ImportState.Done -> {
                    Text(if (s.plan.appName.isNotBlank()) "Imported from ${s.plan.appName}" else "Imported", style = KassaType.title, color = Kassa.colors.ink)
                    Gap(8)
                    Text("${count(s.entries)} entries are now in Kassa, on this phone only.", style = KassaType.body, color = Kassa.colors.ink2)
                    val next = buildList {
                        s.plan.accounts.filter { it.type == app.kassa.money.domain.AccountType.CREDIT_CARD && it.statementDay == null }
                            .forEach { add("Add statement and due days to ${it.name} so Kassa can show what's payable.") }
                        s.plan.accounts.filter { it.type == app.kassa.money.domain.AccountType.CREDIT_CARD && it.payFromId == null }
                            .forEach { add("Pick the account ${it.name} is paid from.") }
                        if (s.plan.people.isNotEmpty()) add("Check ${s.plan.people.joinToString { it.name }} in People.")
                        if (s.plan.bills.isNotEmpty()) add("Check the ${count(s.plan.bills.size)} bills in Upcoming.")
                        add("Pick the accounts to show on Overview in Settings › Home screen.")
                    }
                    Gap(16)
                    Panel { Column { next.forEach { Bullet(it) } } }
                    Gap(24)
                    PrimaryButton("Open Kassa") { vm.reset(); onFinished() }
                }
            }
        }
    }
}

@Composable
private fun Intro(firstRun: Boolean, pick: () -> Unit, onStartFresh: () -> Unit, onCancel: (() -> Unit)?) {
    Gap(if (firstRun) 48 else 8)
    Text(if (firstRun) "Kassa" else "Import from another app", style = KassaType.title, color = Kassa.colors.ink)
    Gap(8)
    Text(
        if (firstRun) "Your money, on this phone only. Kassa has no internet access, so nothing you enter can leave the device."
        else "This replaces everything in Kassa with the backup you pick. You'll see a review before anything changes.",
        style = KassaType.body, color = Kassa.colors.ink2,
    )
    if (firstRun) {
        // The currency this phone shows; it can change later in Settings.
        val settings = app.kassa.money.ui.LocalSettings.current
        val code by settings.currency.collectAsState()
        Text("Currency", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow {
            app.kassa.money.domain.Currencies.all.forEach { c ->
                app.kassa.money.ui.entry.Chip(c.code, selected = c.code == code, small = true) { settings.setCurrency(c.code) }
            }
        }
        Gap(20)
        // Most people have no backup to bring, so starting fresh comes first.
        PrimaryButton("Start fresh", onStartFresh)
        Gap(16)
    } else Gap(28)
    Panel {
        Column {
            Text(if (firstRun) "Have a backup?" else "Bring your history from another app", style = KassaType.bodyStrong, color = Kassa.colors.ink)
            Gap(4)
            Text(
                "A Kassa backup (.kassa), or one from Money Manager (.mmbak) or Cashew (.sql). Kassa works out which you picked, reads it on the phone and never uploads it.",
                style = KassaType.small, color = Kassa.colors.ink2,
            )
            Gap(14)
            if (firstRun) SecondaryButton("Choose backup file", pick) else PrimaryButton("Choose backup file", pick)
        }
    }
    Gap(12)
    if (onCancel != null) TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = Kassa.colors.ink2) }
}

/** A Kassa backup with a password: the same field as Settings › Backup and restore. */
@Composable
private fun KassaPassword(problem: String?, onOpen: (CharArray) -> Unit, onCancel: () -> Unit) {
    // Not saveable on purpose: a password shouldn't sit in saved instance state.
    var password by remember { mutableStateOf("") }
    Text("This backup has a password", style = KassaType.title, color = Kassa.colors.ink)
    Gap(8)
    Text("Enter the password it was saved with. Kassa can't recover a lost one.", style = KassaType.body, color = Kassa.colors.ink2)
    PasswordField(password, { password = it }, "This backup's password", Modifier.fillMaxWidth().padding(top = 16.dp), problem = problem)
    Gap(12)
    PrimaryButton("Open backup") { if (password.isNotEmpty()) onOpen(password.toCharArray()) }
    Gap(4)
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = Kassa.colors.ink2) }
}

/** A Kassa backup, read: what's in it, then restore. Over existing data it asks first, like the Backup page. */
@Composable
private fun KassaReady(contents: BackupContents, firstRun: Boolean, onRestore: () -> Unit, onCancel: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Text("Restore this backup?", style = KassaType.title, color = Kassa.colors.ink)
    Gap(8)
    BackupSummary(contents)
    if (!firstRun) {
        Gap(6)
        Text("Restoring replaces everything in Kassa now with this backup.", style = KassaType.small, color = Kassa.colors.attention)
    }
    Gap(24)
    PrimaryButton(if (firstRun) "Restore backup" else "Replace with this backup") { if (firstRun) onRestore() else confirm = true }
    Gap(4)
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Choose a different file", color = Kassa.colors.ink2) }
    if (confirm) RestoreConfirm(onConfirm = onRestore, onDismiss = { confirm = false })
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
    Text("From a ${plan.sourceName}. Nothing is saved until you tap Import.", style = KassaType.body, color = Kassa.colors.ink2)

    SectionHeader("What comes over")
    Fact("Expenses", count(byKind[TxKind.EXPENSE] ?: 0))
    Fact("Income", count(byKind[TxKind.INCOME] ?: 0))
    Fact("Transfers", count(byKind[TxKind.TRANSFER] ?: 0))
    Fact("Accounts", count(plan.accounts.size))
    Fact("Categories", "${count(plan.categories.count { !it.hidden })} active, ${count(plan.categories.count { it.hidden })} retired")
    Fact("Budgets", count(plan.budgets.size))
    if (plan.people.isNotEmpty()) Fact("People", count(plan.people.size))
    if (plan.bills.isNotEmpty()) Fact("Bills", count(plan.bills.size))

    SectionHeader("Checks", if (r.allMatch) "All match" else "Needs a look")
    val badAccounts = r.accounts.filter { !it.matches }
    val badMonths = r.months.filter { !it.matches }
    Check(
        ok = badAccounts.isEmpty(),
        text = if (badAccounts.isEmpty()) "All ${r.accounts.size} account balances match the backup."
        else "${badAccounts.size} account balances differ: " + badAccounts.joinToString { "${it.name} (${Money.format(it.imported - it.source)})" },
    )
    if (r.months.isNotEmpty()) Check(
        ok = badMonths.isEmpty(),
        text = if (badMonths.isEmpty()) "Spending and income match for all ${r.months.size} months."
        else "${badMonths.size} months differ, starting ${badMonths.first().month}.",
    )

    val (left, other) = plan.issues.partition { it is ImportIssue.LeftBehind }
    if (left.isNotEmpty()) {
        SectionHeader(if (plan.appName.isNotBlank()) "Stays in ${plan.appName}" else "Stays behind")
        left.forEach { Issue(it) }
    }
    if (other.isNotEmpty()) {
        SectionHeader("Worth knowing", "You can fix these later")
        other.forEach { Issue(it) }
    }

    Gap(28)
    PrimaryButton("Import ${count(plan.transactions.size)} entries", onImport)
    if (!firstRun) {
        Gap(6)
        Text(
            if (plan.people.isEmpty() && plan.bills.isEmpty()) "This replaces what's in Kassa now."
            else "This replaces your accounts, categories, entries, budgets and bills. People you already have are kept; the ones from this file are added.",
            style = KassaType.small, color = Kassa.colors.attention,
        )
    }
    Gap(4)
    TextButton(onClick = onPickAgain, modifier = Modifier.fillMaxWidth()) { Text("Choose a different file", color = Kassa.colors.ink2) }
}

@Composable
private fun Issue(issue: ImportIssue) {
    val text = when (issue) {
        is ImportIssue.PossibleDuplicateAccounts -> "These look like the same account: ${issue.names.joinToString(" and ")}. You can archive one after importing."
        is ImportIssue.MismatchedTransfers -> "Transfers whose two sides didn't agree (Kassa kept the sending side; balances still match): ${count(issue.count)}"
        is ImportIssue.UnpairedTransfers -> "${issue.count} transfers had only one half. They were kept so balances match."
        is ImportIssue.ZeroAmountEntries -> "${issue.count} entries have an amount of ${Money.format(0)}. They were kept."
        is ImportIssue.SkippedRows -> "${issue.count} skipped: ${issue.reason}."
        is ImportIssue.MergedCategories -> "Categories with the same name in different capitals were combined: ${issue.names.joinToString()}."
        is ImportIssue.LeftBehind -> "${issue.what}: ${count(issue.count)}"
        is ImportIssue.RefundsAsIncome -> "Refunds (money back in a spending category) come in as income in that category: ${count(issue.count)}"
        is ImportIssue.BalanceCorrections -> "Balance corrections that became a starting balance, so balances match: ${count(issue.count)}"
        is ImportIssue.TransfersAsEntries -> "Transfers with an account that didn't come over, kept as plain entries so balances match: ${count(issue.count)}"
        is ImportIssue.OtherCurrency -> "${issue.accounts.joinToString()} come in with their amounts unchanged, in this phone's currency."
        is ImportIssue.DeletedAccounts -> "Accounts deleted in the backup, left out with their entries: ${count(issue.count)}"
        is ImportIssue.CardsWithoutPayFrom -> "Cards with no pay-from account; set one on the card: ${count(issue.count)}"
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

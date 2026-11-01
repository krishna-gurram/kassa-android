package app.kassa.money.ui.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kassa.money.R
import app.kassa.money.data.backup.BackupContents
import app.kassa.money.domain.Currencies
import app.kassa.money.domain.Money
import app.kassa.money.domain.NumberText
import app.kassa.money.domain.TxKind
import app.kassa.money.importer.ImportIssue
import app.kassa.money.importer.ImportPlan
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.Panel
import app.kassa.money.ui.common.PasswordField
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.settings.BackupSummary
import app.kassa.money.ui.settings.CurrencySheet
import app.kassa.money.ui.settings.NavRow
import app.kassa.money.ui.settings.RestoreConfirm
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

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
                ImportState.Reading -> Busy(stringResource(R.string.importer_busy_reading))
                ImportState.Saving -> Busy(stringResource(R.string.importer_busy_saving))
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
                    Text(stringResource(R.string.importer_restored_title), style = KassaType.title, color = Kassa.colors.ink)
                    Gap(8)
                    Text(stringResource(R.string.importer_restored_body), style = KassaType.body, color = Kassa.colors.ink2)
                    Gap(24)
                    PrimaryButton(stringResource(R.string.importer_open_kassa)) { vm.reset(); onFinished() }
                }
                is ImportState.Review -> Review(s.plan, firstRun, onImport = vm::confirm, onPickAgain = { vm.reset(); pick() })
                is ImportState.Done -> {
                    Text(if (s.plan.appName.isNotBlank()) stringResource(R.string.importer_done_title_from, s.plan.appName) else stringResource(R.string.importer_done_title), style = KassaType.title, color = Kassa.colors.ink)
                    Gap(8)
                    Text(stringResource(R.string.importer_done_body, count(s.entries)), style = KassaType.body, color = Kassa.colors.ink2)
                    val next = buildList {
                        s.plan.accounts.filter { it.type == app.kassa.money.domain.AccountType.CREDIT_CARD && it.statementDay == null }
                            .forEach { add(stringResource(R.string.importer_next_statement, it.name)) }
                        s.plan.accounts.filter { it.type == app.kassa.money.domain.AccountType.CREDIT_CARD && it.payFromId == null }
                            .forEach { add(stringResource(R.string.importer_next_pay_from, it.name)) }
                        if (s.plan.people.isNotEmpty()) add(stringResource(R.string.importer_next_people, s.plan.people.joinToString { it.name }))
                        if (s.plan.bills.isNotEmpty()) add(stringResource(R.string.importer_next_bills, count(s.plan.bills.size)))
                        add(stringResource(R.string.importer_next_overview))
                    }
                    Gap(16)
                    Panel { Column { next.forEach { Bullet(it) } } }
                    Gap(24)
                    PrimaryButton(stringResource(R.string.importer_open_kassa)) { vm.reset(); onFinished() }
                }
            }
        }
    }
}

@Composable
private fun Intro(firstRun: Boolean, pick: () -> Unit, onStartFresh: () -> Unit, onCancel: (() -> Unit)?) {
    Gap(if (firstRun) 48 else 8)
    Text(if (firstRun) stringResource(R.string.app_name) else stringResource(R.string.importer_intro_title), style = KassaType.title, color = Kassa.colors.ink)
    Gap(8)
    Text(
        if (firstRun) stringResource(R.string.importer_intro_first_run_body)
        else stringResource(R.string.importer_intro_replace_body),
        style = KassaType.body, color = Kassa.colors.ink2,
    )
    val settings = app.kassa.money.ui.LocalSettings.current
    val code by settings.currency.collectAsState()
    val chosen by settings.currencyChosen.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    if (firstRun) {
        // The currency this phone shows; it can change later in Settings. A phone in a region Kassa has no currency for
        // starts with none, and Start fresh and Choose backup file open the list instead until one is picked.
        val current = Currencies.byCode(code)
        Gap(12)
        NavRow(stringResource(R.string.importer_currency_label), if (chosen) stringResource(R.string.importer_currency_value, current.name, Money.sample(123_450, style = current)) else stringResource(R.string.importer_currency_choose)) { choosing = true }
        if (choosing) CurrencySheet(if (chosen) code else null, onPick = { settings.setCurrency(it.code) }, onDismiss = { choosing = false })
        Gap(16)
        // Most people have no backup to bring, so starting fresh comes first.
        PrimaryButton(stringResource(R.string.importer_start_fresh)) { if (chosen) onStartFresh() else choosing = true }
        Gap(16)
    } else Gap(28)
    Panel {
        Column {
            Text(if (firstRun) stringResource(R.string.importer_have_backup) else stringResource(R.string.importer_bring_history), style = KassaType.bodyStrong, color = Kassa.colors.ink)
            Gap(4)
            Text(
                stringResource(R.string.importer_backup_formats),
                style = KassaType.small, color = Kassa.colors.ink2,
            )
            Gap(14)
            if (firstRun) SecondaryButton(stringResource(R.string.importer_choose_file), { if (chosen || !firstRun) pick() else choosing = true }) else PrimaryButton(stringResource(R.string.importer_choose_file), pick)
        }
    }
    Gap(12)
    if (onCancel != null) TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.importer_cancel), color = Kassa.colors.ink2) }
}

/** A Kassa backup with a password: the same field as Settings › Backup and restore. */
@Composable
private fun KassaPassword(problem: String?, onOpen: (CharArray) -> Unit, onCancel: () -> Unit) {
    // Not saveable on purpose: a password shouldn't sit in saved instance state.
    var password by remember { mutableStateOf("") }
    Text(stringResource(R.string.importer_password_title), style = KassaType.title, color = Kassa.colors.ink)
    Gap(8)
    Text(stringResource(R.string.importer_password_body), style = KassaType.body, color = Kassa.colors.ink2)
    PasswordField(password, { password = it }, stringResource(R.string.importer_password_label), Modifier.fillMaxWidth().padding(top = 16.dp), problem = problem)
    Gap(12)
    PrimaryButton(stringResource(R.string.importer_open_backup)) { if (password.isNotEmpty()) onOpen(password.toCharArray()) }
    Gap(4)
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.importer_cancel), color = Kassa.colors.ink2) }
}

/** A Kassa backup, read: what's in it, then restore. Over existing data it asks first, like the Backup page. */
@Composable
private fun KassaReady(contents: BackupContents, firstRun: Boolean, onRestore: () -> Unit, onCancel: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.importer_restore_title), style = KassaType.title, color = Kassa.colors.ink)
    Gap(8)
    BackupSummary(contents)
    if (!firstRun) {
        Gap(6)
        Text(stringResource(R.string.importer_restore_replaces), style = KassaType.small, color = Kassa.colors.attention)
    }
    Gap(24)
    PrimaryButton(if (firstRun) stringResource(R.string.importer_restore_button) else stringResource(R.string.importer_replace_button)) { if (firstRun) onRestore() else confirm = true }
    Gap(4)
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.importer_choose_different), color = Kassa.colors.ink2) }
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
    Text(stringResource(R.string.importer_review_title), style = KassaType.title, color = Kassa.colors.ink)
    Gap(8)
    Text(stringResource(R.string.importer_review_from, plan.sourceName), style = KassaType.body, color = Kassa.colors.ink2)

    SectionHeader(stringResource(R.string.importer_what_comes_over))
    Fact(stringResource(R.string.importer_expenses), count(byKind[TxKind.EXPENSE] ?: 0))
    Fact(stringResource(R.string.importer_income), count(byKind[TxKind.INCOME] ?: 0))
    Fact(stringResource(R.string.importer_transfers), count(byKind[TxKind.TRANSFER] ?: 0))
    Fact(stringResource(R.string.importer_accounts), count(plan.accounts.size))
    Fact(stringResource(R.string.importer_categories), stringResource(R.string.importer_categories_value, count(plan.categories.count { !it.hidden }), count(plan.categories.count { it.hidden })))
    Fact(stringResource(R.string.importer_budgets), count(plan.budgets.size))
    if (plan.people.isNotEmpty()) Fact(stringResource(R.string.importer_people), count(plan.people.size))
    if (plan.bills.isNotEmpty()) Fact(stringResource(R.string.importer_bills), count(plan.bills.size))

    SectionHeader(stringResource(R.string.importer_checks), if (r.allMatch) stringResource(R.string.importer_all_match) else stringResource(R.string.importer_needs_look))
    val badAccounts = r.accounts.filter { !it.matches }
    val badMonths = r.months.filter { !it.matches }
    Check(
        ok = badAccounts.isEmpty(),
        text = if (badAccounts.isEmpty()) stringResource(R.string.importer_check_accounts_ok, r.accounts.size)
        else stringResource(R.string.importer_check_accounts_bad, badAccounts.size, badAccounts.joinToString { "${it.name} (${Money.format(it.imported - it.source)})" }),
    )
    if (r.months.isNotEmpty()) Check(
        ok = badMonths.isEmpty(),
        text = if (badMonths.isEmpty()) stringResource(R.string.importer_check_months_ok, r.months.size)
        else stringResource(R.string.importer_check_months_bad, badMonths.size, badMonths.first().month),
    )

    val (left, other) = plan.issues.partition { it is ImportIssue.LeftBehind }
    if (left.isNotEmpty()) {
        SectionHeader(if (plan.appName.isNotBlank()) stringResource(R.string.importer_stays_in, plan.appName) else stringResource(R.string.importer_stays_behind))
        left.forEach { Issue(it) }
    }
    if (other.isNotEmpty()) {
        SectionHeader(stringResource(R.string.importer_worth_knowing), stringResource(R.string.importer_fix_later))
        other.forEach { Issue(it) }
    }

    Gap(28)
    PrimaryButton(stringResource(R.string.importer_import_button, count(plan.transactions.size)), onImport)
    if (!firstRun) {
        Gap(6)
        Text(
            if (plan.people.isEmpty() && plan.bills.isEmpty()) stringResource(R.string.importer_replaces_simple)
            else stringResource(R.string.importer_replaces_detail),
            style = KassaType.small, color = Kassa.colors.attention,
        )
    }
    Gap(4)
    TextButton(onClick = onPickAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.importer_choose_different), color = Kassa.colors.ink2) }
}

@Composable
private fun Issue(issue: ImportIssue) {
    val text = when (issue) {
        is ImportIssue.PossibleDuplicateAccounts -> stringResource(R.string.importer_issue_duplicate_accounts, issue.names.joinToString(" and "))
        is ImportIssue.MismatchedTransfers -> stringResource(R.string.importer_issue_mismatched_transfers, count(issue.count))
        is ImportIssue.UnpairedTransfers -> stringResource(R.string.importer_issue_unpaired_transfers, issue.count)
        is ImportIssue.ZeroAmountEntries -> stringResource(R.string.importer_issue_zero_amount, issue.count, Money.format(0))
        is ImportIssue.SkippedRows -> stringResource(R.string.importer_issue_skipped_rows, issue.count, issue.reason)
        is ImportIssue.MergedCategories -> stringResource(R.string.importer_issue_merged_categories, issue.names.joinToString())
        is ImportIssue.LeftBehind -> stringResource(R.string.importer_issue_left_behind, issue.what, count(issue.count))
        is ImportIssue.RefundsAsIncome -> stringResource(R.string.importer_issue_refunds, count(issue.count))
        is ImportIssue.BalanceCorrections -> stringResource(R.string.importer_issue_balance_corrections, count(issue.count))
        is ImportIssue.TransfersAsEntries -> stringResource(R.string.importer_issue_transfers_as_entries, count(issue.count))
        is ImportIssue.OtherCurrency -> stringResource(R.string.importer_issue_other_currency, issue.accounts.joinToString())
        is ImportIssue.DeletedAccounts -> stringResource(R.string.importer_issue_deleted_accounts, count(issue.count))
        is ImportIssue.CardsWithoutPayFrom -> stringResource(R.string.importer_issue_cards_no_pay_from, count(issue.count))
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
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Kassa.colors.ink, contentColor = Kassa.colors.onInk),
    ) { Text(label, style = KassaType.bodyStrong) }
}

/** A white (raised) filled button, for actions that add something next to a list. */
@Composable
fun RaisedButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 50.dp),
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
        modifier = modifier.fillMaxWidth().heightIn(min = 50.dp),
        shape = RoundedCornerShape(15.dp),
    ) { Text(label, style = KassaType.bodyStrong, color = Kassa.colors.ink) }
}

private fun count(n: Int): String = NumberText.group(n.toString())

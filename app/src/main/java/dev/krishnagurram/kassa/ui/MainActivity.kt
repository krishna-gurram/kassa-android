package dev.krishnagurram.kassa.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.krishnagurram.kassa.KassaApplication
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.ui.accounts.AccountsScreen
import dev.krishnagurram.kassa.ui.activity.ActivityScreen
import dev.krishnagurram.kassa.ui.cards.CardsScreen
import dev.krishnagurram.kassa.ui.entry.EntrySheet
import dev.krishnagurram.kassa.ui.entry.EntryStart
import dev.krishnagurram.kassa.ui.importer.ImportScreen
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaTheme
import dev.krishnagurram.kassa.ui.today.TodayScreen
import dev.krishnagurram.kassa.ui.settings.SettingsActions
import dev.krishnagurram.kassa.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : androidx.fragment.app.FragmentActivity() {
    private val locked = mutableStateOf(false)
    private var hiddenAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as KassaApplication
        if (app.settings.lockEnabled.value) locked.value = true
        setContent {
            KassaTheme {
                val count by app.repository.entryCount.collectAsState(initial = -1)
                val lockOn by app.settings.lockEnabled.collectAsState()
                LaunchedEffect(lockOn) { if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!lockOn) }
                if (locked.value && lockOn) {
                    LockScreen(onUnlock = ::unlock)
                    LaunchedEffect(Unit) { unlock() }
                } else {
                    KassaRoot(entryCount = count, startedFresh = app.settings.startedFresh, onStartFresh = { app.settings.startedFresh = true })
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        hiddenAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        val app = application as KassaApplication
        // Lock again after a minute away.
        if (app.settings.lockEnabled.value && hiddenAt > 0 && System.currentTimeMillis() - hiddenAt > 60_000) locked.value = true
    }

    private fun unlock() {
        val auth = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Kassa")
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= 30) setAllowedAuthenticators(auth)
                else @Suppress("DEPRECATION") setDeviceCredentialAllowed(true)
            }
            .build()
        val prompt = androidx.biometric.BiometricPrompt(this, androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) { locked.value = false }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    // No screen lock set up at all: don't trap the user out of their own data.
                    if (code == androidx.biometric.BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL || code == androidx.biometric.BiometricPrompt.ERROR_HW_NOT_PRESENT) locked.value = false
                }
            })
        prompt.authenticate(info)
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Kassa.colors.ground), contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Text("Kassa is locked", style = dev.krishnagurram.kassa.ui.theme.KassaType.section, color = Kassa.colors.ink)
            androidx.compose.material3.TextButton(onClick = onUnlock) { Text("Unlock", color = Kassa.colors.ink) }
        }
    }
}

private enum class Tab(val label: String) { TODAY("Today"), ACTIVITY("Activity"), CARDS("Cards"), INSIGHTS("Insights"), ACCOUNTS("Accounts") }

@Composable
private fun KassaRoot(entryCount: Int, startedFresh: Boolean, onStartFresh: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var fresh by rememberSaveable { mutableStateOf(startedFresh) }
    var selectedCard by rememberSaveable { mutableStateOf<Long?>(null) }
    var entry by remember { mutableStateOf<EntryStart?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var allocating by remember { mutableStateOf<dev.krishnagurram.kassa.data.db.PendingEntity?>(null) }
    var categoryOpen by remember { mutableStateOf<dev.krishnagurram.kassa.domain.Category?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: LedgerViewModel = viewModel()
    val ledger by vm.ledger.collectAsState()
    val sensitivity by vm.sensitivity.collectAsState()
    val dismissedFlags by vm.dismissedFlags.collectAsState()
    val lockOn by vm.lockEnabled.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val edit: (Transaction) -> Unit = { t ->
        scope.launch { vm.loadForEdit(t.id)?.let { (tx, split) -> entry = EntryStart(editing = tx, splitWith = split) } }
    }

    val needs = object : dev.krishnagurram.kassa.ui.today.NeedsYouActions {
        fun draftFrom(p: dev.krishnagurram.kassa.data.db.PendingEntity) = dev.krishnagurram.kassa.data.EntryDraft(
            id = null, kind = if (p.isCredit) dev.krishnagurram.kassa.domain.TxKind.INCOME else dev.krishnagurram.kassa.domain.TxKind.EXPENSE,
            amount = p.amount, accountId = p.accountId, toAccountId = null, categoryId = if (p.isCredit) null else p.categoryId, personId = null,
            epochMillis = p.epochMillis, note = p.merchant ?: "", splitWith = emptyList(),
        )
        override fun addAlert(p: dev.krishnagurram.kassa.data.db.PendingEntity) {
            if (p.isCredit) { editAlert(p); return }
            vm.save(draftFrom(p)) { vm.dismissPending(p.id) }
        }
        override fun editAlert(p: dev.krishnagurram.kassa.data.db.PendingEntity) { entry = EntryStart(prefill = draftFrom(p), afterSave = { vm.dismissPending(p.id) }) }
        override fun dismissAlert(p: dev.krishnagurram.kassa.data.db.PendingEntity) { vm.dismissPending(p.id, "Dismissed. Nothing was added.") }
        override fun approve(r: dev.krishnagurram.kassa.domain.Recurring, amount: Long) { vm.approveRecurring(r, amount) }
        override fun editRecurring(r: dev.krishnagurram.kassa.domain.Recurring, amount: Long) {
            val l = ledger ?: return
            val due = dev.krishnagurram.kassa.domain.RecurringRules.dueDate(r, java.time.YearMonth.from(l.today))
            entry = EntryStart(
                prefill = dev.krishnagurram.kassa.data.EntryDraft(null, dev.krishnagurram.kassa.domain.TxKind.EXPENSE, amount, r.accountId, null, r.categoryId, null,
                    due.atTime(9, 0).atZone(l.zone).toInstant().toEpochMilli(), r.name, emptyList()),
                afterSave = { vm.markHandled(r) },
            )
        }
        override fun skip(r: dev.krishnagurram.kassa.domain.Recurring) { vm.skipRecurring(r) }
        override fun allocate(p: dev.krishnagurram.kassa.data.db.PendingEntity) { allocating = p }
    }

    Box(Modifier.fillMaxSize().background(Kassa.colors.ground)) {
        val l = ledger
        when {
            entryCount < 0 || l == null -> Unit // database still opening
            entryCount == 0 && l.accounts.accounts.isEmpty() && !fresh -> Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                ImportScreen(firstRun = true, onFinished = { fresh = true; onStartFresh() })
            }
            importing -> Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                BackHandler { importing = false }
                ImportScreen(firstRun = false, onFinished = { importing = false }, onCancel = { importing = false })
            }
            else -> Shell(tab, onTab = { tab = it; if (it != Tab.CARDS) selectedCard = null }, onAdd = { entry = EntryStart() }, snackbar = snackbar) { padding ->
                when (tab) {
                    Tab.TODAY -> TodayScreen(l, padding, onEdit = edit, onOpenCard = { selectedCard = it; tab = Tab.CARDS }, onSeeAll = { tab = Tab.ACTIVITY }, onSettings = { settingsOpen = true }, needs = needs)
                    Tab.ACTIVITY -> ActivityScreen(l, padding, onEdit = edit)
                    Tab.INSIGHTS -> {
                        val monthKey = dev.krishnagurram.kassa.domain.Budgets.key(l.period.yearMonth)
                        dev.krishnagurram.kassa.ui.insights.InsightsScreen(
                            l, padding,
                            sensitivity = runCatching { dev.krishnagurram.kassa.domain.Insights.Sensitivity.valueOf(sensitivity) }.getOrDefault(dev.krishnagurram.kassa.domain.Insights.Sensitivity.BALANCED),
                            dismissed = dismissedFlags.mapNotNull { k -> k.split(':').takeIf { it.size == 2 && it[1] == monthKey.toString() }?.get(0)?.toLongOrNull() }.toSet(),
                            actions = object : dev.krishnagurram.kassa.ui.insights.InsightActions {
                                override fun setCap(category: dev.krishnagurram.kassa.domain.Category, amount: Long) { vm.setCap(category, amount) }
                                override fun dismissFlag(category: dev.krishnagurram.kassa.domain.Category) = vm.dismissFlag(category)
                                override fun openCategory(category: dev.krishnagurram.kassa.domain.Category) { categoryOpen = category }
                            },
                        )
                    }
                    Tab.CARDS -> CardsScreen(l, padding, selectedCard, onSelect = { selectedCard = it }, onEdit = edit, onPay = { vm.save(it) })
                    Tab.ACCOUNTS -> AccountsScreen(
                        l, padding,
                        onImportAgain = { importing = true },
                        onAddAccount = { a, g -> vm.addAccount(a, g) },
                        onArchive = { a, archived -> vm.setArchived(a, archived) },
                        onAddPerson = { vm.addPerson(it) },
                        onSave = { vm.save(it) },
                        onSaveHolding = { vm.saveHolding(it) },
                        onDeleteHolding = { vm.deleteHolding(it.id) },
                    )
                }
            }
        }
        if (settingsOpen && l != null) {
            val alertApps by vm.alertApps.collectAsState()
            val seenApps by vm.seenApps.collectAsState()
            SettingsScreen(
                l, remember(vm) { settingsActions(vm, context) { settingsOpen = false; importing = true } },
                dev.krishnagurram.kassa.ui.settings.SettingsValues(alertApps, seenApps, sensitivity, lockOn),
                onClose = { settingsOpen = false },
            )
        }
        categoryOpen?.let { c ->
            if (l != null) CategorySheet(l, c, onEdit = { categoryOpen = null; edit(it) }, onDismiss = { categoryOpen = null })
        }
        allocating?.let { p ->
            if (l != null) dev.krishnagurram.kassa.ui.invest.AllocateSheet(
                p, l,
                onCash = { vm.dismissPending(p.id, "Counted as cash in the investment account."); allocating = null },
                onBuy = { t, sh, pr -> p.accountId?.let { vm.buy(it, t, sh, pr, p.id) }; allocating = null },
                onDismiss = { allocating = null },
            )
        }
        val start = entry
        if (start != null && l != null) {
            EntrySheet(
                ledger = l, start = start,
                loadNotes = { vm.notes(it) },
                onSave = { vm.save(it, start.afterSave); entry = null },
                onDelete = { vm.delete(it); entry = null },
                onAddPerson = { name, then -> vm.addPerson(name, then) },
                onDismiss = { entry = null },
            )
        }
    }
}

/**
 * Bottom navigation with a floating add button on the cover screen; on the unfolded screen (600dp or wider)
 * a navigation rail with the add button at its top. Switches by itself when the phone folds or unfolds.
 */
@Composable
private fun Shell(tab: Tab, onTab: (Tab) -> Unit, onAdd: () -> Unit, snackbar: SnackbarHostState, content: @Composable (PaddingValues) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val fab: @Composable () -> Unit = {
            FloatingActionButton(onClick = onAdd, containerColor = Kassa.colors.ink, contentColor = Kassa.colors.onInk, shape = RoundedCornerShape(18.dp)) {
                Icon(plusIcon(), "Add an entry")
            }
        }
        if (wide) {
            Scaffold(containerColor = Kassa.colors.ground, snackbarHost = { SnackbarHost(snackbar) }) { inner ->
                Row(Modifier.fillMaxSize().padding(inner)) {
                    NavigationRail(containerColor = Kassa.colors.ground, header = { Spacer(Modifier.height(8.dp)); fab(); Spacer(Modifier.height(24.dp)) }) {
                        Tab.entries.forEach { t ->
                            NavigationRailItem(
                                selected = tab == t, onClick = { onTab(t) },
                                icon = { Icon(iconFor(t), null) }, label = { Text(t.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = Kassa.colors.ink, selectedTextColor = Kassa.colors.ink,
                                    indicatorColor = Kassa.colors.chip, unselectedIconColor = Kassa.colors.ink3, unselectedTextColor = Kassa.colors.ink3,
                                ),
                            )
                        }
                    }
                    Box(Modifier.weight(1f)) { content(PaddingValues(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 32.dp)) }
                }
            }
        } else {
            Scaffold(
                containerColor = Kassa.colors.ground,
                snackbarHost = { SnackbarHost(snackbar) },
                floatingActionButton = fab,
                bottomBar = {
                    NavigationBar(containerColor = Kassa.colors.ground) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t, onClick = { onTab(t) },
                                icon = { Icon(iconFor(t), null) }, label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Kassa.colors.ink, selectedTextColor = Kassa.colors.ink,
                                    indicatorColor = Kassa.colors.chip, unselectedIconColor = Kassa.colors.ink3, unselectedTextColor = Kassa.colors.ink3,
                                ),
                            )
                        }
                    }
                },
            ) { inner ->
                content(PaddingValues(start = 20.dp, end = 20.dp, top = inner.calculateTopPadding() + 16.dp, bottom = inner.calculateBottomPadding() + 88.dp))
            }
        }
    }
}

private fun line(name: String, build: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
        stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        pathBuilder = build,
    ).build()

private fun plusIcon() = line("plus") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }

private fun iconFor(t: Tab): ImageVector = when (t) {
    Tab.TODAY -> line("today") {
        moveTo(7f, 5f); lineTo(17f, 5f); quadTo(20f, 5f, 20f, 8f); lineTo(20f, 17f); quadTo(20f, 20f, 17f, 20f)
        lineTo(7f, 20f); quadTo(4f, 20f, 4f, 17f); lineTo(4f, 8f); quadTo(4f, 5f, 7f, 5f)
        moveTo(4f, 10f); lineTo(20f, 10f); moveTo(9f, 3f); lineTo(9f, 7f); moveTo(15f, 3f); lineTo(15f, 7f)
    }
    Tab.ACTIVITY -> line("activity") { moveTo(5f, 7f); lineTo(19f, 7f); moveTo(5f, 12f); lineTo(19f, 12f); moveTo(5f, 17f); lineTo(14f, 17f) }
    Tab.CARDS -> line("cards") {
        moveTo(5.5f, 6f); lineTo(18.5f, 6f); quadTo(21f, 6f, 21f, 8.5f); lineTo(21f, 16.5f); quadTo(21f, 19f, 18.5f, 19f)
        lineTo(5.5f, 19f); quadTo(3f, 19f, 3f, 16.5f); lineTo(3f, 8.5f); quadTo(3f, 6f, 5.5f, 6f)
        moveTo(3f, 10.5f); lineTo(21f, 10.5f); moveTo(7f, 15f); lineTo(11f, 15f)
    }
    Tab.INSIGHTS -> line("insights") { moveTo(5f, 20f); lineTo(5f, 12f); moveTo(12f, 20f); lineTo(12f, 5f); moveTo(19f, 20f); lineTo(19f, 13f) }
    Tab.ACCOUNTS -> line("accounts") {
        moveTo(4f, 9.5f); lineTo(12f, 5f); lineTo(20f, 9.5f)
        moveTo(6f, 11f); lineTo(6f, 18f); moveTo(10f, 11f); lineTo(10f, 18f)
        moveTo(14f, 11f); lineTo(14f, 18f); moveTo(18f, 11f); lineTo(18f, 18f); moveTo(4f, 20f); lineTo(20f, 20f)
    }
}

private fun settingsActions(vm: LedgerViewModel, context: android.content.Context, onImport: () -> Unit) = object : SettingsActions {
    override fun saveCategory(category: dev.krishnagurram.kassa.domain.Category, budget: Long?, then: (Long) -> Unit) { vm.saveCategory(category, budget, then) }
    override fun setMonthBudget(categoryId: Long, amount: Long?) {
        val l = vm.ledger.value ?: return
        vm.setMonthBudget(categoryId, l.period.yearMonth, amount)
    }
    override fun mergeCategory(from: dev.krishnagurram.kassa.domain.Category, into: dev.krishnagurram.kassa.domain.Category) { vm.mergeCategory(from, into) }
    override fun convertToPerson(account: dev.krishnagurram.kassa.domain.Account, balance: Long, name: String) { vm.convertToPerson(account, balance, name) }
    override fun setMonthStartDay(day: Int) = vm.setMonthStartDay(day)
    override fun importAgain() = onImport()
    override suspend fun savePhoto(uri: android.net.Uri): String? = vm.saveIconImage(uri)
    override fun saveRecurring(r: dev.krishnagurram.kassa.domain.Recurring) { vm.saveRecurring(r) }
    override fun deleteRecurring(id: Long) { vm.deleteRecurring(id) }
    override fun setAlertApp(pkg: String, on: Boolean) = vm.setAlertApp(pkg, on)
    override fun addRule(pkg: String, last4: String?, accountId: Long) { vm.addRule(pkg, last4, accountId) }
    override fun deleteRule(id: Long) { vm.deleteRule(id) }
    override fun openNotificationAccess() {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun hasNotificationAccess(): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    override fun setSensitivity(v: String) = vm.setSensitivity(v)
    override fun setLock(on: Boolean) = vm.setLock(on)
    override suspend fun exportTo(uri: android.net.Uri, passphrase: CharArray): String = vm.exportTo(uri, passphrase)
    override suspend fun readBackup(uri: android.net.Uri, passphrase: CharArray) = vm.readBackup(uri, passphrase)
    override fun restore(json: org.json.JSONObject) { vm.restore(json) }
    override fun appLabel(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

/** A category's month: what it went to, by subcategory, and every entry. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheet(l: Ledger, c: dev.krishnagurram.kassa.domain.Category, onEdit: (Transaction) -> Unit, onDismiss: () -> Unit) {
    val p = l.period
    val ids = l.categories.filter { it.id == c.id || it.parentId == c.id }.map { it.id }.toSet()
    val entries = l.recent.filter { t ->
        t.kind == dev.krishnagurram.kassa.domain.TxKind.EXPENSE && t.categoryId in ids &&
            java.time.Instant.ofEpochMilli(t.epochMillis).atZone(l.zone).toLocalDate().let { it in p }
    }.sortedByDescending { it.epochMillis }
    val bySub = entries.groupBy { t -> l.categoryById[t.categoryId]?.takeIf { it.parentId == c.id }?.name ?: "Other" }
        .mapValues { (_, v) -> v.sumOf { it.categoryAmount } }.entries.sortedByDescending { it.value }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Kassa.colors.raised) {
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).androidx_verticalScroll()) {
            Text(c.name, style = dev.krishnagurram.kassa.ui.theme.KassaType.section, color = Kassa.colors.ink)
            Text("${dev.krishnagurram.kassa.domain.Money.format(entries.sumOf { it.categoryAmount })} this month", style = dev.krishnagurram.kassa.ui.theme.KassaType.body, color = Kassa.colors.ink2)
            if (bySub.size > 1) bySub.forEach { (name, v) ->
                androidx.compose.foundation.layout.Row(Modifier.padding(vertical = 6.dp)) {
                    Text(name, style = dev.krishnagurram.kassa.ui.theme.KassaType.body, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
                    Text(dev.krishnagurram.kassa.domain.Money.format(v), style = dev.krishnagurram.kassa.ui.theme.KassaType.bodyStrong, color = Kassa.colors.ink)
                }
            }
            dev.krishnagurram.kassa.ui.common.SectionHeader("Entries")
            entries.forEachIndexed { i, t -> dev.krishnagurram.kassa.ui.common.TransactionRow(t, l, showDivider = i < entries.lastIndex, onClick = onEdit) }
        }
    }
}

@Composable
private fun Modifier.androidx_verticalScroll(): Modifier = this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

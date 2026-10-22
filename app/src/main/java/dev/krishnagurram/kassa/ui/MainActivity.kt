package dev.krishnagurram.kassa.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as KassaApplication
        setContent {
            KassaTheme {
                val count by app.repository.entryCount.collectAsState(initial = -1)
                KassaRoot(entryCount = count, startedFresh = app.settings.startedFresh, onStartFresh = { app.settings.startedFresh = true })
            }
        }
    }
}

private enum class Tab(val label: String) { TODAY("Today"), ACTIVITY("Activity"), CARDS("Cards"), ACCOUNTS("Accounts") }

@Composable
private fun KassaRoot(entryCount: Int, startedFresh: Boolean, onStartFresh: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var fresh by rememberSaveable { mutableStateOf(startedFresh) }
    var selectedCard by rememberSaveable { mutableStateOf<Long?>(null) }
    var entry by remember { mutableStateOf<EntryStart?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val vm: LedgerViewModel = viewModel()
    val ledger by vm.ledger.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val edit: (Transaction) -> Unit = { t ->
        scope.launch { vm.loadForEdit(t.id)?.let { (tx, split) -> entry = EntryStart(editing = tx, splitWith = split) } }
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
                    Tab.TODAY -> TodayScreen(l, padding, onEdit = edit, onOpenCard = { selectedCard = it; tab = Tab.CARDS }, onSeeAll = { tab = Tab.ACTIVITY }, onSettings = { settingsOpen = true })
                    Tab.ACTIVITY -> ActivityScreen(l, padding, onEdit = edit)
                    Tab.CARDS -> CardsScreen(l, padding, selectedCard, onSelect = { selectedCard = it }, onEdit = edit, onPay = { vm.save(it) })
                    Tab.ACCOUNTS -> AccountsScreen(
                        l, padding,
                        onImportAgain = { importing = true },
                        onAddAccount = { a, g -> vm.addAccount(a, g) },
                        onArchive = { a, archived -> vm.setArchived(a, archived) },
                        onAddPerson = { vm.addPerson(it) },
                        onSave = { vm.save(it) },
                    )
                }
            }
        }
        if (settingsOpen && l != null) {
            SettingsScreen(l, remember(vm) { settingsActions(vm) { settingsOpen = false; importing = true } }, onClose = { settingsOpen = false })
        }
        val start = entry
        if (start != null && l != null) {
            EntrySheet(
                ledger = l, start = start,
                loadNotes = { vm.notes(it) },
                onSave = { vm.save(it); entry = null },
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
    Tab.ACCOUNTS -> line("accounts") {
        moveTo(4f, 9.5f); lineTo(12f, 5f); lineTo(20f, 9.5f)
        moveTo(6f, 11f); lineTo(6f, 18f); moveTo(10f, 11f); lineTo(10f, 18f)
        moveTo(14f, 11f); lineTo(14f, 18f); moveTo(18f, 11f); lineTo(18f, 18f); moveTo(4f, 20f); lineTo(20f, 20f)
    }
}

private fun settingsActions(vm: LedgerViewModel, onImport: () -> Unit) = object : SettingsActions {
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
}

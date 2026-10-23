package dev.krishnagurram.kassa.ui.entry

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.CategoryKind
import dev.krishnagurram.kassa.domain.InputRules
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Recurring
import dev.krishnagurram.kassa.domain.RecurringRules
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.CategoryIconView
import dev.krishnagurram.kassa.ui.common.ConfirmDialog
import dev.krishnagurram.kassa.ui.common.PageTop
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType

private enum class BillField { AMOUNT, CATEGORY, SUB, ACCOUNT, DAY, NAME }

/**
 * Add or edit a recurring bill. Built like the entry page: the amount on top, tiles for category, account and day,
 * the bill's name under them, and one panel at the bottom for whichever is open. The amount is optional: left empty,
 * the bill is estimated each month from the last three entries with the same name and category.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BillPage(
    ledger: Ledger,
    bill: Recurring?,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onSave: (Recurring) -> Unit,
    onDelete: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val existing = bill?.takeIf { it.id != 0L }
    var amount by remember { mutableStateOf(bill?.fixedAmount?.let { centsToInput(it) } ?: "") }
    var categoryId by remember { mutableStateOf(bill?.categoryId?.takeIf { ledger.categoryById[it] != null }) }
    var accountId by remember {
        mutableStateOf(
            bill?.accountId?.takeIf { ledger.accountById[it] != null }
                ?: ledger.accounts.accounts.firstOrNull { !it.archived && it.type == AccountType.CHECKING }?.id
        )
    }
    var day by remember { mutableStateOf(bill?.dayOfMonth ?: minOf(ledger.today.dayOfMonth, 28)) }
    var name by remember { mutableStateOf(bill?.name ?: "") }
    var active by remember { mutableStateOf(bill?.active ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(if (existing == null) BillField.NAME else null) }
    val initial = remember { listOf(amount, categoryId, accountId, day, name, active) }
    val dirty = listOf(amount, categoryId, accountId, day, name, active) != initial
    val leave: () -> Unit = { if (dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    val focus = LocalFocusManager.current
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(field) { if (field == BillField.NAME) runCatching { nameFocus.requestFocus() } else focus.clearFocus() }

    val cents = Money.parseCents(amount) ?: 0L
    val cat = categoryId?.let { ledger.categoryById[it] }
    val topCat = cat?.let { c -> c.parentId?.let { ledger.categoryById[it] } ?: c }
    val ranked = remember(ledger.recent.size) { rankTop(ledger, CategoryKind.EXPENSE) }
    val rankedAccounts = remember(ledger.recent.size, ledger.accounts.accounts.size) { rankAccounts(ledger, TxKind.EXPENSE, to = false) }
    val cards = remember(ledger) { ledger.cards().associate { it.account.id to it.snapshot } }
    val estimate = if (cents > 0 || name.isBlank() || categoryId == null) null else RecurringRules.estimate(
        Recurring(name = name.trim(), categoryId = categoryId!!, accountId = accountId ?: 0, dayOfMonth = day, fixedAmount = null),
        ledger.recent, ledger.today.plusDays(1), ledger.zone,
    )

    fun missing(): BillField? = when {
        name.isBlank() -> BillField.NAME
        categoryId == null -> BillField.CATEGORY
        accountId == null -> BillField.ACCOUNT
        else -> null
    }

    fun advance() { error = null; field = missing() }

    fun save() {
        val problem = InputRules.nameProblem(name, "bill")?.let { BillField.NAME to it } ?: when {
            categoryId == null -> BillField.CATEGORY to "Pick a category."
            accountId == null -> BillField.ACCOUNT to "Pick an account."
            else -> null
        }
        if (problem != null) { field = problem.first; error = problem.second; return }
        val base = bill ?: Recurring(name = "", categoryId = 0, accountId = 0, dayOfMonth = day, fixedAmount = null)
        onSave(base.copy(name = InputRules.clean(name), categoryId = categoryId!!, accountId = accountId!!, dayOfMonth = day, fixedAmount = cents.takeIf { it > 0 }, active = active))
    }

    val saveLabel = if (existing != null) "Save changes" else "Add bill"

    Column(Modifier.fillMaxSize().padding(contentPadding).imePadding()) {
        PageTop(if (existing != null) "Edit bill" else "New bill", onBack = leave) {
            if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Kassa.colors.attention, style = KassaType.bodyStrong) }
            else TextButton(onClick = ::save) { Text("Save", color = Kassa.colors.ink, style = KassaType.bodyStrong) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 600.dp

            val form: @Composable ColumnScope.() -> Unit = {
                AmountHero(amount, focused = field == BillField.AMOUNT, income = false, large = wide) { field = BillField.AMOUNT }
                Text(
                    when {
                        cents > 0 -> "Same every month"
                        estimate != null -> "Estimated ${Money.format(estimate)}"
                        else -> "Estimated from your last 3 bills"
                    },
                    style = KassaType.tiny, color = Kassa.colors.ink2, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryTile(topCat, cat?.takeIf { it.parentId != null }, on = field == BillField.CATEGORY || field == BillField.SUB, modifier = Modifier.weight(1.15f)) { field = BillField.CATEGORY }
                    AccountTile("Paid with", ledger.accountById[accountId], cards, showDue = false, twoLines = false, on = field == BillField.ACCOUNT, modifier = Modifier.weight(1.15f)) { field = BillField.ACCOUNT }
                    Tile("Every month", on = field == BillField.DAY, empty = false, modifier = Modifier.weight(0.7f), onClick = { field = BillField.DAY }) {
                        Text("DAY", style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp), color = Kassa.colors.ink2, modifier = Modifier.padding(top = 4.dp))
                        Text(day.toString(), style = KassaType.hero.copy(fontSize = 26.sp, letterSpacing = 0.sp, lineHeight = 28.sp), color = Kassa.colors.ink)
                    }
                }
                NoteRow(
                    name, { name = InputRules.typed(it, InputRules.NAME_MAX); error = null }, focused = field == BillField.NAME,
                    hint = "Bill name, as in your entries",
                    requester = nameFocus,
                    onFocused = { field = BillField.NAME },
                    onDone = { advance() },
                )
                if (existing != null) Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (active) "Active" else "Paused", style = KassaType.body, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
                    Switch(active, { active = it }, colors = SwitchDefaults.colors(checkedTrackColor = Kassa.colors.ink, checkedThumbColor = Kassa.colors.onInk))
                }
                error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention, modifier = Modifier.padding(top = 6.dp)) }
            }

            val panel: @Composable (Modifier) -> Unit = { m ->
                Panel(m) {
                    when (field) {
                        BillField.AMOUNT, null -> {
                            PanelTitle("Amount", onDone = null)
                            QuickButton("Estimate each month", on = amount.isEmpty(), modifier = Modifier.fillMaxWidth()) { amount = ""; error = null }
                            NumberPad(Modifier.weight(1f)) { k -> amount = applyKey(amount, k); error = null }
                            val next = missing()
                            if (next != null) PrimaryButton(
                                when (next) { BillField.NAME -> "Next: name"; BillField.CATEGORY -> "Next: category"; else -> "Next: paid with" },
                            ) { error = null; field = next }
                            else PrimaryButton(saveLabel, ::save)
                        }
                        BillField.CATEGORY -> {
                            PanelTitle("Category") { field = null }
                            LazyVerticalGrid(GridCells.Fixed(if (wide) 4 else 3), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(ranked, key = { it.id }) { c ->
                                    PickTile(on = topCat?.id == c.id, minHeight = 66.dp, onClick = {
                                        categoryId = c.id; error = null
                                        if (subsRanked(ledger, c).isNotEmpty()) field = BillField.SUB else advance()
                                    }) {
                                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                            CategoryIconView(c.icon, Color(c.color), 34, fallback = c.name.take(1))
                                            Text(c.name, style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                            }
                        }
                        BillField.SUB -> {
                            val t = topCat
                            if (t == null) { field = BillField.CATEGORY } else {
                                PanelTitle(t.name, back = { field = BillField.CATEGORY }) { field = null }
                                LazyVerticalGrid(GridCells.Fixed(if (wide) 3 else 2), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    item(key = "none") {
                                        PickTile(on = cat?.id == t.id, onClick = { categoryId = t.id; advance() }) {
                                            Text("No subcategory", style = KassaType.bodyStrong, color = Kassa.colors.ink2)
                                        }
                                    }
                                    items(subsRanked(ledger, t), key = { it.id }) { s: Category ->
                                        PickTile(on = cat?.id == s.id, onClick = { categoryId = s.id; advance() }) {
                                            Text(s.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                        BillField.ACCOUNT -> {
                            PanelTitle("Paid with") { field = null }
                            LazyVerticalGrid(GridCells.Fixed(if (wide) 3 else 2), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(rankedAccounts, key = { it.id }) { a ->
                                    PickTile(on = accountId == a.id, onClick = { accountId = a.id; advance() }) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(a.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                            a.last4?.takeIf { it.isNotBlank() }?.let { Text("  ··$it", style = KassaType.tiny, color = Kassa.colors.ink3, maxLines = 1) }
                                        }
                                    }
                                }
                            }
                        }
                        BillField.DAY -> {
                            PanelTitle("Day of the month") { field = null }
                            LazyVerticalGrid(GridCells.Fixed(7), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                items((1..31).toList(), key = { it }) { d ->
                                    val sel = d == day
                                    Box(
                                        Modifier.aspectRatio(1f).clip(CircleShape).background(if (sel) Kassa.colors.ink else Color.Transparent)
                                            .clickable { day = d; advance() },
                                        contentAlignment = Alignment.Center,
                                    ) { Text(d.toString(), style = KassaType.body.copy(fontWeight = FontWeight.Medium), color = if (sel) Kassa.colors.onInk else Kassa.colors.ink) }
                                }
                            }
                            if (day > 28) Text("Shorter months use their last day.", style = KassaType.tiny, color = Kassa.colors.ink3)
                        }
                        BillField.NAME -> {
                            PanelTitle("Name") { advance() }
                            val sugg = ledger.recent.asSequence()
                                .filter { it.kind == TxKind.EXPENSE && it.note.isNotBlank() && (categoryId == null || it.categoryId == categoryId) }
                                .filter { name.isBlank() || it.note.contains(name.trim(), ignoreCase = true) }
                                .groupingBy { it.note.trim() }.eachCount().entries.sortedByDescending { it.value }.take(8).map { it.key }
                                .filter { it != name.trim() }
                            if (sugg.isEmpty()) Text("Use the name you give these entries, so past bills count toward the estimate.", style = KassaType.small, color = Kassa.colors.ink3, modifier = Modifier.padding(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                sugg.forEach { s ->
                                    Box(
                                        Modifier.clip(RoundedCornerShape(50)).background(Kassa.colors.ground).clickable {
                                            name = s.take(InputRules.NAME_MAX)
                                            if (categoryId == null) ledger.recent.firstOrNull { it.note.trim() == s && it.kind == TxKind.EXPENSE }?.let { last ->
                                                categoryId = last.categoryId
                                                day = minOf(java.time.Instant.ofEpochMilli(last.epochMillis).atZone(ledger.zone).dayOfMonth, 31)
                                            }
                                            advance()
                                        }.padding(horizontal = 14.dp, vertical = 9.dp),
                                    ) { Text(s, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).widthIn(max = 520.dp)) { form() }
                    panel(Modifier.weight(1f).fillMaxHeight().padding(bottom = 12.dp))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(horizontal = 16.dp)) { form() }
                    Spacer(Modifier.height(10.dp))
                    panel(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }

    if (confirmDelete && existing != null) ConfirmDialog(
        title = "Delete ${existing.name}?",
        body = "Kassa stops asking about this bill. Entries already added for it stay.",
        confirm = "Delete", dismiss = "Keep it",
        onConfirm = { onDelete(existing.id) }, onDismiss = { confirmDelete = false },
    )
    if (confirmDiscard) ConfirmDialog(
        title = if (existing != null) "Discard your changes?" else "Discard this bill?",
        body = if (existing != null) "The bill stays as it was before you opened it." else "Nothing you typed here will be saved.",
        confirm = "Discard", dismiss = "Keep editing",
        onConfirm = onBack, onDismiss = { confirmDiscard = false },
    )
}

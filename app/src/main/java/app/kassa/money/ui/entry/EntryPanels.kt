package app.kassa.money.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountKindFilter
import app.kassa.money.domain.CardSnapshot
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.Money
import app.kassa.money.domain.TxKind
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.common.AccountPickerPanel
import app.kassa.money.ui.common.CategoryPickerPanel
import app.kassa.money.ui.common.SubcategoryPickerPanel
import app.kassa.money.ui.common.MonthCalendar
import app.kassa.money.ui.common.swipeShift
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/*
 * The inputs the entry page's bottom panel shows, one per [Field]. Each reads and changes the page's [EntryState].
 * They're ColumnScope extensions so a grid or pad can take the panel's remaining height.
 */

/** The pad, with Payable and Total buttons when paying a card, then Next or Save. */
@Composable
internal fun ColumnScope.AmountPanel(s: EntryState, toCard: CardSnapshot?, saveLabel: String, onSave: () -> Unit) {
    PanelTitle("Amount", onDone = null)
    if (toCard != null && (toCard.payable > 0 || toCard.totalBalance > 0)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            if (toCard.payable > 0) QuickButton("Payable ${Money.format(toCard.payable)}", on = s.cents == toCard.payable, modifier = Modifier.weight(1f)) {
                s.amount = centsToInput(toCard.payable); s.error = null
            }
            if (toCard.totalBalance > 0) QuickButton("Total ${Money.format(toCard.totalBalance)}", on = s.cents == toCard.totalBalance, modifier = Modifier.weight(1f)) {
                s.amount = centsToInput(toCard.totalBalance); s.error = null
            }
        }
    }
    NumberPad(Modifier.weight(1f), s::pressKey)
    val next = s.missing()
    if (next == null) PrimaryButton(saveLabel, onSave)
    else PrimaryButton(
        when {
            next == Field.CATEGORY -> "Next: category"
            next == Field.TO -> "Next: to"
            s.kind == TxKind.TRANSFER -> "Next: from"
            s.kind == TxKind.INCOME -> "Next: into"
            else -> "Next: paid with"
        },
    ) { s.error = null; s.field = next }
}

/** The shared category picker ([CategoryPickerPanel]) on the entry page's [EntryState]. */
@Composable
internal fun ColumnScope.CategoryPanel(s: EntryState, ledger: Ledger, wide: Boolean) {
    CategoryPickerPanel(
        ledger.categories, if (s.kind == TxKind.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE, s.categoryId,
        s.query, { s.query = it }, { s.searching = it }, wide, onDone = { s.field = null },
        onPick = { id -> s.pickCategory(id); s.advance() },
        // A top-level category with subcategories is picked first, then its subcategories open.
        onOpenSubcategories = { c -> s.pickCategory(c.id); s.field = Field.SUB },
    )
}

/** The shared subcategory picker ([SubcategoryPickerPanel]) on the entry page's [EntryState]. */
@Composable
internal fun ColumnScope.SubcategoryPanel(s: EntryState, ledger: Ledger, top: Category, wide: Boolean) {
    SubcategoryPickerPanel(
        ledger.categories, top, s.categoryId, wide, onBack = { s.field = Field.CATEGORY }, onDone = { s.field = null },
        onPick = { id -> s.pickCategory(id); s.advance() },
    )
}

/**
 * Accounts to pay with (or, for [Field.TO], to move money into): the shared account picker ([AccountPickerPanel]) on the
 * entry page's [EntryState]. A transfer can't come from a card. Picking a card to pay that has a balance payable opens the
 * pad, so the Payable button is one tap away.
 */
@Composable
internal fun ColumnScope.AccountPanel(
    s: EntryState, accountChoices: List<Account>, toChoices: List<Account>, cards: Map<Long, CardSnapshot>, wide: Boolean,
    recentIds: List<Long> = emptyList(),
) {
    val isTo = s.field == Field.TO
    var filter by remember { mutableStateOf(AccountKindFilter.ALL) }
    val source = if (isTo) toChoices else accountChoices
    val excluded: Set<Long> =
        if (isTo) setOfNotNull(s.accountId)
        else if (s.kind == TxKind.TRANSFER) source.filter { it.type.hasStatement && it.id != s.accountId }.map { it.id }.toSet()
        else emptySet()
    AccountPickerPanel(
        source, if (isTo) s.toAccountId else s.accountId, excluded, recentIds, s.query, { s.query = it }, { s.searching = it },
        filter, { filter = it }, wide, onDone = { s.field = null },
    ) { id ->
        if (isTo) {
            s.toAccountId = id
            s.error = null
            val payable = (cards[id]?.payable ?: 0L) > 0
            s.field = if (payable && s.cents <= 0) Field.AMOUNT else s.missing() ?: if (s.cents <= 0) Field.AMOUNT else null
        } else {
            s.accountId = id
            if (s.toAccountId == id) s.toAccountId = null
            s.advance()
        }
    }
}

/** Today, Yesterday, and a month calendar. */
@Composable
internal fun ColumnScope.DatePanel(s: EntryState, today: LocalDate) {
    PanelTitle("Date") { s.field = null }
    val pick: (LocalDate) -> Unit = { d -> s.date = d; s.error = null; s.field = if (s.cents <= 0) Field.AMOUNT else null }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        QuickButton("Today", on = s.date == today, modifier = Modifier.weight(1f)) { pick(today) }
        QuickButton("Yesterday", on = s.date == today.minusDays(1), modifier = Modifier.weight(1f)) { pick(today.minusDays(1)) }
    }
    MonthCalendar(s.calendarMonth, s.date, today, onMonth = { s.calendarMonth = it }, modifier = Modifier.weight(1f), onPick = pick)
}

/** Notes you've used before; picking one also picks the category it last went with, if none is picked yet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.NotePanel(s: EntryState, ledger: Ledger, frequentNotes: List<String>) {
    PanelTitle("Note") { s.field = null }
    val suggestions = noteSuggestions(frequentNotes, s.note, ledger, s.kind, s.categoryId)
    if (suggestions.isEmpty()) Text("Type a merchant or a note.", style = KassaType.small, color = Kassa.colors.ink3, modifier = Modifier.padding(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        suggestions.forEach { text ->
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50)).background(Kassa.colors.ground).clickable {
                    s.pickNote(text, ledger.recent.firstOrNull { it.note == text && it.kind == s.kind }?.categoryId)
                }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(text, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    Spacer(Modifier.weight(1f))
}

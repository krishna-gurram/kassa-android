package app.kassa.money.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountKindFilter
import app.kassa.money.domain.AccountPicking
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.activity.Chevron
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.entry.PanelSearch
import app.kassa.money.ui.entry.PickTile
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/*
 * The one account picker. Add entry (Paid with, From, To), the card payment sheet, the loan forms and Add account all
 * choose an account with these pieces, the way categories use CategoryPicker.kt: search with Done, kind chips, a Recent
 * row, then icon tiles grouped by kind and A to Z. No balances, nothing preselected; closed accounts and the ones the
 * caller excludes are never offered. What is shown and in which order is `AccountPicking` (domain).
 */

/** The accounts used most recently, newest first, for the picker's Recent row. Only what was used; nothing is guessed. */
fun recentAccountIds(ledger: Ledger): List<Long> =
    ledger.recent.sortedByDescending { it.epochMillis }.mapNotNull { it.accountId }.distinct()

/**
 * Open accounts as icon tiles, grouped by kind. [topOption] (when given with [onPickNone]) is a first full-width answer
 * that clears the choice, for fields that may stay empty. Fills the height the caller gives it.
 */
@Composable
fun ColumnScope.AccountPickerPanel(
    accounts: List<Account>,
    selectedId: Long?,
    excluded: Set<Long>,
    recentIds: List<Long>,
    query: String,
    onQuery: (String) -> Unit,
    onSearching: (Boolean) -> Unit,
    filter: AccountKindFilter,
    onFilter: (AccountKindFilter) -> Unit,
    wide: Boolean,
    onDone: () -> Unit,
    topOption: String? = null,
    onPickNone: (() -> Unit)? = null,
    onPick: (Long) -> Unit,
) {
    val focus = LocalFocusManager.current
    val available = remember(accounts, excluded) { AccountPicking.available(accounts, excluded) }
    val kinds = remember(available) { AccountPicking.filtersFor(available) }
    val q = query.trim()
    val groups = remember(available, filter, q) { AccountPicking.groups(available, filter, q) }
    val recent = if (q.isEmpty() && filter == AccountKindFilter.ALL) AccountPicking.recent(recentIds, available, if (wide) 4 else 3) else emptyList()
    val choose: (Account) -> Unit = { a -> onSearching(false); focus.clearFocus(); onPick(a.id) }

    PanelSearch(query, onQuery, stringResource(R.string.common_search_accounts), onFocus = onSearching, onDone = onDone)
    if (kinds.size > 2) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        kinds.forEach { k -> Chip(k.label, selected = filter == k, small = true) { onFilter(k) } }
    }
    if (groups.isEmpty()) Text(
        if (available.isEmpty()) stringResource(R.string.common_add_account_first) else stringResource(R.string.common_no_account_matches, q),
        style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(4.dp),
    )
    LazyVerticalGrid(
        GridCells.Fixed(if (wide) 4 else 3), Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (topOption != null && onPickNone != null && q.isEmpty()) item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
            PickTile(on = selectedId == null, onClick = onPickNone) {
                Text(topOption, style = KassaType.bodyStrong, color = Kassa.colors.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (recent.isNotEmpty()) {
            item(key = "recent", span = { GridItemSpan(maxLineSpan) }) { GroupLabel(stringResource(R.string.common_recent)) }
            items(recent, key = { "r${it.id}" }) { a -> AccountTile(a, selectedId == a.id) { choose(a) } }
        }
        groups.forEach { g ->
            item(key = "g-${g.section.name}", span = { GridItemSpan(maxLineSpan) }) { GroupLabel(g.section.title) }
            items(g.accounts, key = { "a${it.id}" }) { a -> AccountTile(a, selectedId == a.id) { choose(a) } }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(java.util.Locale.getDefault()), style = KassaType.label, color = Kassa.colors.ink2, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

/** One account: its kind's icon over its name (and last four digits); the picked one is outlined and ticked. */
@Composable
private fun AccountTile(a: Account, on: Boolean, onClick: () -> Unit) {
    val last4 = a.last4?.takeIf { it.isNotBlank() }
    val description = if (last4 != null) stringResource(R.string.common_account_ending, a.name, last4) else a.name
    PickTile(on = on, minHeight = 84.dp, onClick = onClick) {
        Box(Modifier.fillMaxWidth().semantics { contentDescription = description; this.selected = on }) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Kassa.colors.chip), contentAlignment = Alignment.Center) {
                    Icon(accountIcon(a.type), null, tint = Kassa.colors.ink2, modifier = Modifier.size(18.dp))
                }
                Text(
                    a.name, style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
                )
                if (last4 != null) Text("··$last4", style = KassaType.tiny, color = Kassa.colors.ink3, maxLines = 1)
            }
            if (on) Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Kassa.colors.ink), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Check, null, tint = Kassa.colors.onInk, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/*
 * Choosing an account inside a bottom sheet. The sheet's form is wrapped in [CategoryPickerHost] (or [AccountPickerHost]
 * when it has no categories); an [AccountChoiceRow] in the form opens the picker in place of the form (with a back arrow),
 * and picking returns to the form.
 */

/** What the picker is choosing: the sheet's title for it, what is picked now, who is left out, and what to do with the answer. */
class AccountRequest(
    val title: String,
    val selectedId: Long?,
    val excluded: Set<Long>,
    val recentIds: List<Long>,
    /** Shown first when the field may stay empty ("No account"); picking it hands back null. Null for none. */
    val noneOption: String?,
    val onPick: (Long?) -> Unit,
)

/** Opens the account picker over the sheet's form. Provided by [CategoryPickerHost]; null outside one. */
val LocalAccountPicker = staticCompositionLocalOf<((AccountRequest) -> Unit)?> { null }

/** The picker as a sheet page: back arrow and title, then the same panel the entry page uses. */
@Composable
internal fun AccountPickerPage(accounts: List<Account>, request: AccountRequest, onClose: () -> Unit, modifier: Modifier) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(AccountKindFilter.ALL) }
    val config = LocalConfiguration.current
    // A grid needs a height to fill, and the sheet has none of its own: most of the screen, so the keyboard still fits.
    val height = (config.screenHeightDp * 0.62f).dp
    Column(modifier.height(height), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Chevron(left = true), stringResource(R.string.common_back), tint = Kassa.colors.ink) }
            Text(request.title, style = KassaType.section, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        AccountPickerPanel(
            accounts, request.selectedId, request.excluded, request.recentIds, query, { query = it }, { searching = it },
            filter, { filter = it }, wide = config.screenWidthDp >= 600, onDone = onClose,
            topOption = request.noneOption, onPickNone = { request.onPick(null); onClose() },
            onPick = { id -> request.onPick(id); onClose() },
        )
    }
}

/**
 * An account choice in a form: an icon tile, the small label, the account's name (or [placeholder] while none is
 * chosen) and a chevron. 56dp tall. Tapping opens the picker over the sheet. [noneOption] lets the field be cleared.
 */
@Composable
fun AccountChoiceRow(
    label: String,
    selectedId: Long?,
    accounts: List<Account>,
    onPick: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    excluded: Set<Long> = emptySet(),
    recentIds: List<Long> = emptyList(),
    noneOption: String? = null,
    placeholder: String = stringResource(R.string.common_choose),
) {
    val open = LocalAccountPicker.current
    val chosen = selectedId?.let { id -> accounts.firstOrNull { it.id == id } }
    val value = chosen?.name ?: placeholder
    val description = stringResource(R.string.common_label_value, label, value)
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).border(1.dp, Kassa.colors.line, shape)
            .clickable(role = Role.Button) { open?.invoke(AccountRequest(label, selectedId, excluded, recentIds, noneOption, onPick)) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (chosen != null) IconBadge(accountIcon(chosen.type), size = 36) else GlyphBadge("?", size = 36)
        Column(Modifier.weight(1f)) {
            Text(label, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = KassaType.bodyStrong, color = if (chosen != null) Kassa.colors.ink else Kassa.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Chevron(left = false), null, tint = Kassa.colors.ink3)
    }
}

/** The body of a bottom sheet that picks accounts but no categories; see [CategoryPickerHost], which it is. */
@Composable
fun AccountPickerHost(accounts: List<Account>, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    CategoryPickerHost(emptyList(), modifier, accounts, content)

package dev.krishnagurram.kassa.ui.accounts

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.krishnagurram.kassa.KassaApplication
import dev.krishnagurram.kassa.data.AccountsState
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.ui.common.EntryRow
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.GlyphBadge
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.importer.SecondaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AccountsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as KassaApplication).repository
    val state: StateFlow<AccountsState?> = repo.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun add(account: Account, newGroupName: String?) = viewModelScope.launch { repo.addAccount(account, newGroupName) }
    fun setArchived(account: Account, archived: Boolean) = viewModelScope.launch { repo.updateAccount(account.copy(archived = archived)) }
}

@Composable
fun AccountsScreen(contentPadding: PaddingValues, onImportAgain: () -> Unit, vm: AccountsViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val state = s ?: return
    var adding by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }

    val active = state.accounts.filter { !it.archived }
    val netWorth = active.sumOf { state.balances[it.id] ?: 0 }
    val sections: List<Pair<String, List<Account>>> = buildList {
        state.groups.forEach { g -> active.filter { it.groupId == g.id }.takeIf { it.isNotEmpty() }?.let { add(g.name to it) } }
        active.filter { a -> a.groupId == null || state.groups.none { it.id == a.groupId } }.takeIf { it.isNotEmpty() }?.let { add("Other" to it) }
    }
    val archived = state.accounts.filter { it.archived }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Column(Modifier.widthIn(max = 720.dp)) {
                Text("On this phone only", style = KassaType.small, color = Kassa.colors.ink2)
                Text("Accounts", style = KassaType.title, color = Kassa.colors.ink)
                Gap(18)
                Text("Net worth", style = KassaType.body, color = Kassa.colors.ink2)
                Text(Money.format(netWorth, wholeDollars = true), style = KassaType.hero.copy(fontSize = KassaType.hero.fontSize * 0.8f), color = Kassa.colors.ink)
                Text("Cards and loans count as what you owe. Investments use the cash recorded in each account for now.", style = KassaType.small, color = Kassa.colors.ink2)
                Gap(14)
                SecondaryButton("Add an account", { adding = true })
            }
        }
        sections.forEach { (name, accounts) ->
            item(key = "g-$name") {
                SectionHeader(name, Money.format(accounts.sumOf { state.balances[it.id] ?: 0 }, wholeDollars = true), Modifier.widthIn(max = 720.dp))
            }
            items(accounts, key = { it.id }) { a -> AccountRow(a, state.balances[a.id] ?: 0, last = a === accounts.last()) { vm.setArchived(a, true) } }
        }
        if (archived.isNotEmpty()) item(key = "archived") {
            Column(Modifier.widthIn(max = 720.dp)) {
                TextButton(onClick = { showArchived = !showArchived }) {
                    Text(if (showArchived) "Hide archived (${archived.size})" else "Show archived (${archived.size})", color = Kassa.colors.ink2)
                }
                if (showArchived) archived.forEach { a -> AccountRow(a, state.balances[a.id] ?: 0, last = a === archived.last(), archiveLabel = "Restore") { vm.setArchived(a, false) } }
            }
        }
        item {
            Column(Modifier.widthIn(max = 720.dp).padding(top = 28.dp)) {
                TextButton(onClick = onImportAgain) { Text("Import again from Money Manager", color = Kassa.colors.ink2) }
            }
        }
    }

    if (adding) AddAccountSheet(
        groups = state.groups,
        payFromOptions = active.filter { it.type in listOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH) },
        onDismiss = { adding = false },
        onSave = { account, newGroup -> vm.add(account, newGroup); adding = false },
    )
}

@Composable
private fun AccountRow(a: Account, balance: Long, last: Boolean, archiveLabel: String = "Archive", onArchive: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 720.dp).clickable { open = !open }) {
        val detail = buildList {
            add(a.type.label)
            if (a.statementDay != null && a.dueDay != null) add("statement ${a.statementDay}, due ${a.dueDay}")
        }.joinToString(", ")
        EntryRow(
            badge = { GlyphBadge(a.name.take(1).uppercase()) },
            title = a.name, subtitle = detail,
            amount = Money.format(balance),
            amountColor = if (balance < 0) Kassa.colors.ink2 else Kassa.colors.ink,
            showDivider = !last && !open,
        )
        if (open) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onArchive) { Text(archiveLabel, color = Kassa.colors.ink2) }
            }
            if (!last) HorizontalDivider(color = Kassa.colors.line)
        }
    }
}

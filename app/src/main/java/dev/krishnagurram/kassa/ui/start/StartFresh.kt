package dev.krishnagurram.kassa.ui.start

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.AccountField
import dev.krishnagurram.kassa.domain.AccountForm
import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.CategoryIcon
import dev.krishnagurram.kassa.domain.CategoryKind
import dev.krishnagurram.kassa.ui.accounts.AccountFields
import dev.krishnagurram.kassa.ui.categories.IconCatalog
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.PageTop
import dev.krishnagurram.kassa.ui.entry.Chip
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType

/** A category offered on first run. [fixed] ones are bills, so they don't count toward everyday spending. */
private data class Starter(val name: String, val kind: CategoryKind, val icon: String, val on: Boolean = true, val fixed: Boolean = false)

/** Common categories for someone starting from nothing. All can be renamed or removed later. */
private val starters = listOf(
    Starter("Bills", CategoryKind.EXPENSE, "bills", fixed = true),
    Starter("Car", CategoryKind.EXPENSE, "car"),
    Starter("Dining", CategoryKind.EXPENSE, "restaurant"),
    Starter("Entertainment", CategoryKind.EXPENSE, "movie"),
    Starter("Gifts", CategoryKind.EXPENSE, "gift", on = false),
    Starter("Groceries", CategoryKind.EXPENSE, "cart"),
    Starter("Health", CategoryKind.EXPENSE, "medical"),
    Starter("Home", CategoryKind.EXPENSE, "home", fixed = true),
    Starter("Personal care", CategoryKind.EXPENSE, "haircut", on = false),
    Starter("Shopping", CategoryKind.EXPENSE, "bag"),
    Starter("Subscriptions", CategoryKind.EXPENSE, "subscriptions", fixed = true),
    Starter("Transport", CategoryKind.EXPENSE, "bus"),
    Starter("Travel", CategoryKind.EXPENSE, "flight"),
    Starter("Interest", CategoryKind.INCOME, "savings"),
    Starter("Other income", CategoryKind.INCOME, "money"),
    Starter("Salary", CategoryKind.INCOME, "payments"),
)

/** The account types worth offering before anything else exists. Every type is in Accounts later. */
private val firstTypes = listOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CREDIT_CARD, AccountType.CASH)

/**
 * "Start fresh" on first run: pick starter categories, then optionally one account. [onDone] gets the categories
 * to create and the account (null when skipped). Nothing is saved until then.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartFresh(onDone: (List<Category>, Account?) -> Unit, onBack: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(0) }
    var picked by rememberSaveable { mutableStateOf(starters.filter { it.on }.map { it.name }) }
    var form by remember { mutableStateOf(AccountForm()) }
    var problems by remember { mutableStateOf(emptyMap<AccountField, String>()) }
    BackHandler { if (step == 1) step = 0 else onBack() }

    fun categories(): List<Category> = starters.filter { it.name in picked }.mapIndexed { i, s ->
        Category(name = s.name, kind = s.kind, icon = CategoryIcon.Symbol(s.icon), color = IconCatalog.colors[i % IconCatalog.colors.size], countsTowardDaily = !s.fixed)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding().widthIn(max = 560.dp)) {
        if (step == 0) {
            PageTop("Categories", onBack)
            Text("Pick what to start with. You can rename, add or remove any of them later.", style = KassaType.body, color = Kassa.colors.ink2)
            CategoryKind.entries.forEach { kind ->
                Text(if (kind == CategoryKind.EXPENSE) "Expenses" else "Income", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
                FlowRow {
                    starters.filter { it.kind == kind }.forEach { s ->
                        val on = s.name in picked
                        Chip(s.name, on) { picked = if (on) picked - s.name else picked + s.name }
                    }
                }
            }
            Gap(24)
            PrimaryButton("Next") { step = 1 }
        } else {
            PageTop("Your first account", onBack = { step = 0 })
            Text("Where your money is. Add the rest any time from Accounts.", style = KassaType.body, color = Kassa.colors.ink2)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AccountFields(form, problems, { field, change -> form = change(form); if (field != null) problems = problems - field },
                    groups = emptyList(), payFromOptions = emptyList(), types = firstTypes, showGroup = false)
            }
            Gap(20)
            PrimaryButton("Start using Kassa") {
                problems = form.problems(emptyList())
                if (problems.isEmpty()) onDone(categories(), form.toAccount(Account(name = "", type = form.type), currentBalance = 0))
            }
            TextButton(onClick = { onDone(categories(), null) }, modifier = Modifier.fillMaxWidth()) { Text("Skip for now", color = Kassa.colors.ink2) }
            Gap(24)
        }
    }
}

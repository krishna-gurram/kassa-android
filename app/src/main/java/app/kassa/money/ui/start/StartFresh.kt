package app.kassa.money.ui.start

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountField
import app.kassa.money.domain.AccountForm
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.StarterCategories
import app.kassa.money.ui.accounts.AccountFields
import app.kassa.money.ui.accounts.AccountFormSaver
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.PageTop
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/** The categories offered on first run, shared with iOS (see `StarterCategories` in :domain). */
private val starters = StarterCategories.all

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
    var picked by rememberSaveable { mutableStateOf(StarterCategories.defaultNames()) }
    var form by rememberSaveable(stateSaver = AccountFormSaver) { mutableStateOf(AccountForm()) }
    var problems by remember { mutableStateOf(emptyMap<AccountField, String>()) }
    BackHandler { if (step == 1) step = 0 else onBack() }

    fun categories(): List<Category> = StarterCategories.categories(picked)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding().widthIn(max = 560.dp)) {
        if (step == 0) {
            PageTop(stringResource(R.string.start_categories), onBack, amounts = false)
            Text(stringResource(R.string.start_pick_categories_hint), style = KassaType.body, color = Kassa.colors.ink2)
            CategoryKind.entries.forEach { kind ->
                Text(if (kind == CategoryKind.EXPENSE) stringResource(R.string.start_expenses) else stringResource(R.string.start_income), style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
                FlowRow {
                    starters.filter { it.kind == kind }.forEach { s ->
                        val on = s.name in picked
                        Chip(s.name, on) { picked = if (on) picked - s.name else picked + s.name }
                    }
                }
            }
            Gap(24)
            PrimaryButton(stringResource(R.string.start_next)) { step = 1 }
        } else {
            PageTop(stringResource(R.string.start_first_account), onBack = { step = 0 }, amounts = false)
            Text(stringResource(R.string.start_first_account_hint), style = KassaType.body, color = Kassa.colors.ink2)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AccountFields(form, problems, { field, change -> form = change(form); if (field != null) problems = problems - field },
                    groups = emptyList(), payFromOptions = emptyList(), types = firstTypes, showGroup = false)
            }
            Gap(20)
            PrimaryButton(stringResource(R.string.start_start_using)) {
                problems = form.problems(emptyList())
                if (problems.isEmpty()) onDone(categories(), form.toAccount(Account(name = "", type = form.type), currentBalance = 0))
            }
            TextButton(onClick = { onDone(categories(), null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.start_skip_for_now), color = Kassa.colors.ink2) }
            Gap(24)
        }
    }
}

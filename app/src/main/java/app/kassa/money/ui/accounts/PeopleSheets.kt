package app.kassa.money.ui.accounts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.Money
import app.kassa.money.domain.Person
import app.kassa.money.ui.common.AmountField
import app.kassa.money.ui.common.ConfirmDialog
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.NameField
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.importer.SecondaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/** True when another person (not [except]) already has this name, ignoring case. */
private fun List<Person>.hasName(name: String, except: Long? = null) = any { it.id != except && it.name.equals(name, ignoreCase = true) }

/**
 * A new person: their name and, if money is already open between you, who owes whom.
 * [onAdd] gets the cleaned name and the opening balance (positive when they owe you), and a callback for once it's saved.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AddPersonSheet(people: List<Person>, onDismiss: () -> Unit, onAdd: (String, Long, () -> Unit) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var theyOwe by rememberSaveable { mutableStateOf(true) }
    var amount by rememberSaveable { mutableStateOf("") }
    var nameProblem by rememberSaveable { mutableStateOf<String?>(null) }
    var amountProblem by rememberSaveable { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Kassa.colors.raised) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.accounts_add_a_person), style = KassaType.section, color = Kassa.colors.ink)
            Gap(12)
            NameField(name, { name = it; nameProblem = null }, stringResource(R.string.accounts_name), Modifier.fillMaxWidth(), problem = nameProblem)
            Gap(4)
            Text(stringResource(R.string.accounts_already_owed_optional), style = KassaType.tiny, color = Kassa.colors.ink2)
            FlowRow(Modifier.padding(top = 6.dp)) {
                Chip(stringResource(R.string.accounts_they_owe_me), selected = theyOwe) { theyOwe = true }
                Chip(stringResource(R.string.accounts_i_owe_them), selected = !theyOwe) { theyOwe = false }
            }
            AmountField(amount, { amount = it; amountProblem = null }, stringResource(R.string.accounts_amount), Modifier.fillMaxWidth(), problem = amountProblem)
            Gap(16)
            PrimaryButton(stringResource(R.string.accounts_add_person)) {
                val cents = if (amount.isBlank()) 0L else Money.parseCents(amount)
                nameProblem = InputRules.nameProblem(name, "person", taken = { people.hasName(it) })
                amountProblem = InputRules.amountProblem(cents, zeroAllowed = true)
                if (nameProblem == null && amountProblem == null) {
                    onAdd(InputRules.clean(name), if (theyOwe) cents!! else -cents!!, onDismiss)
                }
            }
        }
    }
}

/**
 * Rename a person, change what was owed when you added them, or archive them once you're square.
 * [starting] is that amount today (positive: they owe you). Their splits and payments stay either way.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EditPersonSheet(
    p: Person,
    balance: Long,
    starting: Long,
    people: List<Person>,
    onDismiss: () -> Unit,
    onSave: (Person, Long) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(p.name.take(InputRules.NAME_MAX)) }
    var theyOwe by rememberSaveable { mutableStateOf(starting >= 0) }
    var amount by rememberSaveable { mutableStateOf(if (starting == 0L) "" else Money.toInput(Math.abs(starting))) }
    var nameProblem by rememberSaveable { mutableStateOf<String?>(null) }
    var amountProblem by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmArchive by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Kassa.colors.raised) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.accounts_edit_person), style = KassaType.section, color = Kassa.colors.ink)
            Gap(12)
            NameField(name, { name = it; nameProblem = null }, stringResource(R.string.accounts_name), Modifier.fillMaxWidth(), problem = nameProblem)
            Gap(4)
            Text(stringResource(R.string.accounts_already_owed_when_added), style = KassaType.tiny, color = Kassa.colors.ink2)
            FlowRow(Modifier.padding(top = 6.dp)) {
                Chip(stringResource(R.string.accounts_they_owe_me), selected = theyOwe) { theyOwe = true }
                Chip(stringResource(R.string.accounts_i_owe_them), selected = !theyOwe) { theyOwe = false }
            }
            AmountField(amount, { amount = it; amountProblem = null }, stringResource(R.string.accounts_amount), Modifier.fillMaxWidth(), problem = amountProblem, placeholder = "0")
            Text(stringResource(R.string.accounts_edit_person_note), style = KassaType.small, color = Kassa.colors.ink2)
            Gap(16)
            PrimaryButton(stringResource(R.string.accounts_save)) {
                val cents = if (amount.isBlank()) 0L else Money.parseCents(amount)
                nameProblem = InputRules.nameProblem(name, "person", taken = { people.hasName(it, except = p.id) })
                amountProblem = InputRules.amountProblem(cents, zeroAllowed = true)
                if (nameProblem == null && amountProblem == null) onSave(p.copy(name = InputRules.clean(name)), if (theyOwe) cents!! else -cents!!)
            }
            Gap(8)
            if (p.archived) SecondaryButton(stringResource(R.string.accounts_restore), { onSave(p.copy(archived = false), starting) })
            // Archived people don't count in Receivable or Payable, so only a settled person can be archived.
            else if (balance != 0L) Text(
                stringResource(R.string.accounts_person_archive_blocked, p.name, Money.format(Math.abs(balance))),
                style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 4.dp),
            )
            else SecondaryButton(stringResource(R.string.accounts_archive), { confirmArchive = true })
        }
    }
    if (confirmArchive) ConfirmDialog(
        title = stringResource(R.string.accounts_archive_title, p.name),
        body = stringResource(R.string.accounts_person_archive_body, p.name),
        confirm = stringResource(R.string.accounts_archive),
        onConfirm = { onSave(p.copy(archived = true), starting) },
        onDismiss = { confirmArchive = false },
    )
}

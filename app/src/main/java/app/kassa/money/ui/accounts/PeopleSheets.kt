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
import androidx.compose.ui.unit.dp
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
            Text("Add a person", style = KassaType.section, color = Kassa.colors.ink)
            Gap(12)
            NameField(name, { name = it; nameProblem = null }, "Name", Modifier.fillMaxWidth(), problem = nameProblem)
            Gap(4)
            Text("Already owed (optional)", style = KassaType.tiny, color = Kassa.colors.ink2)
            FlowRow(Modifier.padding(top = 6.dp)) {
                Chip("They owe me", selected = theyOwe) { theyOwe = true }
                Chip("I owe them", selected = !theyOwe) { theyOwe = false }
            }
            AmountField(amount, { amount = it; amountProblem = null }, "Amount", Modifier.fillMaxWidth(), problem = amountProblem)
            Gap(16)
            PrimaryButton("Add person") {
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
            Text("Edit person", style = KassaType.section, color = Kassa.colors.ink)
            Gap(12)
            NameField(name, { name = it; nameProblem = null }, "Name", Modifier.fillMaxWidth(), problem = nameProblem)
            Gap(4)
            Text("Already owed when you added them", style = KassaType.tiny, color = Kassa.colors.ink2)
            FlowRow(Modifier.padding(top = 6.dp)) {
                Chip("They owe me", selected = theyOwe) { theyOwe = true }
                Chip("I owe them", selected = !theyOwe) { theyOwe = false }
            }
            AmountField(amount, { amount = it; amountProblem = null }, "Amount", Modifier.fillMaxWidth(), problem = amountProblem, placeholder = "0")
            Text("Changes the starting balance line. Splits and payments stay.", style = KassaType.small, color = Kassa.colors.ink2)
            Gap(16)
            PrimaryButton("Save") {
                val cents = if (amount.isBlank()) 0L else Money.parseCents(amount)
                nameProblem = InputRules.nameProblem(name, "person", taken = { people.hasName(it, except = p.id) })
                amountProblem = InputRules.amountProblem(cents, zeroAllowed = true)
                if (nameProblem == null && amountProblem == null) onSave(p.copy(name = InputRules.clean(name)), if (theyOwe) cents!! else -cents!!)
            }
            Gap(8)
            if (p.archived) SecondaryButton("Restore", { onSave(p.copy(archived = false), starting) })
            // Archived people don't count in Receivable or Payable, so only a settled person can be archived.
            else if (balance != 0L) Text(
                "To archive ${p.name}, settle the ${Money.format(Math.abs(balance))} first or set the starting balance so nothing is open.",
                style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 4.dp),
            )
            else SecondaryButton("Archive", { confirmArchive = true })
        }
    }
    if (confirmArchive) ConfirmDialog(
        title = "Archive ${p.name}?",
        body = "${p.name} leaves the people list and the pickers. The history stays.",
        confirm = "Archive",
        onConfirm = { onSave(p.copy(archived = true), starting) },
        onDismiss = { confirmArchive = false },
    )
}

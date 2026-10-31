package app.kassa.money.ui.common

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.Money

/*
 * The form fields every sheet and page uses. Each one enforces InputRules as you type, so a field can never hold
 * something the database would refuse, and shows its problem underneath in the same place everywhere.
 */

/** A one-line name. Stops at [max] characters and shows the count once you're close. */
@Composable
fun NameField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    max: Int = InputRules.NAME_MAX,
    problem: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(InputRules.typed(it, max)) },
        label = { Text(label) },
        singleLine = true,
        isError = problem != null,
        supportingText = when {
            problem != null -> { { Text(problem, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
            value.length >= max - 10 -> { { Text("${value.length}/$max") } }
            else -> null
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = modifier,
    )
}

/**
 * An amount in the phone's currency: digits and one point, at most [InputRules.AMOUNT_DIGITS] whole digits.
 * [signed] also allows a leading minus, for a balance that can be overdrawn.
 */
@Composable
fun AmountField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    problem: String? = null,
    placeholder: String? = null,
    signed: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(if (signed) InputRules.typedBalance(it) else InputRules.typedAmount(it)) },
        label = { Text(label) },
        prefix = { Text(Money.symbol) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = problem != null,
        supportingText = problem?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** A short number typed as digits only, like the last 4 of a card or a day of the month. */
@Composable
fun DigitsField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    digits: Int,
    modifier: Modifier = Modifier,
    problem: String? = null,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(InputRules.typedDigits(it, digits)) },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = problem != null,
        supportingText = problem?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier,
    )
}

/** A backup password, hidden as it's typed. Used wherever a .kassa file is saved or opened with one. */
@Composable
fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    problem: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(InputRules.typed(it, PASSWORD_MAX)) },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        isError = problem != null,
        supportingText = problem?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = modifier,
    )
}

private const val PASSWORD_MAX = 128

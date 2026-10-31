package app.kassa.money.ui.loans

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import app.kassa.money.domain.IslamicContract
import app.kassa.money.domain.LoanDetailsForm
import app.kassa.money.domain.LoanForm
import app.kassa.money.domain.LoanKind
import app.kassa.money.domain.LoanStage
import app.kassa.money.domain.PaymentBasis

/*
 * What is typed into the loan sheets survives folding or rotating the phone. Each form is kept as a list of plain strings
 * (a saved list can't hold a null, so an empty string stands for "none"); a list that doesn't read back, say from an older
 * version of Kassa, simply starts the form again.
 */

private fun Long?.text() = this?.toString().orEmpty()
private fun Int?.text() = this?.toString().orEmpty()

/** Saves the "Add terms" form. */
internal val LoanFormSaver: Saver<LoanForm, Any> = listSaver(
    save = { f ->
        listOf(
            f.stage.name, f.stageLocked.toString(), f.kind.name, f.contract.name, f.amount, f.rate, f.termMonths, f.firstDay.text(),
            f.balance, f.payment, f.nextDay.text(), f.paymentsLeft, f.original, f.startDay.text(), f.dueDay, f.payFromId.text(),
            f.escrow, f.fee, f.earlyCharge, f.interestCategoryId.text(), f.countAllAsSpending.toString(), f.principalCategoryId.text(),
            f.paymentBasis.name,
        )
    },
    restore = { v ->
        val first = v.getOrNull(7)?.toIntOrNull()
        val next = v.getOrNull(10)?.toIntOrNull()
        if (v.size < 22 || first == null || next == null) null
        else LoanForm(
            stage = LoanStage.entries.firstOrNull { it.name == v[0] } ?: LoanStage.NEW,
            stageLocked = v[1].toBooleanStrictOrNull() ?: false,
            kind = LoanKind.entries.firstOrNull { it.name == v[2] } ?: LoanKind.REDUCING,
            contract = IslamicContract.entries.firstOrNull { it.name == v[3] } ?: IslamicContract.MURABAHA,
            amount = v[4], rate = v[5], termMonths = v[6], firstDay = first,
            balance = v[8], payment = v[9], nextDay = next, paymentsLeft = v[11], original = v[12],
            startDay = v[13].toIntOrNull(), dueDay = v[14], payFromId = v[15].toLongOrNull(),
            escrow = v[16], fee = v[17], earlyCharge = v[18], interestCategoryId = v[19].toLongOrNull(),
            countAllAsSpending = v[20].toBooleanStrictOrNull() ?: false, principalCategoryId = v[21].toLongOrNull(),
            // Added after the first saved lists, so an older one has no 23rd entry and keeps the default.
            paymentBasis = PaymentBasis.entries.firstOrNull { it.name == v.getOrNull(22) } ?: PaymentBasis.PRINCIPAL_AND_INTEREST,
        )
    },
)

/** Saves the "Edit details" form. */
internal val LoanDetailsFormSaver: Saver<LoanDetailsForm, Any> = listSaver(
    save = { f ->
        listOf(
            f.name, f.dueDay, f.payFromId.text(), f.interestCategoryId.text(), f.countAllAsSpending.toString(), f.principalCategoryId.text(),
            f.escrow, f.fee, f.earlyCharge, f.original, f.startDay.text(),
        )
    },
    restore = { v ->
        if (v.size < 11) null
        else LoanDetailsForm(
            name = v[0], dueDay = v[1], payFromId = v[2].toLongOrNull(), interestCategoryId = v[3].toLongOrNull(),
            countAllAsSpending = v[4].toBooleanStrictOrNull() ?: false, principalCategoryId = v[5].toLongOrNull(),
            escrow = v[6], fee = v[7], earlyCharge = v[8], original = v[9], startDay = v[10].toIntOrNull(),
        )
    },
)

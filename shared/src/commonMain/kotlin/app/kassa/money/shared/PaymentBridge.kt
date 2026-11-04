package app.kassa.money.shared

import app.kassa.money.domain.CardPayment
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.PersonPayment
import app.kassa.money.domain.TxKind

/*
 * Paying a card and money between you and a person (iOS stage F): what Swift calls to check and build the rows it writes. The rules
 * are :domain's (`CardPayment`, `PersonPayment`); like `WriteBridge.kt` this only turns them into flat values (Strings, Longs, 0 for
 * "none", kinds as `TxKind` names). A card payment is an ordinary TRANSFER entry (`EntryBridge`), so only its extra rules are here.
 */

private fun payKind(name: String): TxKind? = TxKind.entries.firstOrNull { it.name == name }

private fun payId(id: Long): Long? = if (id > 0) id else null

/** What the pay-card sheet needs besides `EntryBridge`: the stored note and the sheet's own problems. */
object CardPaymentBridge {

    /** The note a card payment is stored with ("Card payment", as on Android). */
    fun note(): String = CardPayment.NOTE

    /** The first problem of paying [cents] from [payFromId] (0 for none): an `EntryField` name (AMOUNT or ACCOUNT) and the sentence; null when it can be saved. */
    fun problem(cents: Long, payFromId: Long, currency: String): FieldProblemData? = withMoney(currency) {
        CardPayment.problem(cents, payId(payFromId))?.let { FieldProblemData(it.first.name, it.second) }
    }
}

/** One payment to or from a person as the sheet has it: flat, 0 means "none", [kind] is PERSON_IN or PERSON_OUT, [id] 0 for a new payment. */
class PersonPaymentInput(
    val id: Long,
    val personId: Long,
    val kind: String,
    val amount: Long,
    val accountId: Long,
    val epochMillis: Long,
    val note: String,
) {
    internal fun draft(): PersonPayment.Draft? {
        val k = payKind(kind) ?: return null
        return PersonPayment.Draft(payId(id), personId, k, amount, payId(accountId), epochMillis, note)
    }
}

/** What the person payment sheet needs from the rules. See `PersonPayment`. */
object PersonPaymentBridge {

    /** The first problem of [input] (an `EntryField` name and the sentence), or null when it can be saved. [currency] words amounts. */
    fun problem(input: PersonPaymentInput, currency: String): FieldProblemData? = withMoney(currency) {
        val draft = input.draft()
            ?: return@withMoney FieldProblemData("AMOUNT", "That kind of entry can't be saved here.")
        PersonPayment.problem(draft)?.let { FieldProblemData(it.first.name, it.second) }
    }

    /** True for PERSON_IN and PERSON_OUT. */
    fun isPaymentKind(kind: String): Boolean = payKind(kind)?.let { PersonPayment.isPaymentKind(it) } ?: false

    /** The note to store: the user's own stays while the direction is unchanged ([editingKind] and [editingNote] are "" for a new payment), else "<Name> paid you" or "You paid <Name>". */
    fun note(kind: String, personName: String, editingKind: String, editingNote: String): String {
        val k = payKind(kind) ?: TxKind.PERSON_IN
        return PersonPayment.noteFor(k, personName, payKind(editingKind), editingNote.ifEmpty { null })
    }

    /** One cell of the `transactions` row (the columns of `BackupRows`): null for the id and for what a payment has no use for (destination, category, split share, external id). */
    fun cell(input: PersonPaymentInput, column: Int): String? {
        val d = input.draft() ?: return null
        return when (BackupRows.columns("transactions").getOrNull(column)) {
            "kind" -> d.kind.name
            "amount" -> d.amount.toString()
            "accountId" -> d.accountId?.toString()
            "personId" -> d.personId.toString()
            "epochMillis" -> d.epochMillis.toString()
            "note" -> InputRules.clean(d.note)
            else -> null
        }
    }

    /** True for the columns an edit rewrites: kind, amount, account, date and note. The person stays, and an imported payment keeps its external id. */
    fun ownsColumn(column: Int): Boolean =
        BackupRows.columns("transactions").getOrNull(column) in setOf("kind", "amount", "accountId", "epochMillis", "note")

    /** The accounts that must be open for this save, as decimal text: the one it newly touches. [beforeAccountId] is the stored payment's (0 for a new one). */
    fun mustBeOpen(input: PersonPaymentInput, beforeAccountId: Long): List<String> =
        PersonPayment.newlyTouched(payId(input.accountId), payId(beforeAccountId)).map { it.toString() }
}

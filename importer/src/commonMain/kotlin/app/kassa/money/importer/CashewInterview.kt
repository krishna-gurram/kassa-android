package app.kassa.money.importer

import app.kassa.money.domain.AccountType

/**
 * The questions a Cashew backup needs answered before its review, in order, as Android's import screen asks them: what each
 * account is, what each loan is, and what happens to each account in another currency. Plain Kotlin so every platform asks
 * the same questions in the same order (Android keeps the same logic in its view model).
 */
class CashewInterview(val scan: CashewScan, phoneCurrency: String) {

    enum class Step { FOUND, ACCOUNTS, LOANS, CURRENCY }

    /** The phone's currency, or the backup's own when the phone has none yet (so nothing reads as another currency by accident). */
    private val phone: String = phoneCurrency.ifBlank { scan.mainCurrency.orEmpty() }

    var choices: CashewChoices = CashewChoices()
        private set

    var step: Step = Step.FOUND
        private set

    /** Which account in another currency is being asked about (index into [otherCurrency]). */
    var currencyIndex: Int = 0
        private set

    val otherCurrency: List<CashewScan.Wallet> get() = scan.otherCurrency(phone)

    fun setAccountType(pk: String, type: AccountType) {
        choices = choices.copy(accountTypes = choices.accountTypes + (pk to type))
    }

    fun setLoan(pk: String, choice: LoanChoice) {
        choices = choices.copy(loans = choices.loans + (pk to choice))
    }

    fun setLeaveOut(pk: String, leaveOut: Boolean) {
        choices = choices.copy(leaveOut = if (leaveOut) choices.leaveOut + pk else choices.leaveOut - pk)
    }

    /** Whether the current question is answered so the next button may show. */
    fun canGoOn(): Boolean = when (step) {
        Step.FOUND -> true
        Step.ACCOUNTS -> scan.accounts.all { choices.accountTypes[it.pk] != null }
        Step.LOANS -> scan.loans.all { choices.loans[it.pk] != null }
        Step.CURRENCY -> true
    }

    /** Moves to the next question; true when every question is answered and the plan can be made. */
    fun next(): Boolean {
        when (step) {
            Step.FOUND -> step = Step.ACCOUNTS
            Step.ACCOUNTS -> if (scan.loans.isNotEmpty()) step = Step.LOANS else if (!startCurrency()) return true
            Step.LOANS -> if (!startCurrency()) return true
            Step.CURRENCY -> if (currencyIndex + 1 < otherCurrency.size) currencyIndex++ else return true
        }
        return false
    }

    private fun startCurrency(): Boolean {
        if (otherCurrency.isEmpty()) return false
        step = Step.CURRENCY
        currencyIndex = 0
        return true
    }

    /** Back one question; true when that leaves the first one (back to picking a file). */
    fun back(): Boolean {
        when (step) {
            Step.FOUND -> return true
            Step.ACCOUNTS -> step = Step.FOUND
            Step.LOANS -> step = Step.ACCOUNTS
            Step.CURRENCY -> if (currencyIndex > 0) currencyIndex-- else step = if (scan.loans.isNotEmpty()) Step.LOANS else Step.ACCOUNTS
        }
        return false
    }

    /** The accounts in another currency that stay in (not left out), for the review's note. */
    fun otherCurrencyKept(): List<String> = otherCurrency.filter { it.pk !in choices.leaveOut }.map { it.name }

    /** The plan for the answers given, with the other-currency note Android adds. */
    fun plan(source: CashewSource, importer: CashewImporter): ImportPlan {
        val plan = importer.plan(source, choices)
        val other = otherCurrencyKept()
        return if (other.isEmpty()) plan else plan.copy(issues = plan.issues + ImportIssue.OtherCurrency(other))
    }
}

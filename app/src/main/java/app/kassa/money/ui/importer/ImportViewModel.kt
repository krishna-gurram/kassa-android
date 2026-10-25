package app.kassa.money.ui.importer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.kassa.money.KassaApplication
import app.kassa.money.data.PickedFile
import app.kassa.money.data.cashew.AndroidCashewSource
import app.kassa.money.data.mm.AndroidMoneyManagerSource
import app.kassa.money.data.mm.NotAMoneyManagerBackup
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Currencies
import app.kassa.money.importer.CashewChoices
import app.kassa.money.importer.CashewImporter
import app.kassa.money.importer.CashewScan
import app.kassa.money.importer.ImportPlan
import app.kassa.money.importer.LoanChoice
import app.kassa.money.importer.MoneyManagerImporter
import app.kassa.money.importer.NotReadableBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ImportState {
    data object Idle : ImportState
    data object Reading : ImportState
    /** A Cashew backup, while its questions are answered: [step] says which. */
    data class Cashew(val scan: CashewScan, val choices: CashewChoices, val step: CashewStep, val phoneCurrency: String) : ImportState {
        val otherCurrency get() = scan.otherCurrency(phoneCurrency)
    }
    data class Review(val plan: ImportPlan) : ImportState
    data object Saving : ImportState
    data class Done(val entries: Int, val plan: ImportPlan) : ImportState
    data class Failed(val message: String) : ImportState
}

/** The Cashew questions, in order. [Currency] repeats for each account in another currency. */
sealed interface CashewStep {
    data object Found : CashewStep
    data object Accounts : CashewStep
    data object Loans : CashewStep
    data class Currency(val index: Int) : CashewStep
}

class ImportViewModel(app: Application) : AndroidViewModel(app) {
    private val kassa = app as KassaApplication
    private val imports = kassa.imports
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state
    private var cashew: AndroidCashewSource? = null

    fun read(uri: Uri) {
        closeCashew()
        _state.value = ImportState.Reading
        viewModelScope.launch {
            _state.value = try {
                withContext(Dispatchers.IO) {
                    when (val picked = PickedFile.identify(getApplication(), uri)) {
                        PickedFile.MoneyManager -> AndroidMoneyManagerSource.open(getApplication(), uri).use { src ->
                            // Show amounts in the backup's own currency when Kassa knows it; it can still be changed.
                            src.mainCurrency()?.let(::useCurrency)
                            ImportState.Review(MoneyManagerImporter().plan(src))
                        }
                        is PickedFile.Cashew -> {
                            val src = AndroidCashewSource.open(picked.copy).also { cashew = it }
                            val scan = CashewImporter().scan(src)
                            scan.mainCurrency?.let(::useCurrency)
                            ImportState.Cashew(scan, CashewChoices(), CashewStep.Found, kassa.settings.currency.value)
                        }
                    }
                }
            } catch (e: NotReadableBackup) {
                ImportState.Failed(e.message.orEmpty())
            } catch (e: NotAMoneyManagerBackup) {
                ImportState.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                ImportState.Failed("Couldn't read that file: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun useCurrency(code: String) {
        code.uppercase().takeIf { c -> Currencies.all.any { it.code == c } }?.let { kassa.settings.setCurrency(it) }
    }

    /* ---------- Cashew questions ---------- */

    private fun cashewState() = _state.value as? ImportState.Cashew

    fun setAccountType(walletPk: String, type: AccountType) = cashewState()?.let {
        _state.value = it.copy(choices = it.choices.copy(accountTypes = it.choices.accountTypes + (walletPk to type)))
    }

    fun setLoan(loanPk: String, choice: LoanChoice) = cashewState()?.let {
        _state.value = it.copy(choices = it.choices.copy(loans = it.choices.loans + (loanPk to choice)))
    }

    fun setLeaveOut(walletPk: String, leaveOut: Boolean) = cashewState()?.let {
        val set = if (leaveOut) it.choices.leaveOut + walletPk else it.choices.leaveOut - walletPk
        _state.value = it.copy(choices = it.choices.copy(leaveOut = set))
    }

    /** Moves to the next question, or to the review once all are answered. */
    fun next() {
        val s = cashewState() ?: return
        val step = when (val st = s.step) {
            CashewStep.Found -> CashewStep.Accounts
            CashewStep.Accounts -> if (s.scan.loans.isNotEmpty()) CashewStep.Loans else firstCurrency(s)
            CashewStep.Loans -> firstCurrency(s)
            is CashewStep.Currency -> if (st.index + 1 < s.otherCurrency.size) CashewStep.Currency(st.index + 1) else null
        }
        if (step != null) { _state.value = s.copy(step = step); return }
        planCashew(s)
    }

    /** Back one question; from the first, back to picking a file. */
    fun back() {
        val s = cashewState() ?: return reset()
        val step = when (val st = s.step) {
            CashewStep.Found -> null
            CashewStep.Accounts -> CashewStep.Found
            CashewStep.Loans -> CashewStep.Accounts
            is CashewStep.Currency -> if (st.index > 0) CashewStep.Currency(st.index - 1) else if (s.scan.loans.isNotEmpty()) CashewStep.Loans else CashewStep.Accounts
        }
        if (step == null) reset() else _state.value = s.copy(step = step)
    }

    private fun firstCurrency(s: ImportState.Cashew): CashewStep? = if (s.otherCurrency.isNotEmpty()) CashewStep.Currency(0) else null

    private fun planCashew(s: ImportState.Cashew) {
        val src = cashew ?: return reset()
        _state.value = ImportState.Reading
        viewModelScope.launch {
            _state.value = try {
                val plan = withContext(Dispatchers.Default) { CashewImporter().plan(src, s.choices) }
                val other = s.otherCurrency.filter { it.pk !in s.choices.leaveOut }.map { it.name }
                ImportState.Review(if (other.isEmpty()) plan else plan.copy(issues = plan.issues + app.kassa.money.importer.ImportIssue.OtherCurrency(other)))
            } catch (e: Exception) {
                ImportState.Failed("Couldn't read that file: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun confirm() {
        val plan = (_state.value as? ImportState.Review)?.plan ?: return
        _state.value = ImportState.Saving
        viewModelScope.launch {
            _state.value = try {
                withContext(Dispatchers.IO) { imports.replaceWith(plan) }
                closeCashew()
                ImportState.Done(plan.transactions.size, plan)
            } catch (e: Exception) {
                ImportState.Failed("Nothing was saved. ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun reset() {
        closeCashew()
        _state.value = ImportState.Idle
    }

    private fun closeCashew() {
        runCatching { cashew?.close() }
        cashew = null
    }

    override fun onCleared() = closeCashew()
}

/** Debug builds only: a file the CI walkthrough asked the Import page to open, as if the user had picked it. */
object DebugImport {
    @Volatile var pending: Uri? = null
    fun take(): Uri? = pending.also { pending = null }
}

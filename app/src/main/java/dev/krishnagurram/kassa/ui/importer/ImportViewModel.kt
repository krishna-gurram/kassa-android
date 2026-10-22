package dev.krishnagurram.kassa.ui.importer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.krishnagurram.kassa.KassaApplication
import dev.krishnagurram.kassa.data.mm.AndroidMoneyManagerSource
import dev.krishnagurram.kassa.data.mm.NotAMoneyManagerBackup
import dev.krishnagurram.kassa.importer.ImportPlan
import dev.krishnagurram.kassa.importer.MoneyManagerImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ImportState {
    data object Idle : ImportState
    data object Reading : ImportState
    data class Review(val plan: ImportPlan) : ImportState
    data object Saving : ImportState
    data class Done(val entries: Int) : ImportState
    data class Failed(val message: String) : ImportState
}

class ImportViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = (app as KassaApplication).repository
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state

    fun read(uri: Uri) {
        _state.value = ImportState.Reading
        viewModelScope.launch {
            _state.value = try {
                val plan = withContext(Dispatchers.IO) {
                    AndroidMoneyManagerSource.open(getApplication(), uri).use { MoneyManagerImporter().plan(it) }
                }
                ImportState.Review(plan)
            } catch (e: NotAMoneyManagerBackup) {
                ImportState.Failed(e.message.orEmpty())
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
                withContext(Dispatchers.IO) { repository.replaceWith(plan) }
                ImportState.Done(plan.transactions.size)
            } catch (e: Exception) {
                ImportState.Failed("Nothing was saved. ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun reset() { _state.value = ImportState.Idle }
}

package com.homefinance.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homefinance.app.HomeFinanceApp
import com.homefinance.app.core.util.DocumentStore
import com.homefinance.app.data.repository.DataTransferRepository
import com.homefinance.app.data.repository.ImportPlan
import com.homefinance.app.data.repository.ImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What an export has to report. */
sealed interface ExportMessage {
    /** The file was written; [rows] is how many transactions it holds. */
    data class Done(val rows: Int) : ExportMessage

    /** Generating or writing failed. No file was promised, so nothing is claimed. */
    data object Failed : ExportMessage
}

/** The export screen's state. */
data class ExportUiState(
    val isWorking: Boolean = false,
    val message: ExportMessage? = null,
)

/**
 * Backs Export Data.
 *
 * The rows are read and encoded by [DataTransferRepository], and the resulting text is written to the
 * URI the system file picker returned. The success message counts what the database actually held at
 * that moment, not what a preview said it would hold.
 */
class ExportViewModel(
    private val transfers: DataTransferRepository,
    private val documents: DocumentStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    fun export(uri: String) {
        if (_uiState.value.isWorking) return

        _uiState.update { it.copy(isWorking = true, message = null) }
        viewModelScope.launch {
            try {
                val export = transfers.exportCsv()
                documents.writeText(uri, export.text)
                _uiState.update { it.copy(isWorking = false, message = ExportMessage.Done(export.rows)) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _uiState.update { it.copy(isWorking = false, message = ExportMessage.Failed) }
            }
        }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    companion object {

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) { "ExportViewModel needs the Application to reach the repository" }

                val app = application as HomeFinanceApp
                ExportViewModel(transfers = app.dataTransferRepository, documents = app.documentStore)
            }
        }

        fun factory(
            transfers: DataTransferRepository,
            documents: DocumentStore,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ExportViewModel(transfers = transfers, documents = documents) }
        }
    }
}

/** What an import has to report. */
sealed interface ImportMessage {
    /** The import finished; [result] is what the database wrote and skipped. */
    data class Done(val result: ImportResult) : ImportMessage

    /** The import itself failed and was rolled back. */
    data object Failed : ImportMessage

    /** The chosen file could not be read at all. */
    data object ReadFailed : ImportMessage
}

/** The import screen's state: either a preview to confirm, or a message about the last attempt. */
data class ImportUiState(
    val isWorking: Boolean = false,
    val plan: ImportPlan? = null,
    val message: ImportMessage? = null,
)

/**
 * Backs Import Data.
 *
 * ### Nothing is written until the plan is confirmed
 *
 * [onFilePicked] reads and validates only; the preview it produces states how many rows are ready,
 * how many are already in the ledger and what is wrong with the rest. [confirmImport] writes exactly
 * those rows in one transaction. A file that cannot be read or understood never reaches a write at all.
 */
class ImportViewModel(
    private val transfers: DataTransferRepository,
    private val documents: DocumentStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    fun onFilePicked(uri: String) {
        if (_uiState.value.isWorking) return

        _uiState.update { it.copy(isWorking = true, plan = null, message = null) }
        viewModelScope.launch {
            try {
                val text = documents.readText(uri)
                val plan = transfers.prepareImport(text)
                _uiState.update { it.copy(isWorking = false, plan = plan) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _uiState.update { it.copy(isWorking = false, message = ImportMessage.ReadFailed) }
            }
        }
    }

    /**
     * Writes the previewed rows.
     *
     * The plan is cleared first, so a second tap cannot write the same rows twice while the first is
     * still in flight; the repository's own duplicate check protects the remaining race.
     */
    fun confirmImport() {
        val plan = _uiState.value.plan ?: return
        if (_uiState.value.isWorking || !plan.isValid) return

        _uiState.update { it.copy(isWorking = true, plan = null) }
        viewModelScope.launch {
            try {
                val result = transfers.commitImport(plan)
                _uiState.update { it.copy(isWorking = false, message = ImportMessage.Done(result)) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _uiState.update { it.copy(isWorking = false, message = ImportMessage.Failed) }
            }
        }
    }

    /** Discards a preview without importing anything. */
    fun dismissPlan() {
        _uiState.update { it.copy(plan = null) }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    companion object {

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) { "ImportViewModel needs the Application to reach the repository" }

                val app = application as HomeFinanceApp
                ImportViewModel(transfers = app.dataTransferRepository, documents = app.documentStore)
            }
        }

        fun factory(
            transfers: DataTransferRepository,
            documents: DocumentStore,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ImportViewModel(transfers = transfers, documents = documents) }
        }
    }
}

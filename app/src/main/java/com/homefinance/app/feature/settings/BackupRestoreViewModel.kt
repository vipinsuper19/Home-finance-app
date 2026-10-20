package com.homefinance.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homefinance.app.HomeFinanceApp
import com.homefinance.app.core.util.DocumentStore
import com.homefinance.app.data.model.AppPreferences
import com.homefinance.app.data.repository.BackupRepository
import com.homefinance.app.data.repository.RestoreInspection
import com.homefinance.app.data.repository.RestoreOutcome
import com.homefinance.app.data.repository.RestoreSummary
import com.homefinance.app.data.repository.SettingsRepository
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Backup & Restore screen has to say after an operation. */
sealed interface BackupMessage {

    /** The file was written and its size and time were recorded. */
    data object Created : BackupMessage

    /** Writing the file failed. Nothing was recorded, so the screen keeps stating the old backup. */
    data object CreateFailed : BackupMessage

    /** The chosen restore file could not be read at all. */
    data object ReadFailed : BackupMessage

    /** The file was read but is not a valid backup; [errors] says why, and nothing was written. */
    data class Rejected(val errors: List<String>) : BackupMessage

    /** The restore replaced the data; [summary] is what arrived. */
    data class Restored(val summary: RestoreSummary) : BackupMessage

    /** The restore itself failed before it could report a validation problem. Nothing was written. */
    data object RestoreFailed : BackupMessage
}

/** Which part of the backup flow is currently running, so the progress line can say so. */
enum class BackupWork {
    Creating,
    Reading,
    Restoring,
}

/** Everything the Backup & Restore screen draws. */
data class BackupRestoreUiState(
    val isWorking: Boolean = false,
    val work: BackupWork? = null,
    val pendingRestore: RestoreInspection.Valid? = null,
    val message: BackupMessage? = null,
)

/**
 * Backs Backup & Restore.
 *
 * ### The order of operations is the safety
 *
 * A backup is generated, **then** written to the destination the household picked, and only after the
 * write returns is the timestamp and size recorded. Recording first would let a failed write leave the
 * screen claiming a backup exists. A restore is read and validated by [BackupRepository.inspect] and
 * held as a pending document; nothing is deleted until the household confirms, and the confirmation
 * names exactly how many rows it is about to replace.
 *
 * The file bytes are handled by [DocumentStore], so this class never knows a path and a JVM test can
 * drive the whole flow with an in-memory store.
 */
class BackupRestoreViewModel(
    private val backups: BackupRepository,
    private val settings: SettingsRepository,
    private val documents: DocumentStore,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {

    /** The last backup facts, from the same preference flow the rest of Settings reads. */
    val preferences: StateFlow<AppPreferences> = settings.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = AppPreferences(),
    )

    private val _uiState = MutableStateFlow(BackupRestoreUiState())
    val uiState: StateFlow<BackupRestoreUiState> = _uiState.asStateFlow()

    /**
     * Builds a backup and writes it to [uri], which is a destination the household chose through the
     * system file picker.
     *
     * A failure at any step - generating, writing, recording - is reported as [BackupMessage.CreateFailed]
     * and leaves the previous backup facts in place.
     */
    fun createBackup(uri: String, appVersion: String) {
        if (_uiState.value.isWorking) return

        _uiState.update { it.copy(isWorking = true, work = BackupWork.Creating, message = null) }
        viewModelScope.launch {
            try {
                val text = backups.create(appVersion)
                documents.writeText(uri, text)
                settings.recordLastBackup(
                    at = clock(),
                    bytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
                )
                _uiState.update {
                    it.copy(isWorking = false, work = null, message = BackupMessage.Created)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isWorking = false, work = null, message = BackupMessage.CreateFailed)
                }
            }
        }
    }

    /**
     * Reads a chosen file and validates it, without changing anything.
     *
     * A valid file becomes [BackupRestoreUiState.pendingRestore] and the screen opens its confirmation;
     * an invalid one is reported and never becomes pending.
     */
    fun onRestoreFilePicked(uri: String) {
        if (_uiState.value.isWorking) return

        _uiState.update { it.copy(isWorking = true, work = BackupWork.Reading, message = null) }
        viewModelScope.launch {
            try {
                val text = documents.readText(uri)
                when (val inspection = backups.inspect(text)) {
                    is RestoreInspection.Invalid -> _uiState.update {
                        it.copy(
                            isWorking = false,
                            work = null,
                            message = BackupMessage.Rejected(inspection.errors),
                        )
                    }
                    is RestoreInspection.Valid -> _uiState.update {
                        it.copy(isWorking = false, work = null, pendingRestore = inspection)
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isWorking = false, work = null, message = BackupMessage.ReadFailed)
                }
            }
        }
    }

    /** Replaces the database with the inspected document, after the household confirmed. */
    fun confirmRestore() {
        val pending = _uiState.value.pendingRestore ?: return
        if (_uiState.value.isWorking) return

        _uiState.update {
            it.copy(isWorking = true, work = BackupWork.Restoring, pendingRestore = null)
        }
        viewModelScope.launch {
            try {
                val outcome = backups.restore(pending.document)
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        work = null,
                        message = when (outcome) {
                            is RestoreOutcome.Restored -> BackupMessage.Restored(outcome.summary)
                            is RestoreOutcome.Rejected -> BackupMessage.Rejected(outcome.errors)
                        },
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isWorking = false, work = null, message = BackupMessage.RestoreFailed)
                }
            }
        }
    }

    /** Closes the confirmation without changing anything. */
    fun dismissRestore() {
        _uiState.update { it.copy(pendingRestore = null) }
    }

    /** Acknowledges the last message so it is not shown again on the next recomposition. */
    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }

    companion object {

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) { "BackupRestoreViewModel needs the Application to reach the repositories" }

                val app = application as HomeFinanceApp
                BackupRestoreViewModel(
                    backups = app.backupRepository,
                    settings = app.settingsRepository,
                    documents = app.documentStore,
                )
            }
        }

        /** The test seam: the same view model over collaborators a test supplies. */
        fun factory(
            backups: BackupRepository,
            settings: SettingsRepository,
            documents: DocumentStore,
            clock: () -> Instant = Instant::now,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BackupRestoreViewModel(
                    backups = backups,
                    settings = settings,
                    documents = documents,
                    clock = clock,
                )
            }
        }
    }
}

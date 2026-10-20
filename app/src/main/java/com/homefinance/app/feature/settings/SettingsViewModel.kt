package com.homefinance.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.homefinance.app.HomeFinanceApp
import com.homefinance.app.core.ui.theme.ThemeMode
import com.homefinance.app.core.util.DateFormatStyle
import com.homefinance.app.data.model.AppCurrency
import com.homefinance.app.data.model.AppLanguage
import com.homefinance.app.data.model.AppPreferences
import com.homefinance.app.data.repository.AppDataRepository
import com.homefinance.app.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Settings screen knows about the household's preferences. */
sealed interface SettingsUiState {

    /** The first read has not landed yet. */
    data object Loading : SettingsUiState

    /** The stored preferences, re-emitted whenever one changes. */
    data class Content(val preferences: AppPreferences) : SettingsUiState

    /** The read itself failed. The screen offers a retry rather than a blank dashboard. */
    data class Failed(val cause: Throwable?) : SettingsUiState
}

/** The progress of "Clear app data". */
data class ClearDataState(
    val isWorking: Boolean = false,
    val hasFailed: Boolean = false,
)

/** One-off outcomes that are shown as a snackbar rather than kept in the screen's state. */
sealed interface SettingsEvent {
    data object WriteFailed : SettingsEvent
    data object DataCleared : SettingsEvent
    data object DataClearFailed : SettingsEvent
}

/**
 * Backs every preferences screen: the dashboard, Theme, Language, Currency, Date format and App
 * Settings.
 *
 * ### One view model, several screens
 *
 * Those six screens read the same `app_setting` rows and change them through named setters. A view
 * model per screen would be six copies of the same `stateIn` pipeline, and the fifth one would
 * eventually forget the retry. Each destination still gets its own instance - Navigation scopes it to
 * the back-stack entry - so leaving Theme does not tear down the dashboard; they simply observe the
 * same source of truth.
 *
 * ### Writes are optimistic only because the database is the state
 *
 * A toggle does not flip a local boolean: it writes the row and lets the `Flow` emit. The switch
 * therefore always shows what is stored, and a failed write leaves it exactly where it was instead of
 * showing a value the app did not save. The failure is reported through [events]; nothing is silently
 * swallowed.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val appData: AppDataRepository,
) : ViewModel() {

    /**
     * A counter rather than a flag: each increment restarts a failed read, and two retries in a row
     * are genuinely two reads. The same pattern the other screens use.
     */
    private val reloads = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<SettingsUiState> = reloads
        .flatMapLatest {
            settings.preferences
                .map<AppPreferences, SettingsUiState>(SettingsUiState::Content)
                .onStart { emit(SettingsUiState.Loading) }
                .catch { failure -> emit(SettingsUiState.Failed(failure)) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SettingsUiState.Loading,
        )

    private val _clearState = MutableStateFlow(ClearDataState())
    val clearState: StateFlow<ClearDataState> = _clearState.asStateFlow()

    private val _events = MutableStateFlow<SettingsEvent?>(null)
    val events: StateFlow<SettingsEvent?> = _events.asStateFlow()

    /** Reads the preferences again after a failed read. */
    fun retry() {
        reloads.update { it + 1 }
    }

    fun setThemeMode(mode: ThemeMode) = write { settings.setThemeMode(mode) }

    fun setLanguage(language: AppLanguage) = write { settings.setLanguage(language) }

    fun setCurrency(currency: AppCurrency) = write { settings.setCurrency(currency) }

    fun setDateFormat(style: DateFormatStyle) = write { settings.setDateFormat(style) }

    fun setShowBalanceOnHome(show: Boolean) = write { settings.setShowBalanceOnHome(show) }

    fun setConfirmBeforeDelete(confirm: Boolean) = write { settings.setConfirmBeforeDelete(confirm) }

    fun setHapticFeedback(enabled: Boolean) = write { settings.setHapticFeedback(enabled) }

    /**
     * Deletes every row of household data and every preference.
     *
     * The confirmation is the caller's job and is deliberately **not** governed by the "Confirm before
     * delete" preference: that switch is about one transaction, and this is irreversible in a much
     * larger way. The screen always asks, and always asks in a dialog that states what will go.
     */
    fun clearAppData() {
        if (_clearState.value.isWorking) return

        _clearState.value = ClearDataState(isWorking = true)
        viewModelScope.launch {
            try {
                appData.clearAll()
                _clearState.value = ClearDataState()
                _events.value = SettingsEvent.DataCleared
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _clearState.value = ClearDataState(hasFailed = true)
                _events.value = SettingsEvent.DataClearFailed
            }
        }
    }

    /** Acknowledges the last snackbar so it is not shown again on the next recomposition. */
    fun onEventShown() {
        _events.value = null
    }

    /** Closes the error state of the clear dialog without hiding the failure message. */
    fun dismissClearError() {
        _clearState.update { it.copy(hasFailed = false) }
    }

    /**
     * Writes one preference, reporting a failure instead of pretending it landed.
     *
     * The write runs on the view model's scope rather than in the composable, so a screen that is
     * rotated mid-write still completes it. Cancellation is rethrown: a write that is cancelled because
     * the screen left must not be reported as a failure.
     */
    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                _events.value = SettingsEvent.WriteFailed
            }
        }
    }

    companion object {

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) { "SettingsViewModel needs the Application to reach the repositories" }

                val app = application as HomeFinanceApp
                SettingsViewModel(
                    settings = app.settingsRepository,
                    appData = app.appDataRepository,
                )
            }
        }

        /** The test seam: the same view model over repositories a test supplies. */
        fun factory(
            settings: SettingsRepository,
            appData: AppDataRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(settings = settings, appData = appData)
            }
        }
    }
}

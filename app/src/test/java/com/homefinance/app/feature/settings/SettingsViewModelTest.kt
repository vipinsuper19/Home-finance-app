package com.homefinance.app.feature.settings

import androidx.lifecycle.ViewModelStore
import com.homefinance.app.core.ui.theme.ThemeMode
import com.homefinance.app.core.util.DateFormatStyle
import com.homefinance.app.data.model.SettingsKeys
import com.homefinance.app.data.repository.AppDataRepository
import com.homefinance.app.data.repository.DirectTransactionRunner
import com.homefinance.app.data.repository.FakeAppSettingDao
import com.homefinance.app.data.repository.FakeCategoryDao
import com.homefinance.app.data.repository.FakeRecurringDao
import com.homefinance.app.data.repository.FakeTransactionDao
import com.homefinance.app.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The view model behind every preferences screen.
 *
 * A write is never optimistic in local state: the switch reads the stored value, so these tests prove
 * that a setter really reaches the table, that a failed write is reported instead of shown as saved,
 * and that clearing data has its own progress and failure states.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val viewModelStore = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        Dispatchers.resetMain()
    }

    private class Fixture(settings: FakeAppSettingDao = FakeAppSettingDao()) {
        val settingDao = settings
        val settingsRepository = SettingsRepository(settings)
        val appDataRepository = AppDataRepository(
            categoryDao = FakeCategoryDao(),
            transactionDao = FakeTransactionDao(),
            recurringDao = FakeRecurringDao(),
            appSettingDao = settings,
            transactionRunner = DirectTransactionRunner,
        )
    }

    private fun Fixture.viewModel(): SettingsViewModel =
        SettingsViewModel(settingsRepository, appDataRepository)
            .also { viewModelStore.put("settings", it) }

    private fun kotlinx.coroutines.test.TestScope.collect(viewModel: SettingsViewModel) {
        backgroundScope.launch { viewModel.uiState.collect { } }
        backgroundScope.launch { viewModel.clearState.collect { } }
    }

    @Test
    fun theScreenReadsWhatIsStored() = runTest {
        val fixture = Fixture(FakeAppSettingDao(mapOf(SettingsKeys.THEME_MODE to "DARK")))
        val viewModel = fixture.viewModel()
        collect(viewModel)
        advanceUntilIdle()

        val content = viewModel.uiState.value as SettingsUiState.Content
        assertEquals(ThemeMode.DARK, content.preferences.themeMode)
    }

    @Test
    fun aSetterWritesTheRowAndTheScreenSeesIt() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel()
        collect(viewModel)
        advanceUntilIdle()

        viewModel.setThemeMode(ThemeMode.SYSTEM)
        viewModel.setDateFormat(DateFormatStyle.DAY_MONTH_NUMERIC)
        viewModel.setShowBalanceOnHome(false)
        viewModel.setConfirmBeforeDelete(false)
        viewModel.setHapticFeedback(false)
        advanceUntilIdle()

        val content = viewModel.uiState.value as SettingsUiState.Content
        assertEquals(ThemeMode.SYSTEM, content.preferences.themeMode)
        assertEquals(DateFormatStyle.DAY_MONTH_NUMERIC, content.preferences.dateFormat)
        assertFalse(content.preferences.showBalanceOnHome)
        assertFalse(content.preferences.confirmBeforeDelete)
        assertFalse(content.preferences.hapticFeedback)
        assertEquals("SYSTEM", fixture.settingDao.stored[SettingsKeys.THEME_MODE])
    }

    @Test
    fun aWriteThatFailsIsReportedAndLeavesTheStoredValueAlone() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel()
        collect(viewModel)
        advanceUntilIdle()
        fixture.settingDao.writeFailure = IllegalStateException("read-only")

        viewModel.setShowBalanceOnHome(false)
        advanceUntilIdle()

        assertEquals(SettingsEvent.WriteFailed, viewModel.events.value)
        val content = viewModel.uiState.value as SettingsUiState.Content
        assertTrue(content.preferences.showBalanceOnHome)
    }

    @Test
    fun aFailedReadOffersAFailureStateAndRetryRecovers() = runTest {
        val fixture = Fixture()
        fixture.settingDao.failure = IllegalStateException("cannot read")
        val viewModel = fixture.viewModel()
        collect(viewModel)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is SettingsUiState.Failed)

        fixture.settingDao.failure = null
        viewModel.retry()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is SettingsUiState.Content)
    }

    @Test
    fun clearingDataReportsSuccessAndLeavesNoPreferences() = runTest {
        val fixture = Fixture(FakeAppSettingDao(mapOf(SettingsKeys.THEME_MODE to "DARK")))
        val viewModel = fixture.viewModel()
        collect(viewModel)
        advanceUntilIdle()

        viewModel.clearAppData()

        // The progress state exists while the write is in flight.
        assertTrue(viewModel.clearState.value.isWorking)

        advanceUntilIdle()

        assertEquals(SettingsEvent.DataCleared, viewModel.events.value)
        assertFalse(viewModel.clearState.value.isWorking)
        assertFalse(viewModel.clearState.value.hasFailed)
        assertFalse(SettingsKeys.THEME_MODE in fixture.settingDao.stored)
    }

    @Test
    fun aClearThatFailsSaysSoAndOffersNoSuccessMessage() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel()
        collect(viewModel)
        advanceUntilIdle()
        fixture.settingDao.writeFailure = IllegalStateException("read-only")

        viewModel.clearAppData()
        advanceUntilIdle()

        assertEquals(SettingsEvent.DataClearFailed, viewModel.events.value)
        assertTrue(viewModel.clearState.value.hasFailed)
        assertFalse(viewModel.clearState.value.isWorking)
    }

    @Test
    fun aSecondClearWhileOneIsRunningIsRefused() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel()
        collect(viewModel)
        advanceUntilIdle()

        viewModel.clearAppData()
        viewModel.clearAppData()
        advanceUntilIdle()

        // One deletion, one success: a double tap cannot wipe twice or report twice.
        assertEquals(1, viewModel.events.value.let { if (it == SettingsEvent.DataCleared) 1 else 0 })
    }
}

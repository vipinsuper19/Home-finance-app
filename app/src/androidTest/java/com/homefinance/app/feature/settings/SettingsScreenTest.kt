package com.homefinance.app.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homefinance.app.R
import com.homefinance.app.core.ui.component.ProvideHapticsEnabled
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.ui.theme.ThemeMode
import com.homefinance.app.data.model.AppPreferences
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Settings dashboard as it is actually drawn.
 *
 * The state and its writes are pinned on the JVM; this asserts the part a JVM test cannot see - that
 * the four groups and their rows are on screen, that a row is a real tap target that reaches the
 * caller, and that a switch reports the value it was toggled to.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun text(id: Int): String = resources.getString(id)

    private fun render(
        preferences: AppPreferences = AppPreferences(),
        onOpenTheme: () -> Unit = {},
        onOpenAppSettings: () -> Unit = {},
        onShowBalanceChange: (Boolean) -> Unit = {},
    ) {
        composeTestRule.setContent {
            ProvideHapticsEnabled(enabled = false) {
                HomeFinanceTheme(themeMode = ThemeMode.LIGHT) {
                    SettingsContent(
                        uiState = SettingsUiState.Content(preferences),
                        onRetry = {},
                        onOpenTheme = onOpenTheme,
                        onOpenLanguage = {},
                        onOpenCurrency = {},
                        onOpenDateFormat = {},
                        onOpenBackupRestore = {},
                        onOpenExport = {},
                        onOpenImport = {},
                        onOpenManageCategories = {},
                        onOpenRecurring = {},
                        onOpenAppSettings = onOpenAppSettings,
                        onShowBalanceChange = onShowBalanceChange,
                        onConfirmDeleteChange = {},
                        onHapticsChange = {},
                        onBack = {},
                    )
                }
            }
        }
    }

    @Test
    fun theDashboardShowsItsFourGroups() {
        render()

        composeTestRule.onNodeWithText(text(R.string.settings_subtitle)).assertIsDisplayed()

        listOf(
            R.string.settings_section_appearance,
            R.string.settings_section_data,
            R.string.settings_section_manage,
            R.string.settings_section_app,
        ).forEach { heading ->
            composeTestRule.onNodeWithText(text(heading)).assertExists()
        }

        composeTestRule.onNodeWithText(text(R.string.settings_theme)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.settings_backup_restore)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.settings_manage_categories)).assertExists()
        composeTestRule.onNodeWithText(text(R.string.app_settings_title)).assertExists()
    }

    @Test
    fun theThemeRowStatesTheStoredValueAndOpensTheThemeScreen() {
        val opened = AtomicReference(false)
        render(
            preferences = AppPreferences(themeMode = ThemeMode.DARK),
            onOpenTheme = { opened.set(true) },
        )

        composeTestRule.onNodeWithText(text(R.string.settings_value_dark)).assertIsDisplayed()
        composeTestRule.onNodeWithText(text(R.string.settings_theme)).performClick()

        assertEquals(true, opened.get())
    }

    @Test
    fun theAppSettingsRowIsARealTapTarget() {
        val opened = AtomicReference(false)
        render(onOpenAppSettings = { opened.set(true) })

        composeTestRule
            .onNode(hasText(text(R.string.app_settings_title)) and hasClickAction())
            .performClick()

        assertEquals(true, opened.get())
    }

    @Test
    fun aSwitchReportsTheValueItWasToggledTo() {
        val reported = AtomicReference<Boolean?>(null)
        render(
            preferences = AppPreferences(showBalanceOnHome = true),
            onShowBalanceChange = { reported.set(it) },
        )

        composeTestRule
            .onNode(
                isToggleable() and hasAnyDescendant(hasText(text(R.string.settings_show_balance))),
            )
            .performClick()

        assertNotNull(reported.get())
        assertEquals(false, reported.get())
    }

    @Test
    fun turningTheBalanceOffIsDrawnAsOff() {
        render(preferences = AppPreferences(showBalanceOnHome = false))

        composeTestRule
            .onNode(
                isToggleable() and hasAnyDescendant(hasText(text(R.string.settings_show_balance))),
            )
            .assertIsOff()
    }
}

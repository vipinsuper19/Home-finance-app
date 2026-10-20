package com.homefinance.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homefinance.app.core.ui.component.ProvideHapticsEnabled
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.core.ui.theme.ThemeMode
import com.homefinance.app.core.util.DateFormatter
import com.homefinance.app.data.model.AppPreferences
import com.homefinance.app.navigation.HomeFinanceShell

/**
 * The single activity of the app; everything else is Compose.
 *
 * ### The one place a preference reaches the whole app
 *
 * The stored preferences are collected here, above the theme, and applied to the three things that
 * are not per-screen: the colour scheme, the system bar icons, and the process-wide date layout that
 * every screen formats dates through. Everything below this point recomposes when a preference
 * changes, so choosing Dark or `05/11/2026` is visible immediately and survives a restart - the
 * values themselves live in `app_setting`, not in this activity.
 *
 * The initial value is [AppPreferences]'s default, which is the light, `5 Nov 2026` look the app
 * shipped with; the stored values arrive a frame later. That is deliberate: a first paint cannot wait
 * for a database read, and a default that matched what the household previously saw is the least
 * surprising thing to paint.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // The bar icons are transparent-backed; enableEdgeToEdge only needs a starting point. The
        // light/dark choice is applied from the stored theme in the content below.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val app = application as HomeFinanceApp

        setContent {
            val preferences by app.settingsRepository.preferences
                .collectAsStateWithLifecycle(initialValue = AppPreferences())

            // Applied before the children compose, so the frame that draws the new theme also draws
            // every date in the new layout; there is no frame where one screen disagrees with another.
            DateFormatter.applyStyle(preferences.dateFormat)

            val darkTheme = when (preferences.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            SideEffect {
                // Dark surfaces need light icons and vice versa. Setting both flags here keeps the
                // status and navigation bars readable in every mode, including System following the
                // device while the activity is already running.
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }

            ProvideHapticsEnabled(enabled = preferences.hapticFeedback) {
                HomeFinanceTheme(themeMode = preferences.themeMode) {
                    HomeFinanceShell()
                }
            }
        }
    }
}

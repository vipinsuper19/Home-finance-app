package com.homefinance.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.homefinance.app.core.ui.theme.HomeFinanceTheme
import com.homefinance.app.navigation.HomeFinanceShell

/**
 * The single activity of the app; everything else is Compose.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // The design is light-only, so system bar icons are pinned to the dark variant.
        // Without this a device set to dark mode would draw light icons over a white app.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            HomeFinanceTheme {
                HomeFinanceShell()
            }
        }
    }
}

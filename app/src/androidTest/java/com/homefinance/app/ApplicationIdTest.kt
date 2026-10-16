package com.homefinance.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke test: it runs on a device or emulator and is not part of
 * `testDebugUnitTest`. It compiles as part of `assembleDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class ApplicationIdTest {

    @Test
    fun applicationIdIsHomeFinance() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        assertEquals("com.homefinance.app", appContext.packageName)
    }
}

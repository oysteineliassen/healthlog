package no.healthlog.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Røyktest: starter appen på en emulator, åpner hver fane og registreringssiden. Fanger
 * krasj som bare skjer på ekte Android (f.eks. regex-flagg som JVM-testene godtar).
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun opensEveryTabAndTheEntryPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Hopper over velkomstsidene, så testen havner rett på I dag.
        OnboardingPreferences.setCompleted(context)

        ActivityScenario.launch(MainActivity::class.java).use {
            val tabs = listOf(
                R.string.nav_trends, R.string.nav_history, R.string.nav_settings,
                R.string.nav_records, R.string.nav_today
            )
            for (tab in tabs) {
                compose.onNode(hasText(context.getString(tab)) and hasClickAction()).performClick()
                compose.waitForIdle()
            }

            compose.onNode(hasText(context.getString(R.string.fab_register_description)) and hasClickAction()).performClick()
            compose.onNodeWithText(context.getString(R.string.entry_dialog_title)).assertIsDisplayed()
        }
    }
}

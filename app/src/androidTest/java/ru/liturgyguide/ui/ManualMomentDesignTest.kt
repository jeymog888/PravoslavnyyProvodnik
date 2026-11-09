package ru.liturgyguide.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ManualMomentDesignTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wordSearchAndMicrophoneAreSeparateReachableActions() {
        var search = 0
        var microphone = 0
        compose.setContent {
            GuideTheme(false) {
                ManualMomentScreen(ArrivalDraft(null, "Сретенский монастырь", ArrivalMode.NEW_SERVICE, ArrivalSource.MANUAL),
                    onLandmark = {}, onSearch = { search++ }, onBack = {}, onMicrophone = { microphone++ })
            }
        }
        val words = compose.onNodeWithTag("manual_search")
        val mic = compose.onNodeWithTag("manual_microphone")
        words.assertIsDisplayed()
        mic.assertIsDisplayed()
        val gap = mic.fetchSemanticsNode().boundsInRoot.top - words.fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Independent actions must not overlap", gap > 0)
        words.performClick()
        mic.performClick()
        compose.runOnIdle { assertEquals(1, search); assertEquals(1, microphone) }
    }
}

package ru.liturgyguide.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.espresso.Espresso.pressBack

/** Opens the explicit update action without accepting a candidate as a side effect. */
fun ComposeContentTestRule.openBookUpdateOptions() {
    val optionsOpen = onAllNodesWithTag("book_options").fetchSemanticsNodes().isNotEmpty()
    if (!optionsOpen) {
        waitUntil(5_000) {
            onAllNodesWithTag("book_update_available", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("book_update_available", useUnmergedTree = true).assertHasNoClickAction()
        onNodeWithTag("book_reader_settings").assertIsDisplayed().performClick()
    }
    onNodeWithTag("book_options").assertIsDisplayed()
    waitUntil(5_000) { onAllNodesWithTag("book_accept_update").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag("book_accept_update").performScrollTo().assertIsDisplayed().assertHasClickAction()
}

fun ComposeContentTestRule.dismissBookUpdateOptions() {
    onNodeWithTag("book_options").assertExists()
    pressBack()
    waitUntil(5_000) { onAllNodesWithTag("book_options").fetchSemanticsNodes().isEmpty() }
}

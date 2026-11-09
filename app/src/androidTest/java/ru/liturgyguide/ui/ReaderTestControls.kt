package ru.liturgyguide.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue

/** Explicit paths through the real UI. No retries, inferred state, or direct player calls. */
internal fun SemanticsNodeInteractionsProvider.openReaderControls() {
    onNodeWithTag("reader_controls_list").assertDoesNotExist()
    onNodeWithTag("reader_controls").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
    onNodeWithTag("reader_controls_list").assertIsDisplayed()
}

internal fun SemanticsNodeInteractionsProvider.readerControl(tag: String): SemanticsNodeInteraction {
    onNodeWithTag("reader_controls_list").assertIsDisplayed().performScrollToNode(hasTestTag(tag))
    return onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
}

internal fun SemanticsNodeInteractionsProvider.openReaderControl(tag: String): SemanticsNodeInteraction {
    openReaderControls()
    return readerControl(tag)
}

internal fun SemanticsNodeInteractionsProvider.dismissReaderControls() {
    onNodeWithTag("reader_controls_list").assertIsDisplayed()
    val dismiss = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
    dismiss.assertCountEquals(1)
    dismiss[0].performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
    onNodeWithTag("reader_controls_list").assertDoesNotExist()
}

internal fun SemanticsNodeInteractionsProvider.openReaderTracks() {
    onNodeWithTag("player_menu").performClick()
    onNodeWithTag("choose_track").assertContentDescriptionEquals("Тексты этапа").performClick()
    onNodeWithTag("track_list").assertIsDisplayed()
}

internal fun SemanticsNodeInteractionsProvider.openReaderDetails() {
    onNodeWithTag("player_menu").performClick()
    onNodeWithTag("open_reading_details").performClick()
    onNodeWithTag("reading_details_date").assertExists()
}

internal fun SemanticsNodeInteractionsProvider.closeReaderDetails() {
    onNodeWithTag("close_reading_details").performClick()
    onNodeWithTag("reading_details_date").assertDoesNotExist()
}

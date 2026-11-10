package ru.liturgyguide.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue

/** Explicit paths through the real UI. No retries, inferred state, or direct player calls. */
internal fun SemanticsNodeInteractionsProvider.openReaderControls() {
    onNodeWithTag("reader_controls_list").assertDoesNotExist()
    onNodeWithTag("reader_mode_chooser").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
    onNodeWithTag("reader_controls_list").assertIsDisplayed()
}

internal fun SemanticsNodeInteractionsProvider.readerControl(tag: String): SemanticsNodeInteraction {
    onNodeWithTag("reader_controls_list").assertIsDisplayed()
    return onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
}

internal fun SemanticsNodeInteractionsProvider.openReaderControl(tag: String): SemanticsNodeInteraction {
    // The continuous reader exposes frequent actions directly and speed in its menu.
    // Unsupported old stage/phrase transport controls deliberately do not get fake aliases.
        return when (tag) {
            "controls_clarify" -> onNodeWithTag("clarify_stage").assertIsDisplayed()
            "controls_order" -> onNodeWithTag("open_map").assertIsDisplayed()
            "scroll_options" -> {
                onNodeWithTag("player_menu").performClick()
                onNodeWithTag("scroll_options").assertIsDisplayed()
            }
            "next_stage" -> {
                onNodeWithTag("lyrics_list").performScrollToNode(hasTestTag("next_stage"))
                onNodeWithTag("next_stage").assertIsDisplayed()
            }
            else -> error("Control $tag belongs to the retired stage-local reader")
        }
}

internal fun SemanticsNodeInteractionsProvider.dismissReaderControls() {
    onNodeWithTag("reader_controls_list").assertIsDisplayed()
    val dismiss = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
    dismiss.assertCountEquals(1)
    dismiss[0].performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
    onNodeWithTag("reader_controls_list").assertDoesNotExist()
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

internal fun SemanticsNodeInteractionsProvider.readerHasModeChooser(): Boolean =
    onAllNodesWithTag("reader_mode_chooser", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

private fun readerModeLabel(mode: String): String = when (mode) {
    "original" -> "Оригинал"
    "text" -> "Русский"
    "meaning" -> "Смысл"
    else -> error("Unknown reader mode: $mode")
}

/** Choose through the visible tab or the visible chooser and its real sheet action. */
internal fun SemanticsNodeInteractionsProvider.selectReaderMode(mode: String) {
    val label = readerModeLabel(mode)
    if (readerHasModeChooser()) {
        openReaderControls()
        readerControl("controls_$mode").assertIsEnabled().assertTextEquals(label).performClick()
        onNodeWithTag("reader_controls_list").assertDoesNotExist()
    } else onNodeWithTag("player_${mode}_tab").assertIsDisplayed().assertIsEnabled().performClick()
    assertReaderMode(mode, selected = true)
}

/** Read the same selected/enabled semantics in either layout, without changing the mode. */
internal fun SemanticsNodeInteractionsProvider.assertReaderMode(mode: String, selected: Boolean? = null, enabled: Boolean? = null) {
    val chooser = readerHasModeChooser()
    if (chooser) openReaderControls()
    val node = if (chooser) readerControl("controls_$mode") else onNodeWithTag("player_${mode}_tab").assertIsDisplayed()
    node.assertTextEquals(readerModeLabel(mode)).assertHeightIsAtLeast(48.dp)
    selected?.let { if (it) node.assertIsSelected() else node.assertIsNotSelected() }
    if (chooser && selected != null) {
        val marker = onNodeWithTag("controls_${mode}_selected_marker", useUnmergedTree = true)
        if (selected) marker.assertIsDisplayed() else marker.assertDoesNotExist()
    }
    enabled?.let { if (it) node.assertIsEnabled() else node.assertIsNotEnabled() }
    if (chooser) {
        dismissReaderControls()
        if (selected == true) onNodeWithTag("reader_selected_mode", useUnmergedTree = true)
            .assertIsDisplayed().assertTextEquals(readerModeLabel(mode))
    }
}

/** Normal keeps three fixed tabs; compact keeps one visible mode and three reachable choices. */
internal fun SemanticsNodeInteractionsProvider.inspectReaderModes(check: (String, SemanticsNodeInteraction) -> Unit) {
    val chooser = readerHasModeChooser()
    if (chooser) openReaderControls()
    listOf("original", "text", "meaning").forEach { mode ->
        val tag = if (chooser) "controls_$mode" else "player_${mode}_tab"
        val node = if (chooser) readerControl(tag) else onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        check(mode, node)
    }
    if (chooser) dismissReaderControls()
}

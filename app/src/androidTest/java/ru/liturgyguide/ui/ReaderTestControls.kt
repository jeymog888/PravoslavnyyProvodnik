package ru.liturgyguide.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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

internal fun SemanticsNodeInteractionsProvider.readerHasModeChooser(): Boolean =
    onAllNodesWithTag("reader_mode_chooser", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

private fun readerModeLabel(mode: String): String = when (mode) {
    "original" -> "Оригинал"
    "text" -> "По-русски"
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

internal fun SemanticsNodeInteractionsProvider.assertReaderStatus(full: String, compact: String): SemanticsNodeInteraction {
    val normal = onAllNodesWithTag("reader_layout_normal").fetchSemanticsNodes().isNotEmpty()
    return onNodeWithTag("reader_status", useUnmergedTree = true).assertIsDisplayed()
        .assertTextEquals(if (normal) full else compact)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, full))
}

internal fun SemanticsNodeInteractionsProvider.assertReaderStatusDetails(full: String) {
    openReaderControls()
    onNodeWithTag("reader_controls_list").performScrollToNode(hasTestTag("controls_reading_status"))
    onNodeWithTag("controls_reading_status").performScrollTo().assertIsDisplayed().assertTextEquals(full)
    dismissReaderControls()
}

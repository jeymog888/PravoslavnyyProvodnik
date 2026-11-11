package ru.liturgyguide.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode

/** Search runs on Default; Compose being idle alone does not mean its result has arrived. */
internal fun ComposeContentTestRule.awaitBookSearch() {
    waitUntil(5_000) {
        onNodeWithTag("book_search_sheet").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "Поиск готов"
    }
}

internal fun ComposeContentTestRule.scrollToBookHit(blockId: String): SemanticsNodeInteraction {
    awaitBookSearch()
    val tag = "book_hit_$blockId"
    // A lazy result below the microphone panel need not exist until its row is scrolled into view.
    onNodeWithTag("book_search_sheet").performScrollToNode(hasTestTag(tag))
    return onNodeWithTag(tag)
}

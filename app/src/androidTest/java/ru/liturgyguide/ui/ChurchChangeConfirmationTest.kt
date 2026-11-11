package ru.liturgyguide.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.service.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import ru.liturgyguide.ui.pressBackWithCiDiagnostics as pressBack
import ru.liturgyguide.ui.closeSoftKeyboardWithCiDiagnostics as closeSoftKeyboard

@RunWith(AndroidJUnit4::class)
class ChurchChangeConfirmationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val container = AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)
    private val firstId = "elisavetinsky_opalikha"
    private val otherId = "vsetsaritsa_nakhabino"
    private val instant = Instant.parse("2026-11-08T07:00:00Z")

    @Test fun filteredChoiceAndBackKeepEverySavedValueUntilExplicitConfirmation() {
        seedReading()
        val before = preferences.all.toMap()
        launch()
        openPicker()
        compose.onNodeWithTag("discovery_query").performTextInput("Нахабино")
        closeSoftKeyboard()
        choose(otherId)
        assertUnchanged(before)
        compose.onNodeWithTag("cancel_church_change").performClick()
        assertUnchanged(before)
        compose.onNodeWithTag("discovery_query").assertTextContains("Нахабино")
        choose(otherId)
        pressBack()
        compose.onNodeWithTag("church_change_confirmation").assertDoesNotExist()
        assertUnchanged(before)
        choose(otherId)
        val confirm = confirmationAction()
        compose.onNodeWithTag("confirm_church_change").performClick()
        val selected = preferences.all.toMap()
        assertEquals(otherId, selected["church_id"])
        assertFalse(selected.containsKey(ReadingContextStore.CONTEXT_KEY))
        assertFalse(selected.containsKey(ServicePlaybackStore.PLAYBACK_KEY))
        assertFalse(selected.containsKey("session"))
        assertFalse(selected.containsKey("stage_id"))
        assertFalse(selected.keys.any(ReadingAcceptanceStore::isBookmarkKey))
        assertEquals(before.filterKeys { key -> key !in setOf("church_id", "stage_id", "session",
            ReadingContextStore.CONTEXT_KEY, ServicePlaybackStore.PLAYBACK_KEY) && !ReadingAcceptanceStore.isBookmarkKey(key) },
            selected - "church_id")
        compose.runOnIdle { confirm(); assertEquals(selected, preferences.all) }
        compose.onNodeWithTag("discovery_content").assertDoesNotExist()
    }

    @Test fun firstChoiceWithoutReadingCompletesWithoutConfirmation() {
        check(preferences.edit().clear().putBoolean("dark", false).commit())
        launch()
        compose.onNodeWithTag("open_church").performClick()
        choose(firstId, expectConfirmation = false)
        compose.onNodeWithTag("discovery_content").assertDoesNotExist()
        compose.onNodeWithTag("church_change_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        compose.onNodeWithTag("church_content").assertDoesNotExist()
        assertEquals(firstId, preferences.getString("church_id", null))
    }

    @Test fun firstHomeChoiceReturnsOnlyAfterACommittedRetry() {
        check(preferences.edit().clear().putBoolean("dark", false).commit())
        val before = preferences.all.toMap()
        val failing = FailNextCommitPreferences(preferences)
        val restoration = launch(failing)
        compose.onNodeWithTag("open_church").performClick()
        compose.runOnIdle { failing.failNextCommit = true }
        choose(firstId, expectConfirmation = false)
        compose.onNodeWithTag("discovery_content").assertIsDisplayed()
        compose.onNodeWithTag("main_content").assertDoesNotExist()
        compose.onNodeWithTag("discovery_saved_${firstId}").assertHasClickAction()
        assertFalse("A failed choice must not select a church", preferences.contains("church_id"))
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_message"))
        compose.onNodeWithTag("discovery_message").assertIsDisplayed()
        assertUnchanged(before)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("discovery_content").assertIsDisplayed()
        compose.onNodeWithTag("main_content").assertDoesNotExist()
        choose(firstId, expectConfirmation = false)
        compose.onNodeWithTag("discovery_content").assertDoesNotExist()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        assertEquals(firstId, preferences.getString("church_id", null))
    }

    @Test fun choosingTheSameChurchKeepsTheReadingWithoutConfirmation() {
        seedReading()
        val before = preferences.all.toMap()
        launch()
        openPicker()
        choose(firstId, expectConfirmation = false)
        compose.onNodeWithTag("discovery_content").assertDoesNotExist()
        compose.onNodeWithTag("church_change_confirmation").assertDoesNotExist()
        assertUnchanged(before)
    }

    @Test fun invalidMetadataWithoutAResolvedChurchStillRequiresConfirmation() {
        check(preferences.edit().clear().putBoolean("dark", false)
            .putString(ReadingContextStore.CONTEXT_KEY, "broken context")
            .putString(ServicePlaybackStore.PLAYBACK_KEY, "broken snapshot")
            .putString("session", "broken session").putInt("stage_id", 42)
            .putString("lyric_phrase_old", "phrase").putLong("lyric_offset_old", 777L).commit())
        val before = preferences.all.toMap()
        launch()
        compose.onNodeWithTag("open_church").performClick()
        choose(firstId)
        assertUnchanged(before)
        compose.onNodeWithTag("cancel_church_change").performClick()
        assertUnchanged(before)
        assertFalse(preferences.contains("church_id"))
        compose.onNodeWithTag("discovery_content").assertIsDisplayed()
        compose.onNodeWithTag("main_content").assertDoesNotExist()
        choose(firstId)
        compose.onNodeWithTag("main_content").assertDoesNotExist()
        assertUnchanged(before)
        compose.onNodeWithTag("confirm_church_change").performClick()
        compose.onNodeWithTag("discovery_content").assertDoesNotExist()
        compose.onNodeWithTag("church_change_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        assertEquals(firstId, preferences.getString("church_id", null))
        assertFalse(preferences.contains(ReadingContextStore.CONTEXT_KEY))
        assertFalse(preferences.contains(ServicePlaybackStore.PLAYBACK_KEY))
        assertFalse(preferences.contains("stage_id"))
        assertFalse(preferences.contains("session"))
        assertFalse(preferences.all.keys.any(ReadingAcceptanceStore::isBookmarkKey))
    }

    @Test fun failedAtomicConfirmationKeepsTheReadingAndAllowsRetry() {
        seedReading()
        val before = preferences.all.toMap()
        val failing = FailNextCommitPreferences(preferences)
        launch(failing)
        openPicker()
        choose(otherId)
        compose.runOnIdle { failing.failNextCommit = true }
        compose.onNodeWithTag("confirm_church_change").performClick()
        compose.onNodeWithTag("church_change_confirmation").assertIsDisplayed()
        compose.onNodeWithText("Не удалось выбрать храм. Сохраненное чтение осталось на месте. Попробуйте еще раз").assertIsDisplayed()
        assertUnchanged(before)
        compose.onNodeWithTag("confirm_church_change").performClick()
        compose.onNodeWithTag("church_change_confirmation").assertDoesNotExist()
        compose.onNodeWithTag("discovery_content").assertDoesNotExist()
        assertEquals(otherId, preferences.getString("church_id", null))
        assertFalse(preferences.contains(ServicePlaybackStore.PLAYBACK_KEY))
    }

    @Test fun aCapturedConfirmationCannotClearAChangedReadingOrAnAlreadyCancelledChoice() {
        seedReading()
        launch()
        openPicker()
        choose(otherId)
        val oldConfirm = confirmationAction()
        compose.runOnIdle { check(preferences.edit().putLong("lyric_offset_changed", 987L).commit()) }
        val replacement = preferences.all.toMap()
        compose.runOnIdle { oldConfirm() }
        compose.onNodeWithTag("church_change_confirmation").assertDoesNotExist()
        assertUnchanged(replacement)
        choose(otherId)
        val cancelledConfirm = confirmationAction()
        compose.onNodeWithTag("cancel_church_change").performClick()
        assertUnchanged(replacement)
        // The unified search is a page. Leaving it must invalidate the cancelled confirmation too.
        compose.onNodeWithContentDescription("Назад").assertIsDisplayed().performClick()
        compose.onNodeWithTag("discovery_content").assertDoesNotExist()
        compose.onNodeWithTag("church_content").assertIsDisplayed()
        compose.runOnIdle { cancelledConfirm() }
        assertUnchanged(replacement)
        compose.onNodeWithTag("discovery_content").assertDoesNotExist()
    }

    @Test fun recreationDropsPendingAndRejectsItsOldConfirmation() {
        seedReading()
        val before = preferences.all.toMap()
        val restoration = launch()
        openPicker()
        choose(otherId)
        val oldConfirm = confirmationAction()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("church_change_confirmation").assertDoesNotExist()
        assertUnchanged(before)
        compose.runOnIdle { oldConfirm() }
        assertUnchanged(before)
        compose.onNodeWithTag("discovery_content").assertIsDisplayed()
        choose(otherId)
        compose.onNodeWithTag("cancel_church_change").performClick()
        assertUnchanged(before)
    }

    @Test fun aLongChurchNameAtLargeTextKeepsBothConfirmationActionsReachable() {
        seedReading()
        val target = SavedChurchRecord.create("Храм святого покровителя города ".repeat(8)
            .take(SavedChurchCodec.MAX_NAME_LENGTH), "Казань", "", "Europe/Moscow")
        assertTrue(ChurchDirectoryStore(preferences).save(target))
        val before = preferences.all.toMap()
        // Combined with the saved 1.15 app text preference, this stresses the dialog at 2.99.
        launch(fontScale = 2.6f)
        openPicker()
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_query"))
        compose.onNodeWithTag("discovery_query").performTextInput("Казань")
        closeSoftKeyboard()
        choose(target.id)
        compose.onNodeWithTag("confirm_church_change").assertIsDisplayed()
        val preview = java.io.File(requireNotNull(context.getExternalFilesDir("qa-preview")), "confirmation-large.png")
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { preview.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        // Connected tests may uninstall the target package. Keep this explicit QA image outside it.
        val exported = "/data/local/tmp/feoria-v06-confirmation-large-${System.currentTimeMillis()}.png"
        check(preview.absolutePath.matches(Regex("[A-Za-z0-9_./-]+")))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand("cp ${preview.absolutePath} $exported")).use {
            check(it.readBytes().isEmpty()) { "Could not export the confirmation preview" }
        }
        listOf("Сменить храм?", "Сменить храм", "Остаться", "Место чтения и закладки будут сброшены").forEach { label ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            val node = compose.onNodeWithText(label, substring = label.startsWith("Место чтения"), useUnmergedTree = true)
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { check(it(layouts)) }
            val layout = layouts.single()
            assertEquals("Requested scale: $label", 2.99f, layout.layoutInput.density.fontScale, .001f)
            // String semantics in Compose 1.7.3 rebuild a wider paragraph from the original
            // constraints. Its unused width is not clipping; check the actual line extents.
            assertFalse("Clipped line count: $label", layout.multiParagraph.didExceedMaxLines)
            (0 until layout.lineCount).forEach { line ->
                assertFalse("Ellipsized: $label", layout.isLineEllipsized(line))
                assertTrue("Horizontal clipping: $label", layout.getLineLeft(line) >= -1f &&
                    layout.getLineRight(line) <= layout.size.width + 1f)
                assertTrue("Vertical clipping: $label", layout.getLineTop(line) >= -1f &&
                    layout.getLineBottom(line) <= layout.size.height + 1f)
            }
            // The message is intentionally scrollable; labels must be wholly visible.
            if (!label.startsWith("Место чтения")) assertFullyVisible(node)
        }
        val confirm = compose.onNodeWithTag("confirm_church_change").assertHeightIsAtLeast(48.dp)
        val cancel = compose.onNodeWithTag("cancel_church_change").assertHeightIsAtLeast(48.dp)
        assertFullyVisible(confirm)
        assertFullyVisible(cancel)
        assertTrue("Confirmation buttons overlap", confirm.getUnclippedBoundsInRoot().bottom <= cancel.getUnclippedBoundsInRoot().top)
        val message = compose.onNodeWithTag("church_change_message")
        assertTrue("Message overlaps the buttons", message.getBoundsInRoot().bottom <= confirm.getUnclippedBoundsInRoot().top)
        message.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 100_000f)) }
        compose.waitForIdle()
        val body = compose.onNodeWithText("Место чтения и закладки будут сброшены", substring = true, useUnmergedTree = true)
        assertTrue("The end of the long church name must be reachable", body.getUnclippedBoundsInRoot().bottom <= message.getBoundsInRoot().bottom + 1.dp)
        assertFullyVisible(confirm)
        assertFullyVisible(cancel)
        compose.onNodeWithTag("cancel_church_change").assertIsDisplayed().performClick()
        assertUnchanged(before)
        choose(target.id)
        compose.onNodeWithTag("confirm_church_change").assertIsDisplayed().performClick()
        assertEquals(target.id, preferences.getString("church_id", null))
    }

    private fun assertFullyVisible(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val visible = node.getBoundsInRoot()
        val full = node.getUnclippedBoundsInRoot()
        val pixel = with(compose.density) { 1f.toDp() }
        assertTrue("Clipped node: visible=$visible, full=$full", visible.left <= full.left + pixel &&
            visible.top <= full.top + pixel && visible.right >= full.right - pixel && visible.bottom >= full.bottom - pixel)
    }

    private fun seedReading() {
        check(preferences.edit().clear().putBoolean("dark", false).putString("church_id", firstId)
            .putBoolean("lyric_original", true).putInt("text_size", 1)
            .putStringSet(ChurchDirectoryStore.FAVORITES_KEY, setOf(firstId)).commit())
        val parish = container.parishes.getOrThrow().directory.churches.first { it.id == firstId }
        val accepted = requireNotNull(ReadingContextFactory.manual(parish, instant))
        val plan = ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(),
            LocalDate.parse(accepted.serviceDate), container.readings?.days?.firstOrNull { it.date == accepted.serviceDate })
        val engine = ServicePlaybackEngine(plan)
        val state = engine.start("creed", PlaybackSpeed.SLOW).copy(elapsedQuarterMillis = 1_234L)
        assertTrue(ReadingAcceptanceStore(preferences).acceptPlayback(plan, accepted, engine.snapshot(state, 0L),
            ServiceSession("creed", instant.toEpochMilli(), autoAdvance = false), true, PlaybackBookmark.at(plan, state)))
        check(preferences.edit().putString("lyric_phrase_other", "retained phrase")
            .putLong("lyric_offset_other", 4321L).putInt("lyric_saved_tempo_other", 16).commit())
    }

    private fun launch(storage: SharedPreferences = preferences, fontScale: Float? = null): StateRestorationTester {
        val local = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "guide_preferences") storage else super.getSharedPreferences(name, mode)
        }
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalContext provides local,
                    LocalActivityResultRegistryOwner provides compose.activity,
                    LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                    AppNavigation(container, Clock.fixed(instant, ZoneOffset.UTC))
                }
            }
        }
    }

    private fun openPicker() {
        compose.onNodeWithTag("nav_church").assertIsDisplayed().performClick()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("change_church"))
        compose.onNodeWithTag("change_church").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("discovery_content").assertIsDisplayed()
    }

    private fun choose(id: String, expectConfirmation: Boolean = true) {
        compose.onNodeWithTag("discovery_content").performScrollToNode(hasTestTag("discovery_saved_$id"))
        compose.onNodeWithTag("discovery_saved_$id").performClick()
        if (expectConfirmation) compose.onNodeWithTag("church_change_confirmation").assertIsDisplayed()
    }

    private fun confirmationAction(): () -> Boolean = compose.onNodeWithTag("confirm_church_change")
        .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!

    private fun assertUnchanged(expected: Map<String, *>) {
        compose.runOnIdle { assertEquals(expected, preferences.all) }
    }

    private class FailNextCommitPreferences(private val backing: SharedPreferences) : SharedPreferences by backing {
        var failNextCommit = false
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean {
                    val committed = editor.commit()
                    if (!failNextCommit) return committed
                    failNextCommit = false
                    return false
                }
            }
        }
    }
}

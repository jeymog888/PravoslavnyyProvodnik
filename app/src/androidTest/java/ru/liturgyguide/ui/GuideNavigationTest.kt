package ru.liturgyguide.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import ru.liturgyguide.LiturgyGuideApplication
import ru.liturgyguide.service.ParishRepository
import ru.liturgyguide.service.PrayerTrackFactory
import ru.liturgyguide.service.ServiceSession
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class GuideNavigationTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val preferences
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)

    // Reset before the activity reads preferences, including saved lyric positions.
    private val cleanPreferences = object : ExternalResource() {
        override fun before() {
            check(preferences.edit().clear().putBoolean("dark", false).commit())
        }
    }

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(cleanPreferences).around(compose)

    @Test
    fun readingCanAdvanceAndReturnWithoutLosingTheStage() {
        tapMain("start_guide")
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Подготовка к литургии")
        compose.onNodeWithTag("previous_stage").assertDoesNotExist()
        compose.onNodeWithTag("next_stage").performClick()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Начало литургии")
        compose.onNodeWithTag("player_text_tab").assertIsSelected()
        compose.onNodeWithTag("lyric_line_0").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithTag("previous_stage").performClick()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Подготовка к литургии")
    }

    @Test
    fun heardWordsLeadToTheRelevantExplanation() {
        openStage("cherubic_hymn", "херувимы")
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Херувимская песнь")
        compose.onNodeWithTag("player_meaning_tab").performClick().assertIsSelected()
        compose.onNodeWithTag("service_content").performScrollToNode(hasText("В чем смысл"))
        compose.onNodeWithText("В чем смысл").assertIsDisplayed()
    }

    @Test
    fun anUnknownPhraseCanBeClearedToRestoreTheGuide() {
        tapMain("find_stage")
        compose.onNodeWithTag("stage_search").performTextInput("несуществующаяфраза")
        closeSoftKeyboard()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasText("Пока не нашли эти слова"))
        compose.onNodeWithText("Пока не нашли эти слова").assertIsDisplayed()
        compose.onNodeWithContentDescription("Очистить поиск").performClick()
        compose.onNodeWithTag("stage_search").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
        )
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_preparation"))
        compose.onNodeWithTag("stage_preparation").assertIsDisplayed()
    }

    @Test
    fun serviceOrderIsSeparateFromSearchAndOptionalListening() {
        tapMain("open_order")
        compose.onNodeWithTag("stage_search").assertDoesNotExist()
        compose.onNodeWithTag("open_listening").assertDoesNotExist()
        compose.onNodeWithTag("stage_preparation").assertIsDisplayed()
        pressBack()
        tapMain("find_stage")
        compose.onNodeWithTag("stage_search").assertIsDisplayed()
        compose.onNodeWithTag("open_listening").assertIsDisplayed()
        compose.onNodeWithText("Послушать фрагмент").assertDoesNotExist()
    }

    @Test
    fun returningFromTheOrderKeepsTheCurrentPhrase() {
        openCreed()
        compose.onNodeWithTag("next_line").performClick()
        compose.onNodeWithTag("lyric_line_1").assertIsSelected()
        compose.onNodeWithTag("open_map").performClick()
        compose.onNodeWithTag("stage_search").assertDoesNotExist()
        pressBack()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Символ веры")
        compose.onNodeWithTag("lyric_line_1").assertIsSelected().assertIsDisplayed()
    }

    @Test
    fun trackPhraseAndLanguageSurviveRecreationAndReturningHome() {
        openCreed()
        compose.onNodeWithTag("next_line").performClick()
        compose.onNodeWithTag("next_line").performClick()
        selectOriginal()
        val expected = requireNotNull(tracksFor("creed")[1].lines[2].original)
        compose.onNodeWithTag("lyric_line_2").assertIsSelected().assertTextEquals(expected)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("lyric_line_2").assertIsSelected().assertTextEquals(expected)
        assertTrue(preferences.getBoolean("lyric_original", false))
        pressBack()
        tapMain("resume_guide")
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Символ веры")
        compose.onNodeWithTag("lyric_line_2").assertIsSelected().assertTextEquals(expected)
        assertManualReading()
    }

    @Test
    fun largeTextAndDarkModePersistAndNavigationRemainsUsable() {
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("dark_switch"))
        compose.onNodeWithTag("dark_switch").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("text_size_2"))
        compose.onNodeWithTag("text_size_2").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("dark_switch"))
        compose.onNodeWithTag("dark_switch").assertIsOn()
        compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("text_size_2"))
        compose.onNodeWithTag("text_size_2").assertIsSelected()
        assertTrue(preferences.getBoolean("dark", false))
        assertEquals(2, preferences.getInt("text_size", -1))
        pressBack()
        tapMain("start_guide")
        compose.onNodeWithTag("next_stage").assertIsDisplayed().performClick()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Начало литургии")
        compose.onNodeWithTag("next_stage").assertIsDisplayed()
    }

    @Test
    fun timedStageFollowingNeedsConfirmationAndCanBePaused() {
        tapMain("start_guide")
        openStageTimer()
        compose.onNodeWithText("Этапы по времени").assertIsDisplayed()
        assertFalse(savedSession().autoAdvance)
        compose.onNodeWithText("Менять этапы самому").performClick()
        assertFalse(savedSession().autoAdvance)
        openStageTimer()
        compose.onNodeWithText("Включить").performClick()
        assertTrue(savedSession().autoAdvance)
        compose.onNodeWithTag("player_menu").performClick()
        compose.onNodeWithTag("toggle_follow").performClick()
        assertFalse(savedSession().autoAdvance)
        compose.onNodeWithTag("next_stage").performClick()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Начало литургии")
    }

    @Test
    fun choosingManualReadingFromTempoOptionsStopsAnActiveScroll() {
        openCreed()
        startScrolling()
        compose.onNodeWithContentDescription("Остановить прокрутку").assertIsDisplayed()
        compose.onNodeWithTag("scroll_options").performClick()
        compose.onNodeWithTag("manual_reading").performClick()
        assertManualReading()
    }

    @Test
    fun choosingAnotherPhraseStopsAutomaticScrolling() {
        openCreed()
        startScrolling()
        compose.onNodeWithTag("next_line").performClick()
        compose.onNodeWithTag("lyric_line_1").assertIsSelected().assertIsDisplayed()
        assertManualReading()
    }

    @Test
    fun switchingToMeaningPausesScrollingAndKeepsThePhrase() {
        openCreed()
        compose.onNodeWithTag("next_line").performClick()
        startScrolling()
        compose.onNodeWithTag("player_meaning_tab").performClick().assertIsSelected()
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
        compose.onNodeWithTag("player_text_tab").performClick()
        compose.onNodeWithTag("lyric_line_1").assertIsSelected()
        assertManualReading()
    }

    @Test
    fun explanationChangesWithTheSelectedText() {
        openCreed()
        val tracks = tracksFor("creed")
        assertNotEquals(tracks[0].explanation, tracks[1].explanation)
        assertTrue(tracks[1].explanation.isNotBlank())
        compose.onNodeWithTag("player_meaning_tab").performClick()
        compose.onNodeWithTag("service_content").performScrollToNode(hasTestTag("track_explanation"))
        compose.onNodeWithTag("track_explanation").assertTextEquals(tracks[1].explanation)
        compose.onNodeWithTag("player_text_tab").performClick()
        chooseTrack(0)
        compose.onNodeWithTag("player_meaning_tab").performClick()
        compose.onNodeWithTag("service_content").performScrollToNode(hasTestTag("track_explanation"))
        compose.onNodeWithTag("track_explanation").assertTextEquals(tracks[0].explanation)
    }

    @Test
    fun backgroundingStopsTheScrollWithoutAdvancingTheSavedPhrase() {
        openCreed()
        startScrolling(8)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        // Longer than one phrase interval proves background time is not played.
        Thread.sleep(9_000L)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithTag("lyric_line_0").assertIsSelected().assertIsDisplayed()
        assertManualReading()
    }

    @Test
    fun startingANewServiceClearsPositionsButKeepsTheLanguagePreference() {
        openCreed()
        compose.onNodeWithTag("next_line").performClick()
        compose.onNodeWithTag("next_line").performClick()
        selectOriginal()
        pressBack()
        tapMain("start_guide")
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Подготовка к литургии")
        compose.onNodeWithTag("open_map").performClick()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_creed"))
        compose.onNodeWithTag("stage_creed").performClick()
        chooseTrack(1)
        compose.onNodeWithTag("lyric_line_0").assertIsSelected()
            .assertTextEquals(requireNotNull(tracksFor("creed")[1].lines[0].original))
        assertTrue(preferences.getBoolean("lyric_original", false))
    }

    @Test
    fun phraseScrollingAndTimedStageFollowingDoNotRunTogether() {
        openCreed()
        startScrolling()
        openStageTimer()
        compose.onNodeWithText("Включить").performClick()
        assertTrue(savedSession().autoAdvance)
        assertManualReading()
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.onNodeWithContentDescription("Остановить прокрутку").assertIsDisplayed()
        assertFalse(savedSession().autoAdvance)
    }

    @Test
    fun defaultParishUsesItsLocalDateAndManualChoiceRemainsAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ParishRepository(context.assets.open("churches.json").bufferedReader().use { it.readText() })
        val parish = repository.selected(null)
        assertEquals("elisavetinsky_opalikha", parish.id)
        compose.onNodeWithTag("open_church").assertTextContains("Елисаветинский храм")
        compose.onNodeWithText(friendlyDate(LocalDate.now(ZoneId.of(parish.timezone)))).assertIsDisplayed()
        openStage("dismissal", "благодарение")
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Благодарение и завершение")
        assertFalse(savedSession().autoAdvance)
    }

    @Test
    fun landscapeWithLargestAppTextKeepsCurrentPhraseAndControlsVisible() {
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("text_size_2"))
        compose.onNodeWithTag("text_size_2").performClick()
        pressBack()
        openCreed()
        try {
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            compose.waitForIdle()
            compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Символ веры").assertIsDisplayed()
            compose.onNodeWithTag("lyric_line_0").assertIsSelected().assertIsDisplayed()
            assertWholePhraseVisible(0)
            compose.onNodeWithTag("next_line").assertIsDisplayed().performClick()
            compose.onNodeWithTag("lyric_line_1").assertIsSelected().assertIsDisplayed()
            assertWholePhraseVisible(1)
            compose.onNodeWithTag("previous_line").assertIsDisplayed()
            compose.onNodeWithTag("next_stage").assertIsDisplayed()
        } finally {
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            }
        }
    }

    @Test
    fun spellingVariantsOpenTheSamePairedPhraseFromMainSearch() {
        data class SearchCase(
            val queries: List<String>, val stageId: String, val stageTitle: String,
            val trackIndex: Int, val lineIndex: Int, val original: String, val russian: String
        )
        val cases = listOf(
            SearchCase(listOf("святого евангелия", "святаго евангелия"), "gospel", "Чтение Евангелия", 0, 0,
                "Премудрость, прости, услышим Святаго Евангелия",
                "Премудрость! Станем прямо и внимательно послушаем Святое Евангелие"),
            SearchCase(listOf("святой боже", "святый боже"), "troparia_trisagion", "Песнопения дня и Трисвятое", 0, 0,
                "Святый Боже, Святый Крепкий, Святый Безсмертный, помилуй нас.",
                "Святой Боже, Святой Всемогущий, Святой Бессмертный, помилуй нас"),
            SearchCase(listOf("духа святого животворящаго"), "creed", "Символ веры", 1, 12,
                "И в Духа Святаго, Господа, Животворящаго,",
                "И в Святого Духа, Господа, дарующего жизнь.")
        )
        cases.forEach { expected ->
            val track = tracksFor(expected.stageId)[expected.trackIndex]
            expected.queries.forEach { query ->
                openStage(expected.stageId, query)
                compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals(expected.stageTitle)
                compose.onNodeWithTag("current_track_title", useUnmergedTree = true).assertTextEquals(track.title)
                compose.onNodeWithTag("lyric_line_${expected.lineIndex}")
                    .assertIsSelected().assertIsDisplayed().assertTextEquals(expected.russian)
                assertEquals(track.id, preferences.getString("lyric_track_${expected.stageId}", null))
                assertEquals(track.lines[expected.lineIndex].id, preferences.getString("lyric_phrase_${track.id}", null))
                assertManualReading()

                selectOriginal()
                compose.onNodeWithTag("lyric_line_${expected.lineIndex}")
                    .assertIsSelected().assertIsDisplayed().assertTextEquals(expected.original)
                if (expected.stageId == "creed") captureQaScreenshot("s2-search-creed-original")
                compose.onNodeWithTag("toggle_original").performClick()
                compose.onNodeWithTag("language_russian").performClick()
                compose.onNodeWithTag("lyric_line_${expected.lineIndex}")
                    .assertIsSelected().assertIsDisplayed().assertTextEquals(expected.russian)
                if (expected.stageId == "creed") captureQaScreenshot("s2-search-creed-russian")
                pressBack()
            }
        }
        tapMain("find_stage")
        compose.onNodeWithTag("stage_search").performTextReplacement("святого евангелия квазислово")
        closeSoftKeyboard()
        compose.onNodeWithText("Пока не нашли эти слова").assertIsDisplayed()
        compose.onNodeWithTag("stage_gospel").assertDoesNotExist()
    }

    @Test
    fun searchingForCreedOpensTheMatchingTextAndClosesTheKeyboard() {
        tapMain("find_stage")
        compose.onNodeWithTag("stage_search").performTextReplacement("Верую")
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_creed"))
        compose.onNodeWithTag("stage_creed").performClick()
        compose.onNodeWithTag("current_track_title", useUnmergedTree = true).assertTextContains("Символ веры")
        compose.onNodeWithTag("lyric_line_0").assertIsSelected().assertTextContains("Верю", substring = true)
        compose.onNodeWithTag("play_lyrics").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 10_000) {
            var hidden = false
            compose.activityRule.scenario.onActivity {
                hidden = ViewCompat.getRootWindowInsets(it.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == false
            }
            hidden
        }
        captureQaScreenshot("creed-search-match")
    }

    @Test
    fun searchingForADailyReadingUsesTodaysTextAndKeepsItsNoteOptional() {
        val today = LocalDate.now(ZoneId.of("Europe/Moscow")).toString()
        val container = (compose.activity.application as LiturgyGuideApplication).container
        val reading = container.readings?.days?.firstOrNull { it.date == today }?.readings
            ?.firstOrNull { it.kind == "gospel" && it.text.contains("Лазарь") }
        if (reading == null) {
            // A downloaded calendar with no matching day must not offer an old Gospel as current.
            tapMain("find_stage")
            compose.onNodeWithTag("stage_search").performTextReplacement("Лазарь")
            closeSoftKeyboard()
            compose.onNodeWithText("Пока не нашли эти слова").assertIsDisplayed()
            return
        }
        openStage("gospel", "Лазарь")
        compose.onNodeWithTag("current_track_title", useUnmergedTree = true).assertTextContains(reading.reference)
        compose.onNode(isSelected() and hasText("Лазарь", substring = true)).assertIsDisplayed()
        captureQaScreenshot("daily-search-match")
        compose.onNodeWithTag("player_meaning_tab").performClick()
        compose.onNodeWithTag("reading_day_note").assertDoesNotExist()
        captureQaScreenshot("daily-meaning-collapsed")
        compose.onNodeWithTag("service_content").performScrollToNode(hasTestTag("reading_note_toggle"))
        compose.onNodeWithTag("reading_note_toggle").performClick()
        compose.onNodeWithTag("reading_day_note").assertIsDisplayed()
        captureQaScreenshot("daily-meaning-expanded")
    }

    @Test
    fun churchHasTodayAndTomorrowAndChangingItStartsAFreshContext() {
        openCreed()
        compose.onNodeWithTag("next_line").performClick()
        pressBack()
        compose.onNodeWithTag("nav_church").performClick()
        val today = LocalDate.now(ZoneId.of("Europe/Moscow"))
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("schedule_today"))
        compose.onNodeWithTag("schedule_today").assertIsSelected()
        compose.onNodeWithText(friendlyDate(today)).assertIsDisplayed()
        compose.onNodeWithTag("schedule_tomorrow").performClick().assertIsSelected()
        compose.onNodeWithText(friendlyDate(today.plusDays(1))).assertIsDisplayed()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("change_church"))
        compose.onNodeWithTag("change_church").performClick()
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("church_vsetsaritsa_nakhabino"))
        compose.onNodeWithTag("church_vsetsaritsa_nakhabino").performClick()
        assertEquals("vsetsaritsa_nakhabino", preferences.getString("church_id", null))
        assertNull(preferences.getString("stage_id", null))
        assertNull(preferences.getString("session", null))
        assertFalse(preferences.all.keys.any { it.startsWith("lyric_line_") })
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("schedule_today"))
        compose.onNodeWithTag("schedule_today").assertIsSelected()
        pressBack()
        compose.onNodeWithTag("resume_guide").assertDoesNotExist()
    }

    @Test
    fun meaningAndReadingPositionSurviveOrderHomeAndSettingsNavigation() {
        openCreed()
        compose.onNodeWithTag("next_line").performClick()
        compose.onNodeWithTag("player_meaning_tab").performClick()
        compose.onNodeWithTag("open_map").performClick()
        pressBack()
        compose.onNodeWithTag("player_meaning_tab").assertIsSelected()
        pressBack()
        tapMain("resume_guide")
        compose.onNodeWithTag("player_meaning_tab").assertIsSelected()
        compose.onNodeWithTag("player_text_tab").performClick()
        compose.onNodeWithTag("lyric_line_1").assertIsSelected()
        compose.onNodeWithTag("player_menu").performClick()
        compose.onNodeWithText("Размер текста и настройки").performClick()
        compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("text_size_1"))
        compose.onNodeWithTag("text_size_1").performClick()
        pressBack()
        compose.onNodeWithTag("player_text_tab").assertIsSelected()
        compose.onNodeWithTag("lyric_line_1").assertIsSelected()
    }

    @Test
    fun findingAPrayerFromMeaningOpensItsTextAtTheMatchedPhrase() {
        openCreed()
        compose.onNodeWithTag("next_line").performClick()
        compose.onNodeWithTag("player_meaning_tab").performClick()
        compose.onNodeWithTag("player_menu").performClick()
        compose.onNodeWithText("Уточнить текущий момент").performClick()
        compose.onNodeWithTag("stage_search").performTextReplacement("Верую")
        closeSoftKeyboard()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_creed"))
        compose.onNodeWithTag("stage_creed").performClick()
        compose.onNodeWithTag("player_text_tab").assertIsSelected()
        compose.onNodeWithTag("current_track_title", useUnmergedTree = true).assertTextContains("Символ веры")
        compose.onNodeWithTag("lyric_line_0").assertIsSelected()
    }

    @Test
    fun searchingAfterFinishingDoesNotReusePositionsFromThePreviousService() {
        openCreed()
        compose.onNodeWithTag("next_line").performClick()
        compose.onNodeWithTag("next_line").performClick()
        compose.onNodeWithTag("open_map").performClick()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_dismissal"))
        compose.onNodeWithTag("stage_dismissal").performClick()
        compose.onNodeWithTag("next_stage").performClick()
        assertNull(preferences.getString("session", null))
        openStage("cherubic_hymn", "херувимы")
        compose.onNodeWithTag("open_map").performClick()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_creed"))
        compose.onNodeWithTag("stage_creed").performClick()
        chooseTrack(1)
        compose.onNodeWithTag("lyric_line_0").assertIsSelected()
    }

    private fun tapMain(tag: String) {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun openStage(id: String, phrase: String) {
        tapMain("find_stage")
        compose.onNodeWithTag("stage_search").performTextReplacement(phrase)
        closeSoftKeyboard()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_$id"))
        compose.onNodeWithTag("stage_$id").performClick()
    }

    private fun openCreed() {
        openStage("creed", "Символ веры")
        chooseTrack(1)
    }

    private fun chooseTrack(index: Int) {
        compose.onNodeWithTag("choose_track").performClick()
        compose.onNodeWithTag("track_list").performScrollToNode(hasTestTag("track_$index"))
        compose.onNodeWithTag("track_$index").performClick()
    }

    private fun selectOriginal() {
        compose.onNodeWithTag("toggle_original").performClick()
        compose.onNodeWithTag("language_original").performClick()
    }

    private fun startScrolling(tempo: Int = 12) {
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.onNodeWithTag("tempo_$tempo").performClick()
        compose.onNodeWithTag("confirm_scroll").performClick()
    }

    private fun assertManualReading() {
        compose.onNodeWithContentDescription("Начать автопрокрутку").assertIsDisplayed()
        compose.onNodeWithTag("line_status").assertTextContains("вручную", substring = true)
    }

    private fun assertWholePhraseVisible(index: Int) {
        val phrase = compose.onNodeWithTag("lyric_line_$index")
        val visible = phrase.getBoundsInRoot()
        val full = phrase.getUnclippedBoundsInRoot()
        assertTrue("Current phrase is vertically clipped: visible=$visible, full=$full",
            visible.bottom.value - visible.top.value + 1f >= full.bottom.value - full.top.value)
        assertTrue("Current phrase is horizontally clipped: visible=$visible, full=$full",
            visible.right.value - visible.left.value + 1f >= full.right.value - full.left.value)
    }

    /** Optional native screenshots for the emulator audit, disabled in the normal test run. */
    private fun captureQaScreenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("feoriaQaScreenshots") != "true") return
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf("mkdir -p /sdcard/Download/feoria-qa", "screencap -p /sdcard/Download/feoria-qa/$name.png").forEach { command ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    private fun openStageTimer() {
        compose.onNodeWithTag("player_menu").performClick()
        compose.onNodeWithTag("toggle_follow").performClick()
    }

    private fun tracksFor(stageId: String) = PrayerTrackFactory.build(requireNotNull(
        (compose.activity.application as LiturgyGuideApplication).container.getStageMatcherResult().getOrThrow().getStageById(stageId)
    ), null)

    private fun savedSession(): ServiceSession = requireNotNull(
        Gson().fromJson(preferences.getString("session", null), ServiceSession::class.java)
    )
}

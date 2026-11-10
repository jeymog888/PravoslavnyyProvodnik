package ru.liturgyguide.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import ru.liturgyguide.ui.closeSoftKeyboardWithCiDiagnostics as closeSoftKeyboard
import ru.liturgyguide.ui.pressBackWithCiDiagnostics as pressBack
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
import ru.liturgyguide.service.ServicePlaybackStore
import ru.liturgyguide.service.StoredServicePlaybackCodec
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Начало литургии")
        val first = tracksFor("beginning").single { it.title == "Благословено Царство" }
        val litany = trackNamed("great_litany", "Миром Господу помолимся")
        chooseLine(litany, 0)
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Молитва о мире и обо всех")
        chooseLine(first, 0)
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Начало литургии")
        compose.assertReaderMode("text", selected = true)
        compose.onFeedLine(first, 0).assertIsSelected().assertIsDisplayed()
        assertManualReading()
    }

    @Test
    fun heardWordsLeadToTheRelevantExplanation() {
        openStage("cherubic_hymn", "херувимы")
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Херувимская песнь")
        compose.onNodeWithTag("player_meaning_tab").performClick().assertIsSelected()
        compose.onNodeWithText("В чём смысл").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anUnknownPhraseCanBeClearedToRestoreTheGuide() {
        openMainSearch()
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
    fun showingAllStagesAfterUnknownWordsDismissesTheKeyboardAndFocus() {
        fun waitForKeyboard(visible: Boolean) = withCiFailureDiagnostics("guide.waitForKeyboard($visible)") {
            compose.waitUntil(timeoutMillis = 10_000) {
                var matches = false
                compose.activityRule.scenario.onActivity {
                    matches = ViewCompat.getRootWindowInsets(it.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == visible
                }
                matches
            }
        }
        openMainSearch()
        compose.onNodeWithTag("stage_search").performClick().performTextInput("несуществующаяфраза")
        waitForKeyboard(true)
        compose.onNodeWithTag("stage_search").assertIsFocused()
        compose.onNodeWithText("Пока не нашли эти слова").assertIsDisplayed()
        compose.onNodeWithText("Показать все этапы").performScrollTo().assertIsDisplayed().performClick()
        waitForKeyboard(false)
        compose.onNodeWithTag("stage_search").assertIsNotFocused().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
        )
        compose.onNodeWithTag("open_listening").assertIsDisplayed()
        compose.onNodeWithTag("stage_preparation").assertIsDisplayed()
        captureQaScreenshot("show-all-without-keyboard")
    }

    @Test
    fun serviceOrderIsSeparateFromSearchAndOptionalListening() {
        tapMain("open_order")
        compose.onNodeWithTag("stage_search").assertDoesNotExist()
        compose.onNodeWithTag("open_listening").assertDoesNotExist()
        compose.onNodeWithTag("stage_preparation").assertIsDisplayed()
        pressBack()
        openMainSearch()
        compose.onNodeWithTag("stage_search").assertIsDisplayed()
        compose.onNodeWithTag("open_listening").assertIsDisplayed()
        compose.onNodeWithTag("listening_screen").assertDoesNotExist()
        compose.onNodeWithTag("start_listening").assertDoesNotExist()
    }

    @Test
    fun orderOpensAtTheBeginningFromThePlayer() {
        tapMain("start_guide")
        compose.onNodeWithTag("open_map").performClick()
        compose.onNodeWithTag("stage_beginning").assertIsSelected().assertIsDisplayed()
        captureQaScreenshot("s2-order-beginning")
    }

    @Test
    fun orderOpensAtDismissalWithLargestAppText() {
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("text_size_2"))
        compose.onNodeWithTag("text_size_2").performClick()
        pressBack()
        openStage("dismissal", "благодарение")
        compose.onNodeWithTag("open_map").performClick()
        compose.onNodeWithTag("stage_dismissal").assertIsSelected().assertIsDisplayed()
        captureQaScreenshot("s2-order-dismissal-large")
    }

    @Test
    fun orderOpensAtCreedAndManualBrowsingReturnsToTheCurrentPhrase() {
        openCreed()
        nextLine()
        compose.onFeedLine(acceptedTrack(), 1).assertIsSelected()
        compose.onNodeWithTag("open_map").performClick()
        compose.onNodeWithTag("stage_search").assertDoesNotExist()
        // No scrolling before this assertion: the selected stage must be visible on entry.
        compose.onNodeWithTag("stage_creed").assertIsSelected().assertIsDisplayed()
        captureQaScreenshot("s2-order-creed")
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_anaphora"))
        compose.onNodeWithTag("stage_anaphora").assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_preparation"))
        compose.onNodeWithTag("stage_preparation").assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithTag("stage_creed").assertIsNotDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("stage_preparation").assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithTag("stage_creed").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("Найти по словам").performClick()
        compose.onNodeWithTag("stage_search").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("stage_preparation").assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithTag("stage_creed").assertIsNotDisplayed()
        pressBack()
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Символ веры")
        compose.onFeedLine(acceptedTrack(), 1).assertIsSelected().assertIsDisplayed()
        // A fresh entry should show the current stage, not the saved list position at preparation.
        compose.onNodeWithTag("open_map").performClick()
        compose.onNodeWithTag("stage_creed").assertIsSelected().assertIsDisplayed()
        pressBack()
        compose.onFeedLine(acceptedTrack(), 1).assertIsSelected().assertIsDisplayed()
    }

    @Test
    fun trackPhraseAndLanguageSurviveRecreationAndReturningHome() {
        openCreed()
        nextLine()
        nextLine()
        selectOriginal()
        val expected = requireNotNull(trackNamed("creed", "Символ веры").lines[2].original)
        compose.onFeedLine(acceptedTrack(), 2).assertIsSelected().assertTextEquals(expected)
        compose.activityRule.scenario.recreate()
        compose.onFeedLine(acceptedTrack(), 2).assertIsSelected().assertTextEquals(expected)
        assertTrue(preferences.getBoolean("lyric_original", false))
        pressBack()
        tapMain("resume_guide")
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Символ веры")
        compose.onFeedLine(acceptedTrack(), 2).assertIsSelected().assertTextEquals(expected)
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
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Начало литургии")
        compose.onNodeWithTag("open_map").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun onePlayerStartsDirectlyAndPausesWithoutASecondStageTimer() {
        tapMain("start_guide")
        compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals("Начало литургии")
        assertManualReading()
        startScrolling()
        assertFalse(savedSession().autoAdvance)
        assertNotNull(StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))
        compose.onNodeWithTag("play_lyrics").performClick()
        assertManualReading()
        assertFalse(savedSession().autoAdvance)
    }

    @Test
    fun tempoOptionsKeepPlayingUntilTheReaderPauseIsPressed() {
        openCreed()
        startScrolling()
        compose.openReaderControl("scroll_options").performClick()
        compose.onNodeWithTag("tempo_normal").assertIsSelected()
        compose.onNodeWithTag("tempo_done").performClick()
        compose.onNodeWithTag("play_lyrics").assert(hasText("Пауза") or hasContentDescription("Пауза")).performClick()
        assertManualReading()
    }

    @Test
    fun choosingAnotherPhraseStopsAutomaticScrolling() {
        openCreed()
        startScrolling()
        nextLine()
        compose.onFeedLine(acceptedTrack(), 1).assertIsSelected().assertIsDisplayed()
        assertManualReading()
    }

    @Test
    fun switchingToMeaningPausesScrollingAndKeepsThePhrase() {
        openCreed()
        nextLine()
        startScrolling()
        compose.onNodeWithTag("player_meaning_tab").performClick().assertIsSelected()
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
        compose.onNodeWithTag("return_to_text").assertIsDisplayed().performClick()
        compose.onNodeWithTag("player_text_tab").assertIsSelected()
        compose.onFeedLine(acceptedTrack(), 1).assertIsSelected()
        assertManualReading()
    }

    @Test
    fun explanationChangesWithTheSelectedText() {
        openCreed()
        val first = trackNamed("creed", "Возлюбим друг друга")
        val creed = trackNamed("creed", "Символ веры")
        assertNotEquals(first.explanation, creed.explanation)
        assertTrue(creed.explanation.isNotBlank())
        compose.onNodeWithTag("player_meaning_tab").performClick()
        compose.onNodeWithTag("track_explanation").performScrollTo().assertTextEquals(creed.explanation)
        compose.onNodeWithTag("player_text_tab").performClick()
        chooseTrack("creed", "Возлюбим друг друга")
        compose.onNodeWithTag("player_meaning_tab").performClick()
        compose.onNodeWithTag("track_explanation").performScrollTo().assertTextEquals(first.explanation)
    }

    @Test
    fun backgroundingAndRecreationKeepTheAcceptedPhrasePaused() {
        openCreed()
        startScrolling()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        val paused = requireNotNull(StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))
        val pausedTrack = requireNotNull(preferences.getString("lyric_track_creed", null))
        val pausedLine = preferences.getInt("lyric_line_$pausedTrack", -1)
        assertTrue(pausedLine >= 0)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.activityRule.scenario.recreate()
        compose.onFeedLine(acceptedTrack(), pausedLine).assertIsSelected().assertIsDisplayed()
        assertManualReading()
        assertEquals(paused, StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))
    }

    @Test
    fun reopeningTheSameReadingKeepsItsPlaceAndLanguagePreference() {
        openCreed()
        chooseLine(trackNamed("creed", "Символ веры"), 2)
        selectOriginal()
        val before = storedReading()
        pressBack()
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag("start_guide"))
        compose.onNodeWithTag("start_guide").assertTextEquals("Продолжить чтение").performClick()
        compose.onFeedLine(trackNamed("creed", "Символ веры"), 2).assertIsSelected().assertIsDisplayed()
        assertEquals(before, storedReading())
        assertTrue(preferences.getBoolean("lyric_original", false))
        assertManualReading()
    }

    @Test
    fun unifiedPlaybackHasNoSeparateStageTimerAndManualSeekPausesIt() {
        openCreed()
        startScrolling()
        compose.onNodeWithTag("player_menu").performClick()
        compose.onNodeWithTag("toggle_follow").assertDoesNotExist()
        compose.onNodeWithText("Этапы по времени").assertDoesNotExist()
        pressBack()
        compose.onNodeWithTag("play_lyrics").assert(hasText("Пауза") or hasContentDescription("Пауза")).assertIsDisplayed()
        assertFalse(savedSession().autoAdvance)
        nextLine()
        compose.onFeedLine(acceptedTrack(), 1).assertIsSelected()
        assertManualReading()
    }

    @Test
    fun selectedParishUsesItsLocalDateAndManualChoiceRemainsAvailable() {
        chooseDefaultParish()
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
            compose.onNodeWithTag("reader_microphone").assertIsDisplayed()
            compose.onFeedLine(acceptedTrack(), 0).assertIsSelected().assertIsDisplayed()
            assertWholePhraseVisible(0)
            chooseLine(trackNamed("creed", "Символ веры"), 1)
            compose.onFeedLine(acceptedTrack(), 1).assertIsSelected().assertIsDisplayed()
            assertWholePhraseVisible(1)
            compose.onNodeWithTag("clarify_stage").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("reader_microphone").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("open_map").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
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
            val trackTitle: String, val lineIndex: Int, val original: String, val russian: String
        )
        val cases = listOf(
            SearchCase(listOf("святого евангелия", "святаго евангелия"), "gospel", "Чтение Евангелия", "Услышим Святаго Евангелия", 0,
                "Премудрость, прости, услышим Святаго Евангелия",
                "Премудрость! Станем прямо и внимательно послушаем Святое Евангелие"),
            SearchCase(listOf("святой боже", "святый боже"), "troparia_trisagion", "Песнопения дня и Трисвятое", "Святый Боже", 0,
                "Святый Боже, Святый Крепкий, Святый Безсмертный, помилуй нас.",
                "Святой Боже, Святой Всемогущий, Святой Бессмертный, помилуй нас"),
            SearchCase(listOf("духа святого животворящаго"), "creed", "Символ веры", "Символ веры", 12,
                "И в Духа Святаго, Господа, Животворящаго,",
                "И в Святого Духа, Господа, дарующего жизнь.")
        )
        cases.forEach { expected ->
            val track = trackNamed(expected.stageId, expected.trackTitle)
            expected.queries.forEach { query ->
                openStage(expected.stageId, query)
                compose.onNodeWithTag("current_stage_title", useUnmergedTree = true).assertTextEquals(expected.stageTitle)
                if (expected.stageId == "creed") {
                    compose.openReaderDetails()
                    compose.onNodeWithTag("reading_details_track").assertTextEquals("Символ веры")
                    compose.closeReaderDetails()
                } else {
                    compose.openReaderDetails()
                    compose.onNodeWithTag("reading_details_track").assertTextEquals(track.title)
                    compose.closeReaderDetails()
                }
                compose.onFeedLine(acceptedTrack(), expected.lineIndex)
                    .assertIsSelected().assertIsDisplayed().assertTextEquals(expected.russian)
                assertEquals(track.id, preferences.getString("lyric_track_${expected.stageId}", null))
                assertEquals(track.lines[expected.lineIndex].id, preferences.getString("lyric_phrase_${track.id}", null))
                assertManualReading()

                selectOriginal()
                compose.onFeedLine(acceptedTrack(), expected.lineIndex)
                    .assertIsSelected().assertIsDisplayed().assertTextEquals(expected.original)
                if (expected.stageId == "creed") captureQaScreenshot("s2-search-creed-original")
                compose.onNodeWithTag("player_text_tab").performClick()
                compose.onFeedLine(acceptedTrack(), expected.lineIndex)
                    .assertIsSelected().assertIsDisplayed().assertTextEquals(expected.russian)
                if (expected.stageId == "creed") captureQaScreenshot("s2-search-creed-russian")
                pressBack()
            }
        }
        openMainSearch()
        compose.onNodeWithTag("stage_search").performTextReplacement("святого евангелия квазислово")
        closeSoftKeyboard()
        compose.onNodeWithText("Пока не нашли эти слова").assertIsDisplayed()
        compose.onNodeWithTag("stage_gospel").assertDoesNotExist()
    }

    @Test
    fun searchingForCreedOpensTheMatchingTextAndClosesTheKeyboard() {
        openMainSearch()
        compose.onNodeWithTag("stage_search").performTextReplacement("Верую")
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_creed"))
        compose.onNodeWithTag("stage_creed").performClick()
        compose.openReaderDetails()
        compose.onNodeWithTag("reading_details_track").assertTextEquals("Символ веры")
        compose.closeReaderDetails()
        compose.onFeedLine(acceptedTrack(), 0).assertIsSelected().assertTextContains("Верю", substring = true)
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
    fun searchingForADailyReadingUsesTodaysTextAndKeepsItsDayExplanation() {
        val today = LocalDate.now(ZoneId.of("Europe/Moscow")).toString()
        val container = (compose.activity.application as LiturgyGuideApplication).container
        val reading = container.readings?.days?.firstOrNull { it.date == today }?.readings
            ?.firstOrNull { it.kind == "gospel" && it.text.contains("Лазарь") }
        if (reading == null) {
            // A downloaded calendar with no matching day must not offer an old Gospel as current.
            openMainSearch()
            compose.onNodeWithTag("stage_search").performTextReplacement("Лазарь")
            closeSoftKeyboard()
            compose.onNodeWithText("Пока не нашли эти слова").assertIsDisplayed()
            return
        }
        openStage("gospel", "Лазарь")
        compose.openReaderDetails()
        compose.onNodeWithTag("reading_details_track").assertTextContains(reading.reference)
        compose.closeReaderDetails()
        compose.onNode(isSelected() and hasText("Лазарь", substring = true)).assertIsDisplayed()
        captureQaScreenshot("daily-search-match")
        compose.onNodeWithTag("player_meaning_tab").performClick()
        compose.onNodeWithTag("prayer_context_note").performScrollTo().assertIsDisplayed()
            .assertTextEquals(requireNotNull(acceptedTrack().contextNote))
        captureQaScreenshot("daily-meaning-note")
    }

    @Test
    fun churchHasTodayAndTomorrowAndChangingItStartsAFreshContext() {
        chooseDefaultParish()
        openCreed()
        nextLine()
        pressBack()
        compose.onNodeWithTag("nav_church").performClick()
        val today = LocalDate.now(ZoneId.of("Europe/Moscow"))
        val scheduleDateFormat = DateTimeFormatter.ofPattern("d MMMM uuuu, EEEE", Locale.forLanguageTag("ru"))
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("schedule_today"))
        compose.onNodeWithTag("schedule_today").assertIsSelected()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("schedule_date"))
        compose.onNodeWithTag("schedule_date").assertTextEquals(today.format(scheduleDateFormat)).assertIsDisplayed()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("schedule_tomorrow"))
        compose.onNodeWithTag("schedule_tomorrow").assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("schedule_date"))
        compose.onNodeWithTag("schedule_date").assertTextEquals(today.plusDays(1).format(scheduleDateFormat)).assertIsDisplayed()
        compose.onNodeWithTag("church_content").performScrollToNode(hasTestTag("change_church"))
        compose.onNodeWithTag("change_church").performClick()
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("church_vsetsaritsa_nakhabino"))
        val beforeChoice = preferences.all.toMap()
        compose.onNodeWithTag("church_vsetsaritsa_nakhabino").performClick()
        compose.runOnIdle { assertEquals(beforeChoice, preferences.all) }
        compose.onNodeWithTag("church_change_confirmation").assertIsDisplayed()
        compose.onNodeWithTag("cancel_church_change").performClick()
        compose.runOnIdle { assertEquals(beforeChoice, preferences.all) }
        compose.onNodeWithTag("church_vsetsaritsa_nakhabino").performClick()
        compose.onNodeWithTag("confirm_church_change").performClick()
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
        nextLine()
        compose.onNodeWithTag("player_meaning_tab").performClick()
        compose.onNodeWithTag("open_map").performClick()
        pressBack()
        compose.onNodeWithTag("player_meaning_tab").assertIsSelected()
        pressBack()
        tapMain("resume_guide")
        compose.onNodeWithTag("player_meaning_tab").assertIsSelected()
        compose.onNodeWithTag("player_text_tab").performClick()
        compose.onFeedLine(acceptedTrack(), 1).assertIsSelected()
        compose.onNodeWithTag("player_menu").performClick()
        compose.onNodeWithText("Размер текста").performClick()
        compose.onNodeWithTag("settings_content").performScrollToNode(hasTestTag("text_size_1"))
        compose.onNodeWithTag("text_size_1").performClick()
        pressBack()
        compose.onNodeWithTag("player_text_tab").assertIsSelected()
        compose.onFeedLine(acceptedTrack(), 1).assertIsSelected()
    }

    @Test
    fun findingAPrayerFromMeaningPreservesTheModeAndAcceptsTheMatchedPhrase() {
        openCreed()
        nextLine()
        compose.onNodeWithTag("player_meaning_tab").performClick()
        compose.onNodeWithTag("clarify_stage").performClick()
        compose.onNodeWithTag("manual_content").performScrollToNode(hasTestTag("manual_search"))
        compose.onNodeWithTag("manual_search").performClick()
        compose.onNodeWithTag("stage_search").performTextReplacement("Верую")
        closeSoftKeyboard()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_creed"))
        compose.onNodeWithTag("stage_creed").performClick()
        compose.assertReaderMode("meaning", selected = true)
        compose.openReaderDetails()
        compose.onNodeWithTag("reading_details_track").assertTextEquals("Символ веры")
        compose.closeReaderDetails()
        compose.selectReaderMode("text")
        compose.onFeedLine(acceptedTrack(), 0).assertIsSelected()
    }

    @Test
    fun searchingAfterFinishingDoesNotReusePositionsFromThePreviousService() {
        openCreed()
        chooseLine(trackNamed("creed", "Символ веры"), 2)
        val before = storedReading()
        val container = (compose.activity.application as LiturgyGuideApplication).container
        val date = LocalDate.parse(before.context.serviceDate)
        val plan = ru.liturgyguide.service.ServicePlaybackPlan.build(container.getStageMatcher().getAllStages(), date,
            container.readings?.days?.singleOrNull { it.date == date.toString() })
        val finished = before.copy(snapshot = before.snapshot.copy(segmentIndex = plan.segments.lastIndex, elapsedQuarterMillis = 0L))
        check(preferences.edit().putString(ServicePlaybackStore.PLAYBACK_KEY,
            StoredServicePlaybackCodec.encode(finished)).commit())
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("finish_reading").assertIsDisplayed().performClick()
        assertNull(preferences.getString("session", null))
        assertFalse(preferences.contains(ServicePlaybackStore.PLAYBACK_KEY))
        assertFalse(preferences.all.keys.any(ru.liturgyguide.service.ReadingAcceptanceStore::isBookmarkKey))
        openStage("cherubic_hymn", "херувимы")
        compose.onNodeWithTag("open_map").performClick()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_creed"))
        compose.onNodeWithTag("stage_creed").performClick()
        chooseTrack("creed", "Символ веры")
        compose.onFeedLine(acceptedTrack(), 0).assertIsSelected()
    }

    private fun tapMain(tag: String) {
        compose.onNodeWithTag("main_content").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun openMainSearch() {
        tapMain("find_stage")
        compose.onNodeWithTag("manual_content").performScrollToNode(hasTestTag("manual_search"))
        compose.onNodeWithTag("manual_search").performClick()
    }

    private fun openStage(id: String, phrase: String) {
        openMainSearch()
        compose.onNodeWithTag("stage_search").performTextReplacement(phrase)
        closeSoftKeyboard()
        compose.onNodeWithTag("stage_list").performScrollToNode(hasTestTag("stage_$id"))
        compose.onNodeWithTag("stage_$id").performClick()
    }

    private fun openCreed() {
        openStage("creed", "Символ веры")
    }

    private fun chooseTrack(stageId: String, title: String) = chooseLine(trackNamed(stageId, title), 0)

    private fun trackNamed(stageId: String, title: String) = tracksFor(stageId).single { it.title == title }

    private fun chooseLine(track: ru.liturgyguide.model.PrayerTrack, index: Int) {
        compose.onNodeWithTag("lyrics_list").performScrollToNode(hasTestTag(feedLineTag(track.lines[index])))
        compose.onFeedLine(track, index).performScrollTo().assertIsDisplayed().performClick()
    }

    private fun nextLine() {
        val track = acceptedTrack()
        val index = preferences.getInt("lyric_line_${track.id}", 0) + 1
        check(index in track.lines.indices)
        chooseLine(track, index)
    }

    private fun storedReading() = requireNotNull(StoredServicePlaybackCodec.decode(
        preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))

    private fun selectOriginal() {
        compose.onNodeWithTag("player_original_tab").performClick()
    }

    private fun startScrolling() {
        compose.onNodeWithTag("play_lyrics").performClick()
        compose.onNodeWithTag("play_lyrics").assert(hasText("Пауза") or hasContentDescription("Пауза")).assertIsDisplayed()
    }

    private fun assertManualReading() {
        compose.onNodeWithTag("play_lyrics").assert(hasText("Автопрокрутка") or hasContentDescription("Автопрокрутка")).assertIsDisplayed()
    }

    private fun assertWholePhraseVisible(index: Int) {
        val phrase = compose.onFeedLine(acceptedTrack(), index)
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

    private fun tracksFor(stageId: String) = PrayerTrackFactory.build(requireNotNull(
        (compose.activity.application as LiturgyGuideApplication).container.getStageMatcherResult().getOrThrow().getStageById(stageId)
    ), null)

    private fun acceptedTrack() = (compose.activity.application as LiturgyGuideApplication).container.recordedFeedTrack(
        requireNotNull(StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null))))

    private fun savedSession(): ServiceSession = requireNotNull(
        Gson().fromJson(preferences.getString("session", null), ServiceSession::class.java)
    )
    private fun chooseDefaultParish() {
        compose.onNodeWithTag("open_church").performClick()
        compose.onNodeWithTag("church_picker").performScrollToNode(hasTestTag("church_elisavetinsky_opalikha"))
        compose.onNodeWithTag("church_elisavetinsky_opalikha").performClick()
        compose.onNodeWithTag("nav_home").performClick()
    }

}

package ru.liturgyguide.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

/** Real navigation and stores, with only the current content expanded by test-only neighbors. */
@RunWith(AndroidJUnit4::class)
class Published05PlaybackIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val production = AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules)
    private val parish get() = production.parishes.getOrThrow().selected(null)
    private val accepted get() = ReadingContext(parish.id, "2026-11-08", parish.timezone,
        ReadingContextSource.PUBLISHED, Instant.parse("2026-11-08T04:00:15.500Z").toEpochMilli())
    private val now = Instant.parse("2026-11-10T12:00:00Z")
    private val originalJson by lazy { asset("published_0_5/liturgy_stages.json") }
    private val expandedJson by lazy { expandedStages() }
    private val oldPlan get() = requireNotNull(Historical05Content(::asset).loadPlan(LocalDate.parse(accepted.serviceDate)))
    private lateinit var currentContainer: AppContainer
    private val futureNote = "Тексты дополнены. При открытии вернемся к последней сохраненной фразе"
    private val completedNote = "Тексты дополнены. Вернулись к последней сохраненной фразе"

    @Test fun previewUsesTheSnapshotAddressAndExplicitResumeRetriesWithoutLosingPreferences() = withCiFailureDiagnostics("migration.previewUsesTheSnapshotAddressAndExplicitResumeRetriesWithoutLosingPreferences") {
        val old = seed(stageId = "creed", speed = PlaybackSpeed.FAST, offset = 12_345L, wrongMarker = true)
        val before = preferences.all.toMap()
        val reads = mutableListOf<String>()
        val container = expandedContainer(reads)
        val plan = plan(container)
        val text = oldPlan.segments[old.snapshot.segmentIndex] as PlaybackSegment.Text
        val expectedIndex = requireNotNull(plan.indexOfLine(text.trackId, text.lineId))
        assertTrue(expectedIndex > old.snapshot.segmentIndex)
        val storage = RecordingPreferences(preferences)
        val restoration = launch(container, storage)
        main("resume_guide").assertTextContains("Символ веры", substring = true)
            .assertTextContains("8 ноября 2026", substring = true)
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
        unchanged(before)
        assertEquals(0, storage.commits)
        compose.runOnIdle { storage.failuresRemaining = 1 }
        main("resume_guide").performClick()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
        unchanged(before)
        assertEquals(1, storage.failedCommits)
        main("resume_guide").performClick()
        assertStored(accepted, "creed", PlaybackSpeed.FAST, 12_345L)
        assertEquals(expectedIndex, saved().snapshot.segmentIndex)
        assertEquals(plan.fingerprint, saved().snapshot.planFingerprint)
        assertEquals(text, plan.segments[expectedIndex])
        assertGlobalsAndOtherBookmarks()
        compose.onNodeWithTag("player_original_tab").assertIsSelected()
        compose.openReaderDetails()
        compose.onNodeWithTag("reading_details_date").assertTextEquals("Тексты на 8 ноября 2026")
        compose.closeReaderDetails()
        val writes = storage.commits
        reads.clear()
        restoration.emulateSavedInstanceStateRestore()
        assertStored(accepted, "creed", PlaybackSpeed.FAST, 12_345L)
        assertEquals(writes, storage.commits)
        assertFalse(reads.any { it.startsWith("published_0_5/") })
        assertTrue(ServicePlaybackStore(preferences, container.historical05Content).restore(plan, accepted) is PlaybackRestoreResult.Valid)
    }

    @Test fun changedWaitExplainsTheReturnOnlyAfterAcceptanceAndKeepsTheNoticeAcrossHomeAndTheme() = withCiFailureDiagnostics("migration.changedWaitExplainsTheReturnOnlyAfterAcceptanceAndKeepsTheNoticeAcrossHomeAndTheme") {
        val old = seed(stageId = "great_litany", speed = PlaybackSpeed.SLOW, wait = true)
        val oldText = requireNotNull(oldPlan.retainedTextAt(old.snapshot.segmentIndex))
        val before = preferences.all.toMap()
        val storage = RecordingPreferences(preferences)
        launch(expandedContainer(), storage)
        main("resume_guide").assertTextContains(futureNote, substring = true)
        compose.runOnIdle { storage.failuresRemaining = 1 }
        main("resume_guide").performClick()
        main("resume_guide").assertTextContains(futureNote, substring = true)
        compose.onNodeWithText(completedNote).assertDoesNotExist()
        unchanged(before)
        main("resume_guide").performClick()
        assertStored(accepted, "great_litany", PlaybackSpeed.SLOW, 0L, restored = true)
        assertEquals(oldText, plan(currentContainer).segments[saved().snapshot.segmentIndex])
        migrationNotice()
        compose.onNodeWithTag("player_meaning_tab").performClick()
        migrationNotice()
        compose.onNodeWithTag("player_menu").performClick()
        compose.onNodeWithTag("open_reading_details").performClick()
        compose.onNodeWithTag("reading_details_migration_notice").assertIsDisplayed().assertTextEquals(completedNote)
        compose.onNodeWithTag("close_reading_details").performClick()
        home()
        main("resume_guide").performClick()
        compose.onNodeWithTag("player_meaning_tab").assertIsSelected()
        migrationNotice()
        compose.onNodeWithTag("player_menu").performClick()
        compose.onNodeWithText("Темный экран").performClick()
        migrationNotice()
        compose.onNodeWithTag("player_text_tab").performClick()
        compose.onNodeWithTag("open_map").performClick()
        stage("antiphons").performClick()
        assertNoMigrationNotice()
        assertEquals("antiphons", plan(currentContainer).stageAt(saved().snapshot.segmentIndex).id)
    }

    @Test fun restoredServiceAndBackFromItsChildrenNeverAcceptThePendingCandidate() = withCiFailureDiagnostics("migration.restoredServiceAndBackFromItsChildrenNeverAcceptThePendingCandidate") {
        seed()
        val storage = RecordingPreferences(preferences)
        val restoration = launch(oldContainer(), storage)
        for (route in listOf("service", "settings", "order", "correction")) {
            // The previous iteration ended on MAIN. Recreate an actual old-content route first.
            compose.runOnIdle { seed(); currentContainer = oldContainer() }
            restoration.emulateSavedInstanceStateRestore()
            main("resume_guide").performClick()
            when (route) {
                "settings" -> {
                    compose.onNodeWithTag("player_menu").performClick()
                    compose.onNodeWithText("Размер текста").performClick()
                }
                "order" -> compose.onNodeWithTag("open_map").performClick()
                "correction" -> compose.onNodeWithTag("clarify_stage").performClick()
            }
            val before = preferences.all.toMap()
            val writesBefore = storage.commits
            compose.runOnIdle { currentContainer = expandedContainer() }
            restoration.emulateSavedInstanceStateRestore()
            if (route != "service") compose.onNodeWithContentDescription("Назад").performClick()
            compose.onNodeWithTag("main_content").assertIsDisplayed()
            main("resume_guide").assertTextContains("8 ноября 2026", substring = true)
            compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
            unchanged(before)
            assertEquals("Restoration and Back must not even attempt a write for $route", writesBefore, storage.commits)
        }
    }

    @Test fun failedCommitAndRollbackDoNotReportAcceptanceAndTheSameCandidateCanBeRetried() = withCiFailureDiagnostics("migration.failedCommitAndRollbackDoNotReportAcceptanceAndTheSameCandidateCanBeRetried") {
        seed(stageId = "great_litany", speed = PlaybackSpeed.SLOW, wait = true)
        val before = preferences.all.toMap()
        val storage = RecordingPreferences(preferences)
        launch(expandedContainer(), storage)
        compose.runOnIdle { storage.failuresRemaining = 2 }
        main("resume_guide").performClick()
        assertEquals(2, storage.failedCommits)
        assertEquals(2, storage.commits)
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        compose.onNodeWithTag("play_lyrics").assertDoesNotExist()
        compose.onNodeWithText(completedNote).assertDoesNotExist()
        main("resume_guide").assertTextContains(futureNote, substring = true)
        assertGlobalsAndOtherBookmarks()
        // This fake applies memory changes before reporting false, including rollback.
        // Matching memory here does not claim physical-disk integrity after two failures.
        unchanged(before)
        main("resume_guide").performClick()
        assertEquals(3, storage.commits)
        assertStored(accepted, "great_litany", PlaybackSpeed.SLOW, 0L, restored = true)
        migrationNotice()
        assertGlobalsAndOtherBookmarks()
    }

    @Test fun choosingFromOrderKeepsTheValidatedContextAndSlowSpeedWithoutAMigrationNotice() = withCiFailureDiagnostics("migration.choosingFromOrderKeepsTheValidatedContextAndSlowSpeedWithoutAMigrationNotice") {
        seed(speed = PlaybackSpeed.SLOW)
        launch(expandedContainer())
        main("open_order").performClick()
        stage("lords_prayer").performClick()
        assertStored(accepted, "lords_prayer", PlaybackSpeed.SLOW, 0L)
        assertNoMigrationNotice()
        assertGlobalsAndOtherBookmarks()
    }

    @Test fun restoredCorrectionKeepsTheValidatedContextAndFastSpeedOnExplicitAcceptance() = withCiFailureDiagnostics("migration.restoredCorrectionKeepsTheValidatedContextAndFastSpeedOnExplicitAcceptance") {
        seed(speed = PlaybackSpeed.FAST)
        val restoration = launch(oldContainer())
        main("resume_guide").performClick()
        compose.onNodeWithTag("clarify_stage").performClick()
        val before = preferences.all.toMap()
        compose.runOnIdle { currentContainer = expandedContainer() }
        restoration.emulateSavedInstanceStateRestore()
        unchanged(before)
        landmark("lords_prayer")
        acceptArrival()
        assertStored(accepted, "lords_prayer", PlaybackSpeed.FAST, 0L)
        assertNoMigrationNotice()
    }

    @Test fun aNewManualMomentUsesTodayAndNormalSpeedAndLateResumeCannotReplaceIt() = withCiFailureDiagnostics("migration.aNewManualMomentUsesTodayAndNormalSpeedAndLateResumeCannotReplaceIt") {
        seed(speed = PlaybackSpeed.FAST)
        val restoration = launch(expandedContainer())
        val lateResume = action(main("resume_guide"))
        main("find_stage").performClick()
        landmark("creed")
        acceptArrival()
        val today = requireNotNull(ReadingContextFactory.manual(parish, now))
        assertStored(today, "creed", PlaybackSpeed.NORMAL, 0L)
        home()
        val before = preferences.all.toMap()
        compose.runOnIdle { lateResume() }
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        unchanged(before)
        restoration.emulateSavedInstanceStateRestore()
        main("resume_guide").performClick()
        assertStored(today, "creed", PlaybackSpeed.NORMAL, 0L)
    }

    @Test fun anExhaustedOccurrenceKeepsItsOwnDateAndNormalSpeedInsteadOfTheCandidateTempo() = withCiFailureDiagnostics("migration.anExhaustedOccurrenceKeepsItsOwnDateAndNormalSpeedInsteadOfTheCandidateTempo") {
        seed(speed = PlaybackSpeed.FAST)
        check(UserServiceStartStore(preferences).save(UserServiceStart(parish.id, "2026-11-08", "23:00"), parish))
        val afterMidnight = Instant.parse("2026-11-08T21:50:00Z")
        launch(expandedContainer(), instant = afterMidnight)
        main("watch_current").performClick()
        compose.onNodeWithTag("pending_service_context").assertTextContains("8 ноября 2026", substring = true)
        landmark("creed")
        acceptArrival()
        val occurrence = ReadingContext(parish.id, "2026-11-08", parish.timezone, ReadingContextSource.USER,
            Instant.parse("2026-11-08T20:00:00Z").toEpochMilli())
        assertStored(occurrence, "creed", PlaybackSpeed.NORMAL, 0L)
    }

    @Test fun aConfirmedChurchChangeClearsTheCandidateAndRejectsItsOldResumeCallback() = withCiFailureDiagnostics("migration.aConfirmedChurchChangeClearsTheCandidateAndRejectsItsOldResumeCallback") {
        seed()
        val restoration = launch(expandedContainer())
        val lateResume = action(main("resume_guide"))
        main("open_church").performClick()
        scroll("church_content", "change_church").performClick()
        val other = production.parishes.getOrThrow().directory.churches.first { it.id != parish.id }
        scroll("church_picker", "church_${other.id}").performClick()
        compose.onNodeWithTag("confirm_church_change").performClick()
        compose.onNodeWithText("Служба", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("main_content").assertIsDisplayed()
        val before = preferences.all.toMap()
        assertFalse(preferences.contains(ServicePlaybackStore.PLAYBACK_KEY))
        assertEquals(other.id, preferences.getString("church_id", null))
        compose.runOnIdle { lateResume() }
        unchanged(before)
        compose.onNodeWithTag("resume_guide").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("resume_guide").assertDoesNotExist()
        unchanged(before)
    }

    @Test fun missingOrCorruptHistoricalAssetsNeverFallBackToLegacyButCurrentValidStillWorks() = withCiFailureDiagnostics("migration.missingOrCorruptHistoricalAssetsNeverFallBackToLegacyButCurrentValidStillWorks") {
        seed()
        val reads = mutableListOf<String>()
        var broken = expandedContainer(reads, historical = { error("missing historical asset") })
        val restoration = launch(broken)
        val before = preferences.all.toMap()
        for (corruption in listOf(false, true)) {
            if (corruption) {
                broken = expandedContainer(reads, historical = { name -> asset(name) + "\n" })
                compose.runOnIdle { currentContainer = broken }
                restoration.emulateSavedInstanceStateRestore()
            }
            main("reading_restore_notice").assertIsDisplayed()
            compose.onNodeWithTag("resume_guide").assertDoesNotExist()
            unchanged(before)
        }
        val plan = plan(broken)
        val state = ServicePlaybackEngine(plan).start("creed", PlaybackSpeed.SLOW)
        compose.runOnIdle {
            save(plan, state, accepted)
            reads.clear()
        }
        restoration.emulateSavedInstanceStateRestore()
        main("resume_guide").performClick()
        assertStored(accepted, "creed", PlaybackSpeed.SLOW, 0L, restored = true)
        assertFalse(reads.any { it.startsWith("published_0_5/") })
    }

    @Test fun assetReaderPreservesDefaultInputsCacheFailuresAndExplicitStageRetry() = withCiFailureDiagnostics("migration.assetReaderPreservesDefaultInputsCacheFailuresAndExplicitStageRetry") {
        val defaultPlan = plan(production)
        val explicitCurrent = AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules, assetReader = ::asset)
        assertEquals(plan(explicitCurrent).fingerprint, defaultPlan.fingerprint)
        val discovery = production.placeDiscovery
        assertSame(discovery, AppContainer(context, discovery, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules).placeDiscovery)
        val counts = mutableMapOf<String, Int>()
        var stagesAvailable = false
        var dailyAvailable = false
        val container = AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules, assetReader = { name ->
            counts[name] = (counts[name] ?: 0) + 1
            when (name) {
                "liturgy_stages.json" -> if (stagesAvailable) originalJson else error("stage read failure")
                "daily_readings.json" -> if (dailyAvailable) asset(name) else error("daily read failure")
                else -> asset(name)
            }
        })
        assertTrue(container.getStageMatcherResult().isFailure)
        stagesAvailable = true
        assertTrue(container.getStageMatcherResult().isFailure)
        assertEquals(1, counts["liturgy_stages.json"])
        assertTrue(container.getStageMatcherResult(forceReload = true).isSuccess)
        assertEquals(2, counts["liturgy_stages.json"])
        assertNull(container.readings)
        dailyAvailable = true
        assertNull(container.readings)
        assertEquals(1, counts["daily_readings.json"])
        assertEquals(oldPlan.fingerprint, container.historical05Content.loadPlan(LocalDate.parse(accepted.serviceDate))?.fingerprint)
    }

    private fun asset(name: String): String = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    private fun oldContainer() = AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules, assetReader = { name ->
        if (name == "liturgy_stages.json") originalJson else asset(name)
    })
    private fun expandedContainer(reads: MutableList<String> = mutableListOf(), historical: ((String) -> String)? = null) =
        AppContainer(context, scheduleOverride = ru.liturgyguide.service.schedule.OfflineInternetSchedules, assetReader = { name ->
            reads += name
            when {
                name == "liturgy_stages.json" -> expandedJson
                name.startsWith("published_0_5/") && historical != null -> historical(name)
                else -> asset(name)
            }
        })
    private fun expandedStages(): String {
        val root = JsonParser.parseString(originalJson).asJsonObject
        root.getAsJsonArray("stages").forEach { element ->
            val stage = element.asJsonObject
            val oldTexts = stage.getAsJsonArray("texts")
            if (oldTexts.size() > 0) {
                val id = stage.get("id").asString
                fun neighbor(side: String) = JsonObject().apply {
                    addProperty("type", "Тестовое дополнение $id $side")
                    addProperty("original", "Дополнение $id $side")
                    addProperty("translation", "Дополнение $id $side")
                    addProperty("explanation", "Только синтетический текст проверки совместимости")
                }
                stage.add("texts", JsonArray().apply {
                    add(neighbor("до")); oldTexts.forEach(::add); add(neighbor("после"))
                })
            }
        }
        return root.toString()
    }
    private fun plan(container: AppContainer, value: ReadingContext = accepted) = ServicePlaybackPlan.build(
        container.getStageMatcher().getAllStages(), LocalDate.parse(value.serviceDate),
        container.readings?.days?.firstOrNull { it.date == value.serviceDate })
    private fun seed(stageId: String = "creed", speed: PlaybackSpeed = PlaybackSpeed.FAST,
        offset: Long = 1_234L, wait: Boolean = false, wrongMarker: Boolean = false): StoredServicePlayback {
        check(preferences.edit().clear().putString("church_id", parish.id).putBoolean("dark", false)
            .putBoolean("lyric_original", true).putInt("text_size", 0).putInt("lyric_tempo", 8).commit())
        val plan = oldPlan
        val index = if (wait) plan.segments.indexOfFirst { it is PlaybackSegment.Wait && it.stageId == stageId }
            else plan.segments.indexOfFirst { it is PlaybackSegment.Text && it.stageId == stageId }
        require(index >= 0)
        val engine = ServicePlaybackEngine(plan)
        val state = engine.start(stageId, speed).copy(segmentIndex = index, elapsedQuarterMillis = offset)
        save(plan, state, accepted)
        check(preferences.edit().putString("lyric_phrase_other_track", "other_phrase")
            .putInt("lyric_line_other_track", 9).putLong("lyric_offset_other_track", 2_003L)
            .putInt("lyric_saved_tempo_other_track", 16).commit())
        if (wrongMarker) check(preferences.edit().putString(ReadingAcceptanceStore.STAGE_KEY, "dismissal")
            .putString(ReadingAcceptanceStore.SESSION_KEY, Gson().toJson(ServiceSession("dismissal", now.toEpochMilli()))).commit())
        return saved()
    }
    private fun save(plan: ServicePlaybackPlan, state: ServicePlaybackState, value: ReadingContext) {
        check(ReadingAcceptanceStore(preferences).acceptPlayback(plan, value,
            ServicePlaybackEngine(plan).snapshot(state, 0L), ServiceSession(plan.stageAt(state.segmentIndex).id,
                Instant.parse("2026-11-08T04:20:00Z").toEpochMilli()), false, PlaybackBookmark.at(plan, state)))
    }
    private fun launch(container: AppContainer, storage: SharedPreferences = preferences, instant: Instant = now): StateRestorationTester {
        currentContainer = container
        val local = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                if (name == "guide_preferences") storage else super.getSharedPreferences(name, mode)
        }
        return StateRestorationTester(compose).also { restoration -> restoration.setContent {
            CompositionLocalProvider(LocalContext provides local) {
                AppNavigation(currentContainer, Clock.fixed(instant, ZoneOffset.UTC), monotonicMillis = { 0L })
            }
        } }
    }
    private fun scroll(parent: String, tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(parent).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun main(tag: String) = scroll("main_content", tag)
    private fun stage(id: String) = scroll("stage_list", "stage_$id")
    private fun landmark(id: String) = scroll("manual_content", "manual_$id").performClick()
    private fun acceptArrival() = scroll("arrival_content", "accept_arrival").performClick()
    private fun home() = compose.onNodeWithContentDescription("К службе").performClick()
    private fun action(node: SemanticsNodeInteraction): () -> Boolean =
        node.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun saved() = requireNotNull(StoredServicePlaybackCodec.decode(preferences.getString(ServicePlaybackStore.PLAYBACK_KEY, null)))
    private fun unchanged(before: Map<String, *>) = compose.runOnIdle { assertEquals(before, preferences.all) }
    // The reading book keeps secondary provenance in its real details dialog.
    // Check its presence/absence there, rather than asserting a retired HUD tag.
    private fun migrationNotice() {
        compose.openReaderDetails()
        compose.onNodeWithTag("reading_details_migration_notice").assertIsDisplayed().assertTextEquals(completedNote)
        compose.closeReaderDetails()
    }
    private fun assertNoMigrationNotice() {
        compose.openReaderDetails()
        compose.onNodeWithTag("reading_details_migration_notice").assertDoesNotExist()
        compose.closeReaderDetails()
    }
    private fun assertStored(value: ReadingContext, stageId: String, speed: PlaybackSpeed, offset: Long, restored: Boolean = false) {
        compose.waitForIdle()
        val stored = saved()
        assertEquals(value, stored.context)
        assertEquals(value, ReadingContextStore(preferences).restore())
        assertEquals(speed, stored.snapshot.speed)
        assertEquals(offset, stored.snapshot.elapsedQuarterMillis)
        val plan = plan(currentContainer, value)
        val state = requireNotNull(ServicePlaybackEngine(plan).restore(stored.snapshot))
        assertFalse(state.isPlaying)
        assertEquals(stageId, plan.stageAt(state.segmentIndex).id)
        val marker = Gson().fromJson(preferences.getString(ReadingAcceptanceStore.SESSION_KEY, null), ServiceSession::class.java)
        assertEquals(stageId, marker.anchorStageId)
        assertFalse(marker.autoAdvance)
        assertEquals(0L, marker.elapsedInStageMillis)
        compose.onNodeWithTag("play_lyrics").assertIsDisplayed().assertTextEquals("Автопрокрутка")
        if (restored) {
            compose.openReaderDetails()
            compose.onNodeWithText("Сохранённое чтение. Место в храме можно уточнить по словам или микрофону").assertIsDisplayed()
            compose.closeReaderDetails()
        }
    }
    private fun assertGlobalsAndOtherBookmarks() {
        assertFalse(preferences.getBoolean("dark", true))
        assertTrue(preferences.getBoolean("lyric_original", false))
        assertEquals(0, preferences.getInt("text_size", -1))
        assertEquals(8, preferences.getInt("lyric_tempo", -1))
        assertEquals("other_phrase", preferences.getString("lyric_phrase_other_track", null))
        assertEquals(9, preferences.getInt("lyric_line_other_track", -1))
        assertEquals(2_003L, preferences.getLong("lyric_offset_other_track", -1L))
        assertEquals(16, preferences.getInt("lyric_saved_tempo_other_track", -1))
    }
    private class RecordingPreferences(private val backing: SharedPreferences) : SharedPreferences by backing {
        var failuresRemaining = 0
        var failedCommits = 0
        var commits = 0
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean {
                    commits++
                    val committed = editor.commit()
                    if (failuresRemaining == 0) return committed
                    failuresRemaining--
                    failedCommits++
                    return false
                }
            }
        }
    }
}

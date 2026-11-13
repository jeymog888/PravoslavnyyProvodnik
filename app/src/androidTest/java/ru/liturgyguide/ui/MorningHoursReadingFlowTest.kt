package ru.liturgyguide.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.book.*
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Recorded primary-source Android UI acceptance. Not live Main, a parish's performed order, or process death. */
@RunWith(AndroidJUnit4::class)
class MorningHoursReadingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "morning-hours-${UUID.randomUUID()}"
    private val directory = File(context.cacheDir, name)
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val store = BookReadingStore(prefs)
    private val mounted = mutableStateOf(true)
    private val parish = Parish("native-morning-hours", "Храм Новомучеников", "Храм", "", "", "", "", "Europe/Moscow",
        "", "", "", "", emptyList(), "", "")
    private val initial = requireNotNull(ServiceBookSelection.resolve(
        ParishService("2026-11-14", "08:30", "Часы. Божественная Литургия", "service_block"), parish))
    private val route = mutableStateOf(initial)
    private val owner = mutableStateOf<ServiceBooks>(repository())

    @After fun clean() {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { mounted.value = false }
        compose.mainClock.advanceTimeBy(64)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        context.deleteSharedPreferences(name)
        directory.deleteRecursively()
    }

    @Test fun hoursAndLiturgyShareSearchAndAllThreeAcceptedEditionsReopenFromDiskWithoutRequests() {
        launchScreen()
        waitReader(WorshipKind.HOUR3)
        val sixth = initial.journeyContext!!.parts.single { it.worship == WorshipKind.HOUR6 }
        val liturgy = initial.journeyContext!!.parts.single { it.worship == WorshipKind.LITURGY }
        val target = parsed(WorshipKind.LITURGY).blocks.single {
            BookText.normalized(it.original).startsWith("благословено царство отца")
        }
        compose.onNodeWithTag("book_find").performClick()
        compose.onNodeWithTag("book_search_query").performTextInput("Благословено Царство Отца")
        closeSoftKeyboard()
        compose.waitUntil(15_000) { owner.value.cached(liturgy) != null && owner.value.cached(sixth) != null }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("book_search_corpus_loading").fetchSemanticsNodes().isEmpty() }
        compose.awaitBookSearch()
        assertEquals("Searching must not accept either neighbour", BookReadingLookup.Absent, store.lookup(sixth, parish.id))
        assertEquals(BookReadingLookup.Absent, store.lookup(liturgy, parish.id))
        val searchedBook = requireNotNull(owner.value.cached(liturgy))
        val hit = BookSearch.find(searchedBook, "Благословено Царство Отца", BookReadingMode.ORIGINAL).single { it.blockId == target.id }
        val tag = "book_cross_hit_${BookLocatedSearchHit(liturgy, searchedBook.contentHash, hit).identity}"
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        compose.waitUntil(15_000) { route.value.activeKey == liturgy && store.positionFor(liturgy, parish.id) != null }
        settle()
        compose.onNodeWithTag("book_text_${target.id}").assertIsDisplayed()
        assertEquals(liturgy, requireNotNull(store.positionFor(liturgy, parish.id)).key)

        choose(WorshipKind.HOUR6)
        compose.onNodeWithTag("book_hours_review").assertDoesNotExist()
        choose(WorshipKind.HOUR3)
        val saved = initial.journeyContext!!.parts.associateWith { key -> requireNotNull(store.positionFor(key, parish.id)) }
        compose.onNodeWithTag("book_open_calendar").performScrollTo().performClick()
        compose.onNodeWithTag("book_offline_complete").performScrollTo().assertTextEquals("Три текста сохранены для чтения без сети")
        androidx.test.espresso.Espresso.pressBack()
        settle()

        val offlineCalls = AtomicInteger()
        val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport {
            offlineCalls.incrementAndGet(); throw IOException("No network")
        }, downloadRussian = false)
        for ((key, expected) in saved) {
            compose.runOnIdle { mounted.value = false }
            settle()
            compose.runOnIdle {
                owner.value = offline
                route.value = initial.selectPart(key, savedCurrentPart = false)!!
                mounted.value = true
            }
            waitReader(key.worship)
            val reopened = requireNotNull(store.positionFor(key, parish.id))
            assertEquals(expected.contentHash, reopened.contentHash)
            assertEquals(expected.blockId, reopened.blockId)
            assertEquals(expected.mode, reopened.mode)
            assertNotNull(offline.cachedVersion(key, expected.contentHash))
        }
        assertEquals(0, offlineCalls.get())
    }

    @Test fun aFirstHourFailureKeepsTheLiturgyImmediatelyAvailableWithoutAcceptingTheFailedPart() {
        owner.value = repository(failThird = true)
        launchScreen()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("book_load_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book_failed_part_liturgy").performScrollTo().assertIsDisplayed().performClick()
        waitReader(WorshipKind.LITURGY)
        assertEquals(BookReadingLookup.Absent, store.lookup(initial.activeKey, parish.id))
        compose.onNodeWithTag("book_order").assertIsDisplayed()
    }

    @Test fun conflictStaysManualAndUnknownCanEnablePlaybackOnlyForThisMountedEdition() {
        val conflict = parsed(WorshipKind.HOUR6, "2026-11-13")
        val unknown = conflict.copy(notices = conflict.notices.filterNot(BookHourSourceReview::isInternalNotice))
            .let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        val source = mutableStateOf(conflict)
        val context13 = requireNotNull(ServiceBookSelection.resolve(
            ParishService("2026-11-13", "08:30", "Часы. Литургия", "service_block"), parish)?.journeyContext)
        var opened: WorshipKind? = null
        compose.setContent { GuideTheme(false) {
            if (mounted.value) key(source.value.contentHash) {
                BookReaderScreen(source.value, parish.id, store, 0, onBack = {}, onOpenUrl = {}, onChooseVersion = {},
                    onSettings = {}, journeyContext = context13, onChoosePart = { opened = it.worship; true })
            }
        } }
        settle()
        compose.onNodeWithTag("book_hours_review").assertIsDisplayed()
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Проверить текст часов").performClick()
        compose.onNodeWithTag("book_hours_review_message").assertTextContains("Кондак апостолов", substring = true)
        compose.onNodeWithTag("book_hours_allow_unchecked_playback").assertDoesNotExist()
        compose.onNodeWithTag("book_hours_open_liturgy").performClick()
        compose.waitUntil { opened == WorshipKind.LITURGY }

        compose.runOnIdle { source.value = unknown }
        settle()
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Проверить текст часов").performClick()
        settle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("book_hours_allow_unchecked_playback").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Остановить прокрутку").performClick()
        compose.mainClock.autoAdvance = true
        assertEquals(BookHourReviewStatus.UNKNOWN, BookHourSourceReview.of(unknown).status)
        compose.runOnIdle { mounted.value = false }
        settle()
        compose.runOnIdle { mounted.value = true }
        settle()
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Проверить текст часов")
        assertEquals(BookHourReviewStatus.CONFLICT, BookHourSourceReview.of(conflict).status)
    }

    @Test fun unverifiedPlaybackConsentCannotBypassAnUnresolvedSearchTarget() {
        val source = parsed(WorshipKind.HOUR6, "2026-11-13").let { book ->
            book.copy(notices = book.notices.filterNot(BookHourSourceReview::isInternalNotice))
                .let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        }
        val first = BookReadingPosition(source.key, source.contentHash, parish.id, source.blocks.first().id,
            source.chapters.first().id, fraction = .2f)
        assertTrue(store.save(first))
        val hit = BookSearch.find(source, "Живый в помощи Вышняго", BookReadingMode.ORIGINAL).first()
        val target = BookLocatedSearchHit(source.key, source.contentHash, hit)
        val allowConsumption = AtomicBoolean(false)
        val attempts = AtomicInteger()
        compose.setContent { GuideTheme(false) {
            if (mounted.value) BookReaderScreen(source, parish.id, store, 0, onBack = {}, onOpenUrl = {},
                onChooseVersion = {}, onSettings = {}, pendingSearchTarget = target,
                onPendingSearchHandled = { attempts.incrementAndGet(); allowConsumption.get() })
        } }
        compose.waitUntil(5_000) { attempts.get() > 0 }
        settle()
        // The measured target is committed before consuming the handoff. A rejected
        // consumption must preserve that durable target, not restore the earlier page.
        val beforeRejectedConsent = requireNotNull(store.positionFor(source.key, parish.id))
        assertEquals(target.hit.blockId, beforeRejectedConsent.blockId)
        assertEquals(source.contentHash, beforeRejectedConsent.contentHash)
        compose.onNodeWithTag("book_hours_review").performClick()
        compose.onNodeWithTag("book_hours_allow_unchecked_playback").performScrollTo().performClick()
        settle()
        compose.onNodeWithTag("book_hours_playback_error").performScrollTo()
            .assertTextContains("Не удалось отменить переход", substring = true)
        assertTrue(attempts.get() >= 2)
        assertEquals("Rejected playback consent must leave the durable target untouched",
            beforeRejectedConsent, store.positionFor(source.key, parish.id))
        androidx.test.espresso.Espresso.pressBack()
        settle()
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Проверить текст часов")

        allowConsumption.set(true)
        compose.onNodeWithTag("book_hours_review").performClick()
        settle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("book_hours_allow_unchecked_playback").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithTag("book_play").assertContentDescriptionEquals("Остановить прокрутку").performClick()
        compose.mainClock.autoAdvance = true
        assertTrue(attempts.get() >= 3)
        assertEquals(BookHourReviewStatus.UNKNOWN, BookHourSourceReview.of(source).status)
    }

    @Test fun threePartNavigationKeepsCompleteLabelsAndTouchSpacingAt320DpAndDoubleTextSize() {
        compose.setContent { GuideTheme(false) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                if (mounted.value) Box(Modifier.width(320.dp)) {
                    BookJourneyParts(initial.journeyContext!!, initial.activeKey, {}, "book_journey_parts", false)
                }
            }
        } }
        settle()
        val image = File(requireNotNull(context.getExternalFilesDir("morning-hours-ui")), "$name-navigation320-font200.png")
        image.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        val bounds = initial.journeyContext!!.parts.map { key ->
            val node = compose.onNodeWithTag("book_part_${key.worship.name.lowercase()}").assertIsDisplayed()
            val bounds = node.getBoundsInRoot()
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(key.worship.title, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                it(layouts)
            }
            assertEquals(1, layouts.size)
            val layout = layouts.single()
            assertFalse("The full part name must be laid out without clipping: ${key.worship.title}; " +
                "size=${layout.size}, constraints=${layout.layoutInput.constraints}, font=${layout.layoutInput.style.fontSize}, " +
                "lineHeight=${layout.layoutInput.style.lineHeight}, widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}",
                layout.hasVisualOverflow)
            bounds
        }
        bounds.zipWithNext().forEach { (first, next) -> assertTrue(next.top - first.bottom >= 12.dp) }
    }

    private fun bytes(kind: WorshipKind, date: String) = instrumentation.context.assets
        .open("morning-hours/${kind.queryValue}-$date-church30.html").use { it.readBytes() }
    private fun parsed(kind: WorshipKind, date: String = "2026-11-14") = ServiceBookParser().parse(
        Jsoup.parse(bytes(kind, date).toString(Charsets.UTF_8)), ServiceBookKey(date, kind), 1)
    private fun repository(failThird: Boolean = false): ServiceBooks = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url ->
        val kind = listOf(WorshipKind.HOUR3, WorshipKind.HOUR6, WorshipKind.LITURGY).singleOrNull {
            ServiceBookKey("2026-11-14", it).sourceUrl() == url
        } ?: throw IOException("Optional source outside this original-book replay")
        if (failThird && kind == WorshipKind.HOUR3) throw IOException("Recorded first-hour network failure")
        ScheduleResponse(url, bytes(kind, "2026-11-14"), "text/html; charset=UTF-8")
    }, downloadRussian = false)
    private fun launchScreen() {
        compose.setContent { GuideTheme(false) {
            if (mounted.value) key(owner.value) {
                ServiceBookScreen(route.value.activeKey, parish.id, owner.value, store, 0, onBack = {}, onSettings = {},
                    onOpenUrl = {}, parish = parish, route = route.value,
                    onRouteChanged = { route.value = it; true }, onOpenCommonLiturgy = {})
            }
        } }
    }
    private fun waitReader(kind: WorshipKind) {
        compose.waitUntil(15_000) { route.value.activeKey.worship == kind &&
            compose.onAllNodesWithTag("book_order").fetchSemanticsNodes().isNotEmpty() }
        settle()
    }
    private fun choose(kind: WorshipKind) {
        compose.onNodeWithTag("book_order").performClick()
        compose.onNodeWithTag("book_part_${kind.name.lowercase()}").performScrollTo().performClick()
        waitReader(kind)
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(750); compose.waitForIdle() }
}

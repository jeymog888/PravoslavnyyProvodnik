package ru.liturgyguide.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Genuine source HTML is parsed on Android; profile publication belongs to the real screen transaction. */
@RunWith(AndroidJUnit4::class)
class BookDedicationSourceFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "dedication-source-${UUID.randomUUID()}"
    private val readingPreferences = context.getSharedPreferences("$name-reading", Context.MODE_PRIVATE)
    private val choiceBacking = context.getSharedPreferences("$name-choice", Context.MODE_PRIVATE)
    private val choicePreferences = ProfilePreferences(choiceBacking)
    private val choices = PatronalChoiceStore(choicePreferences)
    private val store = mutableStateOf(BookReadingStore(readingPreferences))
    private val directory = File(context.cacheDir, name)
    private val cache = FileBookCache(directory)
    private val parish = "dedication-source-test-parish"
    private val genericKey = ServiceBookKey("2026-11-11", churchType = "34")
    private val route = mutableStateOf(BookRoute(genericKey, parish))
    private val mounted = mutableStateOf(true)
    private var contentSet = false
    private val calendarCalls = CopyOnWriteArrayList<String>()
    @Volatile private var denyCalendar = false
    private val catalog by lazy { requireNotNull(PatronalCatalog.decode(context.assets.open(PatronalCatalog.ASSET_NAME).bufferedReader().use { it.readText() })) }
    private val services = BookDedicationServices(choices, PatronalCalendarResolver(choices, ScheduleTransport { url ->
        calendarCalls += url
        check(!denyCalendar) { "Calendar network is denied in the remounted scenario" }
        assertEquals("${PatronalCalendarResolver.BASE_URL}/2026-05-22/", url)
        ScheduleResponse(url, asset("dedication/calendar-nicholas-may-2026-05-22.json"), "text/html; charset=UTF-8")
    })) { catalog }
    private val original by lazy { parse("nicholas-may-default.html", genericKey) }
    private lateinit var destination: ServiceBook
    private val owners = mutableListOf<DiskBooks>()
    private val books = mutableStateOf(newBooks())

    @After fun clean() {
        choicePreferences.rejectProfiles = false
        choicePreferences.beforeProfileCommit = null
        owners.forEach { it.beforeVersionReturn = null; it.releasePending() }
        if (contentSet) {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            compose.waitUntil(5_000) { owners.all { it.inFlight.get() == 0 } }
        }
        context.deleteSharedPreferences("$name-reading")
        context.deleteSharedPreferences("$name-choice")
        directory.deleteRecursively()
    }

    @Test fun aRealMissingTroparionContinuesAtThePublishedHymnAndSurvivesOfflineRemountAndReturn() {
        launch()
        val old = chooseMayFromTheRealMissingTroparion()
        val durableAtCommit = AtomicInteger()
        choicePreferences.beforeProfileCommit = {
            assertEquals(destination, FileBookCache(directory).readVersion(destination.key, destination.contentHash))
            assertEquals(destination.contentHash, saved(destination.key).contentHash)
            durableAtCommit.incrementAndGet()
        }
        assertEquals(PatronalProfileLookup.Absent, choices.readProfile(parish))
        assertNull(store.value.positionFor(route.value.activeKey, parish))
        books.value.releasePending()
        awaitDestination()
        awaitPublishedProfile()
        assertTrue(durableAtCommit.get() > 0)
        assertHymnCursor()
        assertSamePosition(old, saved(genericKey))
        assertEquals(original, cache.readVersion(genericKey, original.contentHash))
        val selectedPosition = saved(destination.key)
        val selectedRoute = requireNotNull(BookRouteCodec.decode(BookRouteCodec.encode(route.value)))
        val callsBefore = calendarCalls.toList()
        denyCalendar = true
        remount(selectedRoute, offline = true)
        awaitDestination()
        assertSamePosition(selectedPosition, saved(destination.key))
        assertTrue(books.value.loads.isEmpty())
        assertEquals(callsBefore, calendarCalls)
        assertEquals(PatronalProfileLookup.Found(proof()), PatronalChoiceStore(choiceBacking).readProfile(parish))
        openDetails()
        details("book_dedication_return").performClick()
        awaitOriginal()
        assertSamePosition(old, saved(genericKey))
        assertNull(route.value.sourceReturn)
        assertEquals(PatronalProfileLookup.Found(proof()), choices.readProfile(parish))
        assertEquals(callsBefore, calendarCalls)
    }

    @Test fun aFailedProfileCommitPreservesTheDestinationOnRemountAndItsReceiptBlocksRebasingAfterEviction() {
        choicePreferences.rejectProfiles = true
        launch()
        chooseMayFromTheRealMissingTroparion()
        books.value.releasePending()
        awaitDestination()
        compose.waitUntil(5_000) { choicePreferences.rejected.get() > 0 }
        assertNotNull(route.value.pendingDedication)
        assertEquals(PatronalProfileLookup.Absent, choices.readProfile(parish))
        val other = destination.blocks.dropWhile { it.id != hymn().id }.drop(1)
            .first { it.kind == BookBlockKind.PRAYER && !it.isAltarText && it.original.length > 200 }
        scrollExactlyTo(destination, other.id)
        val moved = saved(destination.key)
        assertNotEquals(hymn().id, moved.blockId)
        val pending = requireNotNull(BookRouteCodec.decode(BookRouteCodec.encode(route.value)))
        remount(pending, offline = true)
        awaitDestination()
        compose.waitUntil(5_000) { choicePreferences.rejected.get() >= 2 }
        assertSamePosition(moved, saved(destination.key))
        assertTrue(books.value.loads.isEmpty())
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        val editor = readingPreferences.edit()
        readingPreferences.all.forEach { (key, value) ->
            if ((key.startsWith("position:") || key.startsWith("last:")) &&
                (value as? String)?.let(BookReadingCodec::decode)?.key == destination.key) editor.remove(key)
        }
        assertTrue(editor.commit())
        assertTrue("The durable transaction receipt must survive position eviction", readingPreferences.all.keys.any { it.startsWith("dedication:") })
        assertEquals(BookReadingLookup.Absent, store.value.lookup(destination.key, parish))
        compose.runOnIdle { mounted.value = true }
        await("book_load_error")
        compose.onNodeWithTag("book_load_error").assertTextContains("уже был подготовлен", substring = true)
        compose.onNodeWithTag("book_content").assertDoesNotExist()
        assertTrue("A lost accepted cursor must be rejected before any book load or latest mounting", books.value.loads.isEmpty())
        assertNull(store.value.positionFor(destination.key, parish))
        assertEquals(PatronalProfileLookup.Absent, choices.readProfile(parish))
        assertEquals(original, cache.readVersion(genericKey, original.contentHash))
    }

    @Test fun anIndependentDestinationAcceptedDuringTheLastCacheReadCannotBeRebasedOrPublished() {
        launch()
        val old = chooseMayFromTheRealMissingTroparion()
        val changed = AtomicReference<BookReadingPosition>()
        val expectedPreferences = AtomicReference<Map<String, *>>()
        val changes = AtomicInteger()
        books.value.beforeVersionReturn = { book ->
            if (book.key == genericKey) {
                books.value.beforeVersionReturn = null
                val chapter = destination.chapters.first { it.blocks.any { block -> block.kind == BookBlockKind.PRAYER && !block.isAltarText } }
                val block = chapter.blocks.first { it.kind == BookBlockKind.PRAYER && !it.isAltarText }
                val independent = BookReadingPosition(destination.key, destination.contentHash, parish, block.id, chapter.id,
                    fraction = .19f, wordsPerMinute = 119, lastTextMode = BookReadingMode.ORIGINAL)
                assertTrue(BookReadingStore(readingPreferences).save(independent))
                changed.set(independent)
                expectedPreferences.set(readingPreferences.all.toMap())
                changes.incrementAndGet()
            }
        }
        books.value.releasePending()
        compose.waitUntil(5_000) { changes.get() == 1 }
        await("book_load_error")
        compose.onNodeWithTag("book_content").assertDoesNotExist()
        compose.onNodeWithTag("book_load_error").assertTextContains("Не удалось сохранить выбранный текст", substring = true)
        assertEquals(changed.get(), saved(destination.key))
        assertEquals(expectedPreferences.get(), readingPreferences.all)
        assertSamePosition(old, saved(genericKey))
        assertEquals(PatronalProfileLookup.Absent, choices.readProfile(parish))
        assertFalse(readingPreferences.all.keys.any { it.startsWith("dedication:") })
    }

    @Test fun networkAndUnsavedBookFailuresOfferExplicitReturnWithoutAcceptingADedication() {
        launch()
        for ((index, outcome) in listOf(Outcome.NETWORK_FAILURE, Outcome.UNSAVED).withIndex()) {
            books.value.outcome = outcome
            val old = chooseMayFromTheRealMissingTroparion()
            books.value.releasePending()
            compose.waitUntil(5_000) { books.value.finished.get() == index + 1 }
            await("book_load_error")
            compose.onNodeWithTag("book_content").assertDoesNotExist()
            assertEquals(PatronalProfileLookup.Absent, choices.readProfile(parish))
            assertNull(store.value.positionFor(route.value.activeKey, parish))
            assertSamePosition(old, saved(genericKey))
            compose.onNodeWithTag("book_dedication_failed_return").performScrollTo().performClick()
            awaitOriginal()
            assertSamePosition(old, saved(genericKey))
            assertNull(route.value.pendingDedication)
            assertNull(route.value.sourceReturn)
        }
    }

    @Test fun aFailedReturnKeepsTheCurrentPublishedHymnAndAllowsAnExplicitRetry() {
        launch()
        val old = chooseMayFromTheRealMissingTroparion()
        books.value.releasePending()
        awaitDestination()
        awaitPublishedProfile()
        val current = saved(destination.key)
        val currentRoute = route.value
        books.value.hideVersionFor = genericKey
        openDetails()
        details("book_dedication_return").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("book_dedication_profile_message").fetchSemanticsNodes().any {
                it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                    .any { text -> text.text.contains("Текущее чтение осталось") || text.text.contains("Не удалось вернуться") }
            }
        }
        assertEquals(currentRoute, route.value)
        assertSamePosition(current, saved(destination.key))
        assertEquals(PatronalProfileLookup.Found(proof()), choices.readProfile(parish))
        dismissSheet()
        assertHymnCursor()
        books.value.hideVersionFor = null
        openDetails()
        details("book_dedication_return").performClick()
        awaitOriginal()
        assertSamePosition(old, saved(genericKey))
    }

    private fun launch() {
        assertTrue(ServiceBookIntegrity.valid(original))
        assertTrue(cache.write(original))
        val slot = missingTroparion()
        val chapter = original.chapters.single { slot in it.blocks }
        assertTrue(store.value.save(BookReadingPosition(genericKey, original.contentHash, parish, slot.id, chapter.id,
            wordsPerMinute = 107, lastTextMode = BookReadingMode.ORIGINAL)))
        compose.setContent { GuideTheme(false) {
            if (mounted.value) {
                val owner = route.value
                ServiceBookScreen(owner.activeKey, parish, books.value, store.value, 0, onBack = {}, onSettings = {}, onOpenUrl = {},
                    route = owner, dedicationServices = services, isRouteCurrent = { route.value == it },
                    onRouteChanged = { next -> if (route.value != owner) false else { route.value = next; true } },
                    onOpenCommonLiturgy = {})
            }
        } }
        contentSet = true
        awaitOriginal()
    }

    private fun chooseMayFromTheRealMissingTroparion(): BookReadingPosition {
        val entry = compose.onNodeWithTag("book_dedication_entry")
        entry.assert(hasAnyAncestor(hasTestTag("book_block_${missingTroparion().id}")))
        if (!entry.isDisplayed()) entry.performScrollTo()
        entry.assertIsDisplayed().assertTextEquals("Подобрать текст")
        settle()
        assertEquals(missingTroparion().id, saved(genericKey).blockId)
        entry.performClick()
        await("book_dedication_query")
        val query = compose.onNodeWithTag("book_dedication_query").performScrollTo()
        query.performTextReplacement("Николай Чудотворец")
        query.performImeAction()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("book_dedication_results_status").fetchSemanticsNodes().any { node ->
                node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                    .any { it.text == "Праздники посвящения в 2026 году" }
            }
        }
        chooser("book_dedication_result_11183").performClick()
        await("book_dedication_confirmed_title")
        chooser("book_dedication_confirmed_title").assertTextContains("Перенесение мощей", substring = true)
        assertEquals("11183", proof().mineaId)
        assertEquals("2026-05-22", proof().calendarDate)
        chooser("book_dedication_open").performClick()
        compose.waitUntil(5_000) { books.value.inFlight.get() == 1 }
        assertEquals(proof(), route.value.pendingDedication?.selection)
        val old = requireNotNull(route.value.sourceReturn).position
        assertEquals(missingTroparion().id, old.blockId)
        assertSamePosition(old, saved(genericKey))
        return old
    }
    private fun awaitOriginal() { await("book_content"); compose.waitUntil(5_000) { store.value.last(parish)?.contentHash == original.contentHash }; settle() }
    private fun awaitDestination() {
        compose.waitUntil(5_000) { ::destination.isInitialized && store.value.last(parish)?.contentHash == destination.contentHash && exists("book_content") }
        settle()
        assertEquals(destination.key, route.value.activeKey)
    }
    private fun awaitPublishedProfile() { compose.waitUntil(5_000) { route.value.pendingDedication == null && choices.readProfile(parish) == PatronalProfileLookup.Found(proof()) } }
    private fun assertHymnCursor() {
        val at = saved(destination.key)
        assertEquals(hymn().id, at.blockId)
        assertEquals(BookAnchorKind.BLOCK, at.anchorKind)
        assertEquals(0f, at.fraction, .01f)
        assertEquals(107, at.wordsPerMinute)
        assertNotEquals(destination.blocks.first().id, at.blockId)
        compose.onNodeWithTag("book_text_${hymn().id}", useUnmergedTree = true).assertIsDisplayed().assertTextEquals(hymn().original)
    }
    private fun scrollExactlyTo(book: ServiceBook, id: String) {
        val ids = book.chapters.filter { it.blocks.any { block -> !block.isAltarText } }
            .flatMap { listOf("chapter:${it.id}") + it.blocks.filter { block -> !block.isAltarText }.map { block -> block.id } }
        compose.onNodeWithTag("book_content").performScrollToIndex(ids.indexOf(id).also { assertTrue(it >= 0) })
        compose.waitUntil(5_000) { store.value.positionFor(book.key, parish)?.blockId == id }
        settle()
    }
    private fun remount(next: BookRoute, offline: Boolean) {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        val owner = newBooks().also { it.denyLoads = offline }
        compose.runOnIdle { route.value = next; books.value = owner; store.value = BookReadingStore(readingPreferences); mounted.value = true }
    }
    private fun openDetails() {
        compose.onNodeWithContentDescription("Настроить чтение").performClick()
        compose.onNodeWithTag("book_options_offline").performScrollTo().performClick()
        await("book_calendar")
    }
    private fun details(tag: String) = reveal(tag, "book_calendar")
    private fun chooser(tag: String) = reveal(tag, "book_dedication_chooser")
    private fun reveal(tag: String, parent: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(parent).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }
    private fun dismissSheet() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).assertCountEquals(1)[0]
            .performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
        settle()
    }
    private fun proof() = requireNotNull(choices.find("111300"))
    private fun saved(key: ServiceBookKey) = requireNotNull(store.value.positionFor(key, parish))
    private fun assertSamePosition(expected: BookReadingPosition, actual: BookReadingPosition) {
        fun zeros(value: BookReadingPosition) = value.copy(fraction = if (value.fraction == 0f) 0f else value.fraction,
            followingGapFraction = if (value.followingGapFraction == 0f) 0f else value.followingGapFraction)
        assertEquals(zeros(expected), zeros(actual))
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun await(tag: String) { compose.waitUntil(5_000) { exists(tag) } }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(700); compose.waitForIdle() }
    private fun missingTroparion(): BookBlock = afterTroparionLabel(original).first { it.kind == BookBlockKind.LOCAL_VARIATION }
    private fun hymn(): BookBlock = afterTroparionLabel(destination).first { it.kind == BookBlockKind.PRAYER && !it.isAltarText }
    private fun afterTroparionLabel(book: ServiceBook): List<BookBlock> {
        val index = book.blocks.indexOfFirst { BookText.normalized(it.original) == "тропарь храма" }
        assertTrue("The actual source must name the temple troparion", index >= 0)
        return book.blocks.drop(index + 1)
    }
    private fun asset(path: String) = instrumentation.context.assets.open(path).use { it.readBytes() }
    private fun parse(file: String, key: ServiceBookKey, selection: PatronalEventProof? = null): ServiceBook {
        val bytes = asset("dedication/source/$file")
        val provenance = JsonParser.parseString(asset("dedication/source/provenance.json").toString(Charsets.UTF_8)).asJsonObject
        val record = provenance.getAsJsonArray("fixtures").single { it.asJsonObject["file"].asString == file }.asJsonObject
        assertEquals(record["fixtureSha256"].asString, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        return ServiceBookParser().parse(Jsoup.parse(bytes.toString(Charsets.UTF_8)), key, 12345, patronalSelection = selection)
            .also { assertTrue(ServiceBookIntegrity.valid(it)) }
    }
    private fun newBooks() = DiskBooks(directory) { key ->
        val selected = proof()
        assertTrue(selected.matches(key))
        parse("nicholas-may-patronal.html", key, selected).also { destination = it }
    }.also { owners += it }
    private enum class Outcome { SAVED, NETWORK_FAILURE, UNSAVED }
    private data class Request(val key: ServiceBookKey, val force: Boolean)
    private class DiskBooks(directory: File, private val parsed: (ServiceBookKey) -> ServiceBook) : ServiceBooks {
        private val cache = FileBookCache(directory)
        val loads = CopyOnWriteArrayList<Request>()
        val inFlight = AtomicInteger()
        val finished = AtomicInteger()
        private val pending = AtomicReference<CompletableDeferred<Unit>?>(null)
        @Volatile var denyLoads = false
        @Volatile var outcome = Outcome.SAVED
        @Volatile var hideVersionFor: ServiceBookKey? = null
        @Volatile var beforeVersionReturn: ((ServiceBook) -> Unit)? = null
        override fun cached(key: ServiceBookKey) = cache.read(key)
        override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? {
            if (key == hideVersionFor) return null
            return cache.readVersion(key, contentHash)?.also { beforeVersionReturn?.invoke(it) }
        }
        override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
            loads += Request(key, force)
            check(!denyLoads) { "No book load is allowed during offline remount" }
            val gate = CompletableDeferred<Unit>()
            check(pending.compareAndSet(null, gate))
            inFlight.incrementAndGet()
            try {
                gate.await()
                if (outcome == Outcome.NETWORK_FAILURE) return BookLoadResult.Failed(BookFailure.NETWORK, "Нет связи для выбранного текста посвящения")
                val book = parsed(key)
                if (outcome == Outcome.SAVED) check(cache.write(book))
                return BookLoadResult.Ready(book, fromCache = false, saved = outcome == Outcome.SAVED)
            } finally { pending.compareAndSet(gate, null); inFlight.decrementAndGet(); finished.incrementAndGet() }
        }
        fun releasePending() { pending.get()?.complete(Unit) }
    }
    private class ProfilePreferences(private val backing: SharedPreferences) : SharedPreferences by backing {
        @Volatile var rejectProfiles = false
        @Volatile var beforeProfileCommit: (() -> Unit)? = null
        val rejected = AtomicInteger()
        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            var profile = false
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    if (key?.startsWith("patronal:profile:") == true) profile = true
                    editor.putString(key, value)
                    return this
                }
                override fun commit(): Boolean {
                    if (profile) {
                        beforeProfileCommit?.invoke()
                        if (rejectProfiles) { rejected.incrementAndGet(); return false }
                    }
                    return editor.commit()
                }
            }
        }
    }
}

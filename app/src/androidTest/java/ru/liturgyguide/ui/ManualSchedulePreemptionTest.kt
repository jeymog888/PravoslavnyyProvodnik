package ru.liturgyguide.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.SavedChurchRecord
import ru.liturgyguide.service.schedule.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Actual Main navigation and production repository/cache/parser; only remote responses are controlled. */
@RunWith(Parameterized::class)
class ManualSchedulePreemptionTest(private val churchPage: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "manual-schedule-${UUID.randomUUID()}"
    private val preferenceNames = ConcurrentHashMap.newKeySet<String>()
    private val isolatedContext = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val owned = "$prefix-$name"
            preferenceNames += owned
            return context.getSharedPreferences(owned, mode)
        }
    }
    private val userPreferences = listOf("guide_preferences", "service_book_reading", "internet_schedule_cache")
        .associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
    private val mounted = mutableStateOf(true)
    private var contentSet = false
    private val church = SavedChurchRecord.create("Храм в поездке", "Москва", "Тестовая улица, 7", "Europe/Moscow")
    private val parish = church.asParish()
    private val now = Instant.parse("2026-12-31T10:00:00Z")
    private val manualUrl = "http://example.org/manual-schedule/"
    private val oldUrl = "https://example.org/automatic-schedule/"
    private val requests = CopyOnWriteArrayList<Request>()
    private val cancelled = CopyOnWriteArrayList<Request>()
    private val fetched = CopyOnWriteArrayList<String>()
    private val writes = CopyOnWriteArrayList<String>()
    private val lookupCancellations = AtomicInteger()
    @Volatile private var pendingLookup: CancellableContinuation<ScheduleSite?>? = null
    private val preferences get() = isolatedContext.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val cachePreferences get() = isolatedContext.getSharedPreferences("internet_schedule_cache", Context.MODE_PRIVATE)
    private val cache = object : ScheduleCacheStore {
        override fun read(parishId: String): String? = cachePreferences.getString(parishId, null)
        override fun write(parishId: String, value: String) {
            check(cachePreferences.edit().putString(parishId, value).commit())
            writes += parishId
        }
    }
    private val repository = InternetScheduleRepository(ScheduleTransport { url ->
        fetched += url
        check(url == manualUrl) { "The cancelled lookup must never reach its late website: $url" }
        ScheduleResponse(url, """<article data-day="2026-12-31"><li><time>14:30</time><span>Литургия</span></li></article>"""
            .toByteArray(), "text/html; charset=utf-8")
    }, cache, siteResolver = ScheduleSiteResolver {
        suspendCancellableCoroutine { continuation ->
            check(pendingLookup == null) { "Only the initial automatic lookup is expected" }
            pendingLookup = continuation
            continuation.invokeOnCancellation { lookupCancellations.incrementAndGet() }
        }
    })
    private val schedules = object : InternetSchedules {
        override val progress get() = repository.progress
        override fun cached(parish: Parish) = repository.cached(parish)
        override suspend fun refresh(parish: Parish, now: Instant, force: Boolean, sourceOverride: String?): InternetScheduleState {
            val request = Request(parish.id, force, sourceOverride)
            requests += request
            return try { repository.refresh(parish, now, force, sourceOverride) }
            catch (failure: CancellationException) { cancelled += request; throw failure }
        }
    }
    private data class Request(val parishId: String, val force: Boolean, val source: String?)

    @After fun clean() {
        if (contentSet) {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
        }
        pendingLookup?.cancel()
        preferenceNames.forEach { context.deleteSharedPreferences(it) }
        userPreferences.forEach { (name, before) ->
            assertEquals("Main recovery must leave the user's $name unchanged", before,
                context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
        }
    }

    @Test fun manualWebsiteCancelsPendingAutoLookupAndOnlyItsResultReachesTheScreenAndCache() {
        val report = JSONObject().put("scope", "Actual AppNavigation LaunchedEffect, production repository/parser and private persisted cache; controlled remote answers")
            .put("churchPage", churchPage).put("allPassed", false)
        try {
            check(ChurchDirectoryStore(preferences).save(church))
            check(preferences.edit().putString("church_id", church.id).putBoolean("dark", false).commit())
            val books = NavigationMigrationBooks()
            val container = AppContainer(isolatedContext, scheduleOverride = schedules, booksOverride = books)
            compose.setContent {
                CompositionLocalProvider(LocalContext provides isolatedContext,
                    LocalActivityResultRegistryOwner provides compose.activity) {
                    if (mounted.value) AppNavigation(container, Clock.fixed(now, ZoneOffset.UTC))
                }
            }
            contentSet = true
            compose.waitUntil(10_000) { pendingLookup != null }
            val oldLookup = requireNotNull(pendingLookup)
            assertTrue(oldLookup.isActive)
            assertEquals(listOf(Request(church.id, false, null)), requests.toList())
            if (churchPage) compose.onNodeWithTag("nav_church").performClick()
            val content = if (churchPage) "church_content" else "main_content"
            val edit = if (churchPage) "edit_schedule_website" else "today_edit_schedule_website"
            val time = if (churchPage) "church_service_time" else "today_service_time"
            reveal(content, edit).performClick()
            reveal("schedule_source_content", "schedule_website_input").performTextInput(manualUrl)
            val submit = reveal("schedule_source_content", "save_schedule_website").assertIsEnabled()
            val heldSubmit = requireNotNull(submit.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            submit.performClick()
            compose.runOnIdle { heldSubmit(); heldSubmit() }
            compose.onNodeWithTag("schedule_source_content").assertDoesNotExist()
            compose.waitUntil(10_000) {
                cancelled.any { it.source == null } && repository.cached(parish).status == InternetScheduleStatus.READY
            }
            assertEquals(listOf(Request(church.id, false, null), Request(church.id, true, manualUrl)), requests.toList())
            assertEquals(listOf(Request(church.id, false, null)), cancelled.toList())
            assertEquals(1, lookupCancellations.get())
            assertTrue(oldLookup.isCancelled)
            assertEquals(listOf(manualUrl), fetched.toList())
            val accepted = repository.cached(parish)
            assertEquals(manualUrl, accepted.customSource)
            assertEquals(manualUrl, accepted.sourceUrl)
            assertEquals(listOf(InternetService("2026-12-31", "14:30", "Литургия", "liturgy")), accepted.publication?.events)
            reveal(content, time).assertTextEquals("14:30")
            val stored = requireNotNull(cache.read(church.id))
            assertEquals(listOf(church.id), writes.toList())

            // Deliver the remote callback after cancellation. The cancelled continuation
            // must not revive the old job, fetch its page or replace the accepted state.
            oldLookup.resume(ScheduleSite(oldUrl, "https://example.org/directory/", parish.name,
                ScheduleEntityIdentity.bind(parish, oldUrl, "https://example.org/directory/", parish.name,
                    ScheduleBindingMethod.ARTICLE_SCOPE)))
            compose.mainClock.advanceTimeBy(650)
            compose.waitForIdle()
            assertEquals(accepted, repository.cached(parish))
            assertEquals(stored, cache.read(church.id))
            assertEquals(listOf(church.id), writes.toList())
            assertEquals(listOf(manualUrl), fetched.toList())
            assertEquals(2, requests.size)
            reveal(content, time).assertTextEquals("14:30")
            val offline = InternetScheduleRepository(ScheduleTransport { error("Offline read must not request transport") }, cache)
            assertEquals(accepted, offline.cached(parish))
            assertTrue(books.requests.isEmpty())
            report.put("autoCancelled", true).put("lateLookupIgnored", true).put("manualSource", manualUrl)
                .put("manualTime", "14:30").put("cacheWrites", writes.size).put("offlineCacheEqual", true).put("allPassed", true)
        } finally {
            report.put("requests", JSONArray(requests.map { JSONObject().put("parishId", it.parishId).put("force", it.force).put("source", it.source) }))
                .put("cancelledRequests", cancelled.size).put("lookupCancellations", lookupCancellations.get())
                .put("fetchedUrls", JSONArray(fetched.toList()))
            val run = InstrumentationRegistry.getArguments().getString("parishRecoveryRun")
            if (run != null) {
                require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
                val output = File(requireNotNull(context.getExternalFilesDir("parish-recovery")), "$run/main-preemption")
                check(output.mkdirs() || output.isDirectory)
                File(output, "${if (churchPage) "church" else "today"}.json").writeText(report.toString(2))
            }
        }
    }

    private fun reveal(containerTag: String, tag: String): SemanticsNodeInteraction {
        val container = compose.onNodeWithTag(containerTag)
        container.performScrollToIndex(0)
        repeat(40) {
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull() != null) {
                val viewport = container.getBoundsInRoot()
                val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                if (bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
                    return compose.onNodeWithTag(tag).assertIsDisplayed()
            }
            container.performTouchInput {
                swipe(Offset(centerX, height * .8f), Offset(centerX, height * .55f), durationMillis = 800)
            }
        }
        throw AssertionError("Could not reveal $tag by touch in $containerTag")
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "church={0}") fun screens() = listOf(arrayOf(false), arrayOf(true))
    }
}

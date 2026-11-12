package ru.liturgyguide.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.DpRect
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.LiturgyGuideApplication
import ru.liturgyguide.service.ChurchDirectoryStore
import ru.liturgyguide.service.SavedChurchOrigin
import ru.liturgyguide.service.SavedChurchRecord
import ru.liturgyguide.service.discovery.CatalogPlaceDiscovery
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Opt-in acceptance on the owned emulator. Uses the real MainActivity and current app data.
 * The explicit UI selection (and any normal reading-reset confirmation) is retained afterward
 * so root can perform a separate cold, offline launch. No preferences or caches are seeded/cleared.
 */
@RunWith(AndroidJUnit4::class)
class LinkedChurchMainFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences("guide_preferences", Context.MODE_PRIVATE)
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()
    private lateinit var evidence: File
    private val captures = mutableListOf<Map<String, Any>>()
    private val targetId = "osm:way:77503842"
    private val targetName = "Храм Николая Чудотворца (Воронеж)"

    @Test fun realOfflineSearchCanBeReviewedChosenAndReopenedWithoutErasingOtherChurches() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit real-state selection opt-in", arguments.getString("feoriaLinkedChurchMain") == "true")
        check(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("/sdk_gphone")) {
            "Real-state selection is restricted to the owned Android emulator"
        }
        check(Build.VERSION.SDK_INT >= 29) { "Frame-commit evidence requires Android 10 or newer" }
        val run = requireNotNull(arguments.getString("linkedChurchMainRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        evidence = File(requireNotNull(context.getExternalFilesDir("linked-church-main")), run)
        check(!evidence.exists() && evidence.mkdirs()) { "Use a unique evidence run ID" }
        val before = snapshotPreferences()
        val selectedBefore = preferences.getString("church_id", null)
        val serializedRecordsBefore = preferences.getString(ChurchDirectoryStore.RECORDS_KEY, null)
        val recordsBefore = ChurchDirectoryStore(preferences).records()
        val receipt = linkedMapOf<String, Any?>("passed" to false, "run" to run,
            "fingerprint" to Build.FINGERPRINT, "hardware" to Build.HARDWARE,
            "beforeSelectedId" to selectedBefore, "captures" to captures,
            "statePolicy" to "No seed or cleanup; explicit selected church is retained",
            "limits" to "Activity relaunch in one process; no schedule, GPS, physical device or human 20-second claim",
            "offlinePolicy" to "Root disables radios externally; test does not alter connectivity or replace transports")
        writeJson("preferences-before.json", before)
        writeJson("records-before.json", recordsBefore)
        try {
            assertTrue((context.applicationContext as LiturgyGuideApplication).container.placeDiscovery is CatalogPlaceDiscovery)
            assertFalse("This scenario must review a newly found record, not a previously saved shortcut",
                recordsBefore.any { it.id == targetId })
            receipt["apkSha256"] = sha256(File(context.applicationInfo.sourceDir).readBytes())
            receipt["assetSha256"] = sha256(context.assets.open("discovery/churches-ru.json").use { it.readBytes() })
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitTag("nav_church")
                compose.onNodeWithTag("nav_church").assertIsDisplayed().performClick()
                awaitTag("church_content")
                reveal("change_church", "church_content").performClick()
                awaitTag("discovery_content")
                reveal("discovery_query", "discovery_content").performTextReplacement("Никольский Воронеж")
                closeSoftKeyboardWithCiDiagnostics()
                compose.onNodeWithTag("discovery_mode_name").assertIsSelected()
                compose.onNodeWithTag("discovery_query").assertTextContains("Никольский Воронеж")
                assertUnselected(selectedBefore, serializedRecordsBefore)
                val started = SystemClock.elapsedRealtime()
                reveal("discovery_submit", "discovery_content").performClick()
                val targetTag = "discovery_church_$targetId"
                compose.waitUntil(timeoutMillis = 20_000) {
                    assertUnselected(selectedBefore, serializedRecordsBefore)
                    compose.onAllNodes(hasTestTag("discovery_submit") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                }
                receipt["instrumentedSearchMillis"] = SystemClock.elapsedRealtime() - started
                if (exists("discovery_message") || exists("discovery_empty")) {
                    capture("search-error", scenario)
                    fail("The real search did not provide the target; see search-error evidence")
                }
                val target = reveal(targetTag, "discovery_content")
                target.assertTextContains(targetName, substring = true)
                target.assertTextContains("Таранченко", substring = true)
                assertUnselected(selectedBefore, serializedRecordsBefore)
                capture("results-before-selection", scenario)
                writeJson("preferences-before-selection.json", snapshotPreferences())
                assertUnselected(selectedBefore, serializedRecordsBefore)
                target.performClick()
                receipt["resultSelectionTaps"] = 1
                compose.waitUntil(timeoutMillis = 10_000) {
                    exists("church_change_confirmation") || preferences.getString("church_id", null) == targetId
                }
                val confirmation = exists("church_change_confirmation")
                receipt["normalReadingResetConfirmationShown"] = confirmation
                if (confirmation) {
                    assertEquals(selectedBefore, preferences.getString("church_id", null))
                    capture("change-confirmation", scenario)
                    compose.onNodeWithTag("confirm_church_change").assertIsDisplayed().performClick()
                }
                compose.waitUntil(timeoutMillis = 10_000) { preferences.getString("church_id", null) == targetId }
                awaitTag("church_content")
                val saved = assertSavedTarget(recordsBefore)
                assertVisibleIdentity()
                writeJson("selected-record.json", saved)
                writeJson("preferences-selected.json", snapshotPreferences())
                capture("selected-church", scenario)
            }
            // New Activity with no saved-instance bundle: selection must come from real storage.
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitTag("nav_church")
                assertEquals(targetId, preferences.getString("church_id", null))
                compose.onNodeWithTag("nav_church").assertIsDisplayed().performClick()
                awaitTag("church_content")
                assertVisibleIdentity()
                assertSavedTarget(recordsBefore)
                capture("reopened-church", scenario)
            }
            receipt["selectedAfterRelaunch"] = preferences.getString("church_id", null)
            receipt["otherSavedRecordsUnchanged"] = true
            receipt["visualVerdict"] = "Awaiting root screenshot review"
            receipt["passed"] = true
        } catch (failure: Throwable) {
            receipt["failureType"] = failure.javaClass.name
            receipt["failureMessage"] = failure.message.orEmpty()
            throw failure
        } finally {
            receipt["selectedAtEnd"] = preferences.getString("church_id", null)
            writeJson("preferences-after.json", snapshotPreferences())
            writeJson("records-after.json", ChurchDirectoryStore(preferences).records())
            writeJson("receipt.json", receipt)
        }
    }

    private fun assertUnselected(selected: String?, records: String?) {
        assertEquals("Searching must not change the selected church", selected, preferences.getString("church_id", null))
        assertEquals("Searching must not save a result", records, preferences.getString(ChurchDirectoryStore.RECORDS_KEY, null))
    }

    private fun assertSavedTarget(before: List<SavedChurchRecord>): SavedChurchRecord {
        assertEquals(targetId, preferences.getString("church_id", null))
        val records = ChurchDirectoryStore(preferences).records()
        assertEquals(before, records.filterNot { it.id == targetId })
        assertEquals(before.size + 1, records.size)
        return records.single { it.id == targetId }.also {
            assertEquals(targetName, it.name)
            assertEquals("Воронеж", it.city)
            assertEquals("Воронеж, улица Таранченко, 19А", it.address)
            assertEquals(51.65962070, requireNotNull(it.latitude), 0.00000001)
            assertEquals(39.21276430, requireNotNull(it.longitude), 0.00000001)
            assertEquals("https://www.openstreetmap.org/way/77503842", it.sourceUrl)
            assertEquals(SavedChurchOrigin.OPEN_STREET_MAP, it.origin)
            assertEquals("Europe/Moscow", it.timezone)
            assertEquals("", it.website)
        }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun awaitTag(tag: String) = compose.waitUntil(timeoutMillis = 15_000) { exists(tag) }
    private fun assertVisibleIdentity() {
        reveal("church_identity_card", "church_content")
        compose.onNode(hasText(targetName) and hasAnyAncestor(hasTestTag("church_identity_card")),
            useUnmergedTree = true).assertIsDisplayed()
    }
    private fun inside(target: DpRect, viewport: DpRect) = target.right > target.left && target.bottom > target.top &&
        target.top >= viewport.top && target.bottom <= viewport.bottom &&
        target.left >= viewport.left && target.right <= viewport.right

    private fun reveal(tag: String, containerTag: String): SemanticsNodeInteraction {
        val container = compose.onNodeWithTag(containerTag)
        repeat(12) {
            compose.waitForIdle()
            val present = exists(tag)
            val viewport = container.getBoundsInRoot()
            val box = if (present) compose.onNodeWithTag(tag).getUnclippedBoundsInRoot() else null
            if (box != null && inside(box, viewport)) return compose.onNodeWithTag(tag).assertIsDisplayed()
            val backward = box != null && box.top < viewport.top
            container.performTouchInput {
                swipe(Offset(centerX, height * (if (backward) .35f else .8f)),
                    Offset(centerX, height * (if (backward) .65f else .5f)), durationMillis = 500)
            }
        }
        throw AssertionError("Could not reveal $tag with bounded touch gestures in $containerTag")
    }

    private fun capture(label: String, scenario: ActivityScenario<MainActivity>) {
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        // Read lazy semantics children and bounds on the UI thread, not during scroll traversal.
        val tree = compose.runOnIdle {
            buildString {
                fun appendNode(node: SemanticsNode, depth: Int) {
                    append("  ".repeat(depth)).append(node.id).append(' ').append(node.boundsInRoot)
                        .append(' ').append(node.config).append('\n')
                    if (depth < 80) node.children.forEach { appendNode(it, depth + 1) }
                }
                roots.forEach { appendNode(it, 0) }
            }
        }
        File(evidence, "$label.semantics.txt").writeText(tree)
        val committed = CountDownLatch(1)
        scenario.onActivity {
            it.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            it.window.decorView.invalidate()
        }
        assertTrue("A real screen frame must be committed", committed.await(5, TimeUnit.SECONDS))
        var bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        var stable = false
        try {
            for (attempt in 0 until 10) {
                SystemClock.sleep(120)
                val next = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                stable = bitmap.sameAs(next)
                bitmap.recycle()
                bitmap = next
                if (stable) break
            }
            File(evidence, "$label.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            // A real schedule-loading indicator can keep animating offline. Preserve and label
            // the actual frame instead of waiting for an invented schedule success.
            captures += mapOf("label" to label, "twoIdenticalFrames" to stable,
                "widthPx" to bitmap.width, "heightPx" to bitmap.height)
        } finally { bitmap.recycle() }
    }

    private fun snapshotPreferences(): Map<String, Any?> = preferences.all.toSortedMap().mapValues { (_, value) ->
        if (value is Set<*>) value.map { it.toString() }.sorted() else value
    }
    private fun writeJson(name: String, value: Any?) = File(evidence, name).writeText(gson.toJson(value))
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

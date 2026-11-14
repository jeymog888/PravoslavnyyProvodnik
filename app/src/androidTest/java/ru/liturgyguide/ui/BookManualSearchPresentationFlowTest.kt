package ru.liturgyguide.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.speech.SpeechRecognizer
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.liturgyguide.model.*
import ru.liturgyguide.service.*
import ru.liturgyguide.service.book.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real modal/IME/window geometry; declared synthetic books, speech and permission transport. */
@RunWith(Parameterized::class)
class BookManualSearchPresentationFlowTest(private val dark: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val runId = "manual-search-${UUID.randomUUID()}"
    private val mounted = mutableStateOf(true)
    private val opening = mutableIntStateOf(0)
    private val heldWindow = mutableStateOf(false)
    private val currentBook = mutableStateOf(fixture(ServiceBookKey("2026-11-13", WorshipKind.VESPERS)))
    private var modalView: View? = null
    private var modalWindow: Window? = null
    private var contentSet = false
    private val selected = CopyOnWriteArrayList<BookLocatedSearchHit>()
    private val fake = FakeSpeech()
    private val permission = PermissionRegistry()
    @Volatile private var corpusContinuation: Continuation<BookSearchCorpus>? = null
    @Volatile private var alternativeContinuation: Continuation<String>? = null
    private val beforeMainFiles = mutableMapOf<String, String>()
    private var baselineCaptured = false
    private var restoreProbeCreated = 0
    private var restoreProbeSaved = 0
    private var restoreProbeRestored = 0
    private var restoreProbeState: Map<String, Any?> = emptyMap()

    @After fun clean() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        if (contentSet) {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
        }
        // A cancelled loader deliberately may finish late; its owner must already be gone.
        corpusContinuation?.let { it.resume(BookSearchCorpus(listOf(currentBook.value))) }; corpusContinuation = null
        alternativeContinuation?.let { it.resume("late cleanup") }; alternativeContinuation = null
        if (baselineCaptured) assertEquals("This UI harness must not write books or reading preferences", beforeMainFiles, protectedFiles())
    }

    @Test fun manualOpeningUsesActualImeAndCanChooseAResultWithoutTheIdleVoiceCard() = compactNative {
        launch()
        ime(true)
        query().assertIsFocused()
        compose.onNodeWithTag("book_listening_status").assertDoesNotExist()
        val mic = compose.onNodeWithTag("book_search_microphone").assertIsDisplayed()
            .assertContentDescriptionEquals("Найти по микрофону").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val title = compose.onNodeWithTag("book_search_title", useUnmergedTree = true)
        assertTrue(mic.getBoundsInRoot().left - title.getBoundsInRoot().right >= 12.dp)
        wholeWords("book_search_title", "Найти место", singleLine = true)
        wholeWords("book_search_query_label", "Слова из службы", singleLine = true)
        scope("Вечерня")
        capture("manual-ime-empty")
        query().performTextInput(PHRASE)
        compose.awaitBookSearch()
        wholeWords("book_search_query_label", "Слова из службы", singleLine = true)
        compose.onNodeWithTag("book_search_count").assertTextEquals("Совпадений: 2")
        val hitTag = "book_hit_target-one"
        val immediatelyDisplayed = compose.onAllNodesWithTag(hitTag).fetchSemanticsNodes().any {
            val visible = compose.onNodeWithTag(hitTag).getBoundsInRoot()
            visible.bottom > visible.top
        }
        record("initial-result", JSONObject().put("displayedWithoutScroll", immediatelyDisplayed))
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasTestTag(hitTag))
        compose.onNodeWithTag(hitTag).assertIsDisplayed()
        ime(true)
        capture("manual-ime-result")
        compose.onNodeWithTag(hitTag).performClick()
        compose.runOnIdle {
            assertEquals(1, selected.size)
            assertEquals(currentBook.value.key, selected.single().bookKey)
            assertEquals(currentBook.value.contentHash, selected.single().contentHash)
            assertEquals("target-one", selected.single().hit.blockId)
            assertEquals(selected.single(), selected.single().validatedFor(currentBook.value))
        }
    }

    @Test fun dismissingImeStaysDismissedThroughRepeatedMatchesAndLateCorpus() = compactNative {
        launch(holdCorpus = true)
        ime(true); query().assertIsFocused()
        scope("Вечерня")
        query().performTextInput(PHRASE); compose.awaitBookSearch()
        closeSoftKeyboard(); ime(false)
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasTestTag("book_hit_target-two"))
        compose.onNodeWithTag("book_hit_target-two").assertIsDisplayed()
        val neighbour = fixture(currentBook.value.key.copy(worship = WorshipKind.MATINS))
        compose.runOnIdle { requireNotNull(corpusContinuation).resume(BookSearchCorpus(listOf(currentBook.value, neighbour))); corpusContinuation = null }
        compose.awaitBookSearch(); ime(false)
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasTestTag("book_search_scope"))
        scope("Вечерня · Утреня")
        query().performTextReplacement("совсем отсутствующая фраза"); compose.awaitBookSearch()
        closeSoftKeyboard(); ime(false)
        val noHits = "Совпадений в обеих книгах нет. Исправьте услышанные слова или попробуйте другую короткую фразу"
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasText(noHits))
        compose.onNodeWithText(noHits).assertIsDisplayed()
        capture("multiple-then-zero-no-ime")
        assertTrue(selected.isEmpty())
    }

    @Test fun failedNeighbourListsOnlyTheActuallyLoadedBookAndKeepsTheExactError() = compactNative {
        launch(holdCorpus = true)
        ime(true)
        query().performTextInput("нет такой фразы"); closeSoftKeyboard()
        val message = "Сохраненная редакция утрени недоступна. Эта часть не проверена"
        compose.runOnIdle { requireNotNull(corpusContinuation).resume(BookSearchCorpus(listOf(currentBook.value), WorshipKind.MATINS, message)); corpusContinuation = null }
        compose.awaitBookSearch()
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasTestTag("book_search_scope"))
        scope("Вечерня")
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasTestTag("book_search_corpus_error"))
        compose.onNodeWithTag("book_search_corpus_error").assertTextEquals(message)
        val noHits = "Совпадений в открытой книге нет. Исправьте услышанные слова или попробуйте другую короткую фразу"
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasText(noHits))
        compose.onNodeWithText(noHits).assertIsDisplayed()
        compose.onNodeWithText("Совпадений в обеих книгах нет", substring = true).assertDoesNotExist()
        ime(false); capture("neighbour-not-loaded")
    }

    @Test fun microphoneEntryNeverAutofocusesAndItsRequestedPanelSurvivesCancelAndError() = compactNative {
        launch(startMicrophone = true)
        query().assertNotFocusedOrDisabled(); ime(false)
        compose.runOnIdle { assertEquals(1, fake.backend.starts); fake.backend.events!!.ready(); fake.backend.events!!.level(6f); fake.backend.events!!.partial("первая фраза") }
        compose.onNodeWithTag("book_search_microphone").assertDoesNotExist()
        node("book_listening_status").assertTextEquals("Слушаю службу")
        node("book_listening_partial").assertTextEquals("первая фраза")
        node("book_microphone_level").assertIsDisplayed()
        capture("listening-full-panel")
        val old = fake.backend.events!!
        node("book_listening_cancel").performClick()
        compose.runOnIdle { old.result(PHRASE); old.partial("late"); old.level(9f) }
        query().performScrollTo().assertIsEnabled().assertNotFocusedOrDisabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        node("book_listening_status").assertTextEquals("Найти по микрофону")
        node("book_listen").performClick()
        compose.runOnIdle { assertEquals(2, fake.backend.starts); fake.backend.events!!.error(SpeechRecognizer.ERROR_NO_MATCH) }
        node("book_listening_status").assertTextEquals("Не получилось распознать")
        query().performScrollTo().assertIsEnabled().assertNotFocusedOrDisabled()
        ime(false); capture("voice-error-full-panel")
        assertTrue(selected.isEmpty())
    }

    @Test fun initialUnavailableIsCompactUntilAnExplicitCheckAndNeverAutomaticallyStartsAfterAvailabilityChanges() = compactNative {
        fake.backend.available = false
        launch()
        ime(true); closeSoftKeyboard(); ime(false)
        compose.onNodeWithTag("book_listening_status").assertDoesNotExist()
        compose.onNodeWithTag("book_search_microphone")
            .assertContentDescriptionEquals("Найти по микрофону. Распознавание недоступно").performClick()
        node("book_listening_status").assertTextEquals("Микрофон недоступен")
        compose.onNodeWithTag("book_search_microphone").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, fake.backend.starts); fake.backend.available = true }
        node("book_listen").assertTextEquals("Проверить микрофон").performClick()
        node("book_listening_status").assertTextEquals("Найти по микрофону")
        compose.runOnIdle { assertEquals(0, fake.backend.starts) }
        node("book_listen").assertTextEquals("Послушать службу").performClick()
        compose.runOnIdle { assertEquals(1, fake.backend.starts) }
        query().performScrollTo().assertNotFocusedOrDisabled(); ime(false)
        capture("explicit-availability-check")
    }

    @Test fun aRestoredMicrophoneOpeningNeverStartsAgainButExplicitRetryAndANewOpeningMay() = compactNative {
        val restoration = launch(startMicrophone = true)
        compose.runOnIdle { assertEquals(1, fake.backend.starts) }
        query().assertNotFocusedOrDisabled(); ime(false)
        val beforeNonce = requireNotNull(restoreProbeState["nonce"])
        val restoredBefore = restoreProbeRestored
        recordRestoreProbe("before-emulate")
        restoration.emulateSavedInstanceStateRestore()
        recordRestoreProbe("after-emulate")
        assertEquals("The inner state must actually restore the same nonce", beforeNonce, restoreProbeState["nonce"])
        assertTrue("The inner Saver.restore must run", restoreProbeRestored > restoredBefore)
        assertEquals(true, restoreProbeState["targetUsesOuter"])
        compose.waitUntil(10_000) { modalView?.isAttachedToWindow == true && modalWindow != null }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Restoring an opening is not a fresh request", 1, fake.backend.starts) }
        observeIdleIme("restored-microphone")
        query().assertNotFocusedOrDisabled(); ime(false)
        node("book_listening_status").assertTextEquals("Найти по микрофону")
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasTestTag("book_search_query"))
        query().assertIsEnabled().performClick()
        ime(true); query().assertIsFocused().performTextInput("ручной ввод после восстановления")
        compose.runOnIdle { assertEquals(1, fake.backend.starts) }
        closeSoftKeyboard(); ime(false)
        node("book_listen").assertTextEquals("Послушать службу").performClick()
        compose.runOnIdle { assertEquals("A deliberate retry has its own existing ListeningRequestOwner token", 2, fake.backend.starts) }
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasTestTag("book_search_query"))
        query().assertNotFocusedOrDisabled(); ime(false)
        reopen(hold = false)
        compose.runOnIdle { assertEquals("A different explicit microphone opening may listen once", 3, fake.backend.starts) }
        query().assertNotFocusedOrDisabled(); ime(false)
        capture("microphone-restore-spent")
    }

    @Test fun pendingWindowAttemptIsRevokedByManualEditOrMicrophoneBeforeWindowFocusReturns() = compactNative {
        heldWindow.value = true
        launch()
        assertHeld()
        query().performSemanticsAction(SemanticsActions.SetText) { assertTrue(it(AnnotatedString("ручной ввод"))) }
        releaseWindow()
        query().assertNotFocusedOrDisabled(); ime(false)
        query().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("ручной ввод")))
        reopen(hold = true)
        assertHeld()
        compose.onNodeWithTag("book_search_microphone").performClick()
        releaseWindow()
        query().assertNotFocusedOrDisabled(); ime(false)
        compose.runOnIdle { assertEquals(1, fake.backend.starts) }
        node("book_listening_cancel").performClick()
        query().performScrollTo().assertNotFocusedOrDisabled(); ime(false)
        capture("revoked-before-window-focus")
    }

    @Test fun pendingPauseStopAndEditionReplacementCannotRefocusTheSameOpening() = compactNative {
        heldWindow.value = true
        launch()
        assertHeld()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        releaseWindow(); recordRestoreProbe("after-window-release"); query().assertNotFocusedOrDisabled(); ime(false)
        reopen(hold = true)
        assertHeld()
        compose.runOnIdle { currentBook.value = fixture(currentBook.value.key.copy(date = "2026-11-14")) }
        releaseWindow(); recordRestoreProbe("after-window-release"); query().assertNotFocusedOrDisabled(); ime(false)
        assertTrue(selected.isEmpty()); capture("owner-changed-without-autofocus")
    }

    @Test fun restorationWhileWindowFocusIsPendingIsSpentButANewExplicitOpeningMayFocus() = compactNative {
        heldWindow.value = true
        val restoration = launch()
        assertHeld()
        val beforeNonce = requireNotNull(restoreProbeState["nonce"])
        val restoredBefore = restoreProbeRestored
        recordRestoreProbe("before-emulate")
        restoration.emulateSavedInstanceStateRestore()
        recordRestoreProbe("after-emulate")
        assertEquals("The inner state must actually restore the same nonce", beforeNonce, restoreProbeState["nonce"])
        assertTrue("The inner Saver.restore must run", restoreProbeRestored > restoredBefore)
        assertEquals(true, restoreProbeState["targetUsesOuter"])
        assertHeld()
        releaseWindow(); recordRestoreProbe("after-window-release"); query().assertNotFocusedOrDisabled(); ime(false)
        reopen(hold = false)
        ime(true); query().assertIsFocused()
        capture("new-opening-after-spent-restore")
    }

    @Test fun deniedPermissionUsesTheRealActivityResultContractAndKeepsManualInputWithoutAutofocus() = compactNative {
        launch(permissionGranted = false)
        ime(true); closeSoftKeyboard(); ime(false)
        compose.onNodeWithTag("book_search_microphone").performClick()
        compose.waitUntil(5_000) { permission.pending != null }
        compose.runOnIdle { assertEquals(1, permission.requests); assertEquals(Manifest.permission.RECORD_AUDIO, permission.input) }
        node("book_listening_status").assertTextEquals("Нужно разрешение на микрофон")
        query().performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { permission.deny() }
        query().assertIsEnabled().assertNotFocusedOrDisabled()
        val denied = "Микрофон не разрешен. Можно найти место по словам"
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasText(denied))
        compose.onNodeWithText(denied).assertIsDisplayed()
        node("book_listening_status").assertTextEquals("Найти по микрофону")
        compose.runOnIdle { assertEquals(0, fake.backend.starts) }
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasTestTag("book_search_query"))
        query().performTextInput(PHRASE); compose.awaitBookSearch()
        closeSoftKeyboard(); ime(false)
        compose.onNodeWithTag("book_search_sheet").performScrollToNode(hasTestTag("book_hit_target-one"))
        compose.onNodeWithTag("book_hit_target-one").assertIsDisplayed()
        capture("permission-denied-manual-results")
    }

    @Test fun manualWordsOutliveSlowVoiceAlternativesAndLateCorpusWithoutRefocusing() = compactNative {
        launch(holdCorpus = true, holdAlternatives = true)
        ime(true); closeSoftKeyboard(); ime(false)
        compose.onNodeWithTag("book_search_microphone").performClick()
        compose.runOnIdle { fake.backend.events!!.ready(); fake.backend.events!!.alternatives(listOf("первое предположение", PHRASE)) }
        compose.waitUntil(5_000) { alternativeContinuation != null }
        query().performScrollTo().performTextReplacement("мой ручной запрос")
        closeSoftKeyboard(); ime(false)
        val neighbour = fixture(currentBook.value.key.copy(worship = WorshipKind.MATINS))
        compose.runOnIdle {
            requireNotNull(corpusContinuation).resume(BookSearchCorpus(listOf(currentBook.value, neighbour))); corpusContinuation = null
            requireNotNull(alternativeContinuation).resume(PHRASE); alternativeContinuation = null
        }
        compose.awaitBookSearch()
        query().performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("мой ручной запрос")))
        scope("Вечерня · Утреня"); ime(false)
        assertTrue(selected.isEmpty()); capture("manual-revision-kept")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun launch(startMicrophone: Boolean = false, holdCorpus: Boolean = false,
        holdAlternatives: Boolean = false, permissionGranted: Boolean = true): StateRestorationTester {
        beforeMainFiles.putAll(protectedFiles()); baselineCaptured = true
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val outerRegistry = LocalSaveableStateRegistry.current
            GuideTheme(dark) {
                if (mounted.value) key(opening.intValue) {
                    ModalBottomSheet(onDismissRequest = { mounted.value = false },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                        WindowGate()
                        val nativeRegistry = LocalSaveableStateRegistry.current
                        CompositionLocalProvider(LocalActivityResultRegistryOwner provides permission,
                            LocalSaveableStateRegistry provides outerRegistry) {
                            ModalRestoreProbe(outerRegistry, nativeRegistry)
                            BookSearchSheet(currentBook.value, startMicrophone, BookReadingMode.ORIGINAL,
                                onSelect = {}, speechFactory = fake.factory,
                                hasMicrophonePermission = { permissionGranted },
                                corpusLoader = if (holdCorpus) ({ withContext(NonCancellable) {
                                    suspendCoroutine { corpusContinuation = it }
                                } }) else null,
                                speechQuerySelector = if (holdAlternatives) ({ _, _ -> withContext(NonCancellable) {
                                    suspendCoroutine { alternativeContinuation = it }
                                } }) else null,
                                onSelectLocated = { selected += it })
                        }
                    }
                }
            }
        }
        contentSet = true
        compose.waitUntil(10_000) { modalView?.isAttachedToWindow == true && modalWindow != null }
        compose.waitForIdle()
        return restoration
    }

    @Composable private fun ModalRestoreProbe(outerRegistry: SaveableStateRegistry?, nativeRegistry: SaveableStateRegistry?) {
        val targetRegistry = LocalSaveableStateRegistry.current
        val nonce = rememberSaveable(saver = Saver<String, String>(
            save = { restoreProbeSaved++; it }, restore = { restoreProbeRestored++; it }
        )) { restoreProbeCreated++; UUID.randomUUID().toString() }
        SideEffect {
            restoreProbeState = mapOf("nonce" to nonce,
                "outerRegistryIdentity" to System.identityHashCode(outerRegistry),
                "innerRegistryIdentity" to System.identityHashCode(nativeRegistry),
                "sameRegistry" to (outerRegistry === nativeRegistry),
                "targetRegistryIdentity" to System.identityHashCode(targetRegistry),
                "targetUsesOuter" to (targetRegistry === outerRegistry), "testRegistryBridge" to true)
        }
    }
    private fun recordRestoreProbe(phase: String) {
        val config = compose.onAllNodesWithTag("book_search_query").fetchSemanticsNodes().singleOrNull()?.config
        val focused = if (config?.contains(SemanticsProperties.Focused) == true) config[SemanticsProperties.Focused] else null
        record("saveable-$phase", JSONObject(Gson().toJson(restoreProbeState + mapOf(
            "created" to restoreProbeCreated, "saved" to restoreProbeSaved, "restored" to restoreProbeRestored,
            "focused" to focused, "viewIdentity" to System.identityHashCode(modalView),
            "windowIdentity" to System.identityHashCode(modalWindow), "windowFocus" to modalView?.hasWindowFocus(),
            "windowFlags" to modalWindow?.attributes?.flags, "lifecycle" to compose.activity.lifecycle.currentState.toString(),
            "imeVisible" to modalView?.let(ViewCompat::getRootWindowInsets)?.isVisible(WindowInsetsCompat.Type.ime())
        ))))
    }
    private fun observeIdleIme(phase: String) {
        val start = android.os.SystemClock.uptimeMillis()
        var lastSample = -100L
        compose.waitUntil(3_000) {
            val elapsed = android.os.SystemClock.uptimeMillis() - start
            if (elapsed - lastSample >= 80L) {
                recordRestoreProbe("$phase-$elapsed")
                val evidence = nativeWindowEvidence().put("phase", phase).put("elapsedMs", elapsed)
                record("idle-ime-observation", evidence)
                assertTrue("Actual modal insets must be available", evidence.optBoolean("windowInsetsAvailable"))
                assertFalse("A restored microphone opening must not show IME at ${elapsed}ms", evidence.getBoolean("imeVisible"))
                assertEquals(0, evidence.getJSONArray("imeInsetsPx").getInt(3))
                lastSample = elapsed
                compose.mainClock.advanceTimeByFrame()
            }
            elapsed >= 1_000L
        }
    }
    private fun SemanticsNodeInteraction.assertNotFocusedOrDisabled(): SemanticsNodeInteraction {
        val config = fetchSemanticsNode().config
        return if (config.contains(SemanticsProperties.Focused))
            assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, false))
        else assertIsNotEnabled()
    }

    /** Block the real separate dialog window before its field placement can authorize focus. */
    @Composable private fun WindowGate() {
        val view = LocalView.current
        val held = heldWindow.value
        DisposableEffect(view, held) {
            var changed: Window? = null
            var originalFlag = false
            fun apply() {
                val provider = generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().firstOrNull()
                    ?: return
                val window = provider.window
                if (changed == null) { changed = window; originalFlag = window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0 }
                if (held) window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                modalWindow = window; modalView = view
            }
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = apply()
                override fun onViewDetachedFromWindow(v: View) = Unit
            }
            view.addOnAttachStateChangeListener(listener); apply()
            onDispose {
                view.removeOnAttachStateChangeListener(listener)
                changed?.let { if (originalFlag) it.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) else it.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) }
                if (modalView === view) { modalView = null; modalWindow = null }
            }
        }
    }

    private fun reopen(hold: Boolean) {
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        compose.runOnIdle { heldWindow.value = hold; opening.intValue++; mounted.value = true }
        compose.waitUntil(10_000) { modalView?.isAttachedToWindow == true && modalWindow != null }
        compose.waitForIdle()
    }
    private fun assertHeld() {
        compose.waitUntil(5_000) { modalWindow?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0 && modalView?.hasWindowFocus() == false }
        query().assertNotFocusedOrDisabled(); ime(false)
    }
    private fun releaseWindow() {
        compose.runOnIdle { heldWindow.value = false }
        compose.waitUntil(5_000) { modalView?.hasWindowFocus() == true }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(128); compose.waitForIdle()
    }
    private fun query() = compose.onNodeWithTag("book_search_query")
    private fun node(tag: String) = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    private fun scope(expected: String) = compose.onNodeWithTag("book_search_scope")
        .assertTextEquals((if (" · " in expected) "Тексты: " else "Текст: ") + expected)
    private fun ime(visible: Boolean) {
        compose.waitUntil(10_000) {
            var matches = false
            instrumentation.runOnMainSync {
                val view = modalView
                val insets = view?.let(ViewCompat::getRootWindowInsets)
                matches = view?.isAttachedToWindow == true && insets != null &&
                    insets.isVisible(WindowInsetsCompat.Type.ime()) == visible &&
                    (visible || insets.getInsets(WindowInsetsCompat.Type.ime()).bottom == 0)
            }
            matches
        }
        record("ime-state", nativeWindowEvidence().put("expectedImeVisible", visible))
    }
    private fun wholeWords(tag: String, expected: String, singleLine: Boolean = false) {
        try {
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            val fullBounds = node.getUnclippedBoundsInRoot()
            val visibleBounds = node.getBoundsInRoot()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val result = layouts.single()
            val linesFit = (0 until result.lineCount).all { line ->
                val left = result.getLineLeft(line); val right = result.getLineRight(line)
                val top = result.getLineTop(line); val bottom = result.getLineBottom(line)
                listOf(left, right, top, bottom).all { it.isFinite() } &&
                    left <= right && top <= bottom && !result.isLineEllipsized(line) &&
                    left >= 0f && right <= result.size.width && top >= 0f && bottom <= result.size.height
            }
            val textMatches = result.layoutInput.text.text == expected
            val completeText = textMatches && result.lineCount > 0 &&
                result.getLineEnd(result.lineCount - 1) == expected.length &&
                result.getLineEnd(result.lineCount - 1, visibleEnd = true) == expected.length
            val wordsFit = textMatches && result.lineCount > 0 && Regex("\\S+").findAll(expected).all { word ->
                result.getLineForOffset(word.range.first) == result.getLineForOffset(word.range.last)
            }
            val fits = fullBounds == visibleBounds && !result.didOverflowHeight && linesFit && completeText && wordsFit && (!singleLine || result.lineCount == 1)
            val out = File(context.getExternalFilesDir("manual-search-qa"), runId).apply { mkdirs() }
            File(out, "layout.ndjson").appendText(Gson().toJson(mapOf(
                "tag" to tag, "expected" to expected, "text" to result.layoutInput.text.text,
                "size" to listOf(result.size.width, result.size.height),
                "constraints" to result.layoutInput.constraints.toString(),
                "paragraphSize" to listOf(result.multiParagraph.width, result.multiParagraph.height),
                "fullBounds" to fullBounds.toString(), "visibleBounds" to visibleBounds.toString(),
                "linesFit" to linesFit, "completeText" to completeText, "wordsFit" to wordsFit, "fits" to fits,
                "fontScale" to context.resources.configuration.fontScale,
                "screenWidthDp" to context.resources.configuration.screenWidthDp,
                "maxLines" to result.layoutInput.maxLines, "lines" to result.lineCount,
                "overflowWidth" to result.didOverflowWidth, "overflowHeight" to result.didOverflowHeight,
                "lineMetrics" to (0 until result.lineCount).map { line -> listOf(
                    result.getLineStart(line), result.getLineEnd(line), result.getLineLeft(line),
                    result.getLineRight(line), result.getLineTop(line), result.getLineBottom(line),
                    result.isLineEllipsized(line), result.getLineEnd(line, visibleEnd = true)) }
            )) + "\n")
            assertEquals("The complete heading must remain inside the ancestor clip", fullBounds, visibleBounds)
            assertEquals(expected, result.layoutInput.text.text)
            assertFalse(result.didOverflowHeight)
            assertTrue("Every complete line must fit the measured text box without ellipsis", linesFit)
            assertTrue("The full expected text must be present in visible line ends", completeText)
            assertTrue("Words must not split across lines", wordsFit)
            if (singleLine) assertEquals("This compact heading must occupy one measured line", 1, result.lineCount)
        } catch (failure: Throwable) {
            if (failure is AssertionError || failure is Exception) {
                try { capture("failed-${tag.take(90)}") }
                catch (captureFailure: Throwable) { failure.addSuppressed(captureFailure) }
            }
            throw failure
        }
    }
    private fun capture(label: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val out = File(context.getExternalFilesDir("manual-search-qa"), runId).apply { mkdirs() }
        try { File(out, "$label-dark$dark.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val window = nativeWindowEvidence()
        val bounds = JSONObject().put("label", label).put("theme", if (dark) "dark" else "light").put("nativeWindow", window)
        val origin = window.optJSONArray("modalViewScreenOriginPx")
        val density = window.optDouble("densityPxPerDp", Double.NaN)
        for (tag in listOf("book_search_sheet", "book_search_query", "book_search_scope", "book_search_microphone",
            "book_hit_target-one", "book_hit_target-two")) {
            val nodes = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
            val entry = JSONObject().put("composed", nodes.isNotEmpty())
            if (nodes.isNotEmpty()) {
                val node = compose.onNodeWithTag(tag)
                val clipped = node.getBoundsInRoot(); val full = node.getUnclippedBoundsInRoot()
                entry.put("clippedRootDp", JSONArray(listOf(clipped.left.value, clipped.top.value, clipped.right.value, clipped.bottom.value)))
                    .put("unclippedRootDp", JSONArray(listOf(full.left.value, full.top.value, full.right.value, full.bottom.value)))
                if (origin != null && density.isFinite()) {
                    entry.put("derivedClippedScreenPx", JSONArray(listOf(
                        origin.getInt(0) + clipped.left.value * density, origin.getInt(1) + clipped.top.value * density,
                        origin.getInt(0) + clipped.right.value * density, origin.getInt(1) + clipped.bottom.value * density)))
                }
            }
            bounds.put(tag, entry)
        }
        record(label, bounds)
    }
    /** Raw native insets/frame; the bottom IME intersection is derived, not the full keyboard's measured window. */
    private fun nativeWindowEvidence(): JSONObject {
        val result = JSONObject()
        instrumentation.runOnMainSync {
            val view = modalView
            val decor = modalWindow?.decorView
            result.put("coordinateSystem", "physical screen pixels; Compose node root dp separately identified")
                .put("densityPxPerDp", compose.activity.resources.displayMetrics.density)
                .put("screenWidthDp", compose.activity.resources.configuration.screenWidthDp)
                .put("fontScale", compose.activity.resources.configuration.fontScale)
            if (view != null && decor != null) {
                val viewOrigin = IntArray(2); view.getLocationOnScreen(viewOrigin)
                val decorOrigin = IntArray(2); decor.getLocationOnScreen(decorOrigin)
                val frame = android.graphics.Rect(); decor.getWindowVisibleDisplayFrame(frame)
                result.put("modalViewAttached", view.isAttachedToWindow).put("modalViewHasWindowFocus", view.hasWindowFocus())
                    .put("modalViewScreenOriginPx", JSONArray(viewOrigin.toList()))
                    .put("modalViewSizePx", JSONArray(listOf(view.width, view.height)))
                    .put("decorScreenRectPx", JSONArray(listOf(decorOrigin[0], decorOrigin[1], decorOrigin[0] + decor.width, decorOrigin[1] + decor.height)))
                    .put("decorSizePx", JSONArray(listOf(decor.width, decor.height)))
                    .put("visibleWindowDisplayFrameScreenPx", JSONArray(listOf(frame.left, frame.top, frame.right, frame.bottom)))
                val insets = ViewCompat.getRootWindowInsets(view)
                result.put("windowInsetsAvailable", insets != null)
                if (insets != null) {
                    val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
                    result.put("imeVisible", visible).put("imeInsetsPx", JSONArray(listOf(ime.left, ime.top, ime.right, ime.bottom)))
                        .put("systemBarsInsetsPx", JSONArray(listOf(bars.left, bars.top, bars.right, bars.bottom)))
                        .put("derivedBottomImeIntersectionScreenPx", if (visible && ime.bottom > 0)
                            JSONArray(listOf(decorOrigin[0] + ime.left, decorOrigin[1] + decor.height - ime.bottom,
                                decorOrigin[0] + decor.width - ime.right, decorOrigin[1] + decor.height)) else JSONObject.NULL)
                }
            } else result.put("modalWindowAvailable", false)
        }
        return result
    }
    private fun record(label: String, value: JSONObject) {
        val out = File(context.getExternalFilesDir("manual-search-qa"), runId).apply { mkdirs() }
        File(out, "evidence-dark$dark.ndjson").appendText(JSONObject().put("event", label).put("data", value).toString() + "\n")
    }
    private fun protectedFiles(): Map<String, String> {
        val roots = listOf(File(context.applicationInfo.dataDir, "shared_prefs"), File(context.filesDir, "service-books-v2"))
        return roots.flatMap { directory -> directory.walkTopDown().filter(File::isFile).map {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
            it.relativeTo(File(context.applicationInfo.dataDir)).path to digest
        }.toList() }.toMap()
    }
    private fun compactNative(body: () -> Unit) {
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val oldFont = shell("settings get system font_scale").toFloatOrNull()
        val oldSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1)
        val density = compose.activity.resources.displayMetrics.density
        try {
            shell("settings put system font_scale 2.0")
            shell("wm size ${(320 * density).roundToInt()}x${(640 * density).roundToInt()}")
            compose.waitUntil(10_000) { var ready = false; compose.activityRule.scenario.onActivity {
                ready = abs(it.resources.configuration.fontScale - 2f) < .001f && it.resources.configuration.screenWidthDp in 318..322 && it.window.decorView.hasWindowFocus()
            }; ready }
            body()
        } finally {
            if (contentSet) { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED); compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
            try { shell("wm size ${oldSize ?: "reset"}") }
            finally { shell(if (oldFont == null) "settings delete system font_scale" else "settings put system font_scale $oldFont") }
        }
    }
    private class PermissionRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var pending: Int? = null; var requests = 0; var input: String? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            check(contract is ActivityResultContracts.RequestPermission)
            requests++; this.input = input as String; pending = requestCode
        }
        fun deny() {
            val code = requireNotNull(pending); pending = null
            // Raw contract result, not direct mutation of the composable's permission message.
            dispatchResult(code, Activity.RESULT_OK, Intent()
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, arrayOf(Manifest.permission.RECORD_AUDIO))
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED)))
        }
    }
    private class FakeSpeech {
        val backend = Backend()
        val factory: SpeechGuideFactory = { _, changed -> SpeechGuide(backend, SpeechScheduler { _, _ -> SpeechCancellation { } }, changed) }
    }
    private class Backend : SpeechBackend {
        override val apiLevel = 31
        var available = true; var starts = 0; var events: SpeechEvents? = null
        override fun isAvailable() = available
        override fun hasPermission() = true
        override fun create(events: SpeechEvents): SpeechAttempt {
            this.events = events
            return object : SpeechAttempt {
                override fun checkSupport(result: (SpeechSupport) -> Unit, error: (Int) -> Unit) = Unit
                override fun start() { starts++ }
                override fun finish() = Unit
                override fun release() = Unit
            }
        }
    }
    private companion object {
        const val PHRASE = "вечерняя тишина хранит надежду"
        fun fixture(key: ServiceBookKey): ServiceBook {
            val blocks = listOf(
                BookBlock("target-one", BookBlockKind.PRAYER, "Хор", "$PHRASE. Первая синтетическая строка для проверки выбора"),
                BookBlock("target-two", BookBlockKind.PRAYER, "Чтец", "$PHRASE. Вторая синтетическая строка для различения повторов")
            ) + List(14) { BookBlock("padding-$it", BookBlockKind.PRAYER, "Священник",
                "Синтетический скрытый текст для целостности тестовой книги. ".repeat(4), isAltarText = true) }
            val book = ServiceBook(key = key, title = "Синтетическая ${key.worship.title}", sourceUrl = key.sourceUrl(),
                sourceTitle = "Синтетический UI источник", fetchedAtMillis = 1, selectedEventTitle = null,
                chapters = listOf(BookChapter("chapter", "Синтетический раздел", "Синтетическое пояснение", blocks)), contentHash = "")
            return book.copy(contentHash = ServiceBookIntegrity.contentHash(book)).also { check(ServiceBookIntegrity.valid(it)) }
        }
        @JvmStatic @Parameterized.Parameters(name = "dark={0}") fun themes() = listOf(arrayOf(false), arrayOf(true))
    }
}

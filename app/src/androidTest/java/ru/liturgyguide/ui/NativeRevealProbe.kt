package ru.liturgyguide.ui

import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.toSize
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class NativeRevealNode(val id: Int, val rootId: Int, val ancestors: List<Int>,
    val attached: Boolean, val placed: Boolean, val clipped: DpRect?, val unclipped: DpRect?,
    val screen: Rect?, val text: String, val fontScale: Float)
internal data class NativeRevealFrame(val container: NativeRevealNode, val target: NativeRevealNode?) {
    val viewport: DpRect get() = requireNotNull(container.clipped)
    val targetBounds: DpRect? get() = target?.unclipped
}

/** Collection queries never singleton-fetch/print. All live semantics/layout reads stay on Main. */
internal class NativeRevealProbe(private val rule: ComposeContentTestRule,
    private val containerTag: String, private val targetTag: String) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val history = mutableListOf<Map<String, Any?>>()
    private val journal by lazy { File(requireNotNull(instrumentation.targetContext
        .getExternalFilesDir("quiet-reveal-diagnostics")), "reveal-${UUID.randomUUID()}.json") }
    private val routeTags = setOf("church_content", "main_content", "service_book_chooser", "church_picker",
        "schedule_date", "today_service_date", "service_chooser_date", "schedule_sources_heading",
        "church_service_title", "church_read_liturgy", "church_other_services", "personal_start_summary")

    private fun snapshot(stage: String): Map<String, List<NativeRevealNode>> {
        val nodes = rule.onAllNodes(SemanticsMatcher("all reveal diagnostic nodes") { true })
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        return rule.runOnUiThread {
            val wanted = routeTags + containerTag + targetTag
            val roots = mutableSetOf<Int>()
            val values = nodes.mapNotNull { node ->
                val parents = mutableListOf<Int>()
                var parent = node.parent
                while (parent != null) { parents += parent.id; parent = parent.parent }
                val root = parents.lastOrNull() ?: node.id
                roots += root
                val tag = node.config.getOrNull(SemanticsProperties.TestTag) ?: return@mapNotNull null
                if (tag !in wanted) return@mapNotNull null
                val layout = node.layoutInfo
                val live = layout.isAttached && layout.isPlaced
                val clipped = if (live) node.boundsInRoot else null
                val position = if (live) node.positionInRoot else null
                val unclipped = position?.let { Rect(it, node.size.toSize()) }
                val screen = if (live) node.positionOnScreen.let { onScreen ->
                    clipped!!.translate(onScreen - requireNotNull(position))
                } else null
                fun dpRect(rect: Rect?) = rect?.let { with(layout.density) {
                    DpRect(it.left.toDp(), it.top.toDp(), it.right.toDp(), it.bottom.toDp())
                } }
                tag to NativeRevealNode(node.id, root, parents, layout.isAttached, layout.isPlaced,
                    dpRect(clipped), dpRect(unclipped), screen,
                    node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty(), layout.density.fontScale)
            }.groupBy({ it.first }, { it.second })
            history += mapOf("stage" to stage, "clockMs" to rule.mainClock.currentTime,
                "allNodeCount" to nodes.size, "rootIds" to roots.toList(),
                "counts" to wanted.associateWith { values[it].orEmpty().size },
                "nodes" to values.mapValues { (_, entries) -> entries.map { entry ->
                    val invalid = mutableListOf<String>()
                    fun safe(field: String, values: List<Float>?) = values?.mapIndexed { index, value ->
                        if (value.isFinite()) value else { invalid += "$field[$index]"; value.toString() }
                    }
                    fun bounds(field: String, rect: DpRect?) = safe(field,
                        rect?.let { listOf(it.left.value, it.top.value, it.right.value, it.bottom.value) })
                    mapOf("id" to entry.id, "rootId" to entry.rootId, "ancestors" to entry.ancestors,
                        "attached" to entry.attached, "placed" to entry.placed,
                        "clippedDp" to bounds("clippedDp", entry.clipped), "unclippedDp" to bounds("unclippedDp", entry.unclipped),
                        "screenPx" to safe("screenPx", entry.screen?.let { listOf(it.left, it.top, it.right, it.bottom) }),
                        "text" to entry.text, "fontScale" to safe("fontScale", listOf(entry.fontScale))?.single(),
                        "invalidCoordinates" to invalid)
                } })
            values
        }
    }

    /** Missing/multiple/transient owners get at most three real composition frames, never a fallback root. */
    fun bounds(): NativeRevealFrame {
        repeat(4) { frame ->
            val nodes = try { snapshot("bounds-$frame") } catch (failure: Throwable) { fail("snapshot", failure) }
            val containers = nodes[containerTag].orEmpty()
            val targets = nodes[targetTag].orEmpty()
            val container = containers.singleOrNull()
            val target = targets.singleOrNull()
            val modalBlocksChurch = containerTag in setOf("church_content", "main_content") &&
                listOf("service_book_chooser", "church_picker").any { tag -> nodes[tag].orEmpty().any { it.attached && it.placed } }
            if (container != null && container.attached && container.placed && container.clipped != null &&
                container.clipped.right > container.clipped.left && container.clipped.bottom > container.clipped.top &&
                !modalBlocksChurch && targets.size <= 1 && (target == null ||
                    target.attached && target.rootId == container.rootId && container.id in target.ancestors)) {
                return NativeRevealFrame(container, target)
            }
            if (frame < 3) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
        }
        fail("Expected one live $containerTag and at most one target owned by that container")
    }

    /** Same native swipe fractions/duration as the old helpers, with screen coordinates from the guarded owner. */
    fun swipe(fromY: Float, toY: Float, durationMillis: Long) {
        require(fromY in 0f..1f && toY in 0f..1f && durationMillis in 1..2_000)
        val frame = bounds()
        try { realTouch(frame, fromY, toY, durationMillis) }
        catch (failure: Throwable) { fail("native-swipe", failure) }
    }

    private fun realTouch(frame: NativeRevealFrame, fromY: Float, toY: Float, durationMillis: Long) {
        val box = requireNotNull(frame.container.screen)
        if (!listOf(box.left, box.top, box.right, box.bottom).all { it.isFinite() }) fail("Invalid screen bounds")
        val x = box.center.x
        val start = box.top + box.height * fromY
        val end = box.top + box.height * toY
        val downTime = SystemClock.uptimeMillis()
        var y = start
        var pointerDown = false
        var initial: Throwable? = null
        fun inject(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) { "Native touch was rejected" } }
            finally { event.recycle() }
        }
        try {
            inject(MotionEvent.ACTION_DOWN); pointerDown = true
            do {
                val committed = CountDownLatch(1)
                instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback { committed.countDown() } }
                check(committed.await(2, TimeUnit.SECONDS)) { "No native frame during real-touch gesture" }
                val fraction = ((SystemClock.uptimeMillis() - downTime).toFloat() / durationMillis).coerceAtMost(1f)
                y = start + (end - start) * fraction
                inject(MotionEvent.ACTION_MOVE)
            } while (SystemClock.uptimeMillis() - downTime < durationMillis)
            y = end; inject(MotionEvent.ACTION_UP); pointerDown = false
        } catch (failure: Throwable) { initial = failure; throw failure }
        finally {
            if (pointerDown) try { inject(MotionEvent.ACTION_CANCEL) }
            catch (cancel: Throwable) { if (initial != null) initial.addSuppressed(cancel) else throw cancel }
        }
    }

    /** A verified exact tag/id, never first()/another route; existing caller assertions/clicks remain unchanged. */
    fun interaction(frame: NativeRevealFrame): SemanticsNodeInteraction {
        val target = requireNotNull(frame.target)
        check(target.attached && target.placed && target.unclipped != null) { "An unplaced target cannot be reached" }
        write("reached")
        return rule.onNode(hasTestTag(targetTag) and SemanticsMatcher("validated reveal node ${target.id}") { it.id == target.id })
    }

    fun fail(stage: String, cause: Throwable? = null): Nothing {
        var original = cause
        fun diagnosticFailure(failure: Throwable) {
            val first = original
            if (first == null) original = failure else if (first !== failure) first.addSuppressed(failure)
            history += mapOf("stage" to "diagnostic-error", "type" to failure.javaClass.name, "message" to failure.message)
        }
        try { snapshot("failure-$stage") } catch (failure: Throwable) { diagnosticFailure(failure) }
        try { write(stage) } catch (failure: Throwable) { diagnosticFailure(failure) }
        val last = history.lastOrNull { "counts" in it }
        val path = runCatching { journal.absolutePath }.getOrDefault("journal-unavailable")
        throw AssertionError("Reveal failed: route=$containerTag target=$targetTag stage=$stage " +
            "counts=${last?.get("counts")} roots=${last?.get("rootIds")}; bounds/history: $path", original)
    }

    private fun write(result: String) {
        journal.writeText(Gson().toJson(mapOf("expectedContainer" to containerTag, "target" to targetTag,
            "result" to result, "thread" to Thread.currentThread().name,
            "callSite" to Thread.currentThread().stackTrace.filter { it.className.startsWith("ru.liturgyguide") }.take(8).map { it.toString() },
            "history" to history)))
    }
}

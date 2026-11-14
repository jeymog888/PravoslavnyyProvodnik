package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.selects.select
import ru.liturgyguide.service.schedule.InternetScheduleState
import ru.liturgyguide.service.schedule.ScheduleSourceContinuation
import java.util.concurrent.atomic.AtomicBoolean

/** UI action callbacks expire synchronously, before the next composition. */
internal class ScheduleActionSurface(initialRoute: String) {
    var token: Any by mutableStateOf(Any())
        private set
    var route: String = initialRoute
        private set
    fun rotate(): Any { token = Any(); return token }
    fun enter(route: String): Any { this.route = route; return rotate() }
}

internal class AutomaticScheduleSourceView(val surface: Any, val screen: String) {
    data class DrawWitness(val strip: Rect, val action: Rect)
    private val acknowledged = AtomicBoolean(false)
    val drawn = CompletableDeferred<DrawWitness>()
    val left = CompletableDeferred<Unit>()
    fun acknowledge(strip: Rect, action: Rect) {
        if (!left.isCompleted && acknowledged.compareAndSet(false, true)) drawn.complete(DrawWitness(strip, action))
    }
    fun leave() { left.complete(Unit) }
}

/** An automatic right exists before refresh delivery and is never renewed by a late result. */
internal class AutomaticScheduleRefreshEpoch(val owner: Any, val sequence: Int, val day: String) {
    @Volatile var active: Boolean = true
        private set
    fun revoke() { active = false }
}

/** One delivery/job; only explicit schedule viewing may replace its visual gate. */
internal class AutomaticScheduleSourcePresentation(val owner: Any, surface: Any, val sequence: Int,
    screen: String, val day: String, val delivered: InternetScheduleState, val epoch: AutomaticScheduleRefreshEpoch) {
    var view by mutableStateOf(AutomaticScheduleSourceView(surface, screen))
        private set
    private val sourceOrder = delivered.sourceCandidates.map { it.sourceUrl }.distinct().toMutableList()
    private var ticket: ScheduleSourceContinuation? = null
    private var job: Job? = null
    @Volatile var revoked: Boolean = false
        private set
    @Volatile var witness: AutomaticScheduleSourceView.DrawWitness? = null
        private set
    @Volatile var frameAfterDraw: Long? = null
        private set
    var searching by mutableStateOf(true)

    fun attach(ticket: ScheduleSourceContinuation) { check(this.ticket == null); this.ticket = ticket }
    fun attachJob(job: Job) { this.job = job; if (revoked) job.cancel() }
    fun ticket(): ScheduleSourceContinuation = requireNotNull(ticket)
    fun moveView(surface: Any, screen: String) {
        if (revoked || view.surface === surface && view.screen == screen) return
        view.leave()
        view = AutomaticScheduleSourceView(surface, screen)
    }
    fun orderedSources(current: List<String>): List<String> {
        current.forEach { if (it !in sourceOrder) sourceOrder += it }
        return sourceOrder.filter { it in current }
    }
    suspend fun awaitCurrentDrawAndFrame(current: () -> Boolean): Boolean {
        // This loop follows explicit viewing transfers only, never a network retry.
        while (current()) {
            val captured = view
            val visible = select<Boolean> {
                captured.drawn.onAwait { true }
                captured.left.onAwait { false }
            }
            if (!visible) continue
            val frame = withFrameNanos { it }
            if (captured === view && !captured.left.isCompleted && current()) {
                witness = captured.drawn.await()
                frameAfterDraw = frame
                return true
            }
        }
        return false
    }
    fun revoke() { revoked = true; epoch.revoke(); view.leave(); ticket?.revoke(); job?.cancel() }
    suspend fun stopAndJoin() { revoke(); job?.cancelAndJoin() }
}

internal class AutomaticScheduleSourceController {
    private var current: AutomaticScheduleSourcePresentation? = null
    private var refreshEpoch: AutomaticScheduleRefreshEpoch? = null
    fun beginEpoch(owner: Any, sequence: Int, day: String): AutomaticScheduleRefreshEpoch {
        current?.revoke()
        refreshEpoch?.revoke()
        return AutomaticScheduleRefreshEpoch(owner, sequence, day).also { refreshEpoch = it }
    }
    fun owns(epoch: AutomaticScheduleRefreshEpoch): Boolean = refreshEpoch === epoch && epoch.active
    fun matches(owner: Any, sequence: Int, day: String): Boolean = refreshEpoch?.let {
        it.active && it.owner === owner && it.sequence == sequence && it.day == day
    } == true
    fun install(value: AutomaticScheduleSourcePresentation) {
        check(owns(value.epoch))
        current?.revoke()
        current = value
    }
    fun revoke() { refreshEpoch?.revoke(); current?.revoke() }
    suspend fun stopAndJoin() { current?.stopAndJoin(); current = null }
}

/** Non-lazy result and action; the longest status is measured in every phase. */
@Composable
internal fun ScheduleSourceSearchStrip(searching: Boolean, incomplete: Boolean, onReview: () -> Unit,
    onDrawn: ((Rect, Rect) -> Unit)?) {
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var actionCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
        .testTag("schedule_source_search_strip")
        .onGloballyPositioned { rootCoordinates = it }
        .drawWithContent {
            drawContent()
            val root = rootCoordinates
            val action = actionCoordinates
            if (root?.isAttached == true && action?.isAttached == true) {
                val bounds = root.boundsInWindow()
                val button = action.boundsInWindow()
                val rootWhole = bounds.width >= root.size.width - 1f && bounds.height >= root.size.height - 1f
                val buttonWhole = button.width >= action.size.width - 1f && button.height >= action.size.height - 1f
                if (root.size.width > 0 && root.size.height > 0 && rootWhole && buttonWhole &&
                    button.left >= bounds.left && button.right <= bounds.right &&
                    button.top >= bounds.top && button.bottom <= bounds.bottom) onDrawn?.invoke(bounds, button)
            }
        }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Найдены сайты", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("source_search_result"))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Spacer(Modifier.size(20.dp))
            Box(Modifier.weight(1f)) {
                // Reserving the longest phase avoids moving an action under a finger.
                listOf("Ищем другие источники", "Не все источники доступны", "Поиск завершен").forEach { phase ->
                    Text(phase, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alpha(0f).clearAndSetSemantics { })
                }
                Text(if (searching) "Ищем другие источники" else if (incomplete) "Не все источники доступны" else "Поиск завершен",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("source_search_status"))
            }
        }
        OutlinedButton(onClick = onReview, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("source_search_review").onGloballyPositioned { actionCoordinates = it }) {
            Text("Проверить сайты")
        }
    }
}

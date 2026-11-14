package ru.liturgyguide.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.service.book.BookRussianUpdateDescriptor

internal data class BookRussianTextGeometry(val blockId: String, val bounds: Rect, val layout: TextLayoutResult)
internal data class BookRussianNoticeTarget(val blockId: String, val anchorLine: Rect)
internal data class BookRussianOptionsClaim(val owner: Any, val pairIdentity: String, val blockId: String)

private data class RussianNoticeRequest(val owner: Any, val descriptor: BookRussianUpdateDescriptor, val blockId: String)
private data class RussianNoticePlacement(val request: RussianNoticeRequest, val bounds: Rect, val prediction: Rect?)
private class RussianNoticeLifetime { var active = true }

private class RussianNoticeVisuals(val request: RussianNoticeRequest) : SnackbarVisuals {
    override val message = "Есть русский перевод"
    override val actionLabel = "Посмотреть"
    override val withDismissAction = true
    override val duration = SnackbarDuration.Long
}

/** One host and one suspended producer: an error cancels information instead of queueing behind it. */
@Composable
internal fun BookUpdateNoticeHost(
    lifetime: Any, acceptedKey: ServiceBookKey, acceptedHash: String, updateOwner: Any?,
    descriptor: BookRussianUpdateDescriptor?, target: BookRussianNoticeTarget?, readerBounds: Rect?,
    visible: Boolean, allowInformation: Boolean, updateAvailable: Boolean,
    errorRevision: Int, errorMessage: String?, onReview: (String) -> Unit
) {
    val host = remember(lifetime) { SnackbarHostState() }
    val work = remember(lifetime) { RussianNoticeLifetime() }
    DisposableEffect(work) { onDispose { work.active = false } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val focused = LocalWindowInfo.current.isWindowFocused
    var shownPairs by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var handledError by remember(lifetime) { mutableStateOf<String?>(null) }
    var activeInformation by remember(lifetime) { mutableStateOf<RussianNoticeRequest?>(null) }
    var hostBounds by remember(lifetime) { mutableStateOf<Rect?>(null) }
    var placement by remember(lifetime) { mutableStateOf<RussianNoticePlacement?>(null) }
    val currentReview by rememberUpdatedState(onReview)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val messageStyle = MaterialTheme.typography.bodyMedium
    val actionStyle = MaterialTheme.typography.labelLarge
    val foreground = visible && resumed && focused && work.active
    val errorKey = if (errorRevision > 0) errorRevision.toString() + "|" +
        (errorMessage ?: "Не удалось открыть обновленный текст. Вы остались в прежней редакции") else null

    BoxWithConstraints(Modifier.widthIn(max = 600.dp).fillMaxWidth()
        .then(if (foreground) Modifier else Modifier.alpha(0f).clearAndSetSemantics { })
        .onGloballyPositioned { coordinates ->
        val point = coordinates.positionInRoot()
        val next = Rect(point.x, point.y, point.x + coordinates.size.width, point.y + coordinates.size.height)
        if (hostBounds != next) hostBounds = next
    }) {
        // The same explicit dimensions are used by the actual snackbar below.
        val availableWidth = with(density) { (maxWidth - 56.dp).roundToPx() }.coerceAtLeast(1)
        val message = remember(availableWidth, messageStyle, measurer) {
            measurer.measure(AnnotatedString("Есть русский перевод"), messageStyle, constraints = Constraints(maxWidth = availableWidth))
        }
        val action = remember(actionStyle, measurer) { measurer.measure(AnnotatedString("Посмотреть"), actionStyle) }
        val rowHeight = with(density) { maxOf(48.dp.roundToPx(), action.size.height + 16.dp.roundToPx()).toDp() }
        val outerHeight = with(density) { 16.dp.toPx() + 24.dp.toPx() + message.size.height + 8.dp.toPx() + rowHeight.toPx() }
        val buttonFits = action.size.width + with(density) { 16.dp.roundToPx() + 60.dp.roundToPx() } <= availableWidth
        fun fits(bounds: Rect, anchor: Rect, viewport: Rect): Boolean =
            bounds.width > 0f && bounds.height > 0f &&
                bounds.left >= viewport.left && bounds.right <= viewport.right &&
                bounds.top >= viewport.top && bounds.bottom <= viewport.bottom &&
                anchor.top >= viewport.top && anchor.bottom <= viewport.bottom &&
                anchor.bottom + with(density) { 12.dp.toPx() } <= bounds.top

        val predicted = hostBounds?.let { bounds ->
            // An empty Scaffold snackbar host has zero height and may be placed
            // below the bottom bar. Bootstrap against the measured reader edge;
            // the real surface must still pass the actual bounds check below.
            val bottom = minOf(bounds.bottom, readerBounds?.bottom ?: bounds.bottom)
            Rect(bounds.left + with(density) { 12.dp.toPx() }, bottom - outerHeight + with(density) { 8.dp.toPx() },
                bounds.right - with(density) { 12.dp.toPx() }, bottom - with(density) { 8.dp.toPx() })
        }
        val verifiedDescriptor = descriptor?.takeIf {
            updateOwner != null && it.matches(acceptedKey, acceptedHash, updateOwner)
        }
        val potential = if (foreground && allowInformation && updateAvailable && buttonFits &&
            target != null && verifiedDescriptor != null && target.blockId in verifiedDescriptor.newlyRussianBlockIds)
            RussianNoticeRequest(lifetime, verifiedDescriptor, target.blockId) else null
        val actualForRequest = placement?.takeIf { it.request == potential && it.prediction == predicted }?.bounds
        val eligible = potential?.takeIf {
            target != null && readerBounds != null && predicted != null && fits(predicted, target.anchorLine, readerBounds) &&
                (actualForRequest == null || fits(actualForRequest, target.anchorLine, readerBounds))
        }
        val currentEligible by rememberUpdatedState(eligible)
        val currentTarget by rememberUpdatedState(target)
        val currentBounds by rememberUpdatedState(readerBounds)
        val information = eligible?.takeIf { it.descriptor.pairIdentity !in shownPairs || activeInformation == it }
        val pendingError = errorKey?.takeIf { it != handledError }
        val currentPendingError by rememberUpdatedState(pendingError)
        val selectedError = pendingError?.takeIf { foreground }
        // Error identity is selected independently of changing informational geometry.
        val selectedInformation = information.takeIf { pendingError == null }
        LaunchedEffect(lifetime, selectedError, selectedInformation) {
            if (selectedError != null) {
                host.showSnackbar(errorMessage ?: "Не удалось открыть обновленный текст. Вы остались в прежней редакции",
                    withDismissAction = true, duration = SnackbarDuration.Indefinite)
                if (work.active) handledError = selectedError
            } else if (selectedInformation != null) {
                val request = selectedInformation
                activeInformation = request
                try {
                    val result = host.showSnackbar(RussianNoticeVisuals(request))
                    if (result == SnackbarResult.ActionPerformed && work.active && currentEligible == request && currentPendingError == null) {
                        if (request.descriptor.pairIdentity !in shownPairs) shownPairs = shownPairs + request.descriptor.pairIdentity
                        currentReview(request.blockId)
                    }
                } finally {
                    if (activeInformation == request) activeInformation = null
                }
            }
        }
        Box {
            // Keep the existing host's accessibility-aware timeout and error rendering.
            // Its scale animation must not turn a geometry probe into a moving target;
            // the single informational surface is placed directly in this same host box.
            SnackbarHost(host) { current -> if (current.visuals !is RussianNoticeVisuals) Snackbar(current) }
            val data = host.currentSnackbarData
            val informationVisuals = data?.visuals as? RussianNoticeVisuals
            if (data != null && informationVisuals != null) {
                val request = informationVisuals.request
                val actual = placement?.takeIf { it.request == request }?.bounds
                val reallyVisible = currentEligible == request && actual != null &&
                    currentTarget != null && currentBounds != null &&
                    fits(actual, requireNotNull(currentTarget).anchorLine, requireNotNull(currentBounds))
                val currentShown by rememberUpdatedState(reallyVisible)
                LaunchedEffect(request, reallyVisible) {
                    if (reallyVisible) {
                        // One rendered frame, not merely a request queued in SnackbarHostState.
                        withFrameNanos { }
                        if (work.active && currentShown && currentEligible == request && request.descriptor.pairIdentity !in shownPairs)
                            shownPairs = shownPairs + request.descriptor.pairIdentity
                    }
                }
                Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Surface(color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                            val point = coordinates.positionInRoot()
                            val bounds = Rect(point.x, point.y, point.x + coordinates.size.width, point.y + coordinates.size.height)
                            val next = RussianNoticePlacement(request, bounds, predicted)
                            if (placement != next) placement = next
                        }.alpha(if (reallyVisible) 1f else 0f)
                            .then(if (reallyVisible) Modifier.testTag("book_russian_update_notice").semantics { liveRegion = LiveRegionMode.Polite } else Modifier.clearAndSetSemantics { })) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(informationVisuals.message, style = messageStyle)
                            Row(Modifier.fillMaxWidth().height(rowHeight), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = {
                                    if (work.active && currentShown && currentEligible == request && currentPendingError == null &&
                                        host.currentSnackbarData === data) data.performAction()
                                }, enabled = reallyVisible, contentPadding = PaddingValues(8.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.inversePrimary),
                                    modifier = Modifier.weight(1f).fillMaxHeight().testTag("book_russian_notice_action")) {
                                    Text("Посмотреть", style = actionStyle, maxLines = 1, softWrap = false)
                                }
                                IconButton(onClick = {
                                    if (work.active && currentShown && currentEligible == request && currentPendingError == null &&
                                        host.currentSnackbarData === data) {
                                        if (request.descriptor.pairIdentity !in shownPairs) shownPairs = shownPairs + request.descriptor.pairIdentity
                                        data.dismiss()
                                    }
                                }, enabled = reallyVisible,
                                    modifier = Modifier.size(48.dp).align(androidx.compose.ui.Alignment.CenterVertically)
                                        .testTag("book_russian_notice_dismiss")) {
                                    Icon(Icons.Default.Close, "Закрыть сообщение")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

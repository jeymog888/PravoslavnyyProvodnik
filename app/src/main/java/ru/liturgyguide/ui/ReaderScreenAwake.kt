package ru.liturgyguide.ui

import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import ru.liturgyguide.service.PlaybackSegment

/** One Activity window, with identity-guarded cleanup when its composition is replaced. */
class ReaderScreenAwakeOwner(private val setKeepScreenOn: (Boolean) -> Unit) {
    private var current: Binding? = null
    // Establish the actual target state on the first binding, including a recreated window.
    private var enabled: Boolean? = null

    private fun setEnabled(value: Boolean) {
        if (enabled == value) return
        setKeepScreenOn(value)
        enabled = value
    }

    internal fun binding(pause: () -> Unit) = Binding(pause)

    internal inner class Binding(private val pausePlayer: () -> Unit) {
        private var closed = false

        fun attach() {
            if (closed) return
            current = this
            setEnabled(false)
        }

        fun update(value: Boolean) {
            if (!closed && current === this) setEnabled(value)
        }

        /** Foreground loss releases the window before the pause can write to storage. */
        fun pause() {
            if (closed || current !== this) return
            setEnabled(false)
            pausePlayer()
        }

        fun close() {
            if (closed) return
            closed = true
            if (current !== this) return
            setEnabled(false)
            current = null
            pausePlayer()
        }
    }
}

internal fun readerScreenAwakeOwner(window: Window) = ReaderScreenAwakeOwner { enabled ->
    if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

internal fun readerKeepsScreenOn(serviceVisible: Boolean, resumed: Boolean, topResumed: Boolean,
    reading: ActiveServicePlayback?): Boolean = serviceVisible && resumed && topResumed &&
    reading?.state?.isPlaying == true &&
    (reading.segment is PlaybackSegment.Text || reading.segment is PlaybackSegment.Wait)

/** Reuses the one synchronous lifecycle/top-resumed pause path; no reader-layout owner. */
@Composable
internal fun rememberReaderScreenAwakeBinding(player: ServicePlayerState, serviceVisible: Boolean,
    lifecycle: Lifecycle, activityForeground: ActivityForegroundState?, owner: ReaderScreenAwakeOwner?,
    onResumedChange: (Boolean) -> Unit): ReaderScreenAwakeOwner.Binding {
    val target = remember(owner) { owner ?: ReaderScreenAwakeOwner { } }
    val binding = remember(target, player, lifecycle, activityForeground) { target.binding { player.pause() } }
    val updateResumed by rememberUpdatedState(onResumedChange)
    DisposableEffect(binding) {
        binding.attach()
        val observer = LifecycleEventObserver { _, event ->
            val lost = event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP ||
                event == Lifecycle.Event.ON_DESTROY
            updateResumed(!lost && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            if (lost) binding.pause()
        }
        lifecycle.addObserver(observer)
        val unbind = activityForeground?.bind { binding.pause() }
        onDispose {
            lifecycle.removeObserver(observer)
            unbind?.invoke()
            binding.close()
        }
    }
    val requested = readerKeepsScreenOn(serviceVisible, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        activityForeground?.isTopResumed != false, player.reading)
    SideEffect {
        // A loss callback can run before this committed effect; recheck the live owner.
        binding.update(requested && readerKeepsScreenOn(serviceVisible,
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), activityForeground?.isTopResumed != false,
            player.reading))
    }
    return binding
}

package ru.liturgyguide.ui

import androidx.annotation.MainThread
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Activity-owned on API 29+: Recents can take the top position without pausing the lifecycle. */
@Stable
class ActivityForegroundState {
    var isTopResumed by mutableStateOf(false)
        private set
    private class Binding(val onLost: () -> Unit)
    private var binding: Binding? = null

    @MainThread
    fun onTopResumedActivityChanged(isTopResumed: Boolean) {
        this.isTopResumed = isTopResumed
        // Materialize and save the pause in this callback, before any Compose frame can run.
        if (!isTopResumed) binding?.onLost?.invoke()
    }

    @MainThread
    fun bind(onLost: () -> Unit): () -> Unit {
        val ownBinding = Binding(onLost)
        binding = ownBinding
        if (!isTopResumed) onLost()
        return { if (binding === ownBinding) binding = null }
    }
}

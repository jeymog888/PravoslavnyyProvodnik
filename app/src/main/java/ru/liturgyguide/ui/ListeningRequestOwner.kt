package ru.liturgyguide.ui

/** One explicit request. A permission overlay may pause us; leaving the route invalidates it. */
internal class ListeningRequestOwner(
    private val isCurrent: () -> Boolean,
    private val isForeground: () -> Boolean,
    private val start: (Boolean) -> Unit,
    private val cancel: () -> Unit,
    private val denied: () -> Unit
) {
    private data class Request(val token: Long, val unchecked: Boolean, var granted: Boolean)
    private var sequence = 0L
    private var pending: Request? = null
    private var started = false
    private var disposed = false

    /** Returns a token only when the caller must open the permission dialog. */
    fun request(hasPermission: Boolean, unchecked: Boolean = false): Long? {
        if (disposed || !isCurrent() || !isForeground()) return null
        stop()
        val request = Request(++sequence, unchecked, hasPermission)
        pending = request
        if (hasPermission) consumeIfReady()
        return request.token.takeUnless { hasPermission }
    }

    fun permissionResult(token: Long, granted: Boolean) {
        val request = pending ?: return
        if (disposed || request.token != token || !isCurrent()) return
        if (!granted) {
            pending = null
            denied()
        } else {
            request.granted = true
            consumeIfReady()
        }
    }

    fun foregroundChanged() {
        if (disposed) return
        if (!isCurrent()) { stop(); return }
        if (!isForeground() && started) stop()
        else consumeIfReady()
    }

    fun stop() {
        ++sequence
        pending = null
        started = false
        cancel()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        stop()
    }

    private fun consumeIfReady() {
        val request = pending ?: return
        if (disposed || !request.granted || !isCurrent() || !isForeground()) return
        pending = null
        started = true
        start(request.unchecked)
    }
}

/** Shares the Activity's existing foreground callback without installing another binding. */
internal class ListeningInterrupt {
    private class Binding(val owner: Any, val changed: () -> Unit, val stop: () -> Unit)
    private var current: Binding? = null
    fun bind(owner: Any, callback: () -> Unit, onStop: () -> Unit = callback): () -> Unit {
        val binding = Binding(owner, callback, onStop)
        current = binding
        return { if (current === binding) current = null }
    }
    fun foregroundChanged() { current?.changed?.invoke() }
    fun stop() { current?.stop?.invoke() }
}

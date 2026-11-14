package ru.liturgyguide.ui

import kotlinx.coroutines.Job
import ru.liturgyguide.model.ServiceBookKey

/** One actual opening may start one attempt. Its saver always restores an already spent grant. */
internal class BookSearchFocusGrant private constructor(private var available: Boolean) {
    fun take(): Boolean = available.also { available = false }
    companion object {
        fun opening(manual: Boolean) = BookSearchFocusGrant(manual)
        fun restored() = BookSearchFocusGrant(false)
    }
}

/** Process-only identity; no window, coroutine or request can survive save/restore. */
internal class BookSearchFocusAttempt(
    private val opening: Any, private val key: ServiceBookKey, private val hash: String,
    private val window: Any, private val lifecycle: Any
) {
    private var revoked = false
    private var claimed = false
    private var work: Job? = null
    val valid: Boolean get() = !revoked
    fun matches(opening: Any, key: ServiceBookKey, hash: String, window: Any, lifecycle: Any): Boolean =
        !revoked && this.opening === opening && this.key == key && this.hash == hash &&
            this.window === window && this.lifecycle === lifecycle
    fun bind(job: Job) {
        check(work == null)
        if (revoked) job.cancel() else work = job
    }
    fun unbind(job: Job) { if (work === job) work = null }
    fun revoke() {
        revoked = true
        val pending = work
        work = null
        pending?.cancel()
    }
    /** Consumed synchronously immediately before the focus action; IME may still check valid afterwards. */
    fun claim(): Boolean {
        if (revoked || claimed) return false
        claimed = true
        return true
    }
}

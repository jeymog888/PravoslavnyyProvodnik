package ru.liturgyguide.service.schedule

import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import ru.liturgyguide.model.Parish

/** A live resolver transition, never reconstructed from cached candidate metadata. */
class FastMapContinuationReceipt private constructor(private val fingerprint: String) {
    private val used = AtomicBoolean(false)
    internal fun consume(parish: Parish): Boolean = used.compareAndSet(false, true) &&
        fingerprint == ScheduleEntityIdentity.fingerprint(parish)

    internal companion object {
        fun issue(parish: Parish) = FastMapContinuationReceipt(ScheduleEntityIdentity.fingerprint(parish))
    }
}

/**
 * Process-only lease for one exact, committed fast result. Revocation and the
 * repository's final CAS share the storage lock, so a click wins before commit.
 * This is not stored in InternetScheduleState, SharedPreferences or a saved route.
 */
class ScheduleSourceContinuation internal constructor(
    private val producer: Any,
    internal val delivered: InternetScheduleState,
    internal val rawSnapshot: String,
    private val fingerprint: String,
    private val parishDay: String,
    internal val coordinationLock: Any,
) {
    @Volatile private var active = true
    private var boundOwner: Any? = null
    private var consumed = false
    private var runningJob: Job? = null

    fun isActive(): Boolean = active

    internal fun bind(expectedProducer: Any, parish: Parish, now: Instant,
        delivery: InternetScheduleState, owner: Any, validSnapshot: () -> Boolean): Boolean = synchronized(coordinationLock) {
        if (!active || consumed || boundOwner != null || producer !== expectedProducer || delivered !== delivery ||
            !matches(parish, now) || !validSnapshot()) return@synchronized false
        boundOwner = owner
        true
    }

    /** Called before withContext or any other suspension, including for stale callers. */
    internal fun begin(expectedProducer: Any, parish: Parish, now: Instant, owner: Any,
        job: Job, validSnapshot: () -> Boolean): Boolean = synchronized(coordinationLock) {
        if (consumed) return@synchronized false
        consumed = true
        if (!active || producer !== expectedProducer || boundOwner !== owner || !matches(parish, now) || !validSnapshot()) {
            active = false
            return@synchronized false
        }
        runningJob = job
        true
    }

    private fun matches(parish: Parish, now: Instant): Boolean = runCatching {
        fingerprint == ScheduleEntityIdentity.fingerprint(parish) &&
            parishDay == now.atZone(ZoneId.of(parish.timezone)).toLocalDate().toString()
    }.getOrDefault(false)

    internal fun ensureActive() {
        if (!active) throw CancellationException("Source continuation was revoked")
    }

    /** Synchronous boundary; no ordinary metadata write can pass after this returns. */
    fun revoke() {
        val job = synchronized(coordinationLock) { active = false; runningJob }
        job?.cancel()
    }

    internal suspend fun revokeAndJoin() {
        val job = synchronized(coordinationLock) { active = false; runningJob }
        job?.cancel()
        job?.join()
    }

    internal fun finish() = synchronized(coordinationLock) { active = false; runningJob = null }
}

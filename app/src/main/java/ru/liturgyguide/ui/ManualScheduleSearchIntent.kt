package ru.liturgyguide.ui

import java.util.concurrent.atomic.AtomicBoolean
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.schedule.ScheduleEntityIdentity

/** Process-only owner; recreation and every identity/day change create a different reference. */
internal class ManualScheduleSearchOwner(val fingerprint: String, val parishDay: String) {
    private val active = AtomicBoolean(true)
    fun invalidate() { active.set(false) }
    fun isActive() = active.get()
}

/** A click is consumed once before suspension, even when its owner or route is already stale. */
internal class ManualScheduleSearchIntent(val owner: ManualScheduleSearchOwner, val requestSequence: Int,
    val origin: String, private val surfaceOwner: Any = owner) {
    private val consumed = AtomicBoolean(false)
    fun consume(current: ManualScheduleSearchOwner, sequence: Int, parishDay: String, screen: String,
        currentSurfaceOwner: Any = current): Boolean =
        consumed.compareAndSet(false, true) && owner === current && surfaceOwner === currentSurfaceOwner && owner.isActive() &&
            requestSequence == sequence && sequence > 0 && owner.parishDay == parishDay && origin == screen
}

/** Schedule provenance may change for display; source proof still belongs to the exact selected map object. */
internal fun scheduleReviewParish(displayed: Parish?, identity: Parish?): Parish? {
    if (displayed == null) return null
    return identity?.takeIf { it.id == displayed.id &&
        ScheduleEntityIdentity.fingerprint(it) == ScheduleEntityIdentity.fingerprint(displayed) } ?: displayed
}

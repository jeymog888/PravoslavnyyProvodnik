package ru.liturgyguide.service

/** A conservative return to an old phrase needs an explanation when the candidate is accepted. */
enum class PlaybackMigrationNote { EXPANDED_STAGE_LAST_PHRASE }

/** A proposed, paused position in the new plan. Creating it never accepts or saves the reading. */
data class MigratedPlaybackPosition(val state: ServicePlaybackState, val note: PlaybackMigrationNote? = null)

/**
 * Limited 0.5 compatibility, not a registry of arbitrary content versions.
 * The store supplies the old plan only through Historical05Content's release-byte validation.
 * Both engines validate their own positions; an old numeric index never addresses the new plan.
 */
object Published05PlaybackMigration {
    fun migrate(oldPlan: ServicePlaybackPlan, newPlan: ServicePlaybackPlan,
        snapshot: ServicePlaybackSnapshot): MigratedPlaybackPosition? {
        if (oldPlan.contentVersion != 1 || newPlan.contentVersion != oldPlan.contentVersion ||
            oldPlan.serviceDate != newPlan.serviceDate) return null
        val oldState = ServicePlaybackEngine(oldPlan).restore(snapshot) ?: return null
        val oldSegment = oldPlan.segments[oldState.segmentIndex]
        var note: PlaybackMigrationNote? = null
        var offset = oldState.elapsedQuarterMillis
        val newIndex = when (oldSegment) {
            is PlaybackSegment.Text -> matchingTextIndex(oldPlan, newPlan, oldSegment)
            is PlaybackSegment.Wait -> {
                val oldStage = oldPlan.segments.filter { it.stageId == oldSegment.stageId }
                val newStage = newPlan.segments.filter { it.stageId == oldSegment.stageId }
                if (sameStage(oldPlan, newPlan, oldStage, newStage)) {
                    newPlan.segments.indexOf(oldSegment).takeIf { it >= 0 }
                } else {
                    // Only the last OLD text before this wait proves a previously reached phrase.
                    val oldText = oldPlan.retainedTextAt(oldState.segmentIndex) ?: return null
                    offset = 0
                    note = PlaybackMigrationNote.EXPANDED_STAGE_LAST_PHRASE
                    matchingTextIndex(oldPlan, newPlan, oldText)
                }
            }
            is PlaybackSegment.MissingReading -> newPlan.segments.indexOf(oldSegment).takeIf { it >= 0 }
            is PlaybackSegment.End -> newPlan.segments.lastIndex.takeIf { newPlan.segments[it] == oldSegment }
        } ?: return null
        val state = ServicePlaybackEngine(newPlan).restore(ServicePlaybackSnapshot(newPlan.contentVersion,
            newPlan.fingerprint, newIndex, offset, oldState.speed)) ?: return null
        return MigratedPlaybackPosition(state, note)
    }

    private fun matchingTextIndex(oldPlan: ServicePlaybackPlan, newPlan: ServicePlaybackPlan,
        oldText: PlaybackSegment.Text): Int? {
        val index = newPlan.indexOfLine(oldText.trackId, oldText.lineId) ?: return null
        val newText = newPlan.segments[index] as? PlaybackSegment.Text ?: return null
        return index.takeIf { oldText == newText && canonical(oldPlan, oldText) == canonical(newPlan, newText) }
    }

    private fun sameStage(oldPlan: ServicePlaybackPlan, newPlan: ServicePlaybackPlan,
        oldStage: List<PlaybackSegment>, newStage: List<PlaybackSegment>): Boolean =
        oldStage.size == newStage.size && oldStage.zip(newStage).all { (old, new) ->
            old == new && (old !is PlaybackSegment.Text ||
                canonical(oldPlan, old) == canonical(newPlan, new as PlaybackSegment.Text))
        }

    // The plan fingerprint uses the original when present and Russian for Scripture without it.
    private fun canonical(plan: ServicePlaybackPlan, text: PlaybackSegment.Text): String =
        plan.line(text).let { it.original?.takeIf(String::isNotBlank) ?: it.russian }
}

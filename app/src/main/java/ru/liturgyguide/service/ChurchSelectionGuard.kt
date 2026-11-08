package ru.liturgyguide.service

/** A pending choice is neither a selected parish nor a failed storage operation. */
enum class ChurchSelectionResult { SELECTED, PENDING, FAILED, STALE }

@ConsistentCopyVisibility
data class ChurchSelectionSnapshot private constructor(
    val selectedId: String?,
    val readingGeneration: Int,
    val hasActiveReading: Boolean,
    val affectedValues: Map<String, Any?>
) {
    val hasReading: Boolean get() = hasActiveReading || affectedValues.keys.any(::isReadingKey)

    companion object {
        fun capture(selectedId: String?, readingGeneration: Int, hasActiveReading: Boolean,
            values: Map<String, *>): ChurchSelectionSnapshot = ChurchSelectionSnapshot(
            selectedId, readingGeneration, hasActiveReading,
            values.filterKeys { it == "church_id" || isReadingKey(it) }
                .mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
        )

        private fun isReadingKey(key: String): Boolean = key == ReadingContextStore.CONTEXT_KEY ||
            key == ServicePlaybackStore.PLAYBACK_KEY || key == ReadingAcceptanceStore.SESSION_KEY ||
            key == ReadingAcceptanceStore.STAGE_KEY || ReadingAcceptanceStore.isBookmarkKey(key)
    }
}

/** One serialized owner. A request belongs to this exact owner, target and saved reading. */
class ChurchSelectionGuard {
    class Pending internal constructor(
        val targetId: String,
        internal val source: ChurchSelectionSnapshot,
        internal val commit: (String, Boolean) -> Boolean,
        internal val onSelected: () -> Unit
    )

    var pending: Pending? = null
        private set
    private var closed = false

    fun request(targetId: String, source: ChurchSelectionSnapshot, availableIds: Set<String>,
        commit: (targetId: String, preserveReading: Boolean) -> Boolean,
        onSelected: () -> Unit = {}): ChurchSelectionResult {
        if (closed) return ChurchSelectionResult.STALE
        if (targetId !in availableIds) { cancel(); return ChurchSelectionResult.STALE }
        val current = pending
        if (current?.targetId == targetId && current.source == source) return ChurchSelectionResult.PENDING
        cancel()
        val sameChurch = source.selectedId == targetId
        if (!sameChurch && source.hasReading) {
            pending = Pending(targetId, source, commit, onSelected)
            return ChurchSelectionResult.PENDING
        }
        if (!commit(targetId, sameChurch)) return ChurchSelectionResult.FAILED
        onSelected()
        return ChurchSelectionResult.SELECTED
    }

    fun confirm(request: Pending, source: ChurchSelectionSnapshot, availableIds: Set<String>): ChurchSelectionResult {
        if (closed || pending !== request) return ChurchSelectionResult.STALE
        if (request.source != source || request.targetId !in availableIds) {
            cancel()
            return ChurchSelectionResult.STALE
        }
        // Consume before invoking a callback: a double click cannot clear twice.
        pending = null
        if (!request.commit(request.targetId, false)) {
            pending = request
            return ChurchSelectionResult.FAILED
        }
        request.onSelected()
        return ChurchSelectionResult.SELECTED
    }

    fun cancel() { pending = null }
    fun close() { cancel(); closed = true }
}

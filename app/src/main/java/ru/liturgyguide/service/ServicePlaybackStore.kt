package ru.liturgyguide.service

import android.content.SharedPreferences
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** A captured position belongs to one accepted reading, not just to a similar text plan. */
data class StoredServicePlayback(val context: ReadingContext, val snapshot: ServicePlaybackSnapshot)

sealed interface PlaybackRestoreResult {
    data object Absent : PlaybackRestoreResult
    data class Valid(val stored: StoredServicePlayback, val state: ServicePlaybackState) : PlaybackRestoreResult
    /** originalStored is still the old persisted snapshot; only state belongs to the requested new plan. */
    data class MigrationCandidate(val originalStored: StoredServicePlayback, val state: ServicePlaybackState,
        val note: PlaybackMigrationNote?) : PlaybackRestoreResult
    data class Invalid(val reason: Reason) : PlaybackRestoreResult
    enum class Reason { UNREADABLE, MALFORMED, CONTEXT_MISMATCH, PLAN_MISMATCH }
}

/** Reads only. All writes go through ReadingAcceptanceStore with the matching context and marker. */
class ServicePlaybackStore(private val preferences: SharedPreferences,
    private val historicalContent: Historical05Content? = null,
    private val historical06Content: Historical06Content? = null) {
    fun restore(plan: ServicePlaybackPlan, expectedContext: ReadingContext): PlaybackRestoreResult {
        val values = runCatching { preferences.all }.getOrElse {
            return PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.UNREADABLE)
        }
        if (!values.containsKey(PLAYBACK_KEY)) return PlaybackRestoreResult.Absent
        val stored = StoredServicePlaybackCodec.decode(values[PLAYBACK_KEY] as? String)
            ?: return PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.MALFORMED)
        if (stored.context != expectedContext || ReadingContextCodec.validated(expectedContext) == null)
            return PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.CONTEXT_MISMATCH)
        if (stored.context.serviceDate != plan.serviceDate.toString())
            return PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.PLAN_MISMATCH)
        ServicePlaybackEngine(plan).restore(stored.snapshot)?.let { return PlaybackRestoreResult.Valid(stored, it) }
        // Historical I/O is opt-in and happens only after strict decoding and current-plan restore.
        for (load in listOf<() -> ServicePlaybackPlan?>(
            { historical06Content?.loadPlan(plan.serviceDate) },
            { historicalContent?.loadPlan(plan.serviceDate) }
        )) {
            val oldPlan = load() ?: continue
            val migrated = Published05PlaybackMigration.migrate(oldPlan, plan, stored.snapshot) ?: continue
            return PlaybackRestoreResult.MigrationCandidate(stored, migrated.state, migrated.note)
        }
        return PlaybackRestoreResult.Invalid(PlaybackRestoreResult.Reason.PLAN_MISMATCH)
    }

    companion object { const val PLAYBACK_KEY = "service_playback_v1" }
}

/**
 * Flat, bounded and strictly typed. Context is a string encoded by its own versioned codec.
 * This avoids Gson's reflective defaults/coercion and keeps schema changes explicit.
 */
object StoredServicePlaybackCodec {
    const val MAX_JSON_CHARS = 8_192
    private val fields = setOf("version", "context", "contentVersion", "planFingerprint", "segmentIndex", "elapsedQuarterMillis", "speed")
    private val integer = Regex("-?(0|[1-9][0-9]*)")
    private val fingerprint = Regex("[0-9a-f]{64}")

    fun encode(value: StoredServicePlayback): String {
        val context = ReadingContextCodec.encode(value.context)
        require(validSnapshotShape(value.snapshot)) { "Invalid playback snapshot" }
        return JsonObject().apply {
            addProperty("version", 1)
            addProperty("context", context)
            addProperty("contentVersion", value.snapshot.contentVersion)
            addProperty("planFingerprint", value.snapshot.planFingerprint)
            addProperty("segmentIndex", value.snapshot.segmentIndex)
            addProperty("elapsedQuarterMillis", value.snapshot.elapsedQuarterMillis)
            addProperty("speed", value.snapshot.speed.name)
        }.toString().also { require(it.length <= MAX_JSON_CHARS) }
    }

    fun decode(json: String?): StoredServicePlayback? {
        if (json.isNullOrBlank() || json.length > MAX_JSON_CHARS || !standardLexemes(json)) return null
        return runCatching {
            JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false
                reader.beginObject()
                val seen = mutableSetOf<String>()
                var context: ReadingContext? = null
                var version: Int? = null
                var hash: String? = null
                var index: Int? = null
                var elapsed: Long? = null
                var speed: PlaybackSpeed? = null
                while (reader.hasNext()) {
                    val field = reader.nextName()
                    require(field in fields && seen.add(field))
                    when (field) {
                        "version" -> require(number(reader) == 1L)
                        "context" -> context = requireNotNull(ReadingContextCodec.decode(string(reader)))
                        "contentVersion" -> version = exactInt(number(reader))
                        "planFingerprint" -> hash = string(reader)
                        "segmentIndex" -> index = exactInt(number(reader))
                        "elapsedQuarterMillis" -> elapsed = number(reader)
                        "speed" -> speed = PlaybackSpeed.valueOf(string(reader))
                    }
                }
                reader.endObject()
                require(seen == fields && reader.peek() == JsonToken.END_DOCUMENT)
                val snapshot = ServicePlaybackSnapshot(requireNotNull(version), requireNotNull(hash), requireNotNull(index),
                    requireNotNull(elapsed), requireNotNull(speed))
                require(validSnapshotShape(snapshot))
                StoredServicePlayback(requireNotNull(context), snapshot)
            }
        }.getOrNull()
    }

    private fun validSnapshotShape(snapshot: ServicePlaybackSnapshot): Boolean =
        snapshot.contentVersion > 0 && fingerprint.matches(snapshot.planFingerprint) &&
            snapshot.segmentIndex >= 0 && snapshot.elapsedQuarterMillis >= 0

    private fun exactInt(value: Long): Int {
        require(value in Int.MIN_VALUE..Int.MAX_VALUE)
        return value.toInt()
    }

    private fun string(reader: JsonReader): String {
        require(reader.peek() == JsonToken.STRING)
        return reader.nextString()
    }

    private fun number(reader: JsonReader): Long {
        require(reader.peek() == JsonToken.NUMBER)
        val raw = reader.nextString()
        require(integer.matches(raw))
        return raw.toLong()
    }

    // JsonReader 2.10 accepts some non-JSON escapes even with isLenient=false.
    // All values here are quoted strings or integers, so a small lexical guard is sufficient.
    private fun standardLexemes(json: String): Boolean {
        var index = 0
        while (index < json.length) {
            when (json[index]) {
                ' ', '\t', '\r', '\n', '{', '}', ':', ',' -> index++
                '"' -> {
                    index++
                    var closed = false
                    while (index < json.length) {
                        val character = json[index++]
                        if (character == '"') { closed = true; break }
                        if (character < ' ') return false
                        if (character == '\\') {
                            if (index == json.length) return false
                            val escape = json[index++]
                            if (escape == 'u') {
                                if (index + 4 > json.length) return false
                                repeat(4) {
                                    val hex = json[index++]
                                    if (hex !in '0'..'9' && hex !in 'a'..'f' && hex !in 'A'..'F') return false
                                }
                            } else if (escape !in "\"\\/bfnrt") return false
                        }
                    }
                    if (!closed) return false
                }
                else -> {
                    val start = index
                    while (index < json.length && json[index] !in " \t\r\n{}:,\"") index++
                    if (!integer.matches(json.substring(start, index))) return false
                }
            }
        }
        return true
    }
}

/** An address, never an elapsed-time value from the old 8/12/16-second timer. */
data class PlaybackBookmark(val stageId: String, val trackId: String, val lineId: String) {
    companion object {
        fun at(plan: ServicePlaybackPlan, state: ServicePlaybackState): PlaybackBookmark? {
            if (!ServicePlaybackEngine(plan).isValid(state)) return null
            return plan.retainedTextAt(state.segmentIndex)?.let { PlaybackBookmark(it.stageId, it.trackId, it.lineId) }
        }
    }
}

object PlaybackBookmarks {
    /** Also used for manual track selection after migration. Bad or removed IDs never use a stale index. */
    fun forTrack(plan: ServicePlaybackPlan, trackId: String, values: Map<String, *>): PlaybackBookmark? {
        val track = plan.track(trackId) ?: return null
        val segment = plan.segments[plan.indexOfTrack(trackId) ?: return null] as PlaybackSegment.Text
        val phraseKey = "lyric_phrase_$trackId"
        val line = if (values.containsKey(phraseKey)) {
            track.lines.firstOrNull { it.id == values[phraseKey] } ?: track.lines.first()
        } else {
            val index = values["lyric_line_$trackId"] as? Int
            index?.let { track.lines.getOrNull(it) } ?: track.lines.first()
        }
        return PlaybackBookmark(segment.stageId, trackId, line.id)
    }
}

/** One-time, read-only migration. A present new snapshot, even malformed, blocks legacy import. */
object LegacyPlaybackMigration {
    fun resolve(plan: ServicePlaybackPlan, stageId: String, values: Map<String, *>): ServicePlaybackState? {
        if (values.containsKey(ServicePlaybackStore.PLAYBACK_KEY) || plan.indexOfStage(stageId) == null) return null
        val engine = ServicePlaybackEngine(plan)
        val start = engine.start(stageId, PlaybackSpeed.NORMAL)
        val tracks = plan.tracksByStage.getValue(stageId)
        if (tracks.isEmpty()) return start
        val trackKey = "lyric_track_$stageId"
        val savedTrack = values[trackKey] as? String
        val track = tracks.firstOrNull { it.id == savedTrack }
        if (values.containsKey(trackKey) && track == null) return start
        val bookmark = PlaybackBookmarks.forTrack(plan, (track ?: tracks.first()).id, values) ?: return start
        return engine.seekLine(start, bookmark.trackId, bookmark.lineId)
    }
}

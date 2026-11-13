package ru.liturgyguide.service.book

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.liturgyguide.model.PatronalEventProof
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.service.SinglePreferenceCommit

/** Public calendar facts are independent from the user's accepted choice for a parish. */
interface PatronalProofRegistry : PatronalProofs {
    fun rememberProof(proof: PatronalEventProof): Boolean
}

sealed interface PatronalProfileLookup {
    data object Absent : PatronalProfileLookup
    data class Found(val proof: PatronalEventProof) : PatronalProfileLookup
    data object Unreadable : PatronalProfileLookup
}

/** Ephemeral durable-book evidence; calendar data or an unsaved Ready cannot construct it. */
internal class VerifiedPatronalBook private constructor(
    val parishId: String,
    val key: ServiceBookKey,
    val contentHash: String,
    val proof: PatronalEventProof,
    internal val selection: PatronalEventProof
) {
    companion object {
        suspend fun verify(parishId: String, requestedKey: ServiceBookKey, selection: PatronalEventProof,
            ready: BookLoadResult.Ready, books: ServiceBooks): VerifiedPatronalBook? = withContext(Dispatchers.IO) {
            try {
                val book = ready.book
                val snapshot = book.patronalSelection ?: return@withContext null
                if (!validPatronalParishId(parishId) || !ready.saved || book.key != requestedKey ||
                    !selection.matches(requestedKey) || !snapshot.matches(requestedKey) ||
                    !samePatronalIdentity(selection, snapshot) || !ServiceBookIntegrity.valid(book)) return@withContext null
                if (books.cachedVersion(requestedKey, book.contentHash) != book) return@withContext null
                currentCoroutineContext().ensureActive()
                VerifiedPatronalBook(parishId, requestedKey, book.contentHash, snapshot, selection)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
}

/** No raw profile setter: publication requires both durable-book evidence and the old owner. */
class PatronalChoiceStore(private val preferences: SharedPreferences) : PatronalProofRegistry {
    override fun find(id: String): PatronalEventProof? = synchronized(preferences) {
        if (!identifier.matches(id)) return@synchronized null
        runCatching {
            val values = preferences.all
            val public = decodeProof(values[proofKey(id)], id)
            val saved = profiles(values)?.filter { it.proof.id == id }.orEmpty()
            if (public != null) {
                if (saved.any { !samePatronalIdentity(it.proof, public) }) null else public
            } else {
                // A valid selected snapshot is itself durable evidence. Losing the
                // disposable public record must not turn the selection into a generic one.
                val first = saved.firstOrNull()?.proof
                first?.takeIf { candidate -> saved.all { samePatronalIdentity(it.proof, candidate) } }
            }
        }.getOrNull()
    }

    fun readProfile(parishId: String): PatronalProfileLookup = synchronized(preferences) {
        if (!validPatronalParishId(parishId)) return@synchronized PatronalProfileLookup.Unreadable
        runCatching { readProfile(preferences.all, parishId) }.getOrDefault(PatronalProfileLookup.Unreadable)
    }

    override fun rememberProof(proof: PatronalEventProof): Boolean = synchronized(preferences) {
        if (!proof.isValid()) return@synchronized false
        runCatching {
            val values = preferences.all
            if (!canRemember(values, proof)) return@runCatching false
            val key = proofKey(proof.id)
            val encoded = encodeProof(proof)
            if (values[key] == encoded) return@runCatching true
            SinglePreferenceCommit.write(preferences, key, encoded) && decodeProof(preferences.all[key], proof.id) == proof
        }.getOrDefault(false)
    }

    internal fun commitProfile(parishId: String, token: VerifiedPatronalBook, expectedProfile: PatronalProfileLookup): Boolean =
        synchronized(preferences) {
            if (!validPatronalParishId(parishId) || token.parishId != parishId || expectedProfile == PatronalProfileLookup.Unreadable ||
                !token.selection.matches(token.key) || !token.proof.matches(token.key) ||
                !samePatronalIdentity(token.selection, token.proof)) return@synchronized false
            runCatching {
                val values = preferences.all
                if (readProfile(values, parishId) != expectedProfile || !canRemember(values, token.selection)) return@runCatching false
                val profileKey = profileKey(parishId)
                if (profileKey !in values && values.keys.count { it.startsWith(PROFILE_PREFIX) } >= MAX_PROFILES) return@runCatching false
                val changes = linkedMapOf<String, Any?>(profileKey to encodeProfile(parishId, token.proof))
                // Keep more recent public metadata when it has the same identity.
                // The profile deliberately retains the exact snapshot of the saved book.
                if (decodeProof(values[proofKey(token.selection.id)], token.selection.id) == null)
                    changes[proofKey(token.selection.id)] = encodeProof(token.selection)
                SinglePreferenceCommit.write(preferences, changes) &&
                    readProfile(preferences.all, parishId) == PatronalProfileLookup.Found(token.proof)
            }.getOrDefault(false)
        }

    private fun canRemember(values: Map<String, *>, proof: PatronalEventProof): Boolean {
        val existing = decodeProof(values[proofKey(proof.id)], proof.id)
        if (existing != null && !samePatronalIdentity(existing, proof)) return false
        val selected = profiles(values) ?: return false
        if (selected.any { it.proof.id == proof.id && !samePatronalIdentity(it.proof, proof) }) return false
        return proofKey(proof.id) in values || values.keys.count { it.startsWith(PROOF_PREFIX) } < MAX_PROOFS
    }

    private fun readProfile(values: Map<String, *>, parishId: String): PatronalProfileLookup {
        val key = profileKey(parishId)
        if (key !in values) return PatronalProfileLookup.Absent
        val saved = decodeProfile(values[key], key)?.takeIf { it.parishId == parishId } ?: return PatronalProfileLookup.Unreadable
        val public = decodeProof(values[proofKey(saved.proof.id)], saved.proof.id)
        if (public != null && !samePatronalIdentity(saved.proof, public)) return PatronalProfileLookup.Unreadable
        val selected = profiles(values) ?: return PatronalProfileLookup.Unreadable
        if (selected.any { it.proof.id == saved.proof.id && !samePatronalIdentity(it.proof, saved.proof) })
            return PatronalProfileLookup.Unreadable
        return PatronalProfileLookup.Found(saved.proof)
    }

    private data class ProfileRecord(val parishId: String, val proof: PatronalEventProof)

    /** Malformed data in another parish is not permission to rewrite its profile. */
    private fun profiles(values: Map<String, *>): List<ProfileRecord>? {
        val keys = values.keys.filter { it.startsWith(PROFILE_PREFIX) }
        if (keys.size > MAX_PROFILES) return null
        return keys.mapNotNull { decodeProfile(values[it], it) }
    }

    private fun decodeProof(raw: Any?, id: String): PatronalEventProof? = runCatching {
        val root = record(raw)
        require(root.keySet() == setOf("schemaVersion", "proof") && root["schemaVersion"].toString() == "1")
        proof(root["proof"]).takeIf { it.id == id }
    }.getOrNull()

    private fun decodeProfile(raw: Any?, key: String): ProfileRecord? = runCatching {
        val root = record(raw)
        require(root.keySet() == setOf("schemaVersion", "parishId", "proof") && root["schemaVersion"].toString() == "1")
        val parishId = string(root["parishId"])
        require(validPatronalParishId(parishId) && key == profileKey(parishId))
        ProfileRecord(parishId, proof(root["proof"]))
    }.getOrNull()

    private fun record(raw: Any?): JsonObject {
        require(raw is String && raw.length in 1..MAX_RECORD_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES)
        return StrictBookJson.objectValue(raw, maximumDepth = 4, maximumValues = 30)
    }

    private fun proof(element: JsonElement?): PatronalEventProof {
        val value = requireNotNull(element).asJsonObject
        require(value.keySet() == setOf("id", "mineaId", "calendarDate", "title", "churchType", "sourceUrl"))
        return PatronalEventProof(string(value["id"]), string(value["mineaId"]), string(value["calendarDate"]),
            string(value["title"]), string(value["churchType"]), string(value["sourceUrl"])).also { require(it.isValid()) }
    }

    private fun string(value: JsonElement?): String {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }

    private fun encodeProof(proof: PatronalEventProof): String = gson.toJson(mapOf("schemaVersion" to 1, "proof" to proof))
        .also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES) }

    private fun encodeProfile(parishId: String, proof: PatronalEventProof): String =
        gson.toJson(mapOf("schemaVersion" to 1, "parishId" to parishId, "proof" to proof))
            .also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES) }

    companion object {
        private val gson = Gson()
        private val identifier = Regex("[1-9][0-9]{0,8}")
        private const val PROOF_PREFIX = "patronal:proof:"
        private const val PROFILE_PREFIX = "patronal:profile:"
        private const val MAX_RECORD_BYTES = 8_192
        private const val MAX_PROOFS = 512
        private const val MAX_PROFILES = 128
        private fun proofKey(id: String) = "$PROOF_PREFIX$id"
        private fun profileKey(parishId: String) = PROFILE_PREFIX + BookText.sha256(parishId)
    }
}

private fun validPatronalParishId(value: String): Boolean = value.length in 1..160 && value == value.trim() && value.none { it.isISOControl() }
private fun samePatronalIdentity(first: PatronalEventProof, second: PatronalEventProof): Boolean =
    first.id == second.id && first.mineaId == second.mineaId && first.churchType == second.churchType

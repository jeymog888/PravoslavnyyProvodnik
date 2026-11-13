package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import ru.liturgyguide.model.PatronalEventProof
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import java.time.LocalDate
import java.time.LocalTime

enum class BookPartSetKind { PUBLISHED_ORDER, EVENING_TEXTS, HOURS_LITURGY_TEXTS }

/** Local navigation metadata for one published service, never part of a book or its hash. */
data class BookJourneyContext(
    val parishId: String,
    val civilDate: String,
    val time: String,
    val title: String,
    val parts: List<ServiceBookKey>,
    val schemaVersion: Int = 1,
    val partSet: BookPartSetKind? = null
) {
    // Absent in older saved contexts; those always required explicitly named parts.
    val effectivePartSet: BookPartSetKind get() = partSet ?: BookPartSetKind.PUBLISHED_ORDER
    val isEveningTexts: Boolean get() = effectivePartSet == BookPartSetKind.EVENING_TEXTS
    val isHoursLiturgyTexts: Boolean get() = effectivePartSet == BookPartSetKind.HOURS_LITURGY_TEXTS

    fun validatedFor(key: ServiceBookKey, parishId: String?): BookJourneyContext? = runCatching {
        val date = LocalDate.parse(civilDate)
        val start = LocalTime.parse(time)
        val valid = schemaVersion == 1 && this.parishId == parishId && this.parishId.length in 1..160 &&
            date.toString() == civilDate && time.matches(Regex("[0-2][0-9]:[0-5][0-9]")) &&
            title.length in 1..500 &&
            (if (isHoursLiturgyTexts) {
                ServiceBookSelection.isHoursLiturgy(title) && parts.size == 3 &&
                    parts.map { it.worship } == listOf(WorshipKind.HOUR3, WorshipKind.HOUR6, WorshipKind.LITURGY) &&
                    parts.all { it.isValid() && it.date == civilDate && it.topLevel == null }
            } else {
                start.hour >= 12 &&
                    (if (isEveningTexts) ServiceBookSelection.isUnspecifiedEvening(title)
                        else ServiceBookSelection.isCombinedEvening(title)) && parts.size == 2 &&
                    parts.map { it.worship } == listOf(WorshipKind.VESPERS, WorshipKind.MATINS) &&
                    parts.all { it.isValid() && it.date == date.plusDays(1).toString() } &&
                    // Old daily journeys keep accepted keys when the publication named polyeleos.
                    (parts[1].topLevel == null || !isEveningTexts && ServiceBookSelection.isPolyeleosMatins(title))
            }) && parts.map { it.churchType }.distinct().size == 1 &&
            parts.map { it.patronalId }.distinct().size == 1 && key in parts
        takeIf { valid }
    }.getOrNull()

    /** A variant belongs only to the current source; event IDs never cross into its neighbour. */
    fun replacePart(previous: ServiceBookKey, next: ServiceBookKey): BookJourneyContext? {
        if (validatedFor(previous, parishId) == null || !next.isValid() ||
            previous.date != next.date || previous.worship != next.worship || previous.churchType != next.churchType ||
            previous.topLevel != next.topLevel || previous.patronalId != next.patronalId) return null
        return copy(parts = parts.map { if (it == previous) next else it }).validatedFor(next, parishId)
    }
}

/** The profile expectation belongs to the user's choice, not to the previous book's source. */
data class BookDedicationRequest(
    val selection: PatronalEventProof,
    val expectedProfile: PatronalEventProof? = null
) {
    fun validatedFor(key: ServiceBookKey): BookDedicationRequest? = runCatching {
        takeIf { selection.matches(key) && (expectedProfile == null || expectedProfile.isValid()) }
    }.getOrNull()
}

/** One accepted cursor is enough to reconstruct the complete prior route, without a recursive chain. */
data class BookSourceReturn(
    val position: BookReadingPosition,
    val lastSuccessfulKey: ServiceBookKey? = null
) {
    fun routeFor(parishId: String?): BookRoute? = runCatching {
        if (position.parishId != parishId || BookReadingCodec.decode(BookReadingCodec.encode(position)) == null ||
            position.journeyContext?.validatedFor(position.key, parishId) != position.journeyContext) return null
        BookRoute(position.key, parishId, position.journeyContext, lastSuccessfulKey).validated()
            ?.takeIf { it.lastSuccessfulKey == lastSuccessfulKey }
    }.getOrNull()

    internal fun matchesOccurrence(activeKey: ServiceBookKey, journey: BookJourneyContext?, parishId: String?): Boolean {
        val previous = routeFor(parishId) ?: return false
        val oldJourney = previous.journeyContext
        if (oldJourney == null && journey == null) return previous.activeKey.date == activeKey.date &&
            previous.activeKey.worship == activeKey.worship && previous.activeKey.topLevel == activeKey.topLevel
        if (oldJourney == null || journey == null) return false
        return oldJourney.parishId == journey.parishId && oldJourney.civilDate == journey.civilDate &&
            oldJourney.time == journey.time && oldJourney.title == journey.title &&
            oldJourney.effectivePartSet == journey.effectivePartSet && oldJourney.parts.size == journey.parts.size &&
            oldJourney.parts.zip(journey.parts).all { (old, current) ->
                old.date == current.date && old.worship == current.worship && old.topLevel == current.topLevel
            }
    }
}

/** One serialized owner keeps the requested key and its occurrence together across recreation. */
data class BookRoute(
    val activeKey: ServiceBookKey,
    val parishId: String?,
    val journeyContext: BookJourneyContext? = null,
    val lastSuccessfulKey: ServiceBookKey? = null,
    val pendingSearchTarget: BookLocatedSearchHit? = null,
    val pendingDedication: BookDedicationRequest? = null,
    val sourceReturn: BookSourceReturn? = null
) {
    fun validated(): BookRoute? = runCatching {
        if (!activeKey.isValid() || (parishId != null && parishId.length !in 1..160)) return null
        val journey = journeyContext?.validatedFor(activeKey, parishId)
        if (journeyContext?.isHoursLiturgyTexts == true && journey == null) return null
        // New source transactions must never silently lose their occurrence or
        // previous cursor. Legacy routes keep their existing context fallback.
        if ((pendingDedication != null || sourceReturn != null) && journeyContext != null && journey == null) return null
        if (sourceReturn != null && !sourceReturn.matchesOccurrence(activeKey, journey, parishId)) return null
        if (pendingDedication != null && (pendingDedication.validatedFor(activeKey) == null || sourceReturn == null)) return null
        if (pendingSearchTarget != null && (pendingSearchTarget.validated() == null ||
                pendingSearchTarget.bookKey != activeKey || journey == null)) return null
        copy(journeyContext = journey,
            lastSuccessfulKey = lastSuccessfulKey?.takeIf { previous ->
                previous.isValid() && journey != null && journey.parts.any { part -> samePart(part, previous) }
            })
    }.getOrNull()

    fun withVariant(key: ServiceBookKey, savedCurrentPart: Boolean): BookRoute? {
        // A dedication changes the whole source context, even before a cursor is
        // available. It cannot use this daily-variant shortcut to drop the journey.
        if (activeKey.patronalId != key.patronalId) return null
        if (activeKey.churchType != key.churchType &&
            (activeKey.patronalId != null || pendingDedication != null || sourceReturn != null)) return null
        val changed = journeyContext?.replacePart(activeKey, key)
        if (savedCurrentPart && journeyContext != null && changed == null) return null
        return finish(copy(activeKey = key, journeyContext = changed,
            lastSuccessfulKey = if (savedCurrentPart) activeKey else lastSuccessfulKey,
            pendingSearchTarget = null))
    }

    fun selectPart(key: ServiceBookKey, savedCurrentPart: Boolean): BookRoute? {
        val journey = journeyContext?.validatedFor(activeKey, parishId) ?: return null
        if (key !in journey.parts) return null
        return finish(copy(activeKey = key, lastSuccessfulKey = if (savedCurrentPart) activeKey else lastSuccessfulKey,
            pendingSearchTarget = null))
    }

    /** Selection is navigation only; the caller has already committed the current cursor. */
    fun selectSearchHit(target: BookLocatedSearchHit, savedCurrentPart: Boolean): BookRoute? {
        if (!savedCurrentPart || target.validated() == null) return null
        return selectPart(target.bookKey, savedCurrentPart)?.copy(pendingSearchTarget = target)?.let(::finish)
    }

    fun clearPendingSearch(): BookRoute = copy(pendingSearchTarget = null)

    /** The caller has already committed savedPosition before beginning a different source. */
    fun withDedication(selection: PatronalEventProof, savedPosition: BookReadingPosition,
        expectedProfileProof: PatronalEventProof? = null): BookRoute? = runCatching {
        val route = validated() ?: return null
        if (!selection.isValid() || expectedProfileProof?.isValid() == false ||
            savedPosition.key != route.activeKey || savedPosition.parishId != route.parishId ||
            savedPosition.journeyContext != route.journeyContext ||
            BookReadingCodec.decode(BookReadingCodec.encode(savedPosition)) == null) return null
        val sourceChanges = selection.id != activeKey.patronalId || selection.churchType != activeKey.churchType
        fun selected(key: ServiceBookKey) = if (!sourceChanges) key else key.copy(
            churchType = selection.churchType, patronalId = selection.id, mainEvent = null, secondEvent = null)
        val previousJourney = route.journeyContext
        val changed = previousJourney?.copy(parts = previousJourney.parts.map(::selected))
        finish(route.copy(activeKey = selected(route.activeKey), journeyContext = changed, lastSuccessfulKey = null,
            pendingSearchTarget = null, pendingDedication = BookDedicationRequest(selection, expectedProfileProof),
            sourceReturn = BookSourceReturn(savedPosition, route.lastSuccessfulKey)))
    }.getOrNull()

    fun clearPendingDedication(): BookRoute = copy(pendingDedication = null)

    /** Restoring the cursor itself is an explicit, separately verified store operation. */
    fun returnToPreviousSource(): BookRoute? = validated()?.sourceReturn?.routeFor(parishId)

    /** Only the recorded accepted key is eligible, never an arbitrary requested variant. */
    fun returnToLastSuccessful(): BookRoute? {
        val route = validated() ?: return null
        val journey = route.journeyContext ?: return null
        val previous = route.lastSuccessfulKey ?: return null
        val pendingPart = journey.parts.singleOrNull { samePart(it, previous) } ?: return null
        val restored = journey.replacePart(pendingPart, previous) ?: return null
        return finish(route.copy(activeKey = previous, journeyContext = restored, pendingSearchTarget = null))
    }

    private fun finish(next: BookRoute): BookRoute? = runCatching {
        val checked = next.validated() ?: return null
        // A successful navigation must also survive the bounded process-state codec.
        BookRouteCodec.encode(checked)
        checked
    }.getOrNull()

    private fun samePart(first: ServiceBookKey, second: ServiceBookKey) =
        first.date == second.date && first.worship == second.worship && first.churchType == second.churchType &&
            first.topLevel == second.topLevel && first.patronalId == second.patronalId
}

internal object BookJourneyCodec {
    private val gson = Gson()
    fun decode(element: JsonElement?, key: ServiceBookKey, parishId: String?): BookJourneyContext? = runCatching {
        if (element == null || !element.isJsonObject) null
        else {
            val kind = element.asJsonObject.get("partSet")
            // Absent/null is the old explicit contract; an unknown new value is not.
            if (kind != null && !kind.isJsonNull && (!kind.isJsonPrimitive || !kind.asJsonPrimitive.isString ||
                    BookPartSetKind.entries.none { it.name == kind.asString })) return@runCatching null
            gson.fromJson(element, BookJourneyContext::class.java)?.validatedFor(key, parishId)
        }
    }.getOrNull()
}

object BookRouteCodec {
    private val gson = Gson()
    fun decode(json: String?): BookRoute? {
        if (json.isNullOrBlank() || json.length > 8192) return null
        return runCatching {
            val parsed = JsonParser.parseString(json).asJsonObject
            val morningJourney = parsed.get("journeyContext")?.takeIf { it.isJsonObject }?.asJsonObject?.get("partSet")?.let {
                it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == BookPartSetKind.HOURS_LITURGY_TEXTS.name
            } == true
            val objectValue = if (parsed.has("pendingDedication") || parsed.has("sourceReturn") || morningJourney)
                StrictBookJson.objectValue(json, maximumDepth = 16, maximumValues = 4096) else parsed
            val journey = objectValue.remove("journeyContext")
            val previous = objectValue.remove("lastSuccessfulKey")
            val pending = objectValue.remove("pendingSearchTarget")
            val dedication = objectValue.remove("pendingDedication")
            val sourceReturn = objectValue.remove("sourceReturn")
            val route = gson.fromJson(objectValue, BookRoute::class.java).validated() ?: return null
            val target = if (pending == null || pending.isJsonNull) null else {
                if (!validTargetJson(pending)) return null
                gson.fromJson(pending, BookLocatedSearchHit::class.java)?.validated() ?: return null
            }
            val requestedDedication = if (dedication == null || dedication.isJsonNull) null else {
                if (!validDedicationJson(dedication)) return null
                gson.fromJson(dedication, BookDedicationRequest::class.java)
            }
            val previousSource = if (sourceReturn == null || sourceReturn.isJsonNull) null else {
                if (!validSourceReturnJson(sourceReturn)) return null
                val value = sourceReturn.asJsonObject
                val position = BookReadingCodec.decode(value.get("position").toString()) ?: return null
                // BookReadingCodec deliberately drops bad legacy contexts. A new
                // return transaction instead refuses to lose its complete route.
                val rawContext = value.getAsJsonObject("position").get("journeyContext")
                if (rawContext != null && !rawContext.isJsonNull && position.journeyContext == null) return null
                BookSourceReturn(position, gson.fromJson(value.get("lastSuccessfulKey"), ServiceBookKey::class.java))
            }
            val decodedJourney = BookJourneyCodec.decode(journey, route.activeKey, route.parishId)
            if (morningJourney && (decodedJourney == null || !validJourneyJson(journey))) return null
            if (requestedDedication != null || previousSource != null) {
                if (!objectValue.keySet().all { it in setOf("activeKey", "parishId") } ||
                    !validKeyJson(objectValue.get("activeKey")) || !nullableString(objectValue.get("parishId")) ||
                    !validJourneyJson(journey) || previous != null && !previous.isJsonNull && !validKeyJson(previous) ||
                    journey != null && !journey.isJsonNull && decodedJourney == null) return null
            }
            route.copy(journeyContext = decodedJourney,
                lastSuccessfulKey = runCatching { gson.fromJson(previous, ServiceBookKey::class.java) }.getOrNull(),
                pendingSearchTarget = target, pendingDedication = requestedDedication, sourceReturn = previousSource).validated()
        }.getOrNull()
    }

    fun encode(route: BookRoute): String = gson.toJson(requireNotNull(route.validated())).also { require(it.length <= 8192) }

    private fun string(value: JsonElement?) = value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString
    private fun nullableString(value: JsonElement?) = value == null || value.isJsonNull || string(value)

    private fun validKeyJson(value: JsonElement?): Boolean = runCatching {
        val key = requireNotNull(value).asJsonObject
        key.keySet().all { it in setOf("date", "worship", "churchType", "mainEvent", "secondEvent", "topLevel", "patronalId") } &&
            listOf("date", "worship", "churchType").all { string(key.get(it)) } &&
            listOf("mainEvent", "secondEvent", "topLevel", "patronalId").all { nullableString(key.get(it)) } &&
            gson.fromJson(key, ServiceBookKey::class.java).isValid()
    }.getOrDefault(false)

    private fun validProofJson(value: JsonElement?): Boolean = runCatching {
        val proof = requireNotNull(value).asJsonObject
        val names = setOf("id", "mineaId", "calendarDate", "title", "churchType", "sourceUrl")
        proof.keySet() == names && names.all { string(proof.get(it)) } &&
            gson.fromJson(proof, PatronalEventProof::class.java).isValid()
    }.getOrDefault(false)

    private fun validJourneyJson(value: JsonElement?): Boolean = runCatching {
        if (value == null || value.isJsonNull) return true
        val context = value.asJsonObject
        context.keySet().all { it in setOf("parishId", "civilDate", "time", "title", "parts", "schemaVersion", "partSet") } &&
            listOf("parishId", "civilDate", "time", "title").all { string(context.get(it)) } &&
            context.get("schemaVersion").let { it != null && it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.toString() == "1" } &&
            context.get("parts").let { it != null && it.isJsonArray &&
                it.asJsonArray.size() == (if (context.get("partSet")?.let { kind ->
                    string(kind) && kind.asString == BookPartSetKind.HOURS_LITURGY_TEXTS.name } == true) 3 else 2) &&
                it.asJsonArray.all(::validKeyJson) } &&
            context.get("partSet").let { it == null || it.isJsonNull || string(it) && BookPartSetKind.entries.any { kind -> kind.name == it.asString } }
    }.getOrDefault(false)

    private fun validDedicationJson(value: JsonElement): Boolean = runCatching {
        val request = value.asJsonObject
        request.keySet().all { it in setOf("selection", "expectedProfile") } && validProofJson(request.get("selection")) &&
            request.get("expectedProfile").let { it == null || it.isJsonNull || validProofJson(it) }
    }.getOrDefault(false)

    private fun validSourceReturnJson(value: JsonElement): Boolean = runCatching {
        val previous = value.asJsonObject
        if (!previous.keySet().all { it in setOf("position", "lastSuccessfulKey") }) return false
        val position = previous.getAsJsonObject("position")
        val fields = setOf("key", "contentHash", "parishId", "blockId", "chapterId", "fraction", "mode", "wordsPerMinute",
            "showAltar", "anchorKind", "lastTextMode", "journeyContext", "followingGapFraction")
        position.keySet().all { it in fields } && validKeyJson(position.get("key")) &&
            listOf("contentHash", "blockId", "chapterId", "mode", "anchorKind").all { string(position.get(it)) } &&
            nullableString(position.get("parishId")) && nullableString(position.get("lastTextMode")) &&
            position.get("lastTextMode").let { it == null || it.isJsonNull ||
                BookReadingMode.entries.any { mode -> mode != BookReadingMode.MEANING && mode.name == it.asString } } &&
            validJourneyJson(position.get("journeyContext")) &&
            position.get("showAltar").let { it == null || it.isJsonPrimitive && it.asJsonPrimitive.isBoolean } &&
            position.get("wordsPerMinute").let { it == null || it.isJsonPrimitive && it.asJsonPrimitive.isNumber &&
                it.toString().matches(Regex("[0-9]{1,4}")) } &&
            listOf("fraction", "followingGapFraction").all { name ->
                position.get(name).let { it == null || it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            } && previous.get("lastSuccessfulKey").let { it == null || it.isJsonNull || validKeyJson(it) }
    }.getOrDefault(false)

    private fun validTargetJson(element: JsonElement): Boolean = runCatching {
        val target = element.asJsonObject
        fun string(value: JsonElement?) = value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString
        val key = target.getAsJsonObject("bookKey")
        val hit = target.getAsJsonObject("hit")
        string(target.get("contentHash")) && listOf("date", "worship", "churchType").all { string(key.get(it)) } &&
            WorshipKind.entries.any { it.name == key.get("worship").asString } &&
            listOf("mainEvent", "secondEvent", "topLevel", "patronalId").all { key.get(it).let { value -> value == null || value.isJsonNull || string(value) } } &&
            listOf("chapterId", "chapterTitle", "blockId", "excerpt", "mode").all { string(hit.get(it)) } &&
            BookReadingMode.entries.any { it != BookReadingMode.MEANING && it.name == hit.get("mode").asString } &&
            listOf("matchStart", "matchEndExclusive").all {
                val number = hit.get(it)
                number != null && number.isJsonPrimitive && number.asJsonPrimitive.isNumber && number.toString().matches(Regex("0|[1-9][0-9]{0,7}"))
            }
    }.getOrDefault(false)
}

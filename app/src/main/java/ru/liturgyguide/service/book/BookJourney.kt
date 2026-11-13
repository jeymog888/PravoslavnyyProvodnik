package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import java.time.LocalDate
import java.time.LocalTime

enum class BookPartSetKind { PUBLISHED_ORDER, EVENING_TEXTS }

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

    fun validatedFor(key: ServiceBookKey, parishId: String?): BookJourneyContext? = runCatching {
        val date = LocalDate.parse(civilDate)
        val valid = schemaVersion == 1 && this.parishId == parishId && this.parishId.length in 1..160 &&
            date.toString() == civilDate && time.matches(Regex("[0-2][0-9]:[0-5][0-9]")) &&
            LocalTime.parse(time).hour >= 12 && title.length in 1..500 &&
            (if (isEveningTexts) ServiceBookSelection.isUnspecifiedEvening(title)
                else ServiceBookSelection.isCombinedEvening(title)) && parts.size == 2 &&
            parts.map { it.worship } == listOf(WorshipKind.VESPERS, WorshipKind.MATINS) &&
            parts.all { it.isValid() && it.date == date.plusDays(1).toString() } &&
            // Old daily journeys keep their accepted keys even when the publication named polyeleos.
            (parts[1].topLevel == null || !isEveningTexts && ServiceBookSelection.isPolyeleosMatins(title)) &&
            parts[0].churchType == parts[1].churchType && parts[0].patronalId == parts[1].patronalId && key in parts
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

/** One serialized owner keeps the requested key and its occurrence together across recreation. */
data class BookRoute(
    val activeKey: ServiceBookKey,
    val parishId: String?,
    val journeyContext: BookJourneyContext? = null,
    val lastSuccessfulKey: ServiceBookKey? = null,
    val pendingSearchTarget: BookLocatedSearchHit? = null
) {
    fun validated(): BookRoute? = runCatching {
        if (!activeKey.isValid() || (parishId != null && parishId.length !in 1..160)) return null
        val journey = journeyContext?.validatedFor(activeKey, parishId)
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
        val changed = journeyContext?.replacePart(activeKey, key)
        if (savedCurrentPart && journeyContext != null && changed == null) return null
        return copy(activeKey = key, journeyContext = changed,
            lastSuccessfulKey = if (savedCurrentPart) activeKey else lastSuccessfulKey,
            pendingSearchTarget = null).validated()
    }

    fun selectPart(key: ServiceBookKey, savedCurrentPart: Boolean): BookRoute? {
        val journey = journeyContext?.validatedFor(activeKey, parishId) ?: return null
        if (key !in journey.parts) return null
        return copy(activeKey = key, lastSuccessfulKey = if (savedCurrentPart) activeKey else lastSuccessfulKey,
            pendingSearchTarget = null).validated()
    }

    /** Selection is navigation only; the caller has already committed the current cursor. */
    fun selectSearchHit(target: BookLocatedSearchHit, savedCurrentPart: Boolean): BookRoute? {
        if (!savedCurrentPart || target.validated() == null) return null
        return selectPart(target.bookKey, savedCurrentPart)?.copy(pendingSearchTarget = target)?.validated()
    }

    fun clearPendingSearch(): BookRoute = copy(pendingSearchTarget = null)

    /** Only the recorded accepted key is eligible, never an arbitrary requested variant. */
    fun returnToLastSuccessful(): BookRoute? {
        val route = validated() ?: return null
        val journey = route.journeyContext ?: return null
        val previous = route.lastSuccessfulKey ?: return null
        val pendingPart = journey.parts.singleOrNull { samePart(it, previous) } ?: return null
        val restored = journey.replacePart(pendingPart, previous) ?: return null
        return route.copy(activeKey = previous, journeyContext = restored, pendingSearchTarget = null)
    }

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
            val objectValue = JsonParser.parseString(json).asJsonObject
            val journey = objectValue.remove("journeyContext")
            val previous = objectValue.remove("lastSuccessfulKey")
            val pending = objectValue.remove("pendingSearchTarget")
            val route = gson.fromJson(objectValue, BookRoute::class.java).validated() ?: return null
            val target = if (pending == null || pending.isJsonNull) null else {
                if (!validTargetJson(pending)) return null
                gson.fromJson(pending, BookLocatedSearchHit::class.java)?.validated() ?: return null
            }
            route.copy(journeyContext = BookJourneyCodec.decode(journey, route.activeKey, route.parishId),
                lastSuccessfulKey = runCatching { gson.fromJson(previous, ServiceBookKey::class.java) }.getOrNull(),
                pendingSearchTarget = target).validated()
        }.getOrNull()
    }

    fun encode(route: BookRoute): String = gson.toJson(requireNotNull(route.validated()))

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

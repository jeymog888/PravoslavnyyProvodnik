package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import java.time.LocalDate
import java.time.LocalTime

/** Local navigation metadata for one published service, never part of a book or its hash. */
data class BookJourneyContext(
    val parishId: String,
    val civilDate: String,
    val time: String,
    val title: String,
    val parts: List<ServiceBookKey>,
    val schemaVersion: Int = 1
) {
    fun validatedFor(key: ServiceBookKey, parishId: String?): BookJourneyContext? = runCatching {
        val date = LocalDate.parse(civilDate)
        val valid = schemaVersion == 1 && this.parishId == parishId && this.parishId.length in 1..160 &&
            date.toString() == civilDate && time.matches(Regex("[0-2][0-9]:[0-5][0-9]")) &&
            LocalTime.parse(time).hour >= 12 && title.length in 1..500 &&
            ServiceBookSelection.isCombinedEvening(title) && parts.size == 2 &&
            parts.map { it.worship } == listOf(WorshipKind.VESPERS, WorshipKind.MATINS) &&
            parts.all { it.isValid() && it.date == date.plusDays(1).toString() } &&
            // Old daily journeys keep their accepted keys even when the publication named polyeleos.
            (parts[1].topLevel == null || ServiceBookSelection.isPolyeleosMatins(title)) &&
            parts[0].churchType == parts[1].churchType && key in parts
        takeIf { valid }
    }.getOrNull()

    /** A variant belongs only to the current source; event IDs never cross into its neighbour. */
    fun replacePart(previous: ServiceBookKey, next: ServiceBookKey): BookJourneyContext? {
        if (validatedFor(previous, parishId) == null || !next.isValid() ||
            previous.date != next.date || previous.worship != next.worship || previous.churchType != next.churchType ||
            previous.topLevel != next.topLevel) return null
        return copy(parts = parts.map { if (it == previous) next else it }).validatedFor(next, parishId)
    }
}

/** One serialized owner keeps the requested key and its occurrence together across recreation. */
data class BookRoute(
    val activeKey: ServiceBookKey,
    val parishId: String?,
    val journeyContext: BookJourneyContext? = null,
    val lastSuccessfulKey: ServiceBookKey? = null
) {
    fun validated(): BookRoute? = runCatching {
        if (!activeKey.isValid() || (parishId != null && parishId.length !in 1..160)) return null
        val journey = journeyContext?.validatedFor(activeKey, parishId)
        copy(journeyContext = journey,
            lastSuccessfulKey = lastSuccessfulKey?.takeIf { previous ->
                previous.isValid() && journey != null && journey.parts.any { part -> samePart(part, previous) }
            })
    }.getOrNull()

    fun withVariant(key: ServiceBookKey, savedCurrentPart: Boolean): BookRoute? {
        val changed = journeyContext?.replacePart(activeKey, key)
        if (savedCurrentPart && journeyContext != null && changed == null) return null
        return copy(activeKey = key, journeyContext = changed,
            lastSuccessfulKey = if (savedCurrentPart) activeKey else lastSuccessfulKey).validated()
    }

    fun selectPart(key: ServiceBookKey, savedCurrentPart: Boolean): BookRoute? {
        val journey = journeyContext?.validatedFor(activeKey, parishId) ?: return null
        if (key !in journey.parts) return null
        return copy(activeKey = key, lastSuccessfulKey = if (savedCurrentPart) activeKey else lastSuccessfulKey)
    }

    /** Only the recorded accepted key is eligible, never an arbitrary requested variant. */
    fun returnToLastSuccessful(): BookRoute? {
        val route = validated() ?: return null
        val journey = route.journeyContext ?: return null
        val previous = route.lastSuccessfulKey ?: return null
        val pendingPart = journey.parts.singleOrNull { samePart(it, previous) } ?: return null
        val restored = journey.replacePart(pendingPart, previous) ?: return null
        return route.copy(activeKey = previous, journeyContext = restored)
    }

    private fun samePart(first: ServiceBookKey, second: ServiceBookKey) =
        first.date == second.date && first.worship == second.worship && first.churchType == second.churchType &&
            first.topLevel == second.topLevel
}

internal object BookJourneyCodec {
    private val gson = Gson()
    fun decode(element: JsonElement?, key: ServiceBookKey, parishId: String?): BookJourneyContext? = runCatching {
        if (element == null || !element.isJsonObject) null
        else gson.fromJson(element, BookJourneyContext::class.java)?.validatedFor(key, parishId)
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
            val route = gson.fromJson(objectValue, BookRoute::class.java).validated() ?: return null
            route.copy(journeyContext = BookJourneyCodec.decode(journey, route.activeKey, route.parishId),
                lastSuccessfulKey = runCatching { gson.fromJson(previous, ServiceBookKey::class.java) }.getOrNull()).validated()
        }.getOrNull()
    }

    fun encode(route: BookRoute): String = gson.toJson(requireNotNull(route.validated()))
}

package ru.liturgyguide.service.book

import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import java.time.LocalDate
import java.time.LocalTime

object ServiceBookSelection {
    // "A memorial service after the liturgy" names the memorial service, not another liturgy.
    // Remove only dependent timing references; a separately named service remains eligible.
    private val timingReference = Regex("\\b(?:после(?: окончания| завершения)?|по окончании|по завершении|перед|до)\\s+" +
        "(?:(?:(?:ранн|поздн|божественн|праздничн|архиерейск|полиелейн)[а-я]*|(?:утренн|вечерн)(?:ей|его|юю|ее|яя|ий|ем))\\s+){0,3}" +
        "(?:литурги[а-я]*|вечерн[а-я]*|утрен[а-я]*|всенощ[а-я]*(?:\\s+бдени[а-я]*)?|бдени[а-я]*)\\b")

    private fun independentTitle(title: String) = timingReference.replace(BookText.normalized(title), " ")

    // A reference such as "after polyeleos matins" does not name the service being opened.
    // Keep the first supported form narrow; do not infer the rank from the word "polyeleos" alone.
    internal fun isPolyeleosMatins(title: String): Boolean =
        Regex("\\bполиелейная\\s+утреня\\b").containsMatchIn(independentTitle(title))

    internal fun isCombinedEvening(title: String): Boolean {
        val value = independentTitle(title)
        // Unambiguous service nouns, including the genitive, must name both parts.
        // "Вечерняя утреня" and "утренние молитвы" only describe one service.
        return Regex("\\bвечерн(?:я|и|ю|е)\\b").containsMatchIn(value) &&
            Regex("\\bутрен(?:я|и|ю|е)\\b").containsMatchIn(value) &&
            listOf("всенощ", "бдение", "литург", "молеб", "панихид", "акафист", "повечер", "полунощ", "исповед", "лити").none { it in value } &&
            !Regex("\\b(?:час[а-я]*|[1369])\\b").containsMatchIn(value)
    }

    /** Both schedule entry points resolve the original publication exactly once. */
    fun resolve(service: ParishService, parish: Parish?): BookRoute? = runCatching {
        val kind = kindFor(service.title) ?: return null
        val date = LocalDate.parse(service.date)
        val eveningMatins = kind == WorshipKind.MATINS &&
            runCatching { LocalTime.parse(service.time).hour >= 12 }.getOrDefault(false)
        val matinsRank = if (isPolyeleosMatins(service.title)) "3" else null
        val key = forCivilDate(date, kind, parish, eveningMatins)
            .let { if (kind == WorshipKind.MATINS) it.copy(topLevel = matinsRank) else it }
        val journey = if (parish != null && isCombinedEvening(service.title)) BookJourneyContext(
            parish.id, service.date, service.time, service.title,
            listOf(key, forCivilDate(date, WorshipKind.MATINS, parish, eveningMatins = true).copy(topLevel = matinsRank)))
            .validatedFor(key, parish.id) else null
        BookRoute(key, parish?.id, journey).validated()
    }.getOrNull()

    fun kindFor(title: String): WorshipKind? {
        val value = independentTitle(title)
        return when {
            "всенощ" in value || "бдение" in value -> WorshipKind.VIGIL
            "литург" in value -> WorshipKind.LITURGY
            "вечерн" in value -> WorshipKind.VESPERS
            "утрен" in value -> WorshipKind.MATINS
            "первый час" in value || "1 час" in value -> WorshipKind.HOUR1
            "третий час" in value || "3 час" in value -> WorshipKind.HOUR3
            "шестой час" in value || "6 час" in value -> WorshipKind.HOUR6
            "девятый час" in value || "9 час" in value -> WorshipKind.HOUR9
            else -> null
        }
    }

    /** Evening books belong to tomorrow; the published civil date remains unchanged in the schedule. */
    fun forCivilDate(date: LocalDate, kind: WorshipKind, parish: Parish?, eveningMatins: Boolean = false): ServiceBookKey {
        val tomorrow = kind in setOf(WorshipKind.VESPERS, WorshipKind.VIGIL) || (kind == WorshipKind.MATINS && eveningMatins)
        return ServiceBookKey((if (tomorrow) date.plusDays(1) else date).toString(), kind, churchType(parish))
    }

    fun churchType(parish: Parish?): String = ChurchTypeClassifier.classify(parish).requestType

    /** Only for finding a previously accepted v0.8/v0.9 book; never classify a new request with this. */
    internal fun legacyChurchType(parish: Parish?): String {
        val name = BookText.normalized(parish?.name.orEmpty())
        return when {
            "крестовоздвиж" in name || "воздвижения креста" in name -> "12"
            "николая чудотворца" in name || "николая мирликийского" in name || "святителя николая" in name -> "34"
            "иоанна предтечи" in name || "иоанна крестителя" in name -> "32"
            "богородиц" in name || "божией матери" in name || "божьей матери" in name -> "20"
            else -> "30"
        }
    }
}

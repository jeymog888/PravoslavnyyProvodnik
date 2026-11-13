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
    private val vespersNoun = Regex("\\bвечерн(?:я|и|ю|е)\\b")
    private val matinsNoun = Regex("\\bутрен(?:я|и|ю|е)\\b")

    /** A published time without named parts is not evidence that vespers is being read now. */
    fun isUnspecifiedEvening(title: String): Boolean {
        val value = independentTitle(title).trim()
        return (value == "вечернее богослужение" || value.startsWith("вечернее богослужение ")) &&
            !vespersNoun.containsMatchIn(value) && !matinsNoun.containsMatchIn(value) &&
            listOf("литург", "всенощ", "бдение").none { it in value }
    }

    /** Two available base texts, not a claim that the publication names their local order. */
    fun eveningTexts(service: ParishService, parish: Parish?, chosen: WorshipKind): BookRoute? = runCatching {
        if (parish == null || !isUnspecifiedEvening(service.title) || chosen !in setOf(WorshipKind.VESPERS, WorshipKind.MATINS)) return null
        val date = LocalDate.parse(service.date)
        val parts = listOf(forCivilDate(date, WorshipKind.VESPERS, parish),
            forCivilDate(date, WorshipKind.MATINS, parish, eveningMatins = true))
        val key = parts.single { it.worship == chosen }
        val context = BookJourneyContext(parish.id, service.date, service.time, service.title, parts,
            partSet = BookPartSetKind.EVENING_TEXTS).validatedFor(key, parish.id) ?: return null
        BookRoute(key, parish.id, context).validated()
    }.getOrNull()

    // A reference such as "after polyeleos matins" does not name the service being opened.
    // Keep the first supported form narrow; do not infer the rank from the word "polyeleos" alone.
    internal fun isPolyeleosMatins(title: String): Boolean =
        Regex("\\bполиелейная\\s+утреня\\b").containsMatchIn(independentTitle(title))

    /** An explicit schedule note is an expectation, never a request to raise an arbitrary saint's rank. */
    internal fun hasPublishedEveningPolyeleos(title: String): Boolean {
        if (!isUnspecifiedEvening(title)) return false
        val noun = "полиеле(?:й|я|ю|ем|е)"
        val dependent = Regex("\\b(?:без|после|перед|до|по окончании|по завершении)\\s+" +
            "(?:(?:начала|окончания|совершения)\\s+)?$noun\\b")
        val cancelled = Regex("\\b$noun\\s+(?:не\\s+(?:совершается|будет|предусмотрен)|отмен[а-я]*)\\b")
        val cancelledBefore = Regex("\\b(?:отмен[а-я]*|не\\s+будет|не\\s+совершается)\\s+$noun\\b")
        val independent = cancelledBefore.replace(cancelled.replace(dependent.replace(independentTitle(title), " "), " "), " ")
        return Regex("\\b$noun\\b").containsMatchIn(independent)
    }

    internal fun isCombinedEvening(title: String): Boolean {
        val value = independentTitle(title)
        // Unambiguous service nouns, including the genitive, must name both parts.
        // "Вечерняя утреня" and "утренние молитвы" only describe one service.
        return vespersNoun.containsMatchIn(value) && matinsNoun.containsMatchIn(value) &&
            listOf("всенощ", "бдение", "литург", "молеб", "панихид", "акафист", "повечер", "полунощ", "исповед", "лити").none { it in value } &&
            !Regex("\\b(?:час[а-я]*|[1369])\\b").containsMatchIn(value)
    }

    /** The publication names ordinary hours and a liturgy, not an interval or another set of hours. */
    fun isHoursLiturgy(title: String): Boolean {
        val value = independentTitle(title)
        // Parentheses and ':' carry composition, so inspect them before normalization
        // removes punctuation. Numbers in a saint's name outside this span are unrelated.
        val declaredHours = Regex("(?iu)\\bчасы\\s*(?:\\(([^)]{1,180})\\)|:\\s*([^.!?]{1,180}))")
            .findAll(BookText.clean(title))
        val ordinal = Regex("\\b(?:[1369](?:[ -]?(?:й|го))?|первый|первого|третий|третьего|шестой|шестого|девятый|девятого)\\b")
        if (declaredHours.any { ordinal.containsMatchIn(BookText.normalized(it.groupValues.drop(1).joinToString(" "))) }) return false
        return Regex("\\bчасы\\b").containsMatchIn(value) && Regex("\\bлитургия\\b").containsMatchIn(value) &&
            listOf("царск", "пасхальн", "преждеосвящ", "изобразительн").none { it in value } &&
            !Regex("\\b(?:[1369](?:[ -]?(?:й|го))?|первый|первого|третий|третьего|шестой|шестого|девятый|девятого)\\s+(?:час|часы|часа)\\b").containsMatchIn(value) &&
            !Regex("\\bчасы\\s+(?:[1369](?: й| го)?|первый|первого|третий|третьего|шестой|шестого|девятый|девятого)\\b").containsMatchIn(value) &&
            !Regex("\\b(?:после|перед|до|окончании|завершении)\\s+час(?:ы|ов|ами)\\b").containsMatchIn(value)
    }

    fun hoursLiturgyScope(title: String): String = if (listOf("молеб", "панихид", "акафист", "исповед").any {
            it in BookText.normalized(title) })
        "Тексты третьего и шестого часов и литургии. Остальные службы из этой записи в набор не входят"
    else "Тексты третьего и шестого часов и литургии. Местные сокращения и выбор песнопений могут отличаться"

    /** Both schedule entry points resolve the original publication exactly once. */
    fun resolve(service: ParishService, parish: Parish?): BookRoute? = runCatching {
        if (parish != null && isHoursLiturgy(service.title)) {
            val parts = listOf(WorshipKind.HOUR3, WorshipKind.HOUR6, WorshipKind.LITURGY).map {
                forCivilDate(LocalDate.parse(service.date), it, parish)
            }
            val context = BookJourneyContext(parish.id, service.date, service.time, service.title, parts,
                partSet = BookPartSetKind.HOURS_LITURGY_TEXTS).validatedFor(parts.first(), parish.id) ?: return null
            return BookRoute(parts.first(), parish.id, context).validated()
        }
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
            vespersNoun.containsMatchIn(value) -> WorshipKind.VESPERS
            matinsNoun.containsMatchIn(value) -> WorshipKind.MATINS
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

package ru.liturgyguide.service.book

import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import java.time.LocalDate

object ServiceBookSelection {
    // "A memorial service after the liturgy" names the memorial service, not another liturgy.
    // Remove only dependent timing references; a separately named service remains eligible.
    private val timingReference = Regex("\\b(?:после(?: окончания| завершения)?|по окончании|по завершении|перед|до)\\s+" +
        "(?:(?:(?:ранн|поздн|божественн|праздничн|архиерейск)[а-я]*|(?:утренн|вечерн)(?:ей|его|юю|ее|яя|ий|ем))\\s+){0,3}" +
        "(?:литурги[а-я]*|вечерн[а-я]*|утрен[а-я]*|всенощ[а-я]*(?:\\s+бдени[а-я]*)?|бдени[а-я]*)\\b")

    fun kindFor(title: String): WorshipKind? {
        val value = timingReference.replace(BookText.normalized(title), " ")
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

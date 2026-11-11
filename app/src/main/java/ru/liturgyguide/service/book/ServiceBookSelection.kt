package ru.liturgyguide.service.book

import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import java.time.LocalDate

object ServiceBookSelection {
    fun kindFor(title: String): WorshipKind? {
        val value = BookText.normalized(title)
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

    /** Only explicit dedications supported by the source. The chosen variant is visible in the reader. */
    fun churchType(parish: Parish?): String {
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

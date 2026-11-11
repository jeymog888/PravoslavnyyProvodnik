package ru.liturgyguide.service.book

import ru.liturgyguide.model.Parish

enum class ChurchTypeEvidence { EXPLICIT_NAME, UNKNOWN, AMBIGUOUS }

/** A source request fallback is not evidence of the church's dedication. */
data class ChurchTypeClassification(val type: String?, val evidence: ChurchTypeEvidence) {
    val requestType: String get() = type ?: "30"
}

object ChurchTypeClassifier {
    private val building = Regex("\\b(?:храм|храма|церковь|церкви|собор|собора|часовня|часовни|придел|придела)\\b")
    private val location = Regex("(?:^|\\s+)(?:в|на|при|у)\\s+")
    private val lord = Regex("(?:живоначальной троицы|(?:святой |пресвятой )?троицы(?: живоначальной)?|" +
        "(?:святой |пресвятой )живоначальной троицы|воскресения христова|вознесения господня|" +
        "преображения господня|рождества христова|богоявления(?: господня)?|крещения господня|сретения господня)")
    private val cross = Regex("(?:крестовоздвижения|воздвижения (?:честного и животворящего )?креста(?: господня)?)")
    private val nicholas = Regex("(?:(?:святителя|свт|святого|св) )?николая (?:чудотворца|мирликийского(?: чудотворца)?|" +
        "(?:архиепископа )?мир ликийских(?: чудотворца)?)|святителя николая")
    private val forerunner = Regex("(?:(?:рождества|усекновения главы) )?(?:(?:святого|св|пророка) )?иоанна " +
        "(?:предтечи(?: и крестителя господня)?|крестителя(?: господня)?)")
    private val mary = Regex("(?:богородицы|божией матери|божьей матери)\\b")
    private val unknown = ChurchTypeClassification(null, ChurchTypeEvidence.UNKNOWN)
    private val ambiguous = ChurchTypeClassification(null, ChurchTypeEvidence.AMBIGUOUS)

    fun classify(parish: Parish?): ChurchTypeClassification = classifyName(parish?.name.orEmpty())

    /** Match the named building, never its address or an associated monastery in the suffix. */
    fun classifyName(name: String): ChurchTypeClassification {
        val value = BookText.normalized(name)
        // An adjective belongs to the building only in this explicit, bounded form.
        if (Regex("крестовоздвиженск(?:ий|ая) (?:храм|церковь|собор)(?: (?:в|на|при|у) .+)?").matches(value))
            return explicit("12")
        val head = building.find(value) ?: return unknown
        var dedication = value.substring(head.range.last + 1).trim()
        dedication = dedication.removePrefix("в честь ").removePrefix("во имя ")
        location.find(dedication)?.let { dedication = dedication.substring(0, it.range.first) }
        dedication = dedication.replace("богородицы и приснодевы марии", "богородицы")
        if (dedication.isBlank()) return unknown

        // Full matches are intentional: "Воскресения Христова и Новомучеников" is
        // not enough to decide which dedication supplies the local hymn. Do not pick the first.
        val exact = when {
            cross.matches(dedication) -> "12"
            dedication == "воскресения словущего" -> "11"
            lord.matches(dedication) -> "10"
            nicholas.matches(dedication) -> "34"
            forerunner.matches(dedication) -> "32"
            else -> null
        }
        if (exact != null) return explicit(exact)
        if (" и " in dedication || (Regex("\\b(?:придел[а-я]*|храм[а-я]*|церков[а-я]*)\\b").containsMatchIn(dedication)
            && !dedication.startsWith("введения во храм "))) return ambiguous
        // The icon's name may precede or follow "Божией Матери". This selects only
        // the general source category, never a patronal identifier or a particular hymn.
        if (mary.containsMatchIn(dedication)) return explicit("20")
        return unknown
    }

    fun description(sourceType: String, classification: ChurchTypeClassification): String {
        val sourceLabel = label(sourceType)
        return when {
            classification.type == sourceType -> "Вариант по посвящению храма: $sourceLabel"
            classification.type != null -> "Вариант источника: $sourceLabel. По посвящению храма: ${label(classification.type)}"
            classification.evidence == ChurchTypeEvidence.AMBIGUOUS ->
                "Посвящение неоднозначно. Вариант источника: $sourceLabel; для этого храма он не подтвержден"
            else -> "Посвящение пока не определено. Вариант источника: $sourceLabel; для этого храма он не подтвержден"
        }
    }

    private fun label(sourceType: String): String = when (sourceType) {
            "10" -> "Господский храм"
            "11" -> "храм Воскресения Словущего"
            "12" -> "Крестовоздвиженский храм"
            "20" -> "Богородичный храм"
            "31" -> "храм архангела"
            "32" -> "храм Иоанна Предтечи"
            "33" -> "храм апостола"
            "34" -> "храм святителя Николая"
            "35" -> "храм пророка"
            else -> "храм святого"
    }

    private fun explicit(type: String) = ChurchTypeClassification(type, ChurchTypeEvidence.EXPLICIT_NAME)
}

package ru.liturgyguide.service.schedule

import java.util.Locale

/** Conservative building identity for discovery without a known city; never geocodes or supplies a city. */
internal data class PalomnikStreetAddress(
    val street: String,
    val streetKind: String,
    val house: String,
    val corpus: String?,
    val building: String?,
) {
    fun sameBuilding(other: PalomnikStreetAddress): Boolean =
        streetKind == other.streetKind && streetWords(street) == streetWords(other.street) && house == other.house &&
            corpus == other.corpus && building == other.building

    companion object {
        private const val NUMBER = "[0-9]{1,4}(?:/[0-9]{1,4})?(?:\\s*-?\\s*[а-яa-z](?![а-яa-z0-9]))?"
        private val HOUSE = Regex("(?<![а-яa-z0-9/])(?:д(?:ом)?\\.?\\s*)?($NUMBER)((?:[ ,]*(?:корпус|корп\\.?|к\\.?|строение|стр\\.?|с\\.?)\\s*$NUMBER)*)\\s*$")
        private val PART = Regex("[ ,]*(корпус|корп\\.?|к\\.?|строение|стр\\.?|с\\.?)\\s*($NUMBER)")
        private val STREET = Regex("(?<![а-яa-z])(улица|ул\\.?|проспект|пр-кт|просп\\.?|переулок|пер\\.?|проезд|бульвар|бул\\.?|шоссе|набережная|наб\\.?|площадь|пл\\.?)(?=\\s|$)")
        private val WORD = Regex("[а-яa-z0-9]+")
        private fun number(value: String) = value.replace(Regex("[\\s-]"), "")
        private fun streetWords(value: String) = WORD.findAll(value).map { it.value }.toList()

        fun parse(value: String): PalomnikStreetAddress? {
            if (value.length !in 3..500) return null
            val normalized = value.lowercase(Locale.ROOT).replace('ё', 'е').replace('\u00a0', ' ').trim()
                // Only published dotted abbreviations may omit their following space.
                .replace(Regex("(?<![а-яa-z])(ул|просп|пер|бул|наб|пл)\\.(?=[а-яa-z])"), "$1. ")
            val houseMatch = HOUSE.find(normalized) ?: return null
            val prefix = normalized.substring(0, houseMatch.range.first).trimEnd(' ', ',')
            val streetMarker = STREET.findAll(prefix).singleOrNull() ?: return null
            val after = prefix.substring(streetMarker.range.last + 1).trim()
            // OSM commonly publishes "Шереметьевская улица", while parish contacts
            // use "улица Шереметьевская". A suffix must end the street component;
            // only comma-separated locality components may precede its name.
            val street = if (after.isNotEmpty()) after else
                prefix.substring(0, streetMarker.range.first).substringAfterLast(',').trim()
            // Unparsed district/building details must not disappear from the identity comparison.
            if (street.length !in 2..100 || !Regex("[а-яa-z0-9 -]+").matches(street) || WORD.findAll(street).none()) return null
            var corpus: String? = null
            var building: String? = null
            for (part in PART.findAll(houseMatch.groupValues[2])) {
                val label = part.groupValues[1]
                val partNumber = number(part.groupValues[2])
                if (label.startsWith("к")) {
                    if (corpus != null) return null
                    corpus = partNumber
                } else {
                    if (building != null) return null
                    building = partNumber
                }
            }
            val streetKind = when (streetMarker.groupValues[1].trimEnd('.')) {
                "ул" -> "улица"
                "пр-кт", "просп" -> "проспект"
                "пер" -> "переулок"
                "бул" -> "бульвар"
                "наб" -> "набережная"
                "пл" -> "площадь"
                else -> streetMarker.groupValues[1]
            }
            return PalomnikStreetAddress(street, streetKind, number(houseMatch.groupValues[1]), corpus, building)
        }
    }
}

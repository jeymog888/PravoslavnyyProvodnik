package ru.liturgyguide.service.schedule

import ru.liturgyguide.model.Parish

/** Publisher collections, never a table of individual parish URLs or OSM IDs. */
internal data class PublisherGeometryProfile(val id: String, val version: Int, val city: String,
    val ortoxUrl: String, val directoryUrl: String, val area: PublisherArea,
    val minimumOrtOx: Int, val minimumDirectory: Int) {
    fun eligible(parish: Parish): Boolean = parish.address.isBlank() && PublisherGeometry.osmId(parish) != null &&
        ScheduleEntityIdentity.scope(parish.name) == ScheduleEntityScope.CHURCH &&
        parish.latitude?.let { lat -> parish.longitude?.let { lon -> area.contains(PublisherPoint(lat, lon)) } } == true

    companion object {
        val PUBLISHED = listOf(PublisherGeometryProfile("kaluga-publisher", 1, "Калуга",
            "https://eparhia-kaluga.cerkov.ru/xramy/", "https://eparhia-kaluga.ru/kaluzhskaja-eparhija/prihody/kaluga.html",
            PublisherArea(36.05, 54.35, 36.60, 54.75), 188, 36))
    }
}

data class PublisherPageEvidence(val role: String, val requestedUrl: String, val finalUrl: String, val sha256: String)
data class PublisherVerifiedLink(val ownerRole: String, val targetRole: String, val label: String, val url: String)
data class PublisherCatalogRow(val id: String, val name: String, val address: String?, val website: String?,
    val detailUrl: String?, val rector: String?, val phone: String?)
data class PublisherPassport(val id: String, val name: String, val address: String, val rector: String,
    val mapId: String, val mapType: String, val mapName: String, val mapPoint: PublisherPoint)
data class PublisherOwnIdentity(val name: String, val city: String, val address: String, val rector: String,
    val phone: String, val historySection: String, val historicalParagraph: String, val transitionParagraph: String,
    val selfStatement: String)
data class PublisherLinkedAnchor(val url: String, val name: String, val city: String, val address: String, val point: PublisherPoint)

internal object PublisherIdentity {
    fun normalized(value: String) = ScheduleEntityIdentity.normalize(value).replace(Regex("\\s+"), " ")
    fun city(address: String): String? = Regex("(?iu)(?<![а-яё])(?:г\\.|город)\\s*([а-яё-]+(?:\\s+[а-яё-]+)*)(?=,)")
        .findAll(address).map { normalized(it.groupValues[1]) }.toSet().singleOrNull()
    fun publishedLocality(address: String): String? = city(address) ?: Regex(
        "(?iu)(?<![а-яё])(?:пос\\.|п\\.|поселок|с\\.|село|дер\\.|деревня)\\s*([а-яё-]+(?:\\s+[а-яё-]+)*)(?=,|$)")
        .findAll(address).map { "settlement:" + normalized(it.groupValues[1]) }.toSet().singleOrNull()

    /** Enough ownership evidence to locate a candidate, without treating it as the winner.
     * A remote village's clergy or house number need not satisfy the selected city church's
     * full identity. Missing map/ownership evidence instead leaves that candidate unresolved.
     */
    fun correspondingPassport(row: PublisherCatalogRow, passport: PublisherPassport): Boolean =
        Regex("t[1-9][0-9]{0,11}").matches(row.id) && passport.id == row.id &&
            passport.mapId == row.id.removePrefix("t") && passport.mapType == "40" && passport.mapPoint.valid() &&
            listOf(passport.name, passport.mapName).all { it.length in 1..300 && it.none(Char::isISOControl) &&
                ScheduleEntityIdentity.scope(it) == ScheduleEntityScope.CHURCH } &&
            passport.address.length in 1..500 && passport.address.none(Char::isISOControl) &&
            passport.rector.length <= 500 && passport.rector.none(Char::isISOControl) &&
            row.name.isNotBlank() && normalized(row.name) == normalized(passport.name) &&
            normalized(passport.name) == normalized(passport.mapName) &&
            row.address?.takeIf(String::isNotBlank)?.let { normalized(it) == normalized(passport.address) } == true &&
            publishedLocality(passport.address) != null
    fun rector(value: String): String? = normalized(value).takeIf {
        Regex("(?:протоиерей|иерей|священник|игумен|архимандрит) (?:[а-яё-]{2,} ){1,3}[а-яё-]{2,}").matches(it)
    }
    fun phone(value: String): String? {
        val raw = value.trim().replace('\u00a0', ' ')
        if (!Regex("(?:\\+7|8)[0-9 ()-]+").matches(raw)) return null
        val digits = raw.filter(Char::isDigit)
        return digits.takeIf { it.length == 11 && it.first() in "78" }?.let { "+7" + it.drop(1) }
    }
    fun building(value: String) = PalomnikStreetAddress.parse(value.trim().trimEnd('.'))
    fun street(value: String): Pair<String, List<String>>? {
        building(value)?.let { return it.streetKind to streetWords(it.street) }
        val found = Regex("(?iu)(?<![а-яё])(?:улица|улице|ул\\.)\\s+([а-яё -]{2,100})\\.?$").find(value.trim()) ?: return null
        return "улица" to streetWords(found.groupValues[1])
    }
    fun streetWords(value: String): List<String> = normalized(value).split(' ').filter(String::isNotBlank).map { word ->
        if (word.length < 7) word else word.replace(Regex("(?:ая|ой|ое|ом|ый|ий|ей)$"), "")
            .let { if (it == word && it.endsWith('е') && !it.endsWith("ие")) it.dropLast(1) else it }
    }
    /** Full dedication, with only explicit city/district locators removed from the end. */
    fun dedication(raw: String, city: String, address: String = ""): String? {
        var value = normalized(raw)
        val entity = Regex("(?<![а-я])(?:храм|церковь|собор)(?![а-я])").find(value) ?: return null
        val prefix = value.substring(0, entity.range.first)
        if (Regex("(?<![а-я])(?:нижний|верхний|старый|новый|больничный|приписной|часовня|скит)(?![а-я])").containsMatchIn(prefix)) return null
        value = value.substring(entity.range.last + 1).trim().removePrefix("в честь ").removePrefix("во имя ")
        value = value.replace(Regex("(?: г | город | в городе )${Regex.escape(normalized(city))}$"), "")
            .removeSuffix(" ${normalized(city)}").trim()
        val location = Regex(" (?:на|в|при|у) (.+)$").find(value)
        if (location != null) {
            val words = streetWords(location.groupValues[1])
            val available = streetWords(address)
            if (words.isEmpty() || !available.containsAll(words)) return null
            value = value.substring(0, location.range.first).trim()
        }
        return value.takeIf { it.length in 3..200 && it.split(' ').size >= 2 && it.none(Char::isDigit) }
    }
    fun sameName(selected: String, candidate: String, city: String, address: String = "") =
        dedication(selected, city, address)?.let { it == dedication(candidate, city, address) } == true &&
            ScheduleEntityIdentity.scope(candidate) == ScheduleEntityScope.CHURCH

    fun relocated(own: PublisherOwnIdentity, oldAddress: String, selectedName: String): Boolean {
        val old = building(oldAddress) ?: return false
        val current = building(own.address) ?: return false
        if (normalized(own.historySection).let { "истори" !in it || "нашего храма" !in it }) return false
        if (listOf(own.historicalParagraph, own.transitionParagraph).any { it.length !in 10..1500 || it.any { c -> c in "\"«»" || c.isISOControl() } }) return false
        val oldMatch = Regex("(?iu)Сначала\\s+приход\\s+располагался\\s+[^()]{1,100}\\(([^()]+)\\)[^.]{0,180}\\.\\s*А\\s+затем\\s+[^.!?]{0,150}началось\\s+строительство[^.!?]{0,150}храма").find(own.historicalParagraph) ?: return false
        if (building(oldMatch.groupValues[1])?.sameBuilding(old) != true) return false
        val leading = own.historicalParagraph.substringBefore("построен", "")
        if (!sameName(selectedName, leading, own.city, own.address)) return false
        val transition = normalized(own.transitionParagraph)
        if (!Regex("где в то время располагался наш храм к месту строительства нового храма на улице ").containsMatchIn(transition)) return false
        val before = transition.substringBefore("где в то время")
        if (!streetWords(before).containsAll(streetWords(old.street)) || !before.contains(old.streetKind)) return false
        val destination = transition.substringAfter("нового храма на улице ", "")
        if (streetWords(destination) != streetWords(current.street) || current.streetKind != "улица") return false
        return currentStatement(own, selectedName)
    }
    fun currentStatement(own: PublisherOwnIdentity, selectedName: String): Boolean {
        if (own.selfStatement.length !in 10..900 || own.selfStatement.any(Char::isISOControl)) return false
        val current = building(own.address) ?: return false
        val self = Regex("(?iu)наш\\s+храм:\\s*([^()]{3,250})\\(([^()]{3,500})\\)\\.?\\s*$").find(own.selfStatement) ?: return false
        return sameName(selectedName, self.groupValues[1].trim(), own.city, own.address) &&
            building(self.groupValues[2])?.sameBuilding(current) == true && city(self.groupValues[2]) == normalized(own.city)
    }
}

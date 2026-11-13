package ru.liturgyguide.service.schedule

import ru.liturgyguide.model.Parish
import java.net.URI

internal object OwnContactChildIdentity {
    data class NameKey(val scope: ScheduleEntityScope, val dedicationAndQualifiers: List<String>)
    private val omitted = setOf("храм", "церковь", "собор", "часовня", "в", "во", "честь", "имя", "имени")
    private val icon = setOf("иконы", "икона", "божией", "богородицы", "матери")
    private val nonDistinctive = icon + setOf("святой", "святого", "святых", "святителя", "святитель",
        "преподобного", "преподобной", "мученика", "мученицы", "мучеников", "новый", "новая", "старый", "старая",
        "нижний", "нижняя", "верхний", "верхняя", "православный", "православная", "приходской", "приходская")
    private val iconNouns = setOf("икона", "иконы", "иконе", "икону", "иконой", "образ", "образа", "образу", "образом")
    private val motherOfGod = setOf("богородица", "богородицы", "богоматерь", "богоматери", "божия", "божией", "божьей")
    private val genericIconWords = motherOfGod + setOf("матерь", "матери", "пресвятая", "пресвятой", "пречистая", "пречистой",
        "преблагословенная", "преблагословенной", "чудотворная", "чудотворной", "честная", "честной",
        "дева", "девы", "мария", "марии", "приснодева", "приснодевы", "владычица", "владычицы", "наша", "нашей")
    private val buildingQualifiers = setOf("крестильный", "крестильная", "кладбищенский", "кладбищенская",
        "кафедральный", "кафедральная", "домовый", "домовая", "деревянный", "деревянная", "каменный", "каменная",
        "летний", "летняя", "зимний", "зимняя", "приписной", "приписная", "пещерный", "пещерная")
    private fun genericIcon(words: List<String>): Boolean {
        val image = words.indexOfFirst { it in iconNouns }
        if (image < 0) return false
        // A real title can precede "иконы" (Казанской иконы). Check the whole
        // dedication, excluding only known generic words and building qualifiers.
        val dedication = words
        val marian = dedication.any { it in motherOfGod } ||
            dedication.any { it in setOf("мария", "марии") } && dedication.any { it in setOf("дева", "девы", "приснодева", "приснодевы") }
        return marian && dedication.all {
            it in genericIconWords || it in nonDistinctive || it in iconNouns || it in buildingQualifiers
        }
    }
    fun fullName(name: String): Boolean {
        val scope = ScheduleEntityIdentity.scope(name)
        val words = words(name)
        return name.length in 1..300 && name.none(Char::isISOControl) &&
            scope in setOf(ScheduleEntityScope.CHURCH, ScheduleEntityScope.CHAPEL) &&
            words.isNotEmpty() && words.any { it !in nonDistinctive } && !genericIcon(words) &&
            words.none { it in setOf("открыт", "открыта", "ежедневно", "контакты") }
    }
    private fun words(name: String) = ScheduleEntityIdentity.normalize(name).split(' ').filter { it.isNotBlank() && it !in omitted }
    fun nameKey(name: String): NameKey? = if (fullName(name)) NameKey(ScheduleEntityIdentity.scope(name), words(name)) else null
    fun sameName(selected: String, published: String): Boolean {
        val wanted = nameKey(selected) ?: return false
        return wanted == nameKey(published)
    }

    fun ordinaryOsm(parish: Parish): Boolean = runCatching {
        val id = Regex("osm:(node|way|relation):([1-9][0-9]{0,18})").matchEntire(parish.id) ?: return false
        val url = URI(parish.sourceUrl)
        url.scheme == "https" && url.host in setOf("openstreetmap.org", "www.openstreetmap.org") &&
            url.port == -1 && url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null &&
            url.rawPath == "/${id.groupValues[1]}/${id.groupValues[2]}" && fullName(parish.name) &&
            parish.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
            parish.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true
    }.getOrDefault(false)

    fun pointAgrees(parish: Parish, latitude: Double, longitude: Double): Boolean =
        ordinaryOsm(parish) && latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 && OpenScheduleSiteResolver.distanceMeters(requireNotNull(parish.latitude),
                requireNotNull(parish.longitude), latitude, longitude) <= 150.0

    fun objectId(url: String, blockKey: String, name: String) = ScheduleSourceCandidate.sha(
        "own_contact_block\u0000$url\u0000$blockKey\u0000${ScheduleEntityIdentity.normalize(name)}")
    fun conflict(parish: Parish, name: String, locality: String, publishedPhone: String?): Boolean {
        val city = ScheduleEntityIdentity.normalize(locality).removePrefix("в ")
        val phone = phone(parish.phone)
        return !sameName(parish.name, name) || parish.city.isNotBlank() && city != ScheduleEntityIdentity.normalize(parish.city) ||
            phone != null && publishedPhone != null && phone != publishedPhone
    }
    fun phone(value: String): String? = value.filter(Char::isDigit).let {
        when { it.length == 11 && it.first() in "78" -> it.drop(1); it.length == 10 -> it; else -> null }
    }
}

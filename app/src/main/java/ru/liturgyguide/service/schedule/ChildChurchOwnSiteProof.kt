package ru.liturgyguide.service.schedule

import com.google.gson.JsonObject
import ru.liturgyguide.model.Parish
import java.time.Instant

/** The parent stays a monastery. This proof identifies its one explicitly named present church. */
data class ChildChurchOwnSiteProof(
    val proofVersion: Int,
    val selectedFingerprint: String,
    val parentArticleUrl: String,
    val parentArticleTitle: String,
    val parentArticleSha256: String,
    val parentRevisionId: String,
    val parentAddress: String,
    val parentLatitude: Double,
    val parentLongitude: Double,
    val scheduleRequestedUrl: String,
    val scheduleFinalUrl: String,
    val scheduleSha256: String,
    val aboutRequestedUrl: String,
    val aboutFinalUrl: String,
    val aboutSha256: String,
    val aboutText: String,
    val contactRequestedUrl: String,
    val contactFinalUrl: String,
    val contactSha256: String,
    val ownParentName: String,
    val ownMapName: String,
    val ownAddress: String,
    val ownMapEntityId: String,
    val ownLatitude: Double,
    val ownLongitude: Double,
    // Current own-page verification time; catalog revision/hash may be retained on revalidation.
    val checkedAt: String,
) {
    internal fun validFor(parish: Parish, binding: ScheduleSiteBinding): Boolean = runCatching {
        require(proofVersion == 1 && binding.method == ScheduleBindingMethod.CHILD_CHURCH_OWN_SITE)
        require(selectedFingerprint == ScheduleEntityIdentity.fingerprint(parish) && binding.selectedIdentity == selectedFingerprint)
        require(ScheduleEntityIdentity.scope(parish.name) == ScheduleEntityScope.CHURCH && binding.matchedScope == ScheduleEntityScope.MONASTERY)
        require(binding.matchedName == parentArticleTitle && ScheduleEntityIdentity.scope(parentArticleTitle) == ScheduleEntityScope.MONASTERY)
        require(Regex("https://azbyka\\.ru/palomnik/index\\.php\\?curid=[1-9][0-9]{0,12}").matches(parentArticleUrl))
        require(binding.evidenceUrl == parentArticleUrl && binding.siteUrl == scheduleFinalUrl)
        require(Regex("[1-9][0-9]{0,14}").matches(parentRevisionId) && Regex("[1-9][0-9]{0,11}").matches(ownMapEntityId))
        require(listOf(parentArticleSha256, scheduleSha256, aboutSha256, contactSha256).all { Regex("[a-f0-9]{64}").matches(it) })
        require(checkedAt.length in 1..40); Instant.parse(checkedAt)
        val pairs = listOf(scheduleRequestedUrl to scheduleFinalUrl, aboutRequestedUrl to aboutFinalUrl,
            contactRequestedUrl to contactFinalUrl)
        require(pairs.all { (requested, final) -> listOf(requested, final).all {
            ScheduleUrl.publicSource(it) == it && it.startsWith("https://") && ScheduleUrl.sameOrigin(scheduleRequestedUrl, it)
        } })
        require(listOf(scheduleFinalUrl, aboutFinalUrl, contactFinalUrl).distinct().size == 3)
        require(listOf(parentArticleTitle, ownParentName, ownMapName).all { text(it, 300) })
        require(listOf(parentAddress, ownAddress).all { text(it, 500) })
        val selected = requireNotNull(PalomnikStreetAddress.parse(parish.address))
        val parent = requireNotNull(PalomnikStreetAddress.parse(parentAddress))
        val own = requireNotNull(PalomnikStreetAddress.parse(ownAddress))
        val relation = requireNotNull(ChildChurchRelation.parse(aboutText))
        require(listOf(parent, own, relation.address).all(selected::sameBuilding))
        val city = ChildChurchRelation.city(ownAddress) ?: error("Own contact must publish its city")
        if (parish.city.isNotBlank()) require(ScheduleEntityIdentity.normalize(parish.city) == city)
        ChildChurchRelation.city(parentAddress)?.let { require(it == city) }
        require(ChildChurchRelation.sameDedication(parish.name, relation.name, city))
        require(ChildChurchRelation.sameDedication(parentArticleTitle, ownParentName, city))
        require(ScheduleEntityIdentity.scope(ownParentName) == ScheduleEntityScope.MONASTERY && ownParentName == ownMapName)
        require(pointAgrees(parentLatitude, parentLongitude, parish) && pointAgrees(ownLatitude, ownLongitude, parish))
        true
    }.getOrDefault(false)

    companion object {
        private fun text(value: String, maximum: Int) = value.length in 1..maximum && value.none(Char::isISOControl)
        internal fun pointAgrees(latitude: Double, longitude: Double, parish: Parish): Boolean {
            val lat = parish.latitude ?: return false
            val lon = parish.longitude ?: return false
            return listOf(latitude, longitude, lat, lon).all(Double::isFinite) &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0 && lat in -90.0..90.0 && lon in -180.0..180.0 &&
                OpenScheduleSiteResolver.distanceMeters(latitude, longitude, lat, lon) <= 150
        }

        internal fun decode(value: JsonObject): ChildChurchOwnSiteProof {
            require(value.toString().length <= 32_000)
            fun string(key: String): String = requireNotNull(value.get(key)).let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString
            }
            fun number(key: String): Double = requireNotNull(value.get(key)).let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber); it.asDouble.also { n -> require(n.isFinite()) }
            }
            require(value.get("proofVersion")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString == "1" } == true)
            return ChildChurchOwnSiteProof(1, string("selectedFingerprint"), string("parentArticleUrl"), string("parentArticleTitle"),
                string("parentArticleSha256"), string("parentRevisionId"), string("parentAddress"), number("parentLatitude"),
                number("parentLongitude"), string("scheduleRequestedUrl"), string("scheduleFinalUrl"), string("scheduleSha256"),
                string("aboutRequestedUrl"), string("aboutFinalUrl"), string("aboutSha256"), string("aboutText"),
                string("contactRequestedUrl"), string("contactFinalUrl"), string("contactSha256"), string("ownParentName"),
                string("ownMapName"), string("ownAddress"), string("ownMapEntityId"), number("ownLatitude"), number("ownLongitude"), string("checkedAt"))
        }
    }
}

/** A deliberately narrow present/exclusive ownership sentence, not general Russian relation inference. */
internal object ChildChurchRelation {
    data class Relation(val name: String, val address: PalomnikStreetAddress)
    private val statement = Regex("(?iu)(?:^|[.!?]\\s+)Ныне\\s+монастырю\\s+принадлежит\\s+только\\s+(храм[^.!?]{1,240}?),\\s*" +
        "расположенный\\s+на\\s+(улице\\s+[^.!?]{3,180})\\.(?=\\s|$)")
    private const val CHURCH = "(?<![а-яё])(?:храм(?:а|е|у|ом|ы|ов|ах)?|церк(?:овь|ви|овью|вей|вях)|собор(?:а|е|у|ом|ы|ов|ах)?)(?![а-яё])"
    private val contradictory = Regex("(?iu)(?<![а-яё])(?:второй|вторая|другой|другая|еще\\s+один|еще\\s+одна|также|еще)\\s+(?:[а-яё-]+\\s+){0,5}$CHURCH\\b|" +
        "$CHURCH\\b[^.!?]{0,160}(?<![а-яё])(?:действует|действующий|действующая|принадлежит|находится|открыт|открыта|строится|строящийся|будет)(?![а-яё])|" +
        "(?<![а-яё])(?:принадлежит|принадлежат|действуют|действует|открыт|открыта|открыты|имеет|имеются|есть)(?![а-яё])[^.!?]{0,100}$CHURCH\\b|" +
        "(?:^|[.!?]\\s+)(?:(?:ныне|сейчас|сегодня)\\s+)?(?:в|при|у)\\s+монастыр(?:е|я)\\s+" +
        "(?:два|две|три|четыре|пять|несколько|[2-9])\\s+$CHURCH\\b")
    private val newMartyrs = Regex("(?<![а-я])(?:святых\\s+)?новомучеников(?:\\s+и\\s+исповедников)?\\s+(?:российских|церкви\\s+русской)(?![а-я])")
    private val generic = setOf("храм", "церковь", "собор", "монастырь", "мужской", "женский", "епархиальный", "православный",
        "в", "во", "честь", "имя", "имени", "святых", "святого", "святой", "святителя", "святитель")
    // The proof identifies one church, not an associated estate or an off-site service.
    // These locations are outside ScheduleVenue's church-building vocabulary.
    private val unprovenLocation = Regex("(?iu)(?<![а-яё])(?:в|во|на|при|у)\\s+(?:[а-яё.-]+\\s+){0,6}" +
        "(?:скит[а-яё]*|подвор[а-яё]*|монастыр[а-яё]*|кладбищ[а-яё]*|усыпальниц[а-яё]*|келл[а-яё]*|больниц[а-яё]*)(?![а-яё])")

    fun parse(text: String): Relation? {
        if (text.length !in 1..12_000 || text.any(Char::isISOControl)) return null
        val matches = statement.findAll(text).toList()
        val only = matches.singleOrNull() ?: return null
        // The match includes sentence punctuation. Keep a boundary when checking the rest:
        // removing it can turn the next "В монастыре два храма" into a mid-sentence fragment.
        if (contradictory.containsMatchIn(text.replaceRange(only.range, ". "))) return null
        val name = only.groupValues[1]
        if (Regex("(?iu)(?<![а-яё])(?:не|ранее|бывший|планируемый|будущий|кроме|также|еще)(?![а-яё])").containsMatchIn(name)) return null
        val address = PalomnikStreetAddress.parse(only.groupValues[2].replaceFirst(Regex("(?iu)^улице\\s+"), "улица ")) ?: return null
        return Relation(name, address)
    }

    fun city(address: String): String? = Regex("(?iu)(?<![а-яё])(?:г\\.|город)\\s*([а-яё-]+(?:\\s+[а-яё-]+)*)(?=,)")
        .findAll(address).map { ScheduleEntityIdentity.normalize(it.groupValues[1]) }.toSet().singleOrNull()

    fun sameDedication(first: String, second: String, city: String): Boolean {
        fun tokens(raw: String): Set<String> {
            val withoutCitySuffix = raw.replace(Regex("(?iu)\\(\\s*${Regex.escape(city)}\\s*\\)\\s*$"), "")
            return ScheduleEntityIdentity.normalize(withoutCitySuffix).replace(newMartyrs, "russiannewmartyrs")
                .split(' ').filter { it.isNotBlank() && it !in generic }.toSet()
        }
        val a = tokens(first); val b = tokens(second)
        return a.isNotEmpty() && a == b
    }

    /** Main rites may inherit the proven church; unlocated occasional services may not. */
    fun mainService(title: String): Boolean = Regex("(?iu)^\\s*(?:часы\\s*(?:[.,;:+-]|и)\\s*)?(?:" +
        "(?:(?:божественная|ранняя|поздняя)\\s+)*литургия|(?:всенощное\\s+)?бдение|" +
        "(?:(?:великая|малая|вседневная)\\s+)?вечерня|(?:(?:полиелейная|воскресная|праздничная|вседневная)\\s+)?утреня|" +
        "вечернее\\s+богослужение)(?![а-яё])")
        .containsMatchIn(title) && !unprovenLocation.containsMatchIn(title)
}

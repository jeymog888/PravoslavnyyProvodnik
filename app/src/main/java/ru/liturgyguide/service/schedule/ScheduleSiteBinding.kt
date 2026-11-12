package ru.liturgyguide.service.schedule

import ru.liturgyguide.model.Parish
import java.security.MessageDigest
import java.util.Locale

/** What was actually checked: article scope, or a link on the exact selected map object. */
enum class ScheduleBindingMethod { ARTICLE_SCOPE, EXACT_MAP_LINK, DIRECTORY_OWN_CONTACT }
enum class ScheduleEntityScope { CHURCH, MONASTERY, SKETE, CHAPEL, COMPLEX, UNKNOWN }

data class ScheduleSiteBinding(
    val policyVersion: Int,
    val selectedIdentity: String,
    val matchedName: String,
    val matchedScope: ScheduleEntityScope,
    val method: ScheduleBindingMethod,
    val siteUrl: String,
    val evidenceUrl: String,
    val officialContactProof: OfficialContactProof? = null,
) {
    fun validFor(parish: Parish, website: String, evidence: String): Boolean =
        policyVersion == ScheduleEntityIdentity.POLICY_VERSION &&
            selectedIdentity == ScheduleEntityIdentity.fingerprint(parish) &&
            matchedName.length in 1..300 && matchedName.none(Char::isISOControl) &&
            ScheduleUrl.publicSource(siteUrl) == siteUrl && ScheduleDocumentLinks.sameSite(siteUrl, website) &&
            evidenceUrl == evidence && ScheduleUrl.publicSource(evidenceUrl) == evidenceUrl &&
            (method != ScheduleBindingMethod.DIRECTORY_OWN_CONTACT || siteUrl == website) &&
            (method == ScheduleBindingMethod.EXACT_MAP_LINK ||
                ScheduleEntityIdentity.compatible(parish.name, matchedName, matchedScope)) &&
            (if (method == ScheduleBindingMethod.DIRECTORY_OWN_CONTACT)
                officialContactProof?.validFor(parish, this) == true
            else officialContactProof == null)
}

internal object ScheduleEntityIdentity {
    const val POLICY_VERSION = 1
    private val entityWord = Regex("(?<![а-яa-z])(?:храм|церковь|собор|монастыр(?:ь|я|ю|е|ем|и|ей|ям|ях|ями)|" +
        "лавр(?:а|ы|е|у|ой|ою)|пустын(?:ь|и|ью)|скит(?:а|е|у|ом)?|часовн(?:я|и|е|ю|ей|ею)|комплекс(?:а|у|е|ом|ы|ов)?)(?![а-яa-z])")
    fun normalize(value: String): String = value.lowercase(Locale.ROOT).replace('ё', 'е')
        .replace(Regex("[^а-яa-z0-9]+"), " ").trim()

    // The first entity noun distinguishes a church of a monastery from the monastery itself.
    fun scope(name: String): ScheduleEntityScope = entityWord.find(normalize(name))?.value?.let {
        when {
            it in setOf("храм", "церковь", "собор") -> ScheduleEntityScope.CHURCH
            it.startsWith("монастыр") || it.startsWith("лавр") || it.startsWith("пустын") -> ScheduleEntityScope.MONASTERY
            it.startsWith("скит") -> ScheduleEntityScope.SKETE
            it.startsWith("часовн") -> ScheduleEntityScope.CHAPEL
            else -> ScheduleEntityScope.COMPLEX
        }
    } ?: ScheduleEntityScope.UNKNOWN

    fun compatible(selected: String, title: String, explicitScope: ScheduleEntityScope = ScheduleEntityScope.UNKNOWN): Boolean {
        val wanted = scope(selected)
        val found = articleScope(title, explicitScope) ?: return false
        if (found == ScheduleEntityScope.UNKNOWN || found == ScheduleEntityScope.COMPLEX) return false
        return if (wanted == ScheduleEntityScope.UNKNOWN) found == ScheduleEntityScope.CHURCH else wanted == found
    }

    /** Exact map references need rejection of a known conflict, not a guessed type for a proper name. */
    fun hasExplicitConflict(selected: String, title: String): Boolean {
        val wanted = scope(selected)
        val found = scope(title)
        return wanted != ScheduleEntityScope.UNKNOWN && found != ScheduleEntityScope.UNKNOWN && wanted != found
    }

    fun articleScope(title: String, explicitScope: ScheduleEntityScope): ScheduleEntityScope? {
        val titled = scope(title)
        // Palomnik labels a skete as the broader "Monastery" kind. Its article remains
        // about the skete, not permission to use a parent monastery's shared timetable.
        if (titled == ScheduleEntityScope.SKETE && explicitScope == ScheduleEntityScope.MONASTERY) return titled
        // A contradictory explicit kind cannot be rescued by a church word in the heading.
        if (explicitScope != ScheduleEntityScope.UNKNOWN && titled != ScheduleEntityScope.UNKNOWN && explicitScope != titled) return null
        return explicitScope.takeUnless { it == ScheduleEntityScope.UNKNOWN } ?: titled
    }

    fun fingerprint(parish: Parish): String {
        // Normalize full-name formatting and exclude shortName/website; full identity,
        // address and location changes still require verification.
        val values = listOf(parish.id, normalize(parish.name), normalize(parish.city), normalize(parish.address),
            parish.latitude?.toString().orEmpty(), parish.longitude?.toString().orEmpty(), parish.timezone)
        return MessageDigest.getInstance("SHA-256").digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun bind(parish: Parish, url: String, evidence: String, title: String,
        method: ScheduleBindingMethod, kind: ScheduleEntityScope = scope(title)): ScheduleSiteBinding =
        ScheduleSiteBinding(POLICY_VERSION, fingerprint(parish), title, kind, method, url, evidence)
}

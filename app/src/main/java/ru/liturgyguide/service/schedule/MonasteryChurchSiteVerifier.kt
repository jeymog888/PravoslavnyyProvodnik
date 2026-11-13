package ru.liturgyguide.service.schedule

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import ru.liturgyguide.model.Parish
import java.io.StringReader
import java.security.MessageDigest
import java.time.Clock

internal data class MonasteryChurchCandidate(val scheduleUrl: String, val articleUrl: String,
    val parentTitle: String, val articleText: String, val revisionId: String, val address: String,
    val latitude: Double, val longitude: Double)

internal sealed interface ChildChurchSiteCheck {
    data class Confirmed(val site: ScheduleSite) : ChildChurchSiteCheck
    data class Unavailable(val observedSchedule: ScheduleResponse? = null) : ChildChurchSiteCheck
    data object Rejected : ChildChurchSiteCheck
}

/** A source-owned, exclusive child relationship. No guessed routes, directory profile or scope override. */
internal class MonasteryChurchSiteVerifier(private val transport: ScheduleTransport,
    private val clock: Clock = Clock.systemUTC(), private val timeoutMillis: Long = 12_000,
    private val memo: MutableMap<String, ScheduleSite?> = mutableMapOf()) {
    suspend fun verify(parish: Parish, candidate: MonasteryChurchCandidate): ScheduleSite? {
        val key = "child:${ScheduleEntityIdentity.fingerprint(parish)}:${candidate.articleUrl}:${sha(candidate.articleText.toByteArray())}:${candidate.scheduleUrl}"
        if (memo.containsKey(key)) return memo[key]
        val result = (check(parish, candidate, sha(candidate.articleText.toByteArray())) as? ChildChurchSiteCheck.Confirmed)?.site
        currentCoroutineContext().ensureActive()
        memo[key] = result
        return result
    }

    /** Reuse the proven catalog parent; refresh its own current identity pages before any new timetable. */
    suspend fun revalidate(parish: Parish, binding: ScheduleSiteBinding): ChildChurchSiteCheck {
        val proof = binding.childChurchOwnSiteProof ?: return ChildChurchSiteCheck.Rejected
        if (!binding.validFor(parish, binding.siteUrl, binding.evidenceUrl)) return ChildChurchSiteCheck.Rejected
        val parent = MonasteryChurchCandidate(proof.scheduleRequestedUrl, proof.parentArticleUrl, proof.parentArticleTitle,
            "", proof.parentRevisionId, proof.parentAddress, proof.parentLatitude, proof.parentLongitude)
        return check(parish, parent, proof.parentArticleSha256)
    }

    private suspend fun check(parish: Parish, candidate: MonasteryChurchCandidate, parentSha256: String): ChildChurchSiteCheck {
        var observedSchedule: ScheduleResponse? = null
        return try {
            withTimeoutOrNull(timeoutMillis) {
                val schedule = load(candidate.scheduleUrl, candidate.scheduleUrl) ?: return@withTimeoutOrNull ChildChurchSiteCheck.Unavailable()
                observedSchedule = schedule.response
                val aboutUrl = navigation(schedule, "о монастыре") ?: return@withTimeoutOrNull ChildChurchSiteCheck.Rejected
                val contactUrl = navigation(schedule, "контакты") ?: return@withTimeoutOrNull ChildChurchSiteCheck.Rejected
                if (listOf(schedule.url, aboutUrl, contactUrl).distinct().size != 3) return@withTimeoutOrNull ChildChurchSiteCheck.Rejected
                val about = load(aboutUrl, schedule.url) ?: return@withTimeoutOrNull ChildChurchSiteCheck.Unavailable(observedSchedule)
                val aboutContent = mainContent(about) ?: return@withTimeoutOrNull ChildChurchSiteCheck.Rejected
                val aboutText = aboutContent.clone().also { it.select(FOREIGN).remove(); it.select("script,style,.sharedaddy").remove() }.text().trim()
                ChildChurchRelation.parse(aboutText) ?: return@withTimeoutOrNull ChildChurchSiteCheck.Rejected
                val contacts = load(contactUrl, schedule.url) ?: return@withTimeoutOrNull ChildChurchSiteCheck.Unavailable(observedSchedule)
                val own = ownContact(contacts) ?: return@withTimeoutOrNull ChildChurchSiteCheck.Rejected
                val proof = ChildChurchOwnSiteProof(1, ScheduleEntityIdentity.fingerprint(parish), candidate.articleUrl,
                    candidate.parentTitle, parentSha256, candidate.revisionId, candidate.address,
                    candidate.latitude, candidate.longitude, candidate.scheduleUrl, schedule.url, sha(schedule.bytes),
                    aboutUrl, about.url, sha(about.bytes), aboutText, contactUrl, contacts.url, sha(contacts.bytes),
                    own.name, own.mapName, own.address, own.mapId, own.latitude, own.longitude, clock.instant().toString())
                val binding = ScheduleEntityIdentity.bind(parish, schedule.url, candidate.articleUrl, candidate.parentTitle,
                    ScheduleBindingMethod.CHILD_CHURCH_OWN_SITE, ScheduleEntityScope.MONASTERY).copy(childChurchOwnSiteProof = proof)
                currentCoroutineContext().ensureActive()
                if (!binding.validFor(parish, schedule.url, candidate.articleUrl)) return@withTimeoutOrNull ChildChurchSiteCheck.Rejected
                ChildChurchSiteCheck.Confirmed(ScheduleSite(schedule.url, candidate.articleUrl, candidate.parentTitle, binding))
            } ?: ChildChurchSiteCheck.Unavailable(observedSchedule)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ChildChurchSiteCheck.Unavailable(observedSchedule) }
    }

    private data class Page(val response: ScheduleResponse) {
        val document = requireNotNull(response.html())
        val url get() = response.url
        val bytes get() = response.bytes
    }

    private suspend fun load(requested: String, origin: String): Page? {
        if (ScheduleUrl.publicSource(requested) != requested || !requested.startsWith("https://") ||
            !ScheduleUrl.sameOrigin(origin, requested)) return null
        val response = transport.fetchBounded(requested, MAX_BYTES)
        currentCoroutineContext().ensureActive()
        if (response.bytes.size !in 1..MAX_BYTES || ScheduleUrl.publicSource(response.url) != response.url ||
            !ScheduleUrl.sameOrigin(origin, response.url) || !ScheduleUrl.sameOrigin(requested, response.url) ||
            !Regex("(?is)</body>\\s*</html>\\s*$").containsMatchIn(response.bytes.toString(Charsets.ISO_8859_1)) || response.html() == null) return null
        return Page(response)
    }

    private fun navigation(page: Page, label: String): String? {
        val links = page.document.select("nav a[href]").filter {
            !foreign(it) && it.closest("article,.entry-content") == null && ScheduleEntityIdentity.normalize(it.text()) == label
        }
        if (links.isEmpty()) return null
        val urls = links.map { link ->
            val url = ScheduleUrl.publicSource(link.absUrl("href")) ?: return null
            if (!ScheduleUrl.sameOrigin(page.url, url)) return null
            url
        }.distinct()
        return urls.singleOrNull()
    }

    private fun mainContent(page: Page): Element? {
        val article = page.document.select("article").singleOrNull() ?: return null
        if (foreign(article) || article.closest("nav") != null || !article.hasClass("type-page") ||
            article.closest("main,#main,#primary") == null) return null
        val content = article.select(".entry-content").singleOrNull() ?: return null
        return content.takeIf { !foreign(it) && it.closest("article") == article }
    }

    private data class OwnContact(val name: String, val address: String, val mapId: String,
        val mapName: String, val latitude: Double, val longitude: Double)

    private fun ownContact(page: Page): OwnContact? {
        val content = mainContent(page) ?: return null
        val heading = content.select("#eparchy-info .t1").singleOrNull()?.takeUnless(::foreign) ?: return null
        val name = heading.text().trim()
        if (ScheduleEntityIdentity.scope(name) != ScheduleEntityScope.MONASTERY) return null
        val addressLabels = content.select("dt").filter { ScheduleEntityIdentity.normalize(it.text()) == "адрес" }
        val dt = addressLabels.singleOrNull()?.takeUnless(::foreign) ?: return null
        val address = dt.parent()?.takeIf { it.tagName() == "dl" && it.hasClass("addr") && !foreign(it) } ?: return null
        val dd = address.children().singleOrNull { it.tagName() == "dd" } ?: return null
        if (address.childrenSize() != 2 || content.select("dl.contacts").size != 1 ||
            content.select("[itemprop=address],address").isNotEmpty() ||
            Regex("(?iu)(?<![а-яa-z])адрес\\s*:").findAll(content.text()).count() != 1) return null
        val raw = page.bytes.toString(Charsets.UTF_8)
        if (ASSIGNMENT.findAll(raw).count() != 1) return null
        val script = page.document.select("script").filter { ASSIGNMENT.containsMatchIn(it.data()) }.singleOrNull() ?: return null
        if (foreign(script) || !script.parents().contains(content) || script.hasAttr("src")) return null
        val literal = LITERAL.find(script.data())?.groupValues?.get(1)?.takeIf { it.length in 1..8_000 } ?: return null
        val map = strictMap(literal) ?: return null
        fun string(key: String) = map.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        return OwnContact(name, dd.text().trim(), string("id") ?: return null,
            string("ti")?.let { Parser.unescapeEntities(it, false) } ?: return null,
            string("la")?.toDoubleOrNull() ?: return null, string("lo")?.toDoubleOrNull() ?: return null)
    }

    private fun strictMap(json: String): JsonObject? = runCatching {
        JsonReader(StringReader(json)).use { reader ->
            reader.isLenient = false
            reader.beginArray(); reader.beginObject()
            val result = JsonObject()
            var count = 0
            while (reader.hasNext()) {
                require(++count <= 16)
                val key = reader.nextName()
                require(key.length in 1..30 && !result.has(key))
                when (reader.peek()) {
                    JsonToken.STRING -> result.add(key, JsonPrimitive(reader.nextString().also { require(it.length <= 600) }))
                    JsonToken.NULL -> { reader.nextNull(); result.add(key, null) }
                    else -> error("Not a literal publisher field")
                }
            }
            reader.endObject(); reader.endArray()
            require(reader.peek() == JsonToken.END_DOCUMENT)
            require(listOf("id", "la", "lo", "ti").all(result::has))
            result
        }
    }.getOrNull()

    private fun foreign(element: Element): Boolean = element.closest(FOREIGN) != null
    companion object {
        const val MAX_BYTES = 1_000_000
        private const val FOREIGN = "aside,footer,.sidebar,[role=complementary],#comments,.comments,.comment,.comment-content,.discussion,blockquote,q"
        private val ASSIGNMENT = Regex("(?<![a-zA-Z0-9_$])ortoxParentSite\\s*=")
        private val LITERAL = Regex("(?<![a-zA-Z0-9_$.])ortoxParentSite\\s*=\\s*jQuery\\.parseJSON\\(\\s*'([^'\\r\\n]*)'\\s*\\)\\s*;")
        internal fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

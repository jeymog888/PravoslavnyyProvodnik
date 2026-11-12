package ru.liturgyguide.service.schedule

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import ru.liturgyguide.model.Parish
import java.io.StringReader
import java.security.MessageDigest
import java.time.Clock

/** Two inspected OrtOx own-page layouts, with no guessed paths or JavaScript execution. */
internal class OfficialSiteContactVerifier(
    private val transport: ScheduleTransport,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun verify(parish: Parish, profile: OfficialDirectoryProfile, snapshot: OfficialDirectorySnapshot,
        entry: OfficialDirectoryEntry): ScheduleSite? {
        if (OfficialDirectoryProfile.PUBLISHED.none { it == profile } || snapshot.profileId != profile.id ||
            snapshot.directoryUrl != profile.url || ownContactCandidate(parish, profile, snapshot) != entry) return null
        val requested = entry.website?.let(ScheduleUrl::publicSource) ?: return null
        if (!OfficialContactProof.homeUrl(requested)) return null
        return try {
            withTimeoutOrNull(8_000) {
                val home = load(requested) ?: return@withTimeoutOrNull null
                val title = siteTitle(home.document, home.response.url) ?: return@withTimeoutOrNull null
                // A malformed/pasted passport is an explicit refusal, never a reason to crawl onward.
                val hasPassport = home.document.select(".reg-info").isNotEmpty()
                val evidence: Page
                val evidenceRequested: String
                val layout: OfficialContactLayout
                if (hasPassport) {
                    evidence = home; evidenceRequested = requested; layout = OfficialContactLayout.HOME_PASSPORT
                } else {
                    val links = home.document.select("nav#access a[href],nav#mobile-menu a[href]")
                        .filter { !foreign(it) && it.closest("article,.entry-content") == null && label(it.text()) == "контакты" }
                    val urls = links.map { link ->
                        val url = ScheduleUrl.publicSource(link.absUrl("href")) ?: return@withTimeoutOrNull null
                        if (!ScheduleDocumentLinks.sameSite(home.response.url, url)) return@withTimeoutOrNull null
                        url
                    }.distinct()
                    evidenceRequested = urls.singleOrNull()?.takeIf { it != home.response.url } ?: return@withTimeoutOrNull null
                    evidence = load(evidenceRequested) ?: return@withTimeoutOrNull null
                    layout = OfficialContactLayout.CONTACT_PAGE
                }
                val evidenceTitle = siteTitle(evidence.document, home.response.url) ?: return@withTimeoutOrNull null
                if (title != evidenceTitle) return@withTimeoutOrNull null
                val parsed = ownPage(evidence, layout) ?: return@withTimeoutOrNull null
                val name = parsed.name ?: evidenceTitle
                val address = DirectoryAddress.parse(parsed.address, profile)
                val building = address.building ?: return@withTimeoutOrNull null
                val map = parsed.map
                val id = map.string("id") ?: return@withTimeoutOrNull null
                if (!Regex("[1-9][0-9]{0,11}").matches(id)) return@withTimeoutOrNull null
                val mapName = map.string("ti")?.let { Parser.unescapeEntities(it, false) } ?: return@withTimeoutOrNull null
                val latitude = map.string("la")?.toDoubleOrNull() ?: return@withTimeoutOrNull null
                val longitude = map.string("lo")?.toDoubleOrNull() ?: return@withTimeoutOrNull null
                val proof = OfficialContactProof(1, profile.id, snapshot.directoryUrl, snapshot.sourceSha256, snapshot.fetchedAt,
                    entry.id, entry.title, entry.address ?: return@withTimeoutOrNull null, requested, entry.coordinates,
                    requested, home.response.url, sha(home.response.bytes), evidenceRequested, evidence.response.url,
                    sha(evidence.response.bytes), layout, name, evidenceTitle, mapName, parsed.address,
                    address.locality ?: return@withTimeoutOrNull null, building.streetKind, building.street, building.house,
                    building.corpus, building.building, id, latitude, longitude, clock.instant().toString())
                val binding = ScheduleEntityIdentity.bind(parish, home.response.url, evidence.response.url, name,
                    ScheduleBindingMethod.DIRECTORY_OWN_CONTACT).copy(officialContactProof = proof)
                currentCoroutineContext().ensureActive()
                if (!binding.validFor(parish, home.response.url, evidence.response.url)) return@withTimeoutOrNull null
                ScheduleSite(home.response.url, evidence.response.url, name, binding)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private data class Page(val response: ScheduleResponse, val document: Document)
    private suspend fun load(requested: String): Page? {
        val response = transport.fetchBounded(requested, MAX_BYTES)
        currentCoroutineContext().ensureActive()
        if (response.bytes.size !in 1..MAX_BYTES || ScheduleUrl.publicSource(response.url) != response.url ||
            !ScheduleUrl.permittedRedirect(requested, response.url)) return null
        if (!Regex("(?is)</body>\\s*</html>\\s*$").containsMatchIn(response.bytes.toString(Charsets.ISO_8859_1))) return null
        return Page(response, response.html() ?: return null)
    }

    private fun siteTitle(document: Document, home: String): String? {
        if (document.select("#site-title").size != 1) return null
        val title = document.select("#site-title a[rel~=home]").singleOrNull() ?: return null
        if (foreign(title) || title.closest("article,nav") != null) return null
        val link = ScheduleUrl.publicSource(title.absUrl("href")) ?: return null
        if (!OfficialContactProof.homeUrl(link) || !OfficialContactProof.homeUrl(home) ||
            !ScheduleDocumentLinks.sameSite(home, link)) return null
        return title.text().trim().takeIf { it.length in 1..300 && it.none(Char::isISOControl) }
    }

    private data class OwnPage(val name: String?, val address: String, val map: JsonObject)
    private fun ownPage(page: Page, layout: OfficialContactLayout): OwnPage? {
        val document = page.document
        val article = document.select("article").singleOrNull() ?: return null
        if (foreign(article) || article.closest("nav") != null || article.closest("#main,main,#primary") == null || !article.hasClass("type-page")) return null
        val content = article.select(".entry-content").singleOrNull() ?: return null
        if (foreign(content) || content.closest("article") != article) return null
        val name: String?
        val addressScope: Element
        if (layout == OfficialContactLayout.HOME_PASSPORT) {
            val passport = document.select(".reg-info").singleOrNull() ?: return null
            if (!inside(passport, content)) return null
            val heading = passport.select("#eparchy-info .t1").singleOrNull() ?: return null
            if (!inside(heading, content)) return null
            name = heading.text().trim()
            addressScope = passport.select(".reg-info-contact").singleOrNull() ?: return null
            if (addressScope.select("h3").singleOrNull()?.text()?.let(::label) != "контактная информация") return null
        } else {
            if (document.select(".reg-info").isNotEmpty()) return null
            val heading = article.select("h1").singleOrNull() ?: return null
            if (foreign(heading) || label(heading.text()) != "контакты") return null
            val contacts = content.select("dl.contacts").singleOrNull() ?: return null
            if (!inside(contacts, content)) return null
            val ownLink = contacts.select("a[href]").singleOrNull() ?: return null
            val url = ScheduleUrl.publicSource(ownLink.absUrl("href")) ?: return null
            if (!OfficialContactProof.homeUrl(url) || !ScheduleDocumentLinks.sameSite(page.response.url, url)) return null
            name = null; addressScope = content
        }
        val address = content.select("dl.addr").singleOrNull() ?: return null
        if (!inside(address, addressScope) || !inside(address, content)) return null
        val dt = address.children().filter { it.tagName() == "dt" }.singleOrNull() ?: return null
        val dd = address.children().filter { it.tagName() == "dd" }.singleOrNull() ?: return null
        if (address.childrenSize() != 2 || label(dt.text()) != "адрес") return null
        val rawAddress = dd.text().trim()
        // Do not discard another explicitly labelled address or a second contact entity.
        if (Regex("(?iu)(?<![а-яa-z])адрес\\s*:").findAll(content.text()).count() != 1 ||
            content.select("dt").count { label(it.text()) == "адрес" } != 1 ||
            content.select("dl.contacts").size != 1 ||
            content.select("[itemprop=address],address").isNotEmpty()) return null
        val raw = page.response.bytes.toString(Charsets.UTF_8)
        if (ASSIGNMENT.findAll(raw).count() != 1) return null
        val script = document.select("script").filter { ASSIGNMENT.containsMatchIn(it.data()) }.singleOrNull() ?: return null
        if (!inside(script, content) || script.hasAttr("src")) return null
        val literal = LITERAL.find(script.data()) ?: return null
        val json = literal.groupValues[1]
        if (json.length !in 1..8_000) return null
        val map = strictMap(json) ?: return null
        return OwnPage(name, rawAddress, map)
    }

    private fun inside(element: Element, owner: Element) =
        (element == owner || element.parents().contains(owner)) && !foreign(element) && element.closest("nav") == null
    private fun foreign(element: Element): Boolean = element.closest(
        "aside,footer,.sidebar,[role=complementary],#comments,.comments,.comment,.comment-content,.discussion,blockquote") != null
    private fun label(value: String) = ScheduleEntityIdentity.normalize(value)
    private fun JsonObject.string(key: String) = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    /** The observed literal is one flat object. Reject duplicates before any map can overwrite them. */
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
            require(result.has("id") && result.has("la") && result.has("lo") && result.has("ti"))
            result
        }
    }.getOrNull()

    companion object {
        const val MAX_BYTES = 1_000_000
        private val ASSIGNMENT = Regex("(?<![a-zA-Z0-9_$])ortoxParentSite\\s*=")
        private val LITERAL = Regex("(?<![a-zA-Z0-9_$.])ortoxParentSite\\s*=\\s*jQuery\\.parseJSON\\(\\s*'([^'\\r\\n]*)'\\s*\\)\\s*;")
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

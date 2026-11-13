package ru.liturgyguide.service.schedule

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/** Discovery only: these links still need the complete permalink and all ordinary publication guards. */
enum class ScheduleWordPressDiscoveryStatus { NOT_ADVERTISED, SEARCHED, UNAVAILABLE, ALREADY_ATTEMPTED }
enum class ScheduleWordPressQueryStatus { REQUESTED, READ, FAILED, TIMED_OUT }
data class ScheduleWordPressQuery(
    val collection: String, val requestUrl: String, val page: Int = 1, val pageSize: Int = 10,
    val returnedCount: Int? = null, val status: ScheduleWordPressQueryStatus = ScheduleWordPressQueryStatus.REQUESTED,
    // ScheduleResponse exposes no pagination headers. One observed page is never an exhaustive search.
    val completenessKnown: Boolean = false,
)
data class ScheduleWordPressDiscoveryResult(
    val links: List<ScheduleDocumentLink> = emptyList(),
    val status: ScheduleWordPressDiscoveryStatus,
    val queries: List<ScheduleWordPressQuery> = emptyList(),
    val completenessKnown: Boolean = false,
)

/** One instance per approved-site refresh attempt: at most two public REST GETs and 15 seconds total. */
class ScheduleWordPressDiscovery(private val transport: ScheduleTransport, private val timeoutMillis: Long = 15_000) {
    init { require(timeoutMillis in 1..15_000) }
    private val mutex = Mutex()
    private var attempted = false

    suspend fun discover(approvedWebsite: String, page: Document, today: LocalDate): ScheduleWordPressDiscoveryResult = mutex.withLock {
        if (attempted) return@withLock ScheduleWordPressDiscoveryResult(status = ScheduleWordPressDiscoveryStatus.ALREADY_ATTEMPTED)
        val parent = ScheduleUrl.publicSource(page.baseUri())
        val approved = ScheduleUrl.publicSource(approvedWebsite)
        if (parent == null || approved == null || !ScheduleDocumentLinks.sameSite(approved, parent))
            return@withLock ScheduleWordPressDiscoveryResult(status = ScheduleWordPressDiscoveryStatus.UNAVAILABLE)
        val advertised = page.select("link[rel][href]").filter { "https://api.w.org/" in it.attr("rel").split(Regex("\\s+")) }
        if (advertised.isEmpty()) return@withLock ScheduleWordPressDiscoveryResult(status = ScheduleWordPressDiscoveryStatus.NOT_ADVERTISED)
        attempted = true
        val api = runCatching {
            val links = advertised.map { requireNotNull(publishedUrl(parent, it.attr("href"))) }.distinct()
            val link = links.single()
            require(ScheduleDocumentLinks.sameSite(parent, link))
            ApiRoot.parse(link)
        }.getOrNull() ?: return@withLock ScheduleWordPressDiscoveryResult(status = ScheduleWordPressDiscoveryStatus.UNAVAILABLE)
        val observations = mutableListOf<ScheduleWordPressQuery>()
        val entries = mutableListOf<Entry>()
        var failed = false
        var bytesRead = 0
        val context = currentCoroutineContext()
        val completed = withTimeoutOrNull(timeoutMillis) {
            for (collection in listOf("pages", "posts")) {
                context.ensureActive()
                val url = api.query(collection)
                val position = observations.size
                observations += ScheduleWordPressQuery(collection, url)
                // A bounded read may consume its allowance before throwing. Reserve it
                // before the request; only a successful response can return unused bytes.
                val allowance = minOf(MAX_RESPONSE_BYTES, MAX_TOTAL_BYTES - bytesRead)
                bytesRead += allowance
                try {
                    val response = transport.fetchBounded(url, allowance)
                    context.ensureActive()
                    require(response.bytes.isNotEmpty() && response.bytes.size <= allowance)
                    bytesRead -= allowance - response.bytes.size
                    require(ScheduleUrl.publicSource(response.url) == response.url && ScheduleDocumentLinks.sameSite(url, response.url))
                    // An API redirect is not authority to read another endpoint, HTML login page, or another origin.
                    require(URI(url).rawPath == URI(response.url).rawPath && URI(url).rawQuery == URI(response.url).rawQuery)
                    require(response.contentType.substringBefore(';').trim().lowercase(Locale.ROOT) == "application/json")
                    val root = StrictJson.read(response.bytes.toString(Charsets.UTF_8)) { context.ensureActive() }
                    require(root.isJsonArray && root.asJsonArray.size() <= PAGE_SIZE)
                    val parsed = root.asJsonArray.map { parseEntry(it, collection, parent, api.url, today) }
                    require(parsed.map { it.id }.distinct().size == parsed.size)
                    entries += parsed
                    observations[position] = observations[position].copy(returnedCount = parsed.size, status = ScheduleWordPressQueryStatus.READ)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    failed = true
                    observations[position] = observations[position].copy(status = ScheduleWordPressQueryStatus.FAILED)
                }
            }
            true
        }
        if (completed != true) {
            failed = true
            observations.indices.filter { observations[it].status == ScheduleWordPressQueryStatus.REQUESTED }.forEach {
                observations[it] = observations[it].copy(status = ScheduleWordPressQueryStatus.TIMED_OUT)
            }
        }
        // Conflicting metadata for a repeated global WordPress id is not a ranking tie.
        val conflicts = entries.groupBy { it.id }.filterValues { values -> values.map { it.url to it.type }.distinct().size > 1 }.keys
        if (conflicts.isNotEmpty()) failed = true
        val links = entries.filter { it.qualified && it.id !in conflicts }.sortedWith(
            compareByDescending<Entry> { it.score }.thenBy { it.url }.thenBy { it.id })
            .distinctBy { it.url }.take(3).map { ScheduleDocumentLink(it.url, parent, document = false) }
        ScheduleWordPressDiscoveryResult(links, if (failed) ScheduleWordPressDiscoveryStatus.UNAVAILABLE else ScheduleWordPressDiscoveryStatus.SEARCHED,
            observations.toList())
    }

    private data class Entry(val id: Long, val type: String, val url: String, val qualified: Boolean, val score: Int)

    private fun parseEntry(value: JsonElement, collection: String, parent: String, api: String, today: LocalDate): Entry {
        require(value.isJsonObject)
        val row = value.asJsonObject
        val idValue = requireNotNull(row.get("id"))
        require(idValue.isJsonPrimitive && idValue.asJsonPrimitive.isNumber && Regex("[1-9][0-9]{0,11}").matches(idValue.asString))
        val id = idValue.asLong
        require(string(row, "status", 30) == "publish")
        val type = string(row, "type", 30)
        require(type == if (collection == "pages") "page" else "post")
        val url = requireNotNull(publishedUrl(parent, string(row, "link", 2_048)))
        val uri = URI(url)
        val foreign = !ScheduleDocumentLinks.sameSite(parent, url)
        val isApi = uri.rawPath.startsWith(URI(api).rawPath.takeIf { it != "/" }.orEmpty() + "wp/v2/") ||
            uri.rawPath.contains("/wp-json/") || queryParts(uri.rawQuery).any { it.first == "rest_route" }
        val binary = Regex("(?i)\\.(?:pdf|jpe?g|png|webp|gif|svg|zip|json|xml|rss|ics)$").containsMatchIn(uri.path)
        val administrative = Regex("(?i)/(?:wp-admin(?:/|$)|wp-login\\.php(?:/|$))").containsMatchIn(uri.path)
        val title = rendered(row, "title", 2_048)
        val excerpt = row.get("excerpt")?.takeUnless { it.isJsonNull }?.let { rendered(row, "excerpt", 32_768) }.orEmpty()
        val cleanTitle = plain(title)
        val cleanExcerpt = plain(excerpt)
        val normalized = cleanTitle.lowercase(Locale.ROOT).replace('ё', 'е')
        val scheduleTitle = SCHEDULE.containsMatchIn(normalized)
        val calendarOnly = !scheduleTitle && Regex("(?iu)(?:календарь|духовенство|^клир(?:\\s|$))").containsMatchIn(normalized)
        val otherTimetable = Regex("(?iu)(?:заняти|школ|урок|экскурси|автобус|прием(?:а|ов)?|дежурств)").containsMatchIn(normalized)
        val bishopsSchedule = scheduleTitle && Regex("(?iu)(?:архиерейск|патриарш|митрополич)").containsMatchIn(normalized)
        val excerptPage = Jsoup.parseBodyFragment(excerpt)
        excerptPage.select("script,style,nav,footer,aside,header,[hidden]").remove()
        val scheduleExcerpt = excerptPage.select("h1,h2,h3").any { SCHEDULE.containsMatchIn(it.text()) && WORSHIP.containsMatchIn(it.text()) } ||
            Regex("(?iu)^\\s*расписан[а-яё]*\\s+богослуж").containsMatchIn(cleanExcerpt)
        val qualified = !foreign && !isApi && !binary && !administrative && !calendarOnly && !otherTimetable && !bishopsSchedule &&
            (scheduleTitle || scheduleExcerpt)
        // These are relevance hints from published text only. Never use date/modified as a service date or freshness proof.
        val score = (if (scheduleTitle) 100 else 50) + periodScore(cleanTitle + " " + cleanExcerpt, today)
        return Entry(id, type, url, qualified, score)
    }

    private fun rendered(row: JsonObject, key: String, maximum: Int): String {
        val value = requireNotNull(row.get(key))
        require(value.isJsonObject)
        val obj = value.asJsonObject
        obj.get("protected")?.takeUnless { it.isJsonNull }?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean)
        }
        return string(obj, "rendered", maximum)
    }

    private fun string(row: JsonObject, key: String, maximum: Int): String {
        val value = requireNotNull(row.get(key))
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString.also { require(it.length <= maximum && !it.contains('\u0000')) }
    }

    private fun periodScore(value: String, today: LocalDate): Int {
        val periods = PERIOD.findAll(value.lowercase(Locale.ROOT)).mapNotNull {
            runCatching { YearMonth.of(it.groupValues[2].toInt(), MONTHS.indexOfFirst { month -> it.groupValues[1].startsWith(month) } + 1) }.getOrNull()
        }.distinct().toList()
        if (periods.size != 1) return 0
        val distance = ChronoUnit.MONTHS.between(YearMonth.from(today), periods.single())
        return when (distance) { 0L -> 40; 1L -> 10; -1L -> 5; else -> -20 - minOf(abs(distance), 12).toInt() }
    }

    private data class ApiRoot(val url: String, val queryRoute: Boolean) {
        fun query(collection: String): String {
            val route = "wp/v2/$collection"
            val params = linkedMapOf("search" to "расписание", "per_page" to PAGE_SIZE.toString(), "page" to "1",
                "orderby" to "relevance", "order" to "desc", "status" to "publish", "context" to "view",
                "_fields" to "id,status,type,link,title,excerpt")
            val base = if (queryRoute) {
                params["rest_route"] = "/$route"
                url.substringBefore('?')
            } else url.trimEnd('/') + "/$route"
            return base + "?" + params.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
        }
        companion object {
            fun parse(url: String): ApiRoot {
                val uri = URI(url)
                val query = queryParts(uri.rawQuery)
                require(query.isEmpty() || query.size == 1 && query.single() == ("rest_route" to "/"))
                return ApiRoot(url, query.isNotEmpty())
            }
        }
    }

    companion object {
        internal const val PAGE_SIZE = 10
        internal const val MAX_RESPONSE_BYTES = 262_144
        internal const val MAX_TOTAL_BYTES = 393_216
        private val SCHEDULE = Regex("(?iu)(?:расписан|raspisan|schedule)")
        private val WORSHIP = Regex("(?iu)(?:богослуж|служб|литурги|вечерн|всенощ)")
        private val MONTHS = listOf("январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")
        private val PERIOD = Regex("(?iu)(январ[ьяе]?|феврал[ьяе]?|март[ае]?|апрел[ьяе]?|ма[йяе]|июн[ьяе]?|июл[ьяе]?|август[ае]?|сентябр[ьяе]?|октябр[ьяе]?|ноябр[ьяе]?|декабр[ьяе]?)\\s+(20[0-9]{2})(?![0-9])")
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun queryParts(value: String?): List<Pair<String, String>> = value?.takeIf(String::isNotBlank)?.split('&')?.map {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }.orEmpty()
        private fun publishedUrl(parent: String, raw: String): String? = runCatching {
            require(raw.length in 1..2_048 && raw.none(Char::isISOControl))
            var url = requireNotNull(ScheduleUrl.publicSource(URI(parent).resolve(raw.trim()).toString()))
            // Follow the existing same-host HTML policy: HTTPS pages never add cleartext children.
            if (parent.startsWith("https://") && url.startsWith("http://")) {
                val upgraded = requireNotNull(ScheduleUrl.normalized(url))
                require(ScheduleDocumentLinks.sameSite(parent, upgraded))
                url = upgraded
            }
            url
        }.getOrNull()
        private fun plain(html: String): String = Jsoup.parseBodyFragment(html).also {
            it.select("script,style,nav,footer,aside,header,[hidden]").remove()
        }.text().replace('\u00a0', ' ').trim()
    }

    /** Strict bounded JSON, including duplicate member rejection; Gson's lenient parse helpers are not used. */
    private object StrictJson {
        fun read(text: String, check: () -> Unit): JsonElement {
            val reader = JsonReader(StringReader(text)).also { it.isLenient = false }
            var nodes = 0
            fun value(depth: Int): JsonElement {
                require(depth <= 20 && ++nodes <= 20_000)
                if (nodes % 100 == 0) check()
                return when (reader.peek()) {
                    JsonToken.BEGIN_ARRAY -> JsonArray().also { array ->
                        reader.beginArray(); while (reader.hasNext()) array.add(value(depth + 1)); reader.endArray()
                    }
                    JsonToken.BEGIN_OBJECT -> JsonObject().also { obj ->
                        reader.beginObject()
                        while (reader.hasNext()) { val name = reader.nextName(); require(!obj.has(name)); obj.add(name, value(depth + 1)) }
                        reader.endObject()
                    }
                    JsonToken.STRING -> JsonPrimitive(reader.nextString())
                    JsonToken.NUMBER -> JsonPrimitive(java.math.BigDecimal(reader.nextString()))
                    JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                    JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                    else -> error("Invalid JSON")
                }
            }
            return value(0).also { require(reader.peek() == JsonToken.END_DOCUMENT) }
        }
    }
}

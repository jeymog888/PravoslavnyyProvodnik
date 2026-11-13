package ru.liturgyguide.service.book

import com.google.gson.JsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import ru.liturgyguide.model.PatronalEventProof
import ru.liturgyguide.service.schedule.ScheduleTransport
import ru.liturgyguide.service.schedule.UrlConnectionScheduleTransport
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.util.Locale

sealed interface PatronalResolution {
    data class Confirmed(val proof: PatronalEventProof) : PatronalResolution
    data class Ambiguous(val choices: List<PatronalEventProof>) : PatronalResolution
    data class Unavailable(val message: String, val retryable: Boolean) : PatronalResolution
}

/** Resolve a Minea post to exact public calendar events, never to a similarly named saint. */
class PatronalCalendarResolver(
    private val proofs: PatronalProofRegistry,
    private val transport: ScheduleTransport = UrlConnectionScheduleTransport(timeoutMillis = 8_000, limitBytes = MAX_BYTES)
) {
    suspend fun resolve(entry: PatronalCatalogEntry, gregorianYear: Int): PatronalResolution {
        val rule = entry.dateRule
        if (!entry.candidateEligible || rule == null || !identifier.matches(entry.mineaId) ||
            gregorianYear !in PatronalDates.FIRST_YEAR..PatronalDates.LAST_YEAR)
            return PatronalResolution.Unavailable("Не удалось определить дату этого посвящения", retryable = false)
        return withTimeoutOrNull(RESOLUTION_BUDGET_MILLIS) {
            resolveWithinBudget(entry.mineaId, rule, gregorianYear)
        } ?: PatronalResolution.Unavailable("Проверка календаря заняла слишком долго. Попробуйте еще раз", retryable = true)
    }

    private suspend fun resolveWithinBudget(mineaId: String, rule: PatronalDateRule, year: Int): PatronalResolution {
        val base = PatronalDates.candidates(rule, year)
        // A non-leap Julian February 29 has two documented neighboring candidates.
        // Neither can be treated as absent until both public calendars were checked.
        val leapAlternatives = if (base.isEmpty() && rule == PatronalDateRule.FixedJulian(2, 29))
            (PatronalDates.candidates(PatronalDateRule.FixedJulian(2, 28), year) +
                PatronalDates.candidates(PatronalDateRule.FixedJulian(3, 1), year)).distinct().sorted() else emptyList()
        val firstDates = base.ifEmpty { leapAlternatives }
        if (firstDates.isEmpty()) return PatronalResolution.Unavailable("Не удалось определить дату этого посвящения", false)
        val selected = linkedMapOf<String, PatronalEventProof>()
        suspend fun checkDates(dates: List<LocalDate>): PatronalResolution.Unavailable? {
            for (date in dates) {
                currentCoroutineContext().ensureActive()
                val page = try { load(date, mineaId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: InvalidCalendarResponse) {
                    return PatronalResolution.Unavailable("Календарь вернул неподтвержденный ответ. Попробуйте еще раз", true)
                } catch (_: Exception) {
                    return PatronalResolution.Unavailable("Не удалось проверить календарь полностью. Проверьте связь и попробуйте еще раз", true)
                }
                for (proof in page) {
                    val previous = selected[proof.id]
                    if (previous != null && previous.churchType != proof.churchType)
                        return PatronalResolution.Unavailable("Календарь вернул противоречивые сведения о посвящении", true)
                    selected.putIfAbsent(proof.id, proof)
                }
            }
            return null
        }
        checkDates(firstDates)?.let { return it }
        if (selected.isEmpty() && base.isNotEmpty()) {
            // Moving commemorations can be published on an adjacent day. This is
            // a bounded second approach, not a full-year crawl or a fuzzy match.
            val adjacent = (1..7).flatMap { distance -> base.flatMap {
                listOf(it.minusDays(distance.toLong()), it.plusDays(distance.toLong()))
            } }.filter { it.year in PatronalDates.FIRST_YEAR..PatronalDates.LAST_YEAR && it !in base }.distinct()
            checkDates(adjacent)?.let { return it }
        }
        if (selected.isEmpty()) return PatronalResolution.Unavailable(
            "Календарь не подтвердил это посвящение. Можно выбрать другое название", retryable = false)
        // Facts may be cached now. Calendar discovery alone never changes a parish profile.
        for (proof in selected.values) {
            val stored = try { withContext(Dispatchers.IO) { proofs.rememberProof(proof) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            currentCoroutineContext().ensureActive()
            if (!stored) return PatronalResolution.Unavailable("Не удалось сохранить сведения о посвящении на телефоне. Попробуйте еще раз", true)
        }
        val choices = selected.values.toList()
        return if (choices.size == 1) PatronalResolution.Confirmed(choices.single()) else PatronalResolution.Ambiguous(choices)
    }

    private suspend fun load(date: LocalDate, mineaId: String): List<PatronalEventProof> {
        val url = "$BASE_URL/$date/"
        val response = transport.fetchBounded(url, MAX_BYTES)
        currentCoroutineContext().ensureActive()
        if (response.url != url || response.bytes.size !in 1..MAX_BYTES) invalid()
        val mediaType = response.contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        // The public endpoint was verified to return JSON with text/html on 13 Nov
        // 2026. Only this exact endpoint may use that MIME; its body must still be strict JSON.
        if (mediaType !in setOf("application/json", "text/json", "text/html")) invalid()
        val charset = Regex("(?i)charset\\s*=\\s*[\"']?([^;\\s\"']+)").find(response.contentType)?.groupValues?.get(1)
        if (charset != null && charset.lowercase(Locale.ROOT) !in setOf("utf-8", "utf8")) invalid()
        return withContext(Dispatchers.Default) {
            try {
                val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.bytes)).toString()
                val root = StrictBookJson.objectValue(json, maximumDepth = 24, maximumValues = 100_000)
                require(root.keySet().size in 1..100)
                val events = requireNotNull(root["events"]).also { require(it.isJsonArray) }.asJsonArray
                require(events.size() <= 100)
                val ids = mutableSetOf<String>()
                events.mapNotNull { value ->
                    val event = value.asJsonObject
                    require(event.keySet().size in 4..100 && event.keySet().containsAll(setOf("id", "title", "minea_id", "church_type")))
                    val id = positiveId(event["id"], allowEmpty = false)
                    require(ids.add(id))
                    val selectedMinea = positiveId(event["minea_id"], allowEmpty = true)
                    val churchType = positiveId(event["church_type"], allowEmpty = true)
                    val rawTitle = event["title"].also {
                        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString)
                    }.asString
                    require(rawTitle.length in 1..2_000)
                    val title = BookText.clean(Jsoup.parseBodyFragment(rawTitle).apply {
                        select("script,style,iframe,object").remove()
                    }.text())
                    require(title.length in 1..500 && title.none { it.isISOControl() })
                    if (selectedMinea != mineaId || churchType.isEmpty()) null else
                        PatronalEventProof(id, selectedMinea, date.toString(), title, churchType, url).takeIf { it.isValid() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { invalid() }
        }
    }

    private fun positiveId(value: JsonElement?, allowEmpty: Boolean): String {
        require(value != null && value.isJsonPrimitive &&
            (value.asJsonPrimitive.isString || value.asJsonPrimitive.isNumber))
        val token = if (value.asJsonPrimitive.isNumber) value.toString() else value.asString
        require(identifier.matches(token) || allowEmpty && value.asJsonPrimitive.isString && token.isEmpty())
        return token
    }

    private class InvalidCalendarResponse : IllegalArgumentException()
    private fun invalid(): Nothing = throw InvalidCalendarResponse()

    companion object {
        const val BASE_URL = "https://azbyka.ru/worships/calendar/api"
        const val MAX_BYTES = 1_500_000
        private const val RESOLUTION_BUDGET_MILLIS = 20_000L
        private val identifier = Regex("[1-9][0-9]{0,8}")
    }
}

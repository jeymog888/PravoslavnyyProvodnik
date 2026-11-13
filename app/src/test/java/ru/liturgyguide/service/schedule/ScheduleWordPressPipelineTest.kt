package ru.liturgyguide.service.schedule

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** These fixtures enter through refresh, not a helper-generated anchor injected into HTML. */
class ScheduleWordPressPipelineTest {
    private val site = "https://parish.example/"
    private val canonical = site + "unlisted-publication/"
    private val now = Instant.parse("2026-11-13T06:00:00Z")
    private val noAlternative = ScheduleSiteResolver { null }

    private fun parish() = Parish("custom:wordpress-pipeline", "Никольский храм", "Никольский храм", "Москва", "Адрес", "", site,
        "Europe/Moscow", "", "", "", "", emptyList(), "", "")

    private class Cache : ScheduleCacheStore {
        private val data = ConcurrentHashMap<String, String>()
        override fun read(parishId: String) = data[parishId]
        override fun write(parishId: String, value: String) { data[parishId] = value }
    }

    private fun response(url: String, body: String, type: String = "text/html; charset=utf-8") =
        ScheduleResponse(url, body.toByteArray(Charsets.UTF_8), type)

    private fun page(body: String, api: String = "/wp-json/") =
        """<html><head><link rel="https://api.w.org/" href="$api"></head><body>$body</body></html>"""

    private fun service(day: String = "2026-11-15", time: String = "09:00") =
        """<article data-day="$day"><li><time>$time</time><span>Литургия</span></li></article>"""

    private fun entry(url: String = canonical, id: Int = 1, title: String = "Расписание богослужений на ноябрь 2026",
        type: String = "page", content: String = "", modified: String? = null) = JsonObject().apply {
        addProperty("id", id)
        addProperty("status", "publish")
        addProperty("type", type)
        addProperty("link", url)
        add("title", JsonObject().apply { addProperty("rendered", title) })
        add("excerpt", JsonObject().apply { addProperty("rendered", ""); addProperty("protected", false) })
        // Real servers can ignore _fields. Neither field is publication evidence.
        if (content.isNotBlank()) add("content", JsonObject().apply { addProperty("rendered", content) })
        modified?.let { addProperty("modified", it) }
    }

    private fun rows(vararg entries: JsonObject) = JsonArray().apply { entries.forEach(::add) }.toString()
    private fun isRest(url: String) = url.contains("/wp-json/wp/v2/")
    private fun rest(url: String, pages: String, posts: String = "[]") =
        response(url, if (url.substringBefore('?').endsWith("/pages")) pages else posts, "application/json")

    private fun locatedParish() = parish().copy(address = "Осенняя улица, 32 с1", latitude = 55.76964415, longitude = 37.4061332)

    private fun knownConflict(selected: Parish) = ScheduleSourceCandidate(
        selectedIdentity = ScheduleEntityIdentity.fingerprint(selected), provider = "other_public_index",
        providerLabel = "Публичный справочник", providerObjectId = "wrong-house", name = selected.name,
        address = "Осенняя улица, 33 к3", sourceUrl = site + "other-church/", evidenceUrl = "https://index.example/church/wrong-house",
        evidenceSha256 = "a".repeat(64), checkedAt = now.minusSeconds(10).toString(),
        latitude = selected.latitude!!, longitude = selected.longitude!!, outcome = ScheduleIdentityOutcome.CONFLICT,
        reason = "Адрес относится к другому зданию")

    private fun rememberConflict(cache: Cache, selected: Parish, ready: InternetScheduleState): ScheduleSourceCandidate {
        val candidate = knownConflict(selected)
        assertTrue(candidate.validFor(selected))
        cache.write(selected.id, ScheduleCacheCodec.encode(ready.copy(sourceCandidates = listOf(candidate),
            candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(selected))))
        return candidate
    }

    @Test fun `unlinked schedule is found only through declared REST then full canonical HTML and survives offline recreation`() = runBlocking {
        val cache = Cache()
        val selected = parish()
        val calls = mutableListOf<String>()
        val transport = ScheduleTransport { url ->
            calls += url
            when {
                url == site -> response(url, page("<h1>Новости прихода</h1><nav><a href='/about/'>О приходе</a></nav>"))
                isRest(url) -> rest(url, rows(entry(content = service(time = "22:45"))))
                url == canonical -> response(url, "<h1>Ноябрь 2026</h1>" + service(time = "08:30"))
                else -> error("Unexpected URL $url")
            }
        }
        val repository = InternetScheduleRepository(transport, cache, siteResolver = noAlternative)
        val ready = repository.refresh(selected, now)
        assertEquals(InternetScheduleStatus.READY, ready.status)
        assertEquals(4, calls.size)
        assertEquals(site, calls.first())
        assertEquals(2, calls.count(::isRest))
        assertEquals(canonical, calls.last())
        assertEquals(canonical, ready.publication?.sourceUrl)
        assertEquals(site, ready.publication?.linkedFrom)
        assertEquals("html", ready.publication?.documentFormat)
        assertEquals(listOf("2026-11-15"), ready.publication?.events?.map { it.date })
        assertEquals(listOf("08:30"), ready.publication?.events?.map { it.time })
        assertEquals(listOf("08:30"), selected.withInternetSchedule(ready, now).schedule.map { it.time })

        val offline = InternetScheduleRepository(ScheduleTransport { throw IOException("offline") }, cache, siteResolver = noAlternative)
        assertEquals(ready, offline.cached(selected))
        val failed = offline.refresh(selected, now.plusSeconds(60), force = true)
        assertEquals(InternetScheduleStatus.UNAVAILABLE, failed.status)
        assertEquals(ready.publication, failed.publication)
        assertEquals(listOf("08:30"), selected.withInternetSchedule(failed, now.plusSeconds(60)).schedule.map { it.time })
        assertEquals(failed, offline.cached(selected))
    }

    @Test fun `parent dated cancellation filters REST child events and remains authoritative after recreation`() = runBlocking {
        val cache = Cache()
        val selected = parish()
        val calls = mutableListOf<String>()
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            when {
                url == site -> response(url, page("<h1>Ноябрь 2026</h1><h2>15 ноября - все службы отменены</h2>"))
                isRest(url) -> rest(url, rows(entry()))
                url == canonical -> response(url, service() + service("2026-11-16", "08:00"))
                else -> error("Unexpected URL $url")
            }
        }, cache, siteResolver = noAlternative)
        val ready = repository.refresh(selected, now)
        assertTrue(canonical in calls)
        assertEquals(InternetScheduleStatus.READY, ready.status)
        assertEquals(listOf("2026-11-16"), ready.publication?.events?.map { it.date })
        assertTrue(ready.sourceHasWithdrawals)
        assertFalse(ready.publicationRejected)
        val recreated = InternetScheduleRepository(ScheduleTransport { throw IOException("offline") }, cache, siteResolver = noAlternative)
        assertEquals(ready, recreated.cached(selected))
        val offline = recreated.refresh(selected, now.plusSeconds(60), force = true)
        assertEquals(ready.publication, offline.publication)
        assertTrue(offline.sourceHasWithdrawals)
        assertEquals(listOf("2026-11-16"), selected.withInternetSchedule(offline, now).schedule.map { it.date })
        assertEquals(offline, recreated.cached(selected))
    }

    @Test fun `global parent cancellation prevents every REST request and removes previously cached events`() = runBlocking {
        val cache = Cache()
        val selected = parish()
        var body = page(service())
        val calls = mutableListOf<String>()
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            check(url == site) { "Global cancellation must stop before REST or child requests: $url" }
            response(url, body)
        }, cache, siteResolver = ScheduleSiteResolver { error("Withdrawal must not switch source") })
        assertNotNull(repository.refresh(selected, now).publication)
        calls.clear()
        body = page("<h1>Все службы отменены</h1>" + service())
        val rejected = repository.refresh(selected, now.plusSeconds(60), force = true)
        assertEquals(listOf(site), calls)
        assertNull(rejected.publication)
        assertTrue(rejected.publicationRejected)
        assertTrue(rejected.sourceHasWithdrawals)
        assertEquals(rejected, repository.cached(selected))
    }

    @Test fun `full canonical building restriction wins over valid looking REST title and content`() = runBlocking {
        val calls = mutableListOf<String>()
        val selected = parish()
        val fakeMedia = "https://cdn.example/forged.pdf"
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            when {
                url == site -> response(url, page("<h1>Новости прихода</h1>"))
                isRest(url) -> rest(url, rows(entry(content = "<h1>Никольский храм</h1>" + service() +
                    "<a href='$fakeMedia'>Расписание PDF</a>")))
                url == canonical -> response(url, "<h1>Казанский храм</h1><h2>Ноябрь 2026</h2>" + service())
                else -> error("REST content cannot authorize requests: $url")
            }
        }, Cache(), siteResolver = noAlternative)
        val rejected = repository.refresh(selected, now)
        assertEquals(4, calls.size)
        assertTrue(canonical in calls)
        assertFalse(fakeMedia in calls)
        assertNotEquals(InternetScheduleStatus.READY, rejected.status)
        assertNull(rejected.publication)
        assertTrue(rejected.publicationRejected)
        assertFalse(rejected.sourceHasWithdrawals)
    }

    @Test fun `new REST modified timestamp cannot make old full publication current`() = runBlocking {
        val calls = mutableListOf<String>()
        val selected = parish()
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            when {
                url == site -> response(url, page("<h1>Новости прихода</h1>"))
                isRest(url) -> rest(url, rows(entry(title = "Расписание богослужений на октябрь 2025",
                    modified = "2026-11-13T10:00:00", content = service())))
                url == canonical -> response(url, "<h1>Октябрь 2025</h1>" + service("2025-10-15"))
                else -> error("Unexpected URL $url")
            }
        }, Cache(), siteResolver = noAlternative)
        val result = repository.refresh(selected, now)
        assertTrue(canonical in calls)
        assertEquals(InternetScheduleStatus.UNSUPPORTED, result.status)
        assertNull(result.publication)
        assertNull(repository.cached(selected).publication)
        assertTrue(selected.withInternetSchedule(result, now).schedule.isEmpty())
    }

    @Test fun `API failure after dated parent cancellation cannot revive cached or bundled cancelled service`() = runBlocking {
        val cache = Cache()
        val selected = parish().copy(scheduleValidFrom = "2026-11-15", scheduleValidUntil = "2026-11-15",
            schedule = listOf(ParishService("2026-11-15", "11:00", "Литургия", "liturgy")))
        var cancelled = false
        val calls = mutableListOf<String>()
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            when {
                url == site -> response(url, page(if (cancelled)
                    "<h1>Ноябрь 2026</h1><h2>15 ноября - все службы отменены</h2>" else service()))
                isRest(url) -> throw IOException("declared API unavailable")
                else -> error("Unexpected URL $url")
            }
        }, cache, siteResolver = ScheduleSiteResolver { error("Withdrawal must not switch source") })
        assertNotNull(repository.refresh(selected, now).publication)
        calls.clear()
        cancelled = true
        val rejected = repository.refresh(selected, now.plusSeconds(60), force = true)
        assertEquals(2, calls.count(::isRest))
        assertNull(rejected.publication)
        assertTrue(rejected.publicationRejected)
        assertTrue(rejected.sourceHasWithdrawals)
        assertTrue(selected.withInternetSchedule(rejected, now).schedule.isEmpty())
        val recreated = InternetScheduleRepository(ScheduleTransport { throw IOException("offline") }, cache, siteResolver = noAlternative)
        assertEquals(rejected, recreated.cached(selected))
        val offline = recreated.refresh(selected, now.plusSeconds(120), force = true)
        assertNull(offline.publication)
        assertTrue(offline.sourceHasWithdrawals)
        assertTrue(selected.withInternetSchedule(offline, now).schedule.isEmpty())
    }

    @Test fun `cancelling suspended WordPress fetch after new parent withdrawal never revives old cached service`() = runBlocking {
        val cache = Cache()
        val selected = parish().copy(scheduleValidFrom = "2026-11-15", scheduleValidUntil = "2026-11-15",
            schedule = listOf(ParishService("2026-11-15", "11:00", "Литургия", "liturgy")))
        var cancelled = false
        val enteredRest = CompletableDeferred<Unit>()
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            when {
                url == site -> response(url, page(if (cancelled)
                    "<h1>Ноябрь 2026</h1><h2>15 ноября - все службы отменены</h2>" else service()))
                isRest(url) -> {
                    enteredRest.complete(Unit)
                    awaitCancellation()
                }
                else -> error("Unexpected URL $url")
            }
        }, cache, siteResolver = ScheduleSiteResolver { error("Withdrawal must not switch source") })
        val old = repository.refresh(selected, now)
        assertEquals(listOf("2026-11-15"), old.publication?.events?.map { it.date })
        cancelled = true
        val job = launch { repository.refresh(selected, now.plusSeconds(60), force = true) }
        withTimeout(5_000) { enteredRest.await() }
        job.cancelAndJoin()

        // Strict regression: the completed parent safety observation must outlive its job.
        // A new repository rules out a transient in-memory flag masquerading as quarantine.
        val recreated = InternetScheduleRepository(ScheduleTransport { throw IOException("offline") }, cache, siteResolver = noAlternative)
        val quarantined = recreated.cached(selected)
        assertNull("Cancelled refresh must durably quarantine the withdrawn old publication", quarantined.publication)
        assertTrue(quarantined.publicationRejected)
        assertTrue(quarantined.sourceHasWithdrawals)
        assertTrue(selected.withInternetSchedule(quarantined, now).schedule.isEmpty())
        val offline = recreated.refresh(selected, now.plusSeconds(120), force = true)
        assertNull(offline.publication)
        assertTrue(offline.publicationRejected)
        assertTrue(selected.withInternetSchedule(offline, now).schedule.isEmpty())
    }

    @Test fun `successful ordinary HTML PDF and image schedules do not request WordPress REST`() = runBlocking {
        for (format in listOf("html", "pdf", "image")) {
            val calls = mutableListOf<String>()
            val binary = site + if (format == "pdf") "schedule.pdf" else "schedule.jpg"
            val repository = InternetScheduleRepository(ScheduleTransport { url ->
                calls += url
                when (url) {
                    site -> response(url, page(if (format == "html") service() else "<a href='$binary'>Расписание</a>"))
                    binary -> response(url, "fixture", if (format == "pdf") "application/pdf" else "image/jpeg")
                    else -> error("Successful $format schedule must not request REST: $url")
                }
            }, Cache(), ScheduleDocumentExtractor { data -> ScheduleDocument(data.url, format, listOf(
                ScheduleDocumentPage(listOf(ScheduleDocumentLine("Ноябрь 2026"), ScheduleDocumentLine("15 ноября 09:00 Литургия"))))) },
                siteResolver = noAlternative)
            val ready = repository.refresh(parish(), now)
            assertEquals(format, InternetScheduleStatus.READY, ready.status)
            assertEquals(format, format, ready.publication?.documentFormat)
            assertEquals(format, if (format == "html") listOf(site) else listOf(site, binary), calls)
            assertEquals(format, 0, calls.count(::isRest))
        }
    }

    @Test fun `ordinary safety conflict stops before advertised WordPress REST`() = runBlocking {
        val image = site + "schedule.jpg"
        val calls = mutableListOf<String>()
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            when (url) {
                site -> response(url, page("<a href='$image'>Расписание</a>"))
                image -> response(url, "fixture", "image/jpeg")
                else -> error("Safety conflict must stop before REST: $url")
            }
        }, Cache(), ScheduleDocumentExtractor { data -> ScheduleDocument(data.url, "image", listOf(
            ScheduleDocumentPage(emptyList(), rawOcrEvidence = listOf("Все службы отменены"),
                ocrFailure = ScheduleOcrFailure.CONFLICTING_EVIDENCE))) }, siteResolver = noAlternative)
        val result = repository.refresh(parish(), now)
        assertEquals(listOf(site, image), calls)
        assertNull(result.publication)
        assertTrue(result.publicationRejected)
        assertFalse(result.sourceHasWithdrawals)
    }

    @Test fun `canonical link skipped by ordinary HTML quota can still use the separate WordPress quota`() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            when {
                url == site -> response(url, page("<a href='/schedule/empty1/'>Расписание</a>" +
                    "<a href='/schedule/empty2/'>Расписание</a><a href='$canonical'>Расписание</a>"))
                url in listOf(site + "schedule/empty1/", site + "schedule/empty2/") -> response(url, "<h1>Архив</h1>")
                isRest(url) -> rest(url, rows(entry()))
                url == canonical -> response(url, service())
                else -> error("Unexpected URL $url")
            }
        }, Cache(), siteResolver = noAlternative)
        val result = repository.refresh(parish(), now)
        assertEquals(listOf(site, site + "schedule/empty1/", site + "schedule/empty2/"), calls.take(3))
        assertTrue(calls.drop(3).take(2).all(::isRest))
        assertEquals(canonical, calls.last())
        assertEquals(1, calls.count { it == canonical })
        assertEquals(InternetScheduleStatus.READY, result.status)
        assertEquals(canonical, result.publication?.sourceUrl)
    }

    @Test fun `redirect aliases do not consume a canonical slot or truncate remaining REST candidates`() = runBlocking {
        val first = site + "one/"
        val redirected = "https://www.parish.example/two/"
        val third = site + "three/"
        val calls = mutableListOf<String>()
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            when {
                url == site -> response(url, page("<h1>Новости</h1>"))
                isRest(url) -> rest(url, rows(entry(first, 1), entry(redirected, 2),
                    entry(third, 3, title = "Расписание богослужений")))
                url == first -> response(redirected, "<h1>Архив</h1>")
                url == third -> response(url, service())
                else -> error("Redirect target must not be fetched a second time: $url")
            }
        }, Cache(), siteResolver = noAlternative)
        val result = repository.refresh(parish(), now)
        assertTrue(first in calls)
        assertFalse(redirected in calls)
        assertTrue(third in calls)
        assertEquals(5, calls.size)
        assertEquals(InternetScheduleStatus.READY, result.status)
        assertEquals(third, result.publication?.sourceUrl)
        assertEquals(site, result.publication?.linkedFrom)
    }

    @Test fun `redirected canonical HTML gives its full response URL as provenance for an explicit external PDF`() = runBlocking {
        val finalPage = "https://www.parish.example/current-publication/"
        val document = "https://cdn.example/current.pdf"
        val cache = Cache()
        val calls = mutableListOf<String>()
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            when {
                url == site -> response(url, page("<h1>Новости</h1>"))
                isRest(url) -> rest(url, rows(entry()))
                url == canonical -> response(finalPage, "<h1>Расписание</h1><a href='$document'>Расписание PDF</a>")
                url == document -> response(url, "fixture", "application/pdf")
                else -> error("Unexpected URL $url")
            }
        }, cache, ScheduleDocumentExtractor { data -> ScheduleDocument(data.url, "pdf", listOf(ScheduleDocumentPage(listOf(
            ScheduleDocumentLine("Ноябрь 2026"), ScheduleDocumentLine("15 ноября 08:45 Литургия"))))) }, siteResolver = noAlternative)
        val ready = repository.refresh(parish(), now)
        assertEquals(5, calls.size)
        assertEquals(document, calls.last())
        assertEquals(InternetScheduleStatus.READY, ready.status)
        assertEquals(site, ready.publication?.website)
        assertEquals(document, ready.publication?.sourceUrl)
        assertEquals(finalPage, ready.publication?.linkedFrom)
        assertEquals("pdf", ready.publication?.documentFormat)
        val recreated = InternetScheduleRepository(ScheduleTransport { throw IOException("offline") }, cache, siteResolver = noAlternative)
        assertEquals(ready, recreated.cached(parish()))
    }

    @Test fun `canonical response on another host cannot supply HTML events or authorize media`() = runBlocking {
        val foreign = "https://other.example/current/"
        val media = "https://cdn.example/forged.jpg"
        val calls = mutableListOf<String>()
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            when {
                url == site -> response(url, page("<h1>Новости</h1>"))
                isRest(url) -> rest(url, rows(entry()))
                url == canonical -> response(foreign, "<h1>Расписание</h1>" + service() + "<img src='$media'>")
                else -> error("Foreign HTML cannot authorize a new request: $url")
            }
        }, Cache(), siteResolver = noAlternative)
        val result = repository.refresh(parish(), now)
        assertEquals(4, calls.size)
        assertEquals(canonical, calls.last())
        assertFalse(media in calls)
        assertNull(result.publication)
        assertNotEquals(InternetScheduleStatus.READY, result.status)
    }

    @Test fun `manual root alias redirecting to known wrong house preserves accepted source and records conflict`() = runBlocking {
        val selected = locatedParish()
        val cache = Cache()
        val blocked = knownConflict(selected).sourceUrl
        val alias = site + "innocent-alias/"
        val calls = mutableListOf<String>()
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            when (url) {
                site -> response(url, page(service(time = "08:30")))
                alias -> response(blocked, page(service(time = "22:15")))
                else -> error("Known-conflict redirect must stop before REST: $url")
            }
        }, cache, siteResolver = noAlternative)
        val ready = repository.refresh(selected, now)
        rememberConflict(cache, selected, ready)
        val rejected = repository.refresh(selected, now.plusSeconds(60), force = true, sourceOverride = alias)
        assertEquals(listOf(site, alias), calls)
        assertEquals(ready.publication, rejected.publication)
        assertEquals(ready.siteBinding, rejected.siteBinding)
        assertEquals(ready.website, rejected.website)
        assertEquals(ready.customSource, rejected.customSource)
        assertEquals(ScheduleSourceChangeBlock.CONFLICT, rejected.sourceChangeBlock)
        assertFalse(rejected.sourceHasWithdrawals)
        assertEquals(rejected, repository.cached(selected))
    }

    @Test fun `accepted root newly redirecting to known wrong house cannot retain its old events`() = runBlocking {
        val selected = locatedParish()
        val cache = Cache()
        val blocked = knownConflict(selected).sourceUrl
        val calls = mutableListOf<String>()
        var redirected = false
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            check(url == site) { "Known-conflict redirect must stop before REST: $url" }
            response(if (redirected) blocked else url, page(service(time = if (redirected) "22:15" else "08:30")))
        }, cache, siteResolver = noAlternative)
        val ready = repository.refresh(selected, now)
        rememberConflict(cache, selected, ready)
        redirected = true
        val rejected = repository.refresh(selected, now.plusSeconds(60), force = true)
        assertEquals(listOf(site, site), calls)
        assertNull(rejected.publication)
        assertTrue(rejected.publicationRejected || rejected.sourceNeedsVerification)
        assertNull(rejected.siteBinding)
        assertFalse(rejected.sourceHasWithdrawals)
        assertNotEquals(InternetScheduleStatus.READY, rejected.status)
        assertNull(repository.cached(selected).publication)
        assertEquals(rejected, repository.cached(selected))
    }

    @Test fun `known conflicting canonical child and its redirect are skipped without invalidating accepted parent`() = runBlocking {
        for (redirected in listOf(false, true)) {
            val selected = locatedParish()
            val cache = Cache()
            val blocked = knownConflict(selected).sourceUrl
            val calls = mutableListOf<String>()
            var refreshing = false
            val repository = InternetScheduleRepository(ScheduleTransport { url ->
                calls += url
                when {
                    url == site -> response(url, page(if (refreshing) "<h1>Новости</h1>" else service(time = "08:30")))
                    isRest(url) -> rest(url, rows(entry(if (redirected) canonical else blocked)))
                    redirected && url == canonical -> response(blocked, page(service(time = "22:15")))
                    else -> error("Known conflicting child must not be fetched: $url")
                }
            }, cache, siteResolver = noAlternative)
            val ready = repository.refresh(selected, now)
            rememberConflict(cache, selected, ready)
            calls.clear()
            refreshing = true
            val result = repository.refresh(selected, now.plusSeconds(60), force = true)
            assertEquals(2, calls.count(::isRest))
            assertFalse(blocked in calls)
            assertEquals(redirected, canonical in calls)
            assertEquals(ready.publication, result.publication)
            assertFalse(result.publicationRejected)
            assertFalse(result.sourceHasWithdrawals)
            assertEquals(result, repository.cached(selected))
        }
    }

    @Test fun `WordPress has two extra canonical HTML and two directly published documents beyond ordinary three plus three`() = runBlocking {
        val calls = mutableListOf<String>()
        val children = (1..3).map { site + "publication$it/" }
        val ordinaryDocs = (1..3).map { site + "ordinary$it.pdf" }
        val rescueDocs = (1..3).map { "https://cdn.example/rescue$it.pdf" }
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            when {
                url == site -> response(url, page((1..3).joinToString("") { "<a href='/schedule/nav$it/'>Расписание</a>" } +
                    ordinaryDocs.joinToString("") { "<a href='$it'>Расписание PDF</a>" }))
                url.startsWith(site + "schedule/nav") -> response(url, "<h1>Архив</h1>")
                url in ordinaryDocs -> response(url, "fixture", "application/pdf")
                isRest(url) -> rest(url, rows(*children.mapIndexed { index, link -> entry(link, index + 1) }.toTypedArray()))
                url == children[0] -> response(url, "<h1>Расписание</h1>" + rescueDocs.joinToString("") { "<a href='$it'>Расписание PDF</a>" })
                url == children[1] -> response(url, "<a href='/schedule/unbudgeted/'>Расписание</a>")
                url in rescueDocs -> response(url, "fixture", "application/pdf")
                else -> error("Exceeded HTML/document budget or followed arbitrary rescue navigation: $url")
            }
        }, Cache(), ScheduleDocumentExtractor { data -> ScheduleDocument(data.url, "pdf", listOf(ScheduleDocumentPage(
            if (data.url == rescueDocs[1]) listOf(ScheduleDocumentLine("Ноябрь 2026"),
                ScheduleDocumentLine("16 ноября 08:15 Литургия")) else emptyList()))) }, siteResolver = noAlternative)
        val result = repository.refresh(parish(), now)
        assertEquals(3, calls.count { it == site || it.startsWith(site + "schedule/nav") })
        assertEquals(3, calls.count { it in ordinaryDocs })
        assertEquals(2, calls.count(::isRest))
        assertEquals(children.take(2), calls.filter { it in children })
        assertEquals(rescueDocs.take(2), calls.filter { it in rescueDocs })
        assertFalse(calls.any { it.endsWith("unbudgeted/") })
        assertEquals(InternetScheduleStatus.READY, result.status)
        assertEquals(rescueDocs[1], result.publication?.sourceUrl)
        assertEquals(children[0], result.publication?.linkedFrom)
        assertEquals("pdf", result.publication?.documentFormat)
        assertEquals(listOf("08:15"), result.publication?.events?.map { it.time })
    }
}

package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ChildChurchOwnSiteTest {
    private val now = Instant.parse("2026-11-13T01:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val source = "https://nskmonastyr.cerkov.ru/raspisanie-bogosluzhenij/"
    private val about = "https://nskmonastyr.cerkov.ru/o-monastyre/"
    private val contact = "https://nskmonastyr.cerkov.ru/kontakty/"
    private val article = "https://azbyka.ru/palomnik/index.php?curid=6081"
    private val parentTitle = "Мужской монастырь Новомучеников Российских (Новосибирск)"
    private val ownName = "Епархиальный мужской монастырь в честь новомучеников и исповедников Церкви Русской"
    private fun parish() = Parish("osm:way:55228075", "Храм в честь Святых Новомучеников и Исповедников Российских",
        "Храм Новомучеников", "", "улица Немировича-Данченко, 120/1", "", "", "Asia/Novosibirsk",
        "https://www.openstreetmap.org/way/55228075", "", "", "", emptyList(), "", "", 54.97553165, 82.9086244)
    private fun resource(path: String) = javaClass.classLoader!!.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }
    private fun fixture(name: String) = resource("schedule/child-church-own-site/$name")
    private fun response(url: String, text: String, html: Boolean = true) = ScheduleResponse(url, text.toByteArray(),
        if (html) "text/html; charset=UTF-8" else "application/json")

    private inner class Source : ScheduleTransport {
        var revision = fixture("nsk-palomnik-api.json")
        val pages = linkedMapOf(source to fixture("nsk-schedule.html"), about to fixture("nsk-about.html"), contact to fixture("nsk-contact.html"))
        val calls = mutableListOf<String>()
        var rewrite: (String, ScheduleResponse) -> ScheduleResponse = { _, value -> value }
        var before: suspend (String) -> Unit = {}
        override suspend fun fetch(url: String): ScheduleResponse {
            calls += url; before(url)
            val body = when {
                "api.openstreetmap.org" in url -> response(url, "{\"elements\":[]}", false)
                "prop=revisions" in url -> response(url, revision, false)
                "list=search" in url -> response(url, resource("schedule/palomnik/cityless/novosibirsk-${if ("%22" in url) "street" else "name"}.json"), false)
                "categorymembers" in url -> response(url, Gson().toJson(mapOf("query" to mapOf("categorymembers" to listOf(mapOf("pageid" to 6081, "title" to parentTitle))))), false)
                url in pages -> response(url, pages.getValue(url))
                else -> throw IOException("No fixture: $url")
            }
            return rewrite(url, body)
        }
        fun candidate(): MonasteryChurchCandidate {
            val page = JsonParser.parseString(revision).asJsonObject.getAsJsonObject("query").getAsJsonObject("pages").getAsJsonObject("6081")
            val row = page.getAsJsonArray("revisions").single().asJsonObject
            return MonasteryChurchCandidate(source, article, page.get("title").asString,
                row.getAsJsonObject("slots").getAsJsonObject("main").get("*").asString,
                row.get("revid").asString, "Россия, г. Новосибирск, ул. Немировича-Данченко 120/1", 54.975263, 82.908625)
        }
        fun changeWiki(transform: (String) -> String) {
            val data = JsonParser.parseString(revision).asJsonObject
            val main = data.getAsJsonObject("query").getAsJsonObject("pages").getAsJsonObject("6081")
                .getAsJsonArray("revisions").single().asJsonObject.getAsJsonObject("slots").getAsJsonObject("main")
            main.addProperty("*", transform(main.get("*").asString)); revision = data.toString()
        }
    }

    private class Cache : ScheduleCacheStore {
        val values = mutableMapOf<String, String>()
        override fun read(parishId: String) = values[parishId]
        override fun write(parishId: String, value: String) { values[parishId] = value }
    }

    @Test fun `real raw source resolves automatically imports actual current blocks and reopens exact committed cache offline`() = runBlocking {
        val network = Source(); val cache = Cache(); val selected = parish()
        val repository = InternetScheduleRepository(network, cache)
        val result = repository.refresh(selected, now, force = true)
        assertEquals(InternetScheduleStatus.READY, result.status)
        assertEquals("", result.customSource); assertEquals("", result.configuredWebsite)
        assertFalse(result.sourceNeedsVerification); assertFalse(result.publicationRejected)
        assertEquals(source, result.website); assertEquals(article, result.siteEvidenceUrl)
        val binding = requireNotNull(result.siteBinding)
        assertEquals(ScheduleBindingMethod.CHILD_CHURCH_OWN_SITE, binding.method)
        assertEquals(ScheduleEntityScope.MONASTERY, binding.matchedScope); assertEquals(parentTitle, binding.matchedName)
        assertTrue(binding.validFor(selected, source, article)); assertNull(binding.officialContactProof)
        val proof = requireNotNull(binding.childChurchOwnSiteProof)
        assertEquals(ownName, proof.ownParentName); assertEquals(ownName, proof.ownMapName)
        assertEquals(MonasteryChurchSiteVerifier.sha(fixture("nsk-about.html").toByteArray()), proof.aboutSha256)
        assertEquals("", selected.city)
        val publication = requireNotNull(result.publication)
        assertTrue("The main table does not print its year", publication.yearInferred)
        assertEquals(listOf("08:30", "17:00"), publication.forDate(LocalDate.of(2026, 11, 13)).map { it.time })
        val compound = publication.forDate(LocalDate.of(2026, 11, 14)).first()
        assertEquals("08:30", compound.time)
        assertTrue(compound.title.contains("Литургия")); assertTrue(compound.title.contains("Молебен")); assertTrue(compound.title.contains("Панихида"))
        assertEquals("service_block", compound.kind)
        assertTrue(publication.events.all { ChildChurchRelation.mainService(it.title) })
        var offlineCalls = 0
        val offline = InternetScheduleRepository(ScheduleTransport { offlineCalls++; throw IOException() }, cache)
        assertEquals(result, offline.cached(selected))
        assertEquals(result, offline.refresh(selected, now.plusSeconds(60)))
        assertEquals(0, offlineCalls)
        assertEquals(result, ScheduleCacheCodec.decode(cache.read(selected.id), selected))
        assertTrue(network.calls.containsAll(listOf(source, about, contact)))
    }

    @Test fun `same structures on another host with different dedication city and address are not hardcoded to Novosibirsk`() = runBlocking {
        val network = Source()
        fun renamed(raw: String) = raw.replace("nskmonastyr.cerkov.ru", "monastery.example.org")
            .replace("святых новомучеников Российских", "святителя Николая Чудотворца")
            .replace("новомучеников и исповедников Церкви Русской", "Николая Чудотворца")
            .replace("Новомучеников Российских", "Николая Чудотворца")
            .replace("Немировича-Данченко", "Монастырская").replace("120/1", "8")
            .replace("Новосибирск", "Тверь").replace("54.9754910", "56.8600000").replace("82.9086530", "35.9100000")
        val ownRenamed = "Епархиальный мужской монастырь в честь Николая Чудотворца"
        val oldMap = ownName.map { if (it.code > 127) "\\u%04x".format(it.code) else it.toString() }.joinToString("")
        network.pages.clear()
        listOf(source to "nsk-schedule.html", about to "nsk-about.html", contact to "nsk-contact.html").forEach { (url, name) ->
            network.pages[renamed(url)] = renamed(fixture(name)).replace(oldMap, Gson().toJson(ownRenamed).removeSurrounding("\""))
        }
        val selected = parish().copy(id = "osm:way:99955228", name = "Храм святителя Николая Чудотворца", address = "улица Монастырская, 8",
            latitude = 56.86004, longitude = 35.91002)
        val candidate = network.candidate().let { it.copy(scheduleUrl = renamed(it.scheduleUrl), parentTitle = renamed(it.parentTitle),
            articleText = renamed(it.articleText), address = renamed(it.address), latitude = 56.86, longitude = 35.91) }
        val found = requireNotNull(MonasteryChurchSiteVerifier(network, clock).verify(selected, candidate))
        assertEquals("https://monastery.example.org/raspisanie-bogosluzhenij/", found.url)
        assertTrue(requireNotNull(found.binding).validFor(selected, found.url, found.evidenceUrl))
    }

    @Test fun `catalog monastery alone still cannot identify the selected child church`() = runBlocking {
        val network = Source().also { it.pages.clear() }
        assertNull(OpenScheduleSiteResolver(network).resolve(parish()))
        assertNull(PalomnikScheduleSiteResolver(Source()).resolve(parish()))
    }

    @Test fun `relationship must be present exclusive nonnegative and in own main content`() = runBlocking {
        val original = fixture("nsk-about.html")
        val variants = listOf(
            original.replace("Ныне монастырю принадлежит только храм", "Ранее монастырю принадлежал только храм"),
            original.replace("принадлежит только храм", "будет принадлежать только храм"),
            original.replace("принадлежит только храм", "принадлежит храм"),
            original.replace("принадлежит только храм", "не принадлежит только храм"),
            original.replace("святых новомучеников Российских, расположенный", "святителя Николая, расположенный"),
            original.replace("Немировича-Данченко, 120/1", "Немировича-Данченко, 120/2"),
            original.replace("Немировича-Данченко, 120/1", "Немировича-Данченко"),
            original.replace("Ныне монастырю", "<blockquote>Ныне монастырю").replace("120/1.", "120/1.</blockquote>"),
            original.replace("Ныне монастырю", "<aside>Ныне монастырю").replace("120/1.", "120/1.</aside>"),
            original.replace("Ныне монастырю", "<div class='comments'>Ныне монастырю").replace("120/1.", "120/1.</div>"),
            original.replace("Храм в честь святых новомучеников Российских был освящен", "Другой храм святителя Николая действует. Храм в честь святых новомучеников Российских был освящен"),
            original.replace("Храм в честь святых новомучеников Российских был освящен", "Ныне монастырю принадлежит также церковь святителя Николая. Храм в честь святых новомучеников Российских был освящен"),
            original.replace("Храм в честь святых новомучеников Российских был освящен", "При монастыре действует собор святителя Николая. Храм в честь святых новомучеников Российских был освящен"),
            original.replace("Храм в честь святых новомучеников Российских был освящен", "В монастыре два храма. Храм в честь святых новомучеников Российских был освящен")
        )
        for ((index, html) in variants.withIndex()) {
            val network = Source().also { it.pages[about] = html }
            assertNull("Unsafe relationship variant $index", MonasteryChurchSiteVerifier(network, clock).verify(parish(), network.candidate()))
        }
        val historical = Source().also { it.pages[about] = original.replace("Храм в честь святых новомучеников Российских был освящен",
            "Ранее в монастыре было два храма. Храм в честь святых новомучеников Российских был освящен") }
        assertNotNull("Past buildings do not contradict a present exclusive statement",
            MonasteryChurchSiteVerifier(historical, clock).verify(parish(), historical.candidate()))
    }

    @Test fun `dedication word chest is not the present tense verb est before Church of Russia`() {
        val relation = "Ныне монастырю принадлежит только храм в честь святых новомучеников Российских, " +
            "расположенный на улице Немировича-Данченко, 120/1."
        for (dedication in listOf("Монастырь в честь святых новомучеников Российских - монастырь епархии Русской православной церкви.",
            "Храм в честь новомучеников и исповедников Церкви Русской.")) {
            assertNotNull("The suffix of честь cannot supply a separate есть verb", ChildChurchRelation.parse("$relation $dedication"))
        }
        for (current in listOf("Ныне монастырю принадлежит также церковь святителя Николая.",
            "При монастыре действует собор святителя Николая.", "В монастыре два храма.", "В монастыре есть церковь святителя Николая.")) {
            assertNull("A genuine current second building remains a contradiction: $current", ChildChurchRelation.parse("$relation $current"))
        }
    }

    @Test fun `both source owned addresses one parent map and selected geometry must agree`() = runBlocking {
        for ((index, selected) in listOf(parish().copy(address = "улица Немировича-Данченко, 120/2"),
            parish().copy(address = "улица Немировича-Данченко"), parish().copy(city = "Калуга"),
            parish().copy(latitude = null), parish().copy(longitude = Double.NaN),
            parish().copy(latitude = 54.98)).withIndex()) {
            val network = Source()
            assertNull("Wrong selected identity $index", MonasteryChurchSiteVerifier(network, clock).verify(selected, network.candidate()))
        }
        val original = fixture("nsk-contact.html")
        val variants = listOf(original.replace("Немировича-Данченко 120/1", "Немировича-Данченко 120/2"),
            original.replace("Немировича-Данченко 120/1", "Немировича-Данченко"),
            original.replace("54.9754910", "54.9854910"),
            original.replace("ortoxParentSite =", "notThePublisherMap ="),
            original.replace("ortoxParentSite =", "ortoxParentSite = []; ortoxParentSite ="),
            original.replace("[{\"id\":\"36778\"", "[{\"id\":\"36778\",\"id\":\"36779\""),
            original.replace("<dt>Адрес:</dt>", "<dt>Адрес:</dt><dd>Россия, г. Новосибирск, ул. Чужая 10</dd><dt>Адрес:</dt>"))
        for ((index, html) in variants.withIndex()) {
            val network = Source().also { it.pages[contact] = html }
            assertNull("Wrong own contact $index", MonasteryChurchSiteVerifier(network, clock).verify(parish(), network.candidate()))
        }
    }

    @Test fun `complete candidate set cannot select one parent by network success or competing timetable links`() = runBlocking {
        val ambiguous = Source()
        val data = JsonParser.parseString(ambiguous.revision).asJsonObject
        val pages = data.getAsJsonObject("query").getAsJsonObject("pages")
        val second = pages.getAsJsonObject("6081").deepCopy().also { it.addProperty("pageid", 6082) }
        pages.add("6082", second); ambiguous.revision = data.toString()
        ambiguous.rewrite = { url, value -> if ("list=search" in url) response(url,
            Gson().toJson(mapOf("query" to mapOf("searchinfo" to mapOf("totalhits" to 2), "search" to listOf(6081, 6082).map {
                mapOf("ns" to 0, "pageid" to it, "title" to parentTitle)
            }))), false) else value }
        assertNull(OpenScheduleSiteResolver(ambiguous).resolve(parish()))
        assertFalse(ambiguous.calls.contains(source))
        val competing = Source().also { it.changeWiki { text -> text.replace("=== Богослужения ===",
            "=== Богослужения ===\n[https://other.example.org/timetable/ Актуальное расписание на сайте храма]") } }
        assertNull(OpenScheduleSiteResolver(competing).resolve(parish()))
        assertFalse(competing.calls.contains(source))
        val stale = Source().also { it.changeWiki { text -> text.replace("Актуальное расписание", "Архивное расписание") } }
        assertNull(OpenScheduleSiteResolver(stale).resolve(parish()))
        assertFalse(stale.calls.contains(source))
    }

    @Test fun `navigation requires one own explicit link and complete bounded same origin responses`() = runBlocking {
        val original = fixture("nsk-schedule.html")
        val variants = listOf(original.replace("О монастыре", "История"),
            original.replace("//nskmonastyr.cerkov.ru/o-monastyre/", "https://foreign.example.org/about/"),
            original.replaceFirst("</nav>", "<a href='/other-about/'>О монастыре</a></nav>"))
        for (html in variants) {
            val network = Source().also { it.pages[source] = html }
            assertNull(MonasteryChurchSiteVerifier(network, clock).verify(parish(), network.candidate()))
        }
        for (url in listOf(source, about, contact)) {
            for (mode in listOf("truncated", "oversized", "redirect")) {
                val network = Source().also { fixture -> fixture.rewrite = { requested, value ->
                    if (requested != url) value else when (mode) {
                        "truncated" -> value.copy(bytes = value.bytes.toString(Charsets.UTF_8).substringBeforeLast("</body>").toByteArray())
                        "oversized" -> value.copy(bytes = ByteArray(MonasteryChurchSiteVerifier.MAX_BYTES + 1))
                        else -> value.copy(url = "https://foreign.example.org/copied/")
                    }
                } }
                assertNull("$url $mode", MonasteryChurchSiteVerifier(network, clock).verify(parish(), network.candidate()))
            }
        }
    }

    @Test fun `timeout is bounded and caller cancellation is never converted into a successful proof`() = runBlocking {
        val slow = Source().also { it.before = { url -> if (url == about) awaitCancellation() } }
        assertNull(MonasteryChurchSiteVerifier(slow, clock, timeoutMillis = 30).verify(parish(), slow.candidate()))
        val cancelled = Source().also { it.before = { url -> if (url == contact) throw CancellationException("test cancelled") } }
        try { MonasteryChurchSiteVerifier(cancelled, clock).verify(parish(), cancelled.candidate()); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }

    @Test fun `cached relationship is bound to exact selection evidence and complete typed proof`() = runBlocking {
        val network = Source(); val cache = Cache(); val selected = parish()
        val result = InternetScheduleRepository(network, cache).refresh(selected, now, true)
        val encoded = requireNotNull(cache.read(selected.id))
        val binding = requireNotNull(result.siteBinding)
        assertFalse(binding.validFor(selected.copy(address = "улица Немировича-Данченко, 120/2"), source, article))
        assertFalse(binding.validFor(selected.copy(name = "Храм святителя Николая"), source, article))
        assertFalse(binding.validFor(selected.copy(latitude = 54.9756), source, article))
        assertFalse(binding.validFor(selected, "https://nskmonastyr.cerkov.ru/other/", article))
        for (key in listOf("aboutSha256", "contactFinalUrl", "parentRevisionId", "selectedFingerprint", "aboutText")) {
            val changed = JsonParser.parseString(encoded).asJsonObject
            changed.getAsJsonObject("siteBinding").getAsJsonObject("childChurchOwnSiteProof").remove(key)
            val restored = requireNotNull(ScheduleCacheCodec.decode(changed.toString(), selected))
            assertTrue("Missing $key requires verification", restored.sourceNeedsVerification)
            assertNull(restored.publication)
        }
        val changed = JsonParser.parseString(encoded).asJsonObject
        changed.getAsJsonObject("siteBinding").getAsJsonObject("childChurchOwnSiteProof").addProperty("ownLatitude", 54.985)
        assertTrue(requireNotNull(ScheduleCacheCodec.decode(changed.toString(), selected)).sourceNeedsVerification)
    }

    @Test fun `a new network publication must reconfirm the child relationship and cannot reuse an old exclusive statement`() = runBlocking {
        for (reason in listOf("second-church", "unavailable-contact", "second-church-and-unavailable-contact")) {
            val network = Source(); val cache = Cache(); val selected = parish()
            val repository = InternetScheduleRepository(network, cache)
            val accepted = repository.refresh(selected, now, true)
            assertEquals(InternetScheduleStatus.READY, accepted.status)
            val checkedAbout = network.calls.count { it == about }
            network.pages[source] = network.pages.getValue(source).replace("17:00", "18:00")
            if (reason.startsWith("second-church")) network.pages[about] = network.pages.getValue(about).replace(
                "Храм в честь святых новомучеников Российских был освящен",
                "Ныне монастырю принадлежит также церковь святителя Николая. Храм в честь святых новомучеников Российских был освящен")
            if (reason.contains("unavailable-contact")) network.pages.remove(contact)
            val result = repository.refresh(selected, now.plusSeconds(60), true)
            assertEquals(reason, InternetScheduleStatus.UNAVAILABLE, result.status)
            assertTrue("Every network refresh rechecks own relationship pages", network.calls.count { it == about } > checkedAbout)
            assertFalse("A failed relationship cannot publish the changed 18:00 row",
                result.publication?.events.orEmpty().any { it.time == "18:00" })
            if (reason.startsWith("second-church")) {
                assertTrue(reason, result.sourceNeedsVerification)
                assertNull("Unverified data must not appear as a current cached publication", repository.cached(selected).publication)
            } else {
                assertFalse(reason, result.sourceNeedsVerification)
                assertEquals("A failed connection keeps only the previous saved edition", accepted.publication, result.publication)
                assertEquals(result, repository.cached(selected))
            }
        }
    }

    @Test fun `offline nonforce refresh after seven hours preserves the exact saved publication and its original age`() = runBlocking {
        val network = Source(); val cache = Cache(); val selected = parish()
        val repository = InternetScheduleRepository(network, cache)
        val accepted = repository.refresh(selected, now, true)
        assertEquals(InternetScheduleStatus.READY, accepted.status)
        network.before = { throw IOException("No connection") }
        val afterSevenHours = now.plusSeconds(7 * 60 * 60)
        val result = repository.refresh(selected, afterSevenHours)
        assertEquals(InternetScheduleStatus.UNAVAILABLE, result.status)
        assertFalse(result.sourceNeedsVerification)
        assertEquals(accepted.publication, result.publication)
        assertEquals(accepted.publication?.fetchedAt, result.publication?.fetchedAt)
        assertEquals(accepted.siteBinding, result.siteBinding)
        assertEquals(afterSevenHours.toString(), result.checkedAt)
        assertEquals(result, repository.cached(selected))
        assertTrue("The already saved timetable remains available within its original freshness window",
            selected.withInternetSchedule(result, afterSevenHours).schedule.isNotEmpty())
    }

    @Test fun `successful refresh uses fresh own pages before publishing changed main blocks and caller cancellation leaves cache untouched`() = runBlocking {
        val network = Source(); val cache = Cache(); val selected = parish()
        val repository = InternetScheduleRepository(network, cache)
        val accepted = repository.refresh(selected, now, true)
        val oldProof = requireNotNull(accepted.siteBinding?.childChurchOwnSiteProof)
        val ownCalls = network.calls.count { it == about || it == contact }
        network.pages[source] = network.pages.getValue(source).replace("17:00", "18:00")
        val refreshed = repository.refresh(selected, now.plusSeconds(60), true)
        assertEquals(InternetScheduleStatus.READY, refreshed.status)
        assertTrue(requireNotNull(refreshed.publication).forDate(LocalDate.of(2026, 11, 13)).any { it.time == "18:00" })
        assertEquals(ownCalls + 2, network.calls.count { it == about || it == contact })
        val newProof = requireNotNull(refreshed.siteBinding?.childChurchOwnSiteProof)
        assertEquals(oldProof.parentArticleSha256, newProof.parentArticleSha256)
        assertEquals(oldProof.parentRevisionId, newProof.parentRevisionId)
        assertNotEquals(oldProof.scheduleSha256, newProof.scheduleSha256)
        val before = cache.read(selected.id)
        network.before = { url -> if (url == contact) throw CancellationException("Cancelled refresh") }
        try { repository.refresh(selected, now.plusSeconds(120), true); fail("Caller cancellation must propagate") }
        catch (_: CancellationException) { }
        assertEquals(before, cache.read(selected.id))
    }

    @Test fun `withdrawal already seen before an unavailable contact removes old starts without publishing new ones`() = runBlocking {
        val network = Source(); val cache = Cache(); val selected = parish()
        val repository = InternetScheduleRepository(network, cache)
        val accepted = repository.refresh(selected, now, true)
        assertEquals(InternetScheduleStatus.READY, accepted.status)
        val page = Jsoup.parse(network.pages.getValue(source).replace("Вечернее богослужение.", "Вечернее богослужение отменено."), source)
        page.select("article .entry-content table").single().append(
            "<tr><td>13 ноября пятница</td><td>18:30</td><td>Вечерня</td></tr>")
        network.pages[source] = page.outerHtml(); network.pages.remove(contact)
        val result = repository.refresh(selected, now.plusSeconds(60), true)
        assertEquals(InternetScheduleStatus.UNAVAILABLE, result.status)
        assertTrue(result.sourceHasWithdrawals)
        assertFalse(result.sourceNeedsVerification)
        val retained = requireNotNull(result.publication)
        assertEquals(accepted.publication?.fetchedAt, retained.fetchedAt)
        assertEquals(listOf("08:30"), retained.forDate(LocalDate.of(2026, 11, 13)).map { it.time })
        assertFalse(retained.events.any { it.time == "18:30" })
        assertEquals(result, repository.cached(selected))
    }

    @Test fun `occasional services foreign chapel and withdrawals never inherit the proven main church`() = runBlocking {
        val network = Source()
        val document = Jsoup.parse(network.pages.getValue(source), source)
        val table = document.select("article .entry-content table").single()
        table.append("<tr><td>13 ноября пятница</td><td>11:00</td><td>Молебен</td></tr>" +
            "<tr><td>13 ноября пятница</td><td>12:00</td><td>Акафист</td></tr>" +
            "<tr><td>13 ноября пятница</td><td>13:00</td><td>Панихида</td></tr>" +
            "<tr><td>13 ноября пятница</td><td>14:00</td><td>Часы</td></tr>" +
            "<tr><td>13 ноября пятница</td><td>15:00</td><td>Исповедь</td></tr>" +
            "<tr><td>13 ноября пятница</td><td>11:15</td><td>Молебен (литургия была утром)</td></tr>" +
            "<tr><td>13 ноября пятница</td><td>12:15</td><td>Молебен. Литургия - в воскресенье</td></tr>" +
            "<tr><td>13 ноября пятница</td><td>16:00</td><td>Литургия в часовне Владимирской иконы Божией Матери</td></tr>" +
            "<tr><td>13 ноября пятница</td><td>16:15</td><td>Литургия в скиту Иоанна Предтечи</td></tr>" +
            "<tr><td>13 ноября пятница</td><td>16:20</td><td>Литургия на подворье</td></tr>" +
            "<tr><td>13 ноября пятница</td><td>16:25</td><td>Литургия в другом монастыре</td></tr>")
        network.pages[source] = document.outerHtml()
        val cache = Cache(); val repository = InternetScheduleRepository(network, cache)
        val initial = repository.refresh(parish(), now, true)
        assertEquals(InternetScheduleStatus.READY, initial.status)
        assertEquals(listOf("08:30", "17:00"), requireNotNull(initial.publication).forDate(LocalDate.of(2026, 11, 13)).map { it.time })
        network.pages[source] = network.pages.getValue(source).replace("Вечернее богослужение.", "Вечернее богослужение отменено.")
        val cancelled = repository.refresh(parish(), now.plusSeconds(60), true)
        assertTrue(cancelled.sourceHasWithdrawals)
        assertFalse(requireNotNull(cancelled.publication).forDate(LocalDate.of(2026, 11, 13)).any { it.time == "17:00" })
        network.pages.clear()
        val unavailable = repository.refresh(parish(), now.plusSeconds(120), true)
        assertTrue(unavailable.sourceHasWithdrawals)
        assertFalse(requireNotNull(unavailable.publication).forDate(LocalDate.of(2026, 11, 13)).any { it.time == "17:00" })
    }
}

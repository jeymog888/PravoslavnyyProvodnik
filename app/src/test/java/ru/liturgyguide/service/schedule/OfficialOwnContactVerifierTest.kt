package ru.liturgyguide.service.schedule

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Full real pages are the positive controls; each negative starts from a fresh copy. */
class OfficialOwnContactVerifierTest {
    private val f = OfficialOwnContactFixtures

    @Test fun `four source captures and complete directory keep their published bytes`() {
        val provenance = JsonParser.parseString(f.text("provenance.json")).asJsonObject
        provenance.getAsJsonArray("sources").forEach { source ->
            val value = source.asJsonObject
            val bytes = f.bytes(value["file"].asString)
            assertEquals(value["bytes"].asInt, bytes.size)
            assertEquals(value["sha256"].asString, f.sha(bytes))
            assertEquals(0, value.getAsJsonArray("transformations").size())
        }
        val directory = provenance.getAsJsonObject("directory")
        assertEquals(directory["bytes"].asInt, f.directoryBytes().size)
        assertEquals(directory["sha256"].asString, f.sha(f.directoryBytes()))
        assertEquals(233, f.snapshot().entries.size)
    }

    @Test fun `real Nikolsky own contacts bind exact card map object address and raw hashes`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val snapshot = f.snapshot()
        val site = requireNotNull(f.verify(calls = calls))
        val binding = requireNotNull(site.binding)
        val proof = requireNotNull(binding.officialContactProof)
        assertEquals(listOf(f.httpHome, f.contact), calls)
        assertEquals(f.home, site.url)
        assertEquals(f.contact, site.evidenceUrl)
        assertEquals("Храм во имя святителя Николая Чудотворца", site.matchedName)
        assertEquals(ScheduleBindingMethod.DIRECTORY_OWN_CONTACT, binding.method)
        assertTrue(binding.validFor(f.nikolsky(), site.url, site.evidenceUrl))
        assertEquals(OfficialContactLayout.CONTACT_PAGE, proof.layout)
        assertEquals(f.profile.id, proof.publisherProfileId)
        assertEquals(f.profile.url, proof.directoryUrl)
        assertEquals(snapshot.sourceSha256, proof.directorySha256)
        assertEquals(snapshot.fetchedAt, proof.directoryFetchedAt)
        assertEquals("t36237", proof.cardId)
        assertEquals("36237", proof.publisherEntityId)
        assertEquals(f.httpHome, proof.cardWebsite)
        assertEquals(f.httpHome, proof.homeRequestedUrl)
        assertEquals(f.home, proof.homeFinalUrl)
        assertEquals(f.contact, proof.evidenceRequestedUrl)
        assertEquals(f.contact, proof.evidenceFinalUrl)
        assertEquals(f.sha(f.bytes("nikolsky-home.html")), proof.homeSha256)
        assertEquals(f.sha(f.bytes("nikolsky-contact.html")), proof.evidenceSha256)
        assertEquals("Россия, г. Воронеж, ул.Таранченко д.19а", proof.rawAddress)
        assertEquals("воронеж", proof.city)
        assertEquals("улица", proof.streetKind)
        assertEquals("таранченко", proof.street)
        assertEquals("19а", proof.house)
        assertNull(proof.corpus)
        assertNull(proof.building)
        assertEquals(51.6596060, proof.latitude, 0.00000001)
        assertEquals(39.2126847, proof.longitude, 0.00000001)
        assertEquals(f.now.toString(), proof.checkedAt)
    }

    @Test fun `real Spassky passport has complete identity despite short title and empty h1`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val snapshot = f.snapshot()
        val entry = snapshot.entries.single { it.id == "t21964" }
        val site = requireNotNull(OfficialSiteContactVerifier(ScheduleTransport { url ->
            calls += url
            assertEquals("http://voronezh-nikolskoe-14.cerkov.ru/", url)
            f.html(f.text("spassky-home.html"), f.spasskyHome)
        }, f.clock).verify(f.spassky(), f.profile, snapshot, entry))
        val proof = requireNotNull(site.binding?.officialContactProof)
        assertEquals(1, calls.size)
        assertEquals(OfficialContactLayout.HOME_PASSPORT, proof.layout)
        assertEquals("Спасский храм", proof.siteTitle)
        assertEquals("Храм Спаса Нерукотворного г. Воронеж", proof.ownName)
        assertEquals("21964", proof.publisherEntityId)
        assertEquals("16б", proof.house)
        assertEquals(proof.homeSha256, proof.evidenceSha256)
        assertEquals(f.spasskyHome, site.evidenceUrl)
        assertTrue(requireNotNull(site.binding).validFor(f.spassky(), site.url, site.evidenceUrl))
    }

    @Test fun `publisher literal rejects wrong entity multiple entities duplicate keys and expressions`() = runBlocking<Unit> {
        val raw = f.text("nikolsky-contact.html")
        val cases = linkedMapOf(
            "wrong entity" to f.map(raw) { it[0].asJsonObject.addProperty("id", "21964") },
            "multiple entities" to f.map(raw) { it.add(it[0].deepCopy()) },
            "duplicate id key" to f.mapLiteral(raw) { it.replaceFirst("\"id\":\"36237\"", "\"id\":\"21964\",\"id\":\"36237\"") },
            "literal concatenation" to raw.replace("jQuery.parseJSON('", "jQuery.parseJSON('' + '"),
            "nonfinite coordinates" to f.map(raw) { it[0].asJsonObject.addProperty("la", "NaN") },
            "outside coordinate range" to f.map(raw) { it[0].asJsonObject.addProperty("lo", "200") },
        )
        for ((reason, changed) in cases) {
            assertNotEquals(reason, raw, changed)
            assertNull(reason, f.verify(contactBody = changed))
        }
    }

    @Test fun `extra map assignment anywhere cannot be hidden outside accepted article`() = runBlocking<Unit> {
        val raw = f.text("nikolsky-contact.html")
        val assignment = f.assignment.find(raw)!!.value
        val changed = f.document(raw, f.contact) { it.body().append("<aside><script>$assignment;</script></aside>") }
        assertNull(f.verify(contactBody = changed))
        val outside = f.document(raw, f.contact) { document ->
            val script = document.select("article script").single { "ortoxParentSite" in it.data() }
            script.remove()
            document.body().appendElement("aside").appendChild(script)
        }
        assertNull(f.verify(contactBody = outside))
    }

    @Test fun `addresses in sidebar and comments are not own contact evidence`() = runBlocking<Unit> {
        for (wrapper in listOf("<aside id='secondary'></aside>", "<div id='comments'></div>")) {
            val changed = f.document(f.text("nikolsky-contact.html"), f.contact) { document ->
                val address = document.select("article .entry-content dl.addr").single()
                address.remove()
                document.body().append(wrapper)
                document.body().children().last()!!.appendChild(address)
            }
            assertNull(wrapper, f.verify(contactBody = changed))
        }
    }

    @Test fun `address inside a comment nested in the own content is still foreign evidence`() = runBlocking<Unit> {
        val changed = f.document(f.text("nikolsky-contact.html"), f.contact) { document ->
            document.select("article .entry-content dl.addr").single().wrap("<div class='comment-content'></div>")
        }
        assertNull(f.verify(contactBody = changed))
    }

    @Test fun `own article must have one address and contact heading without a competing signed address`() = runBlocking<Unit> {
        val raw = f.text("nikolsky-contact.html")
        val variants = listOf<(Document) -> Unit>(
            { it.select("article h1").single().text("История другого прихода") },
            { it.select("article").single().removeClass("type-page").addClass("type-post") },
            { it.select("article .entry-content").single().append(it.select("article dl.addr").single().outerHtml()) },
            { it.select("article .entry-content").single().append("<p>Адрес: г. Воронеж, ул. Таранченко, 19</p>") },
            { it.body().append(it.select("article").single().outerHtml()) },
        )
        for ((index, mutation) in variants.withIndex())
            assertNull("own structure $index", f.verify(contactBody = f.document(raw, f.contact, mutation)))
    }

    @Test fun `map and own full names reject another dedication subset union and neighboring Tolga`() = runBlocking<Unit> {
        val raw = f.text("nikolsky-contact.html")
        for (wrong in listOf("Храм Николая Японского г. Воронеж", "Никольский храм",
            "Храм Николая Чудотворца и Николая Японского г. Воронеж", "Храм Николая Чудотворца (Николая Японского)",
            "Храм Толгской иконы Божией Матери г. Воронеж")) {
            assertNull("map $wrong", f.verify(contactBody = f.map(raw) { it[0].asJsonObject.addProperty("ti", wrong) }))
            assertNull("site $wrong", f.verify(contactBody = f.document(raw, f.contact) {
                it.select("#site-title a[rel=home]").single().text(wrong)
            }))
        }
        assertNull(f.verify(selected = f.nikolsky().copy(name = "Никольский храм")))
        assertNull(f.verify(contactBody = f.document(raw, f.contact) {
            it.select("#site-title a[rel=home]").single().text("Монастырь Николая Чудотворца")
        }))
    }

    @Test fun `same publisher id cannot override exact house city corpus building or complete address parsing`() = runBlocking<Unit> {
        val raw = f.text("nikolsky-contact.html")
        for (address in listOf("Россия, г. Воронеж, ул.Таранченко д.19", "Россия, г. Москва, ул.Таранченко д.19а",
            "Россия, г. Воронеж, ул.Таранченко д.19а к.1", "Россия, г. Воронеж, ул.Таранченко д.19а стр.1",
            "Россия, г. Воронеж, ул.Таранченко д.19а неразобранные детали", "Россия, г. Воронеж, ул.Таранченко д.19а ???")) {
            assertNull(address, f.verify(contactBody = f.document(raw, f.contact) {
                it.select("article dl.addr dd").single().text(address)
            }))
        }
        val distant = f.map(raw) { it[0].asJsonObject.addProperty("la", "51.6606") }
        assertNull(f.verify(contactBody = distant))
    }

    @Test fun `explicit map title address cannot contradict the own contact address`() = runBlocking<Unit> {
        val raw = f.text("nikolsky-contact.html")
        val originalMap = JsonParser.parseString(f.assignment.find(raw)!!.groupValues[1]).asJsonArray[0].asJsonObject
        val originalName = originalMap["ti"].asString
        val house = "д. 19а"
        assertEquals(1, Regex(Regex.escape(house)).findAll(originalName).count())
        val ownAddress = Jsoup.parse(raw, f.contact).select("article dl.addr dd").single().text()
        val accepted = mutableListOf<String>()
        val contradictions = listOf("д. 19", "д. 20", "д. 19а корпус 2").map { it to originalName.replace(house, it) } +
            listOf("переулок вместо улицы" to originalName.replace("ул. Таранченко", "пер. Таранченко"))
        for ((reason, wrongName) in contradictions) {
            assertNotEquals(originalName, wrongName)
            val changed = f.map(raw) { it[0].asJsonObject.addProperty("ti", wrongName) }
            val changedMap = JsonParser.parseString(f.assignment.find(changed)!!.groupValues[1]).asJsonArray[0].asJsonObject
            assertEquals(wrongName, changedMap["ti"].asString)
            assertEquals(originalMap["id"], changedMap["id"])
            assertEquals(originalMap["la"], changedMap["la"])
            assertEquals(originalMap["lo"], changedMap["lo"])
            assertEquals(ownAddress, Jsoup.parse(changed, f.contact).select("article dl.addr dd").single().text())
            if (f.verify(contactBody = changed) != null) accepted += reason
        }
        assertTrue("Conflicting explicit map addresses accepted: $accepted", accepted.isEmpty())
    }

    @Test fun `navigation duplicates deduplicate but two distinct contact pages never choose one`() = runBlocking<Unit> {
        val raw = f.text("nikolsky-home.html")
        val links = Jsoup.parse(raw, f.home).select("nav a[href]").filter { it.text().trim() == "Контакты" }
        assertEquals(2, links.size)
        assertEquals(1, links.map { it.absUrl("href") }.distinct().size)
        assertNotNull(f.verify())
        val ambiguous = f.document(raw, f.home) {
            it.select("nav#access").single().append("<a href='/other-contacts/'>Контакты</a>")
        }
        val calls = mutableListOf<String>()
        assertNull(f.verify(homeBody = ambiguous, calls = calls))
        assertEquals(listOf(f.httpHome), calls)
        val onlyArticle = f.document(raw, f.home) { document ->
            document.select("nav a[href]").filter { it.text().trim() == "Контакты" }.forEach { it.remove() }
            document.select("article").first()!!.append("<a href='/kontakty/'>Контакты</a>")
        }
        assertNull(f.verify(homeBody = onlyArticle))
    }

    @Test fun `foreign home contact and own link cannot bridge sibling cerkov hosts`() = runBlocking<Unit> {
        assertNull(f.verify(homeFinalUrl = "https://other.cerkov.ru/"))
        assertNull(f.verify(contactFinalUrl = "https://other.cerkov.ru/kontakty/"))
        assertNull(f.verify(contactBody = f.document(f.text("nikolsky-contact.html"), f.contact) {
            it.select("#site-title a[rel=home]").single().attr("href", "https://other.cerkov.ru/")
        }))
        assertNull(f.verify(contactBody = f.document(f.text("nikolsky-contact.html"), f.contact) {
            it.select("article dl.contacts a[href]").single().attr("href", "https://other.cerkov.ru/")
        }))
    }

    @Test fun `identity loads use bounded transport and recheck a nonconforming response size`() = runBlocking<Unit> {
        val snapshot = f.snapshot()
        val entry = snapshot.entries.single { it.id == "t36237" }
        val bounded = mutableListOf<Pair<String, Int>>()
        var tooLarge = false
        val transport = object : ScheduleTransport {
            override suspend fun fetch(url: String): ScheduleResponse = error("Identity must use bounded fetching")
            override suspend fun fetchBounded(url: String, maximumBytes: Int): ScheduleResponse {
                bounded += url to maximumBytes
                val response = f.response(url)
                return if (tooLarge && url == f.contact) response.copy(bytes = ByteArray(1_000_001) { 32 }) else response
            }
        }
        val verifier = OfficialSiteContactVerifier(transport, f.clock)
        assertNotNull(verifier.verify(f.nikolsky(), f.profile, snapshot, entry))
        assertEquals(listOf(f.httpHome to 1_000_000, f.contact to 1_000_000), bounded)
        tooLarge = true
        assertNull(verifier.verify(f.nikolsky(), f.profile, snapshot, entry))
    }

    @Test fun `truncated and oversized home or contact bodies cannot be repaired into proof`() = runBlocking<Unit> {
        val home = f.text("nikolsky-home.html")
        val contact = f.text("nikolsky-contact.html")
        assertNull(f.verify(homeBody = home.substringBeforeLast("</body>")))
        assertNull(f.verify(contactBody = contact.substringBeforeLast("</body>")))
        assertNull(f.verify(homeBody = home.replace("</body>", "x".repeat(1_000_001) + "</body>")))
        assertNull(f.verify(contactBody = contact.replace("</body>", "x".repeat(1_000_001) + "</body>")))
    }

    @Test fun `passport wrong full title or competing passport cannot fall through to contacts`() = runBlocking<Unit> {
        val snapshot = f.snapshot()
        val entry = snapshot.entries.single { it.id == "t21964" }
        for (mutation in listOf<(Document) -> Unit>(
            { it.select("#site-title a[rel=home]").single().text("Храм Николая Японского") },
            { it.select(".reg-info #eparchy-info .t1").single().text("Храм Николая Чудотворца") },
            { it.select("article .entry-content").single().append(it.select(".reg-info").single().outerHtml()) },
        )) {
            var calls = 0
            val changed = f.document(f.text("spassky-home.html"), f.spasskyHome, mutation)
            val verifier = OfficialSiteContactVerifier(ScheduleTransport { calls++; f.html(changed, f.spasskyHome) }, f.clock)
            assertNull(verifier.verify(f.spassky(), f.profile, snapshot, entry))
            assertEquals(1, calls)
        }
    }

    @Test fun `finite dedication normalization requires both complete names and keeps old matcher behavior`() {
        fun match(a: String, b: String) = OfficialContactNames.matches(a, b, "Воронеж", "Воронежская область")
        assertTrue(match("Храм Николая Чудотворца (Воронеж)", "Храм во имя святителя Николая Чудотворца"))
        assertTrue(match("Церковь Спаса Нерукотворного Образа", "Храм Спаса Нерукотворного г. Воронеж"))
        for ((selected, candidate) in listOf(
            "Храм Николая Чудотворца (Воронеж)" to "Храм Николая Японского г. Воронеж",
            "Никольский храм" to "Храм Николая Чудотворца",
            "Храм Николая Чудотворца" to "Храм Николая Чудотворца и Николая Японского")) {
            assertFalse(match(selected, candidate))
            assertFalse(match(candidate, selected))
            assertTrue("Old discovery matching stays unchanged", OpenScheduleSiteResolver.namesMatch(selected, candidate))
        }
    }

    @Test fun `address context and parentheses cannot erase an added patron from dedication`() {
        val selected = "Храм Николая Чудотворца"
        val union = "Храм Николая Чудотворца и Николая Японского"
        assertFalse(OfficialContactNames.matches(selected, union, "Воронеж", "Воронежская область", "Николая Японского"))
        assertFalse(OfficialContactNames.matches(selected, union, "Николая Японского", "Воронежская область"))
        assertFalse(OfficialContactNames.matches(selected, "Храм Николая Чудотворца (Николая Японского)",
            "Воронеж", "Воронежская область"))
        assertTrue(OfficialContactNames.matches(selected, "Храм Николая Чудотворца (Свято-Никольский храм)",
            "Воронеж", "Воронежская область"))
    }

    @Test fun `compact dotted street abbreviations keep house letters and unparsed suffixes`() {
        val compact = requireNotNull(PalomnikStreetAddress.parse("Россия, г. Воронеж, ул.Таранченко д.19а"))
        assertEquals("19а", compact.house)
        assertTrue(compact.sameBuilding(requireNotNull(PalomnikStreetAddress.parse("Воронеж, улица Таранченко, 19А"))))
        assertFalse(compact.sameBuilding(requireNotNull(PalomnikStreetAddress.parse("Воронеж, улица Таранченко, 19"))))
        val parts = requireNotNull(PalomnikStreetAddress.parse("Воронеж, ул.Фрунзе д.16б к.1 стр.2"))
        assertEquals("16б", parts.house)
        assertEquals("1", parts.corpus)
        assertEquals("2", parts.building)
        for (address in listOf("Воронеж, улТаранченко д.19а", "Воронеж, улицаТаранченко д.19а",
            "Воронеж, ул.Таранченко д.19а ???", "Воронеж, ул.Таранченко д.19а неразобранные детали"))
            assertNull(address, PalomnikStreetAddress.parse(address))
    }
}

internal object OfficialOwnContactFixtures {
    val now: Instant = Instant.parse("2026-11-12T06:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    val profile = OfficialDirectoryProfile.PUBLISHED.single()
    const val httpHome = "http://nikolskyhram.cerkov.ru/"
    const val home = "https://nikolskyhram.cerkov.ru/"
    const val contact = home + "kontakty/"
    const val schedule = home + "raspisanie-bogosluzhenij/"
    const val spasskyHome = "https://voronezh-nikolskoe-14.cerkov.ru/"
    val assignment = Regex("ortoxParentSite\\s*=\\s*jQuery\\.parseJSON\\('([^']+)'\\)")
    fun bytes(name: String) = requireNotNull(javaClass.getResource("/schedule/official-own-contact/$name")).readBytes()
    fun text(name: String) = bytes(name).toString(Charsets.UTF_8)
    fun directoryBytes() = requireNotNull(javaClass.getResource("/schedule/voronezh/official-directory.html")).readBytes()
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun html(body: String, url: String) = ScheduleResponse(url, body.toByteArray(), "text/html; charset=UTF-8")
    fun snapshot(body: String = directoryBytes().toString(Charsets.UTF_8)) =
        requireNotNull(OrtOxDirectoryParser.parse(html(body, profile.url), profile, now))
    fun nikolsky() = Parish("osm:way:77503842", "Храм Николая Чудотворца (Воронеж)", "Храм Николая Чудотворца (Воронеж)",
        "Воронеж", "Воронеж, улица Таранченко, 19А", "", "", "Europe/Moscow", "https://www.openstreetmap.org/way/77503842",
        "", "", "", emptyList(), "", "", 51.6596207, 39.2127643)
    fun spassky() = Parish("osm:way:110564636", "Церковь Спаса Нерукотворного Образа", "Спасская церковь", "Воронеж",
        "Воронеж, улица Фрунзе, 16Б", "", "", "Europe/Moscow", "https://www.openstreetmap.org/way/110564636",
        "", "", "", emptyList(), "", "", 51.65450065, 39.2053557)
    fun document(raw: String, url: String, change: (Document) -> Unit): String = Jsoup.parse(raw, url).apply(change).outerHtml()
    fun mapLiteral(raw: String, change: (String) -> String): String {
        val found = assignment.findAll(raw).single()
        return raw.replaceRange(found.groups[1]!!.range, change(found.groupValues[1]))
    }
    fun map(raw: String, change: (JsonArray) -> Unit) = mapLiteral(raw) {
        JsonParser.parseString(it).asJsonArray.apply(change).toString()
    }
    suspend fun verify(homeBody: String = text("nikolsky-home.html"), contactBody: String = text("nikolsky-contact.html"),
        homeFinalUrl: String = home, contactFinalUrl: String = contact, selected: Parish = nikolsky(),
        calls: MutableList<String> = mutableListOf()): ScheduleSite? {
        val snapshot = snapshot()
        return OfficialSiteContactVerifier(ScheduleTransport { url ->
            calls += url
            when (url) {
                httpHome, home -> html(homeBody, homeFinalUrl)
                contact -> html(contactBody, contactFinalUrl)
                else -> error("Unexpected own-contact request: $url")
            }
        }, clock).verify(selected, profile, snapshot, snapshot.entries.single { it.id == "t36237" })
    }
    fun response(url: String): ScheduleResponse = when {
        url == profile.url -> ScheduleResponse(url, directoryBytes(), "text/html; charset=UTF-8")
        url == httpHome || url == home -> html(text("nikolsky-home.html"), home)
        url == contact -> html(text("nikolsky-contact.html"), contact)
        url == schedule -> html(text("nikolsky-schedule.html"), schedule)
        "categorymembers" in url -> ScheduleResponse(url, "{\"query\":{\"categorymembers\":[]}}".toByteArray(), "application/json")
        "openstreetmap.org/api" in url || "wikipedia.org/w/api.php" in url -> ScheduleResponse(url, "{}".toByteArray(), "application/json")
        else -> error("Unexpected fixture request: $url")
    }
    class Cache : ScheduleCacheStore {
        private val values = mutableMapOf<String, String>()
        override fun read(parishId: String) = values[parishId]
        override fun write(parishId: String, value: String) { values[parishId] = value }
    }
}

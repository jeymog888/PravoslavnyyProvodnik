package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import java.security.MessageDigest

/** Captured public source bodies; all negative mutations below are confined to test memory. */
internal object PatronalFixtures {
    private val gson = Gson()
    val labels = listOf("nicholas-may", "nicholas-dec", "elizabeth", "presentation-mary", "trinity", "presentation")
    fun text(name: String): String = requireNotNull(javaClass.classLoader?.getResource("patronal/$name")).readText()
    fun document(name: String): Document = Jsoup.parse(text("$name.html"))
    fun proof(label: String = "nicholas-may"): PatronalEventProof {
        val value = JsonParser.parseString(text("proofs.json")).asJsonArray.single { it.asJsonObject["label"].asString == label }
        return gson.fromJson(value.asJsonObject["proof"], PatronalEventProof::class.java)
    }
    fun key(label: String = "nicholas-may") = proof(label).let {
        ServiceBookKey("2026-11-11", churchType = it.churchType, patronalId = it.id)
    }
    fun parse(label: String = "nicholas-may") = ServiceBookParser().parse(document("$label-patronal"), key(label), 12345,
        patronalSelection = proof(label))
    fun rehash(book: ServiceBook) = book.copy(contentHash = ServiceBookIntegrity.contentHash(book))
    fun templePrayer(book: ServiceBook, label: String): BookBlock {
        val index = book.blocks.indexOfFirst { BookText.normalized(it.original) == label }
        check(index >= 0) { "The source must contain the temple hymn label" }
        return book.blocks.drop(index + 1).first { it.kind == BookBlockKind.PRAYER && !it.isAltarText }
    }
}

class PatronalSourceContractTest {
    @Test fun evidenceRequiresBoundedIdsARealCivilDateAndItsExactPublicCalendarOrigin() {
        val proof = PatronalFixtures.proof()
        assertTrue(proof.isValid())
        for (invalid in listOf(proof.copy(id = "01"), proof.copy(id = "0"), proof.copy(mineaId = ""),
            proof.copy(mineaId = "1e3"), proof.copy(title = ""), proof.copy(title = "<b>Николай</b>"),
            proof.copy(title = "Николай\n"), proof.copy(calendarDate = "2026-02-29"),
            proof.copy(calendarDate = "1899-05-22", sourceUrl = "https://azbyka.ru/worships/calendar/api/1899-05-22/"),
            proof.copy(sourceUrl = proof.sourceUrl + "#other"),
            proof.copy(sourceUrl = proof.sourceUrl.replace("https://", "http://")))) assertFalse(invalid.isValid())
        assertFalse(proof.matches(PatronalFixtures.key().copy(churchType = "30")))
        assertFalse(proof.matches(PatronalFixtures.key().copy(patronalId = null)))
    }

    @Test fun everyPublicCaptureKeepsItsFrozenFixtureHashAndSourceProvenance() {
        val manifest = JsonParser.parseString(PatronalFixtures.text("provenance.json")).asJsonObject
        assertEquals(11, manifest.getAsJsonArray("fixtures").size())
        manifest.getAsJsonArray("fixtures").forEach { value ->
            val row = value.asJsonObject
            val bytes = PatronalFixtures.text(row["file"].asString).toByteArray(Charsets.UTF_8)
            assertEquals(row["fixtureBytes"].asInt, bytes.size)
            assertEquals(row["fixtureSha256"].asString,
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            assertTrue(row["sourceUrl"].asString.startsWith("https://azbyka.ru/worships/?"))
            assertTrue(row["rawSha256"].asString.matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test fun allSixRealDedicationsAreConfirmedByBlankSelectedIdAndRetainCompleteBooks() {
        for (label in PatronalFixtures.labels) {
            val document = PatronalFixtures.document("$label-patronal")
            val proof = PatronalFixtures.proof(label)
            assertTrue(proof.isValid())
            assertEquals("", document.select("#bg_worships_patronal option[selected]").single().text())
            assertTrue(document.select("#bg_worships_church").isEmpty())
            val book = PatronalFixtures.parse(label)
            assertEquals(proof, book.patronalSelection)
            assertEquals(PatronalFixtures.key(label), book.key)
            assertEquals(book.key.sourceUrl(), book.sourceUrl)
            assertTrue(label, ServiceBookIntegrity.valid(book))
            assertTrue(book.blocks.size > 200)
            assertTrue(book.blocks.any { it.kind == BookBlockKind.SCRIPTURE && it.scriptureReferences.isNotEmpty() })
            assertTrue(book.chapters.any { BookText.normalized(it.title).contains("благодарственные молитвы") })
            assertFalse(book.blocks.any { it.kind == BookBlockKind.LOCAL_VARIATION })
        }
    }

    @Test fun mayAndDecemberNicholasHaveDifferentRealTropariaAndKontakia() {
        val may = PatronalFixtures.parse("nicholas-may")
        val december = PatronalFixtures.parse("nicholas-dec")
        val base = ServiceBookParser().parse(PatronalFixtures.document("nicholas-may-default"),
            may.key.copy(patronalId = null), 12345)
        assertEquals(2, base.blocks.count { it.kind == BookBlockKind.LOCAL_VARIATION })
        fun prayer(book: ServiceBook, label: String) = BookText.normalized(PatronalFixtures.templePrayer(book, label).original)
        assertTrue(prayer(may, "тропарь храма").startsWith("приспе день светлаго торжества"))
        assertTrue(prayer(december, "тропарь храма").startsWith("правило веры и образ кротости"))
        assertTrue(prayer(may, "кондак храма").startsWith("взыдоша яко звезда"))
        assertTrue(prayer(december, "кондак храма").startsWith("в мирех святе священнодействитель"))
        assertNotEquals(may.key.canonical(), december.key.canonical())
        assertNotEquals(may.contentHash, december.contentHash)
    }

    @Test fun marysSpecificHymnReplacesTheGenericOneEvenWhenTheBaselineHasNoGap() {
        val specific = PatronalFixtures.parse("presentation-mary")
        val base = ServiceBookParser().parse(PatronalFixtures.document("presentation-mary-default"),
            specific.key.copy(patronalId = null), 12345)
        assertFalse(base.blocks.any { it.kind == BookBlockKind.LOCAL_VARIATION })
        assertTrue(BookText.normalized(PatronalFixtures.templePrayer(base, "тропарь храма").original)
            .startsWith("к богородице прилежно ныне притецем"))
        assertTrue(BookText.normalized(PatronalFixtures.templePrayer(specific, "тропарь храма").original)
            .startsWith("днесь благоволения божия предображение"))
        assertTrue(BookText.normalized(PatronalFixtures.templePrayer(specific, "кондак храма").original)
            .startsWith("пречистый храм спасов"))
    }

    @Test fun anActualHttp200FallbackIsRejectedInsteadOfBorrowingItsOrdinaryChurchSelection() {
        val invalidProof = PatronalFixtures.proof().copy(id = "999999", churchType = "30")
        val key = PatronalFixtures.key().copy(patronalId = invalidProof.id, churchType = "30")
        val fallback = PatronalFixtures.document("invalid-patronal")
        assertTrue(fallback.select("#bg_worships_patronal").isEmpty())
        assertEquals("30", requireNotNull(fallback.selectFirst("#bg_worships_church option[selected]")).attr("value"))
        assertThrows(UnconfirmedPatronalSelection::class.java) {
            ServiceBookParser().parse(fallback, key, 12345, patronalSelection = invalidProof)
        }
    }

    @Test fun proofAndUniqueExplicitControlAreRequiredWithoutFirstOptionOrForeignWrapperFallbacks() {
        val key = PatronalFixtures.key()
        val proof = PatronalFixtures.proof()
        val mutations = listOf<Pair<String, (Document) -> Unit>>(
            "missing" to { it.select("#bg_worships_patronal").remove() },
            "no selected option" to { it.select("#bg_worships_patronal option").removeAttr("selected") },
            "wrong id" to { it.select("#bg_worships_patronal option[selected]").attr("value", "129540") },
            "missing value" to { it.select("#bg_worships_patronal option[selected]").removeAttr("value") },
            "wrong element" to { it.select("#bg_worships_patronal").tagName("div") },
            "foreign wrapper" to { it.select("#bg_worships_patronal option[selected]").wrap("<template></template>") },
            "duplicate control" to { d -> val c = d.select("#bg_worships_patronal").single(); c.after(c.clone()) },
            "duplicate selection" to { d -> val c = d.select("#bg_worships_patronal").single(); c.appendChild(c.select("option[selected]").single().clone()) },
            "ordinary church retained" to { it.body().append("<select id=bg_worships_church><option selected value=34></option></select>") }
        )
        for ((label, mutate) in mutations) {
            val document = PatronalFixtures.document("nicholas-may-patronal"); mutate(document)
            assertThrows(label, UnconfirmedPatronalSelection::class.java) {
                ServiceBookParser().parse(document, key, 12345, patronalSelection = proof)
            }
        }
        for (badProof in listOf(null, proof.copy(id = "129540"), proof.copy(churchType = "30"),
            proof.copy(sourceUrl = "https://example.org/calendar/${proof.calendarDate}/"))) {
            assertThrows(UnconfirmedPatronalSelection::class.java) {
                ServiceBookParser().parse(PatronalFixtures.document("nicholas-may-patronal"), key, 12345,
                    patronalSelection = badProof)
            }
        }
    }

    @Test fun theRealCombinedPatronalAndPolyeleosProtocolRetainsTheSameProof() {
        val proof = PatronalFixtures.proof()
        val key = ServiceBookKey("2026-11-13", WorshipKind.MATINS, "34", topLevel = "3", patronalId = proof.id)
        val before = PatronalFixtures.document("matins-default-2026-11-13")
        val after = PatronalFixtures.document("matins-rank3-2026-11-13")
        val request = MatinsRankSource.resolve(before, key, proof)
        assertEquals("0", request.mainEvent)
        assertTrue(request.url.contains("patronal=${proof.id}"))
        assertEquals(BookRankSelection("0", "", "3"), MatinsRankSource.validate(after, key, request, proof))
        val book = ServiceBookParser().parse(after, key, 12345, rankRequest = request, patronalSelection = proof)
        assertTrue(ServiceBookIntegrity.valid(book))
        assertTrue(MatinsRankSource.hasPolyeleosOrder(book))
        assertEquals(proof, book.patronalSelection)
        for (document in listOf(before, after)) {
            val wrong = document.clone(); wrong.select("#bg_worships_patronal option[selected]").attr("value", "129540")
            assertThrows(UnconfirmedPatronalSelection::class.java) {
                if (document === before) MatinsRankSource.resolve(wrong, key, proof)
                else MatinsRankSource.validate(wrong, key, request, proof)
            }
        }
    }

    @Test fun cachedSnapshotAndSourceKeyMustStillAgreeAfterAnAttackerRecomputesTheHash() {
        val book = PatronalFixtures.parse()
        val proof = requireNotNull(book.patronalSelection)
        val mutations = listOf(book.copy(patronalSelection = null),
            book.copy(key = book.key.copy(patronalId = "129540")),
            book.copy(patronalSelection = proof.copy(churchType = "30")),
            book.copy(patronalSelection = proof.copy(calendarDate = "2026-05-23")),
            book.copy(patronalSelection = proof.copy(sourceUrl = "https://azbyka.ru/days/${proof.calendarDate}")),
            book.copy(sourceUrl = book.sourceUrl.replace("patronal=${proof.id}", "patronal=129540")),
            book.copy(key = book.key.copy(patronalId = null)))
        mutations.forEach { assertFalse(ServiceBookIntegrity.valid(PatronalFixtures.rehash(it))) }
    }

    @Test fun nullKeysKeepTheirOldCanonicalUrlAndOmittedJsonWhileConcreteIdsSeparateAddresses() {
        val old = ServiceBookKey("2026-11-11", churchType = "34")
        assertEquals("2026-11-11|LITURGY|34|~|~", old.canonical())
        assertEquals("https://azbyka.ru/worships/?date=2026-11-11&worship=liturgy&size=full&church=34", old.sourceUrl())
        assertFalse(Gson().toJson(old).contains("patronal"))
        assertFalse(Gson().toJson(BookFixtures.book()).contains("patronal"))
        val specific = old.copy(patronalId = "111300")
        assertEquals(old.canonical() + "|patronal=111300", specific.canonical())
        assertEquals(old.sourceUrl() + "&patronal=111300", specific.sourceUrl())
        assertNotEquals(old.canonical(), specific.canonical())
        for (invalid in listOf("", "0", "01", "-1", "1e3", "111300&church=30", "1".repeat(10)))
            assertFalse(old.copy(patronalId = invalid).isValid())
    }
}

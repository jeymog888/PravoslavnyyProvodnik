package ru.liturgyguide.service.book

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate

/** Genuine metadata and independent civil-date boundaries, never proof of a selected local rite. */
class PatronalCatalogTest {
    private val catalog by lazy { requireNotNull(PatronalCatalog.decode(asset())) }

    @Test fun bundledMetadataRetainsAllSourceRecordsAndItsFrozenProvenance() {
        assertEquals(1100, catalog.entries.size)
        assertEquals(1100, catalog.entries.map { it.mineaId }.toSet().size)
        val proof = JsonParser.parseString(resource("patronal-minea-index-v1.provenance.json")).asJsonObject
        val bytes = asset().toByteArray(Charsets.UTF_8)
        assertEquals(proof["assetBytes"].asInt, bytes.size)
        assertEquals(proof["assetSha256"].asString,
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        assertEquals(11, proof.getAsJsonArray("sourcePages").size())
        val lazarus = entry("20528")
        assertEquals("0&#8212;08 Воскрешение прав. Ла́заря", lazarus.sourceTitle)
        assertEquals("Воскрешение прав. Лазаря", lazarus.displayTitle)
        assertEquals(PatronalDateRule.PaschaOffset(-8), lazarus.dateRule)
        assertTrue(entry("9197").candidateEligible)
    }

    @Test fun ordinaryNicholasFormsFindBothDistinctCelebrationsWithoutDeduplicatingThem() {
        for (query in listOf("Николай Чудотворец", "Никола́я чудотво́рца", "Николаевский", "Никольский храм")) {
            val ids = catalog.search(query, 2026).map { it.entry.mineaId }
            assertTrue("$query must find the May celebration", "11183" in ids)
            assertTrue("$query must find the December celebration", "9906" in ids)
        }
        val found = catalog.search("Николай Чудотворец", 2026)
        assertEquals(setOf("9906", "11183"), found.take(2).map { it.entry.mineaId }.toSet())
        assertEquals(listOf(LocalDate.of(2026, 5, 22)), found.single { it.entry.mineaId == "11183" }.dateCandidates)
        assertEquals(listOf(LocalDate.of(2026, 12, 19)), found.single { it.entry.mineaId == "9906" }.dateCandidates)
    }

    @Test fun iconWordsDistinguishVladimirIconsFromPrinceAndBishop() {
        val found = catalog.search("Владимирской иконы", 2026)
        assertEquals(setOf("9592", "16896", "17499"), found.map { it.entry.mineaId }.toSet())
        assertFalse(found.any { it.entry.mineaId in setOf("7156", "10861") })
        val ordinary = catalog.search("Владимир", 2026)
        assertTrue(ordinary.any { it.entry.mineaId == "7156" })
        assertTrue(ordinary.any { it.entry.mineaId == "10861" })
        assertEquals(listOf(LocalDate.of(2026, 9, 8)), found.single { it.entry.mineaId == "9592" }.dateCandidates)
    }

    @Test fun trinitySynonymFindsPentecostInsteadOfSaintsFromTrinityMonasteries() {
        for (query in listOf("Троица", "Живоначальной Троицы", "Троицкий храм", "Пятидесятница")) {
            val found = catalog.search(query, 2026)
            assertEquals(query, listOf("9197"), found.map { it.entry.mineaId })
            assertEquals(listOf(LocalDate.of(2026, 5, 31)), found.single().dateCandidates)
        }
        assertEquals(listOf("7484"), catalog.search("Преполовение Пятидесятницы", 2026).map { it.entry.mineaId })
    }

    @Test fun ordinaryElizabethAndIconNameFormsStayUsefulWithoutInternet() {
        for (query in listOf("Елисавета", "Елисаветинский", "Елизаветинский храм")) {
            assertEquals(query, listOf("9369"), catalog.search(query, 2026).map { it.entry.mineaId })
        }
        for (query in listOf("Нечаянная Радость", "Нечаянной Радости", "иконы Божией Матери Нечаянная Радость",
            "иконы Божьей Матери Нечаянная Радость")) {
            assertEquals(query, listOf("21630"), catalog.search(query, 2026).map { it.entry.mineaId })
        }
    }

    @Test fun elijahAliasNeverCollapsesIliodorIntoAnotherSaint() {
        val fixture = decodeEntries(
            record("1", "07-20 Пророка Илии"),
            record("2", "11-19 Мученика Илиодора"))
        assertEquals(listOf("1"), fixture.search("Ильинский храм", 2026).map { it.entry.mineaId })
        assertEquals(listOf("2"), fixture.search("Илиодора", 2026).map { it.entry.mineaId })
        assertEquals(listOf("1"), fixture.search("Илья", 2026).map { it.entry.mineaId })
    }

    @Test fun realElijahQueriesDoNotMatchTheConjunctionInIconTitles() {
        for (query in listOf("Илья", "Илия", "Ильинский храм")) {
            val ids = catalog.search(query, 2026).map { it.entry.mineaId }
            assertTrue(query, "9410" in ids)
            assertFalse("The conjunction in Отрада, или Утешение is not Elijah", "10840" in ids)
            assertFalse("The conjunction in the icon name is not Elijah", "10889" in ids)
        }
    }

    @Test fun ordinaryPeterAndPaulQueriesFindTheirJointDedicationWithoutConfusingPaulinus() {
        for (query in listOf("Петр и Павел", "Пётр и Павел", "Петра и Павла", "Петропавловский храм"))
            assertTrue(query, catalog.search(query, 2026).any { it.entry.mineaId == "9305" })
        val fixture = decodeEntries(record("1", "06-29 Апостола Павла"), record("2", "06-22 Святителя Павлина"))
        assertEquals(listOf("1"), fixture.search("Павел", 2026).map { it.entry.mineaId })
        assertEquals(listOf("2"), fixture.search("Павлин", 2026).map { it.entry.mineaId })
    }

    @Test fun repeatedJsonFieldNamesCannotSilentlyReplaceMetadata() {
        val valid = json(record("1", "12-06 Николая"))
        assertNotNull(PatronalCatalog.decode(valid))
        for (duplicate in listOf(
            valid.replace("\"mineaId\":\"1\"", "\"mineaId\":\"2\",\"mineaId\":\"1\""),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2,\"schemaVersion\":1"),
            valid.replace("\"sourcePage\":1", "\"sourcePage\":2,\"sourcePage\":1")
        )) assertNull("Duplicate keys must not select the last value", PatronalCatalog.decode(duplicate))
    }

    @Test fun dailyOrdersGeneralServicesAndSurroundingFeastsAreNotReadyDedications() {
        for (id in listOf("6764", "7480", "9205", "9111", "9626", "9638", "6814", "17746"))
            assertFalse("$id must retain its source metadata without being a dedication candidate", entry(id).candidateEligible)
        val christmas = catalog.search("Рождество Богородицы", 2026)
        assertTrue(christmas.any { it.entry.mineaId == "9631" })
        assertFalse(christmas.any { it.entry.mineaId in setOf("9626", "9638", "9641", "9644", "9650") })
        assertTrue(catalog.search("Родительская суббота", 2026).isEmpty())
        assertTrue(entry("9200").candidateEligible) // The named feast of the Holy Spirit survives its weekday prefix.
    }

    @Test fun limitsUnknownQueriesAndUnsupportedYearsDoNotCreateFallbackChoices() {
        assertTrue(catalog.search("", 2026).isEmpty())
        assertTrue(catalog.search("храм в честь", 2026).isEmpty())
        assertTrue(catalog.search("несуществующеепосвящение", 2026).isEmpty())
        assertTrue(catalog.search("Николай", 1899).isEmpty())
        assertTrue(catalog.search("Николай", 2201).isEmpty())
        assertTrue(catalog.search("Николай", 2026, limit = 0).isEmpty())
        assertEquals(1, catalog.search("Николай", 2026, limit = 1).size)
        assertEquals(100, catalog.search("муч", 2026, limit = Int.MAX_VALUE).size)
        assertTrue(catalog.search("а".repeat(201), 2026).isEmpty())
    }

    @Test fun unknownDateAndAmbiguousDashNeverBecomeAComputedFeast() {
        val fixture = decodeEntries(record("1", "Общая служба мученику"), record("2", "0–08 Лазаря"),
            record("3", "13-01 Неподдерживаемый префикс"), record("4", "02-30 Неверная дата"))
        assertTrue(fixture.entries.all { it.dateRule == null && !it.candidateEligible })
        assertTrue(fixture.search("Лазаря", 2026).isEmpty())
    }

    @Test fun malformedAndAmbiguousJsonFailsClosedInsteadOfDroppingRows() {
        assertNull(PatronalCatalog.decode(null))
        assertNull(PatronalCatalog.decode("[]"))
        assertNull(PatronalCatalog.decode("{"))
        assertNull(PatronalCatalog.decode(json(record("1", "12-06 Николая"), record("1", "05-09 Николая"))))
        assertNull(PatronalCatalog.decode(json()))
        assertNull(PatronalCatalog.decode(json(record("1", "12-06 Николая")).replace("\"schemaVersion\":1", "\"schemaVersion\":1.0")))
        val extra = JsonParser.parseString(json(record("1", "12-06 Николая"))).asJsonObject.apply { addProperty("extra", "ignored?") }
        assertNull(PatronalCatalog.decode(extra.toString()))
        assertNull(PatronalCatalog.decode(" ".repeat(PatronalCatalog.MAX_BYTES + 1)))
        val wideUtf8 = json(*(1..700).map { record(it.toString(), "12-06 " + "я".repeat(400)) }.toTypedArray())
        assertTrue(wideUtf8.length < PatronalCatalog.MAX_BYTES)
        assertTrue(wideUtf8.toByteArray(Charsets.UTF_8).size > PatronalCatalog.MAX_BYTES)
        assertNull(PatronalCatalog.decode(wideUtf8))
    }

    @Test fun titleMarkupUnsafeOriginsAndWrongFieldTypesAreRejected() {
        for (title in listOf("12-06 <b>Николая</b>", "12-06 &lt;b&gt;Николая&lt;/b&gt;", " ", "12-06 Николая\n"))
            assertNull(title, PatronalCatalog.decode(json(record("1", title))))
        for (url in listOf("http://azbyka.ru/worships/minea/name/", "https://azbyka.ru.evil.test/worships/minea/name/",
            "https://user@azbyka.ru/worships/minea/name/", "https://azbyka.ru/worships/minea/%2e%2e/admin/",
            "https://azbyka.ru/worships/minea/name/?redirect=other", "https://azbyka.ru/worships/minea/name/#fragment"))
            assertNull(url, PatronalCatalog.decode(json(record("1", "12-06 Николая").apply { addProperty("sourceUrl", url) })))
        assertNull(PatronalCatalog.decode(json(record("1", "12-06 Николая").apply { addProperty("mineaId", 1) })))
        assertNull(PatronalCatalog.decode(json(record("01", "12-06 Николая"))))
        assertNull(PatronalCatalog.decode(json(record("1", "12-06 Николая").apply { addProperty("sourcePage", "1") })))
    }

    @Test fun julianCalendarConversionHandlesBothSidesOfCenturyLeapBoundaries() {
        assertEquals(LocalDate.of(1900, 1, 13), PatronalDates.julianToGregorian(1900, 1, 1))
        assertEquals(LocalDate.of(1900, 3, 12), PatronalDates.julianToGregorian(1900, 2, 28))
        assertEquals(LocalDate.of(1900, 3, 13), PatronalDates.julianToGregorian(1900, 2, 29))
        assertEquals(LocalDate.of(1900, 3, 14), PatronalDates.julianToGregorian(1900, 3, 1))
        assertEquals(LocalDate.of(1918, 2, 14), PatronalDates.julianToGregorian(1918, 2, 1))
        assertEquals(LocalDate.of(1970, 1, 1), PatronalDates.julianToGregorian(1969, 12, 19))
        assertEquals(LocalDate.of(2100, 3, 14), PatronalDates.julianToGregorian(2100, 2, 29))
        assertEquals(LocalDate.of(2100, 3, 15), PatronalDates.julianToGregorian(2100, 3, 1))
        assertEquals(LocalDate.of(2200, 3, 15), PatronalDates.julianToGregorian(2200, 2, 29))
        assertEquals(LocalDate.of(2200, 3, 16), PatronalDates.julianToGregorian(2200, 3, 1))
    }

    @Test fun januaryCandidatesUsePreviousJulianYearWithoutAssumingThirteenDays() {
        val christmas = PatronalDateRule.FixedJulian(12, 25)
        assertEquals(listOf(LocalDate.of(1900, 1, 6)), PatronalDates.candidates(christmas, 1900))
        assertEquals(listOf(LocalDate.of(2026, 1, 7)), PatronalDates.candidates(christmas, 2026))
        assertEquals(listOf(LocalDate.of(2100, 1, 7)), PatronalDates.candidates(christmas, 2100))
        assertEquals(listOf(LocalDate.of(2200, 1, 8)), PatronalDates.candidates(christmas, 2200))
        assertEquals(listOf(LocalDate.of(2100, 12, 20)), PatronalDates.candidates(PatronalDateRule.FixedJulian(12, 6), 2100))
        // The Julian leap day shifts this date entirely outside Gregorian 1900.
        assertTrue(PatronalDates.candidates(PatronalDateRule.FixedJulian(12, 19), 1900).isEmpty())
        assertTrue(PatronalDates.candidates(PatronalDateRule.FixedJulian(2, 29), 2026).isEmpty())
    }

    @Test fun genuineMovingRulesKeepTheirSignsAndKnownPaschaDates() {
        val dates = mapOf(1900 to "1900-04-22", 2024 to "2024-05-05", 2026 to "2026-04-12",
            2027 to "2027-05-02", 2100 to "2100-05-02", 2200 to "2200-04-06")
        dates.forEach { (year, expected) -> assertEquals(LocalDate.parse(expected), PatronalDates.orthodoxPascha(year)) }
        for (year in PatronalDates.FIRST_YEAR..PatronalDates.LAST_YEAR)
            assertEquals("Pascha must remain Sunday in Gregorian $year", DayOfWeek.SUNDAY, PatronalDates.orthodoxPascha(year).dayOfWeek)
        assertEquals(listOf(LocalDate.of(2026, 4, 4)), PatronalDates.candidates(requireNotNull(entry("20528").dateRule), 2026))
        assertEquals(listOf(LocalDate.of(2026, 5, 31)), PatronalDates.candidates(requireNotNull(entry("9197").dateRule), 2026))
        assertNotEquals(entry("20528").dateRule, PatronalDateRule.PaschaOffset(8))
    }

    @Test fun invalidCivilDatesAndUnsupportedComputationYearsAreNotNormalized() {
        assertThrows(IllegalArgumentException::class.java) { PatronalDates.julianToGregorian(1901, 2, 29) }
        assertThrows(IllegalArgumentException::class.java) { PatronalDates.julianToGregorian(1900, 2, 30) }
        assertThrows(IllegalArgumentException::class.java) { PatronalDateRule.FixedJulian(2, 30) }
        assertThrows(IllegalArgumentException::class.java) { PatronalDateRule.PaschaOffset(100) }
        assertThrows(IllegalArgumentException::class.java) { PatronalDates.orthodoxPascha(1899) }
        assertTrue(PatronalDates.candidates(PatronalDateRule.PaschaOffset(0), 2201).isEmpty())
    }

    private fun entry(id: String) = catalog.entries.single { it.mineaId == id }
    private fun asset() = resource(PatronalCatalog.ASSET_NAME)
    private fun resource(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
    private fun record(id: String, title: String) = JsonObject().apply {
        addProperty("mineaId", id); addProperty("sourceTitle", title)
        addProperty("sourceUrl", "https://azbyka.ru/worships/minea/test-$id/"); addProperty("sourcePage", 1)
    }
    private fun json(vararg entries: JsonObject) = JsonObject().apply {
        addProperty("schemaVersion", 1); addProperty("capturedAt", "2026-11-13"); addProperty("sourceUrl", PatronalCatalog.SOURCE_URL)
        add("entries", com.google.gson.JsonArray().apply { entries.forEach { add(it) } })
    }.toString()
    private fun decodeEntries(vararg entries: JsonObject) = requireNotNull(PatronalCatalog.decode(json(*entries)))
}

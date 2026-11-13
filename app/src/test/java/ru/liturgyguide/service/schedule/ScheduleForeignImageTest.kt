package ru.liturgyguide.service.schedule

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.service.ParishRepository
import java.time.LocalDate

class ScheduleForeignImageTest {
    private val curated = ParishRepository(requireNotNull(javaClass.getResource("/churches.json")).readText()).directory.churches
    private val child = curated.single { it.id == "vsetsaritsa_nakhabino" }
    private val site = ImageScheduleFixture.parent
    private val date = LocalDate.of(2026, 11, 13)
    private val witness = requireNotNull(KnownOtherScheduleVenue.resolve(child, site, site, curated))
    private fun result(document: ScheduleDocument, known: KnownOtherScheduleVenue? = witness): ScheduleParseResult {
        val checked = ScheduleDocumentParser.inspect(document, date, child)
        return ScheduleForeignImage.certify(document, date, child, site, site, known, checked)
    }

    @Test fun unchangedRawAndHocrFirstImageNeedPositiveOtherServicesBeforeReceivingTheReason() {
        for (geometry in listOf(false, true)) {
            val document = ImageScheduleFixture.document(1, geometry)
            val selected = ScheduleDocumentParser.inspect(document, date, child)
            assertEquals(ScheduleParseDisposition.REJECTED, selected.disposition)
            assertNull(selected.rejectionReason)
            val foreign = ScheduleDocumentParser.inspect(document, date, witness.parish)
            assertEquals(ScheduleParseDisposition.PARSED, foreign.disposition)
            assertTrue(foreign.events.any { it.date == "2026-11-15" && it.time == "09:30" })
            val certified = result(document)
            assertEquals(selected.copy(rejectionReason = ScheduleRejectionReason.OTHER_ENTITY_ONLY), certified)
            assertTrue(certified.events.isEmpty())
            assertTrue(certified.withdrawnDates.isEmpty()); assertTrue(certified.withdrawnStarts.isEmpty())
            assertFalse(certified.withdrawsAll); assertFalse(certified.safetyConflict)
            assertEquals(selected, result(document, null))
        }
    }

    @Test fun noBareMismatchOrNameWithoutAnActualScheduleCanReceiveTheReason() {
        val original = ImageScheduleFixture.document(1, false)
        val page = original.pages.single()
        val invalid = listOf(original.copy(format = "pdf"), original.copy(pages = listOf(page, page)),
            original.copy(pages = listOf(page.copy(lines = page.lines.take(2)))),
            original.copy(pages = listOf(page.copy(lines = listOf(ScheduleDocumentLine("13 пятница")) + page.lines))),
            ImageScheduleFixture.changed(original) { if (it.text.contains("Елисаветинская")) it.copy(text = "Неизвестный храм") else it })
        invalid.forEach { assertNull(result(it).rejectionReason) }
        assertNull(result(ImageScheduleFixture.document(3, false)).rejectionReason)
        // The continuation has no complete primary identity of its own. It must not
        // inherit a certificate merely because it follows a known foreign image.
        assertNull(result(ImageScheduleFixture.document(2, false)).rejectionReason)
    }

    @Test fun everyCancellationQualityGapAndPotentialSecondVenueKeepsTheConservativePolicy() {
        val original = ImageScheduleFixture.document(1, false)
        val page = original.pages.single()
        val line = page.lines[2]
        val variants = listOf(page.copy(sourceTruncated = true), page.copy(ocrFailure = ScheduleOcrFailure.UNSUPPORTED_LAYOUT),
            page.copy(ocrFailure = ScheduleOcrFailure.CONFLICTING_EVIDENCE),
            page.copy(rawOcrEvidence = listOf("Все службы отменены")),
            page.copy(rawOcrEvidence = listOf(page.lines.joinToString(" ") { it.text })),
            page.copy(lines = page.lines + ScheduleDocumentLine("Все богослужения отменены")),
            page.copy(lines = page.lines + ScheduleDocumentLine("Никольский храм")),
            page.copy(lines = page.lines + ScheduleDocumentLine("Раздел", headingLevel = 2)),
            page.copy(lines = page.lines + ScheduleDocumentLine("13 пятница 17:00 Вечерня в Казанском храме"))) +
            listOf(line.copy(struck = true), line.copy(confidence = 44), line.copy(temporalConfidence = 54),
                line.copy(startsDayContainer = true), line.copy(endsDayContainer = true), line.copy(endsTable = true),
                line.copy(venue = witness.parish.name), line.copy(venueEvidence = line.text),
                line.copy(serviceText = "Литургия отменена"), line.copy(headingLevel = -1),
                line.copy(text = line.text + "\nНеизвестная область")).map { modified ->
                page.copy(lines = page.lines.mapIndexed { index, old -> if (index == 2) modified else old })
            }
        variants.forEachIndexed { index, changed ->
            assertNull("variant=$index", result(original.copy(pages = listOf(changed))).rejectionReason)
        }
    }

    @Test fun anAlreadyUnsafeOrMixedSelectedResultCannotBeRelabeledAsOnlyOther() {
        val document = ImageScheduleFixture.document(1, false)
        val rejected = ScheduleDocumentParser.inspect(document, date, child)
        for (unsafe in listOf(rejected.copy(safetyConflict = true), rejected.copy(withdrawsAll = true),
            rejected.copy(withdrawnStarts = setOf("2026-11-13T17:00")), rejected.copy(withdrawnDates = setOf("2026-11-13")),
            rejected.copy(events = ScheduleDocumentParser.inspect(document, date, witness.parish).events)))
            assertEquals(unsafe, ScheduleForeignImage.certify(document, date, child, site, site, witness, unsafe))
    }

    @Test fun aNewPlainOcrScheduleSectionCannotInheritTheOldForeignHeader() {
        val first = ImageScheduleFixture.document(1, false)
        val extra = listOf(ScheduleDocumentLine("Расписание Всецарицы"), ScheduleDocumentLine("13 пятница 17:00 Вечерня"))
        assertTrue(extra.all { it.headingLevel == 0 })
        val mixed = first.copy(pages = listOf(first.pages.single().copy(lines = first.pages.single().lines + extra)))
        val selected = ScheduleDocumentParser.inspect(mixed, date, child)
        val other = ScheduleDocumentParser.inspect(mixed, date, witness.parish)
        // Both parsers currently inherit the first venue. Their successful/failed
        // parse alone must not certify this newly announced, unscoped section.
        assertEquals(ScheduleParseDisposition.REJECTED, selected.disposition)
        assertTrue(selected.events.isEmpty()); assertTrue(selected.withdrawnDates.isEmpty())
        assertTrue(selected.withdrawnStarts.isEmpty()); assertFalse(selected.withdrawsAll); assertFalse(selected.safetyConflict)
        assertEquals(ScheduleParseDisposition.PARSED, other.disposition)
        assertTrue(other.events.any { it.date == "2026-11-13" && it.time == "17:00" })
        assertNull(result(mixed).rejectionReason)
        val beforeBody = first.copy(pages = listOf(first.pages.single().copy(lines = first.pages.single().lines.take(2) +
            ScheduleDocumentLine("РАСПИСАНИЕ: Всецарицы") + first.pages.single().lines.drop(2))))
        assertNull(result(beforeBody).rejectionReason)
        assertEquals(ScheduleRejectionReason.OTHER_ENTITY_ONLY, result(first).rejectionReason)
    }

    @Test fun theFirstScheduleTitleCannotHideAnotherDedicationUnderAForeignHeading() {
        val original = ImageScheduleFixture.document(1, false)
        val page = original.pages.single()
        assertEquals("Расписание богослужений на ноябрь 2026 г.", page.lines[1].text)
        for (title in listOf("Расписание Всецарицы на ноябрь 2026 г.",
            "Расписание\u00a0Всецарицы на ноябрь 2026 г.",
            "Расписание\u202fВсецарицы на ноябрь 2026 г.",
            "\u2007Расписание Всецарицы на ноябрь 2026 г.",
            "Расписание богослужений Всецарицы на ноябрь 2026 г.",
            "Расписание богослужений на ноябрь 2026 г. - Всецарица")) {
            val mixed = original.copy(pages = listOf(page.copy(lines = page.lines.mapIndexed { index, line ->
                if (index == 1) line.copy(text = title) else line
            })))
            val selected = ScheduleDocumentParser.inspect(mixed, date, child)
            val other = ScheduleDocumentParser.inspect(mixed, date, witness.parish)
            assertEquals(ScheduleParseDisposition.REJECTED, selected.disposition)
            assertTrue(selected.events.isEmpty()); assertTrue(selected.withdrawnDates.isEmpty())
            assertTrue(selected.withdrawnStarts.isEmpty()); assertFalse(selected.safetyConflict); assertFalse(selected.withdrawsAll)
            assertEquals(ScheduleParseDisposition.PARSED, other.disposition)
            assertTrue(other.events.isNotEmpty())
            assertNull(title, result(mixed).rejectionReason)
        }
        assertEquals(ScheduleRejectionReason.OTHER_ENTITY_ONLY, result(original).rejectionReason)
    }
}

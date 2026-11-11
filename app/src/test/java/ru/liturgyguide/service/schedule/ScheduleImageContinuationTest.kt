package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.service.ParishRepository
import java.time.LocalDate

/** Unmodified public Android OCR, not an idealized transcription of the photographed table. */
internal object ImageScheduleFixture {
    val today: LocalDate = LocalDate.of(2026, 11, 12)
    const val parent = "https://xn----7sbbgncsdakjgxi3cgfp8d.xn--p1ai/schedule"
    fun text(name: String) = requireNotNull(javaClass.getResource("/schedule/images/$name")).readText()
    val parish = ParishRepository(requireNotNull(javaClass.getResource("/churches.json")).readText())
        .selected("elisavetinsky_opalikha").copy(schedule = emptyList(), scheduleValidFrom = "", scheduleValidUntil = "")
    fun document(page: Int, geometry: Boolean = true): ScheduleDocument {
        val raw = Gson().fromJson(text("opalikha-$page.json"), ScheduleDocument::class.java)
        return if (!geometry) raw else raw.copy(pages = listOf(ScheduleDocumentPage(
            ScheduleOcrLayout.fromHocr(text("opalikha-$page.hocr")))))
    }
    fun changed(document: ScheduleDocument, transform: (ScheduleDocumentLine) -> ScheduleDocumentLine?) =
        document.copy(pages = listOf(ScheduleDocumentPage(document.pages.single().lines.mapNotNull(transform))))
    fun joined(first: ScheduleDocument = document(1), next: ScheduleDocument = document(2)) =
        ScheduleImageContinuation.join(first, next, parent, parish)
    fun events(document: ScheduleDocument) = ScheduleDocumentParser.parse(document, today, parish)
}

class ScheduleImageContinuationTest {
    private fun starts(document: ScheduleDocument) = ImageScheduleFixture.events(document).map { "${it.date}T${it.time}" }.toSet()

    @Test fun twoPhotographedHalvesPreserveCivilDatesAndNeverBorrowDaughterChurch() {
        val joined = requireNotNull(ImageScheduleFixture.joined())
        val events = ImageScheduleFixture.events(joined)
        assertEquals(ImageScheduleFixture.parent, joined.sourceUrl)
        assertEquals(1, joined.pages.size)
        for (start in listOf("2026-11-12T08:00", "2026-11-15T07:00", "2026-11-15T09:30", "2026-11-15T17:00",
            "2026-11-16T08:00", "2026-11-20T17:00", "2026-11-21T08:00", "2026-11-29T07:00",
            "2026-11-29T09:30", "2026-11-30T08:00", "2026-11-30T17:00")) assertTrue(start, start in starts(joined))
        assertEquals(listOf("07:00", "09:30"), events.filter { it.date == "2026-11-29" && it.kind == "liturgy" }.map { it.time })
        assertFalse(events.any { it.date == "2026-12-01" || it.date == "2026-11-01" })
        assertNull(ImageScheduleFixture.joined(joined, ImageScheduleFixture.document(3)))
        assertTrue(ImageScheduleFixture.events(ImageScheduleFixture.document(3)).isEmpty())
        assertTrue(ImageScheduleFixture.events(ImageScheduleFixture.document(2)).isEmpty())
    }

    @Test fun continuationAlsoWorksWithRawRowsWithoutMakingUpMissingGeometry() {
        val joined = requireNotNull(ImageScheduleFixture.joined(ImageScheduleFixture.document(1, false), ImageScheduleFixture.document(2, false)))
        assertTrue("2026-11-16T08:00" in starts(joined))
        assertTrue("2026-11-30T08:00" in starts(joined))
        // Raw flattened rows cannot safely disambiguate the split calendar feast at 20/21 Nov.
        assertTrue(joined.pages.single().lines.all { it.serviceText == null && it.venueEvidence == null })
    }

    @Test fun phoneAndExplicitHeaderAreRequiredAndPrintedContradictionsAreNeverOverridden() {
        val first = ImageScheduleFixture.document(1)
        val second = ImageScheduleFixture.document(2)
        val invalidFirst = listOf(
            ImageScheduleFixture.changed(first) { if (it.text.contains("2026")) it.copy(text = it.text.replace("2026", "")) else it },
            ImageScheduleFixture.changed(first) { if (it.text.contains("Елисаветинская")) null else it },
            ImageScheduleFixture.changed(first) { if (it.text.startsWith("15 воскресенье")) it.copy(text = it.text.replace("воскресенье", "суббота")) else it },
            ImageScheduleFixture.changed(first) { if (it.text.startsWith("14 суббота")) it.copy(temporalConfidence = 60) else it }
        )
        invalidFirst.forEach { assertNull(ImageScheduleFixture.joined(it, second)) }
        val invalidSecond = listOf(
            ImageScheduleFixture.changed(second) { if (it.text.startsWith("Телефон")) null else it },
            ImageScheduleFixture.changed(second) { if (it.text.startsWith("Телефон")) it.copy(text = "Телефон храма 8(915)346-47-52") else it },
            ImageScheduleFixture.changed(second) { if (it.text.startsWith("16 понедельник")) it.copy(text = it.text.replace("16 понедельник", "17 вторник")) else it },
            ImageScheduleFixture.changed(second) { if (it.text.startsWith("16 понедельник")) it.copy(text = it.text.replace("понедельник", "вторник")) else it },
            ImageScheduleFixture.changed(second) { if (it.text.startsWith("16 понедельник")) it.copy(temporalConfidence = 60) else it },
            second.copy(pages = listOf(ScheduleDocumentPage(listOf(ScheduleDocumentLine("Декабрь 2026")) + second.pages.single().lines))),
            second.copy(pages = listOf(ScheduleDocumentPage(listOf(ScheduleDocumentLine("Казанский храм")) + second.pages.single().lines)))
        )
        invalidSecond.forEach { assertNull(ImageScheduleFixture.joined(first, it)) }
        assertNull(ImageScheduleFixture.joined(second, first))
        assertNull(ScheduleImageContinuation.join(first, second, "file:///schedule", ImageScheduleFixture.parish))
    }

    @Test fun cancellationSurvivesTitleCleanupAndUndatedTailCannotDiscardASafetyNotice() {
        val second = ImageScheduleFixture.document(2)
        val cancelled = ImageScheduleFixture.changed(second) {
            if (it.text.startsWith("16 понедельник")) it.copy(text = it.text + " Литургия отменена") else it
        }
        val result = ScheduleDocumentParser.inspect(requireNotNull(ImageScheduleFixture.joined(next = cancelled)), ImageScheduleFixture.today, ImageScheduleFixture.parish)
        assertTrue("2026-11-16T08:00" in result.withdrawnStarts)
        assertFalse(result.events.any { it.date == "2026-11-16" && it.time == "08:00" })
        val tailCancellation = ImageScheduleFixture.changed(second) {
            if (it.text.startsWith("1 вторник")) it.copy(text = it.text + " Богослужения отменены") else it
        }
        assertNull(ImageScheduleFixture.joined(next = tailCancellation))
    }
}

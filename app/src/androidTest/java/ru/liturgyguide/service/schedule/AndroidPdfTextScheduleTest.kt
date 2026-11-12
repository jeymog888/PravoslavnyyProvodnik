package ru.liturgyguide.service.schedule

import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.time.LocalDate

/** Actual Android PDFBox decoding of the source PDF, followed by the production extractor/parser. */
class AndroidPdfTextScheduleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val url = "https://trisvyat-dp.church.ua/files/2026/11/РАСПИСАНИЕ-БОГОСЛУЖЕНИЙ-НА-НОЯБРЬ-2026.pdf"

    @Test fun reportedMonthlyPdfKeepsPrintedCellsOnBothPagesWithoutOcr() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveMonthlyPdf") == "true")
        // Root supplies the already downloaded public PDF, inside filesDir. No network or user prefs.
        val relative = requireNotNull(args.getString("monthlyPdfFile"))
        val file = File(app.filesDir, relative).canonicalFile
        require(file.path.startsWith(app.filesDir.canonicalPath + File.separator) && file.isFile)
        val decoded = PdfScheduleTextExtractor(app).extract(file) { false }
        val output = app.filesDir.resolve("monthly-pdf-proof").apply { mkdirs() }
        output.resolve("glyph-pages.json").writeText(com.google.gson.Gson().toJson(decoded))
        assertEquals(2, decoded.size)
        assertTrue("Every monthly page must pass the glyph route", decoded.all { it != null })
        val source = "https://troitseparaskevo.ru/wp-content/uploads/2026/10/расписание_ноябрь-26.pdf"
        val document = requireNotNull(AndroidScheduleDocumentExtractor(app)
            .extract(ScheduleResponse(source, file.readBytes(), "application/pdf")))
        assertEquals("No OCR substitution of a readable table", decoded, document.pages)
        val parish = ru.liturgyguide.model.Parish("proof", "Храм Иконы Божией Матери Нечаянная Радость в Марьиной Роще",
            "Нечаянная Радость", "", "Шереметьевская улица, 33 с1", "", "", "Europe/Moscow",
            "", "", "", "", emptyList(), "", "")
        val parsed = ScheduleDocumentParser.inspect(document, LocalDate.of(2026, 11, 12), parish)
        output.resolve("parsed.json").writeText(com.google.gson.Gson().toJson(parsed))
        assertEquals(ScheduleParseDisposition.PARSED, parsed.disposition)
        assertFalse(parsed.yearInferred)
        fun times(date: String) = parsed.events.filter { it.date == date }.map { it.time }
        assertEquals(listOf("08:00", "17:00"), times("2026-11-12"))
        assertEquals(listOf("08:00", "17:00"), times("2026-11-13"))
        assertEquals(listOf("17:00"), times("2026-11-16"))
        assertEquals(listOf("17:00"), times("2026-11-30"))
        assertTrue(parsed.events.none { it.title.contains("храм открыт", ignoreCase = true) })
        assertTrue(document.pages.all { page -> page.lines.any {
            it.headingLevel == 1 && it.text.contains("НЕЧАЯННАЯ РАДОСТЬ")
        } })
    }

    @Test fun reportedMonthlyPdfRejectsPartlyHiddenInkAndNonRectangularCellClips() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveMonthlyPdf") == "true")
        val file = File(app.filesDir, requireNotNull(args.getString("monthlyPdfFile"))).canonicalFile
        require(file.path.startsWith(app.filesDir.canonicalPath + File.separator) && file.isFile)
        assertTrue("Unmodified source must pass before the negative mutations",
            PdfScheduleTextExtractor(app).extract(file) { false }.all { it != null })
        for (unknownShape in listOf(false, true)) {
            val changed = File.createTempFile("monthly-clip-", ".pdf", app.cacheDir)
            try {
                PDDocument.load(file).use { document ->
                    val page = document.getPage(0)
                    val source = page.contents.use { it.readBytes().toString(Charsets.ISO_8859_1) }
                    val clip = requireNotNull(Regex("([0-9.]+) ([0-9.]+) ([0-9.]+) ([0-9.]+) re\\s+W n").find(source))
                    val replacement = if (unknownShape) clip.value.replace("re", "re 1 1 m")
                    else "${clip.groupValues[1]} ${clip.groupValues[2]} 20 ${clip.groupValues[4]} re W n"
                    val stream = PDStream(document)
                    stream.createOutputStream(COSName.FLATE_DECODE).use {
                        it.write(source.replaceRange(clip.range, replacement).toByteArray(Charsets.ISO_8859_1))
                    }
                    page.setContents(stream)
                    document.save(changed)
                }
                assertNull(if (unknownShape) "An unproven clip path is not a rectangular cell"
                    else "A cell clip cutting the printed service must not publish its complete hidden text",
                    PdfScheduleTextExtractor(app).extract(changed) { false }.first())
            } finally { changed.delete() }
        }
    }

    @Test fun sourcePdfSuperscriptsYieldThePrintedMinutesAndCorrectDatedEvents() = runBlocking {
        withFixture("dnepr-2026-11.pdf") { file ->
            val decoded = PdfScheduleTextExtractor(app).extract(file) { false }
            assertEquals(1, decoded.size)
            val page = requireNotNull(decoded.single())
            assertTrue(page.lines.any { "07:30" in it.text })
            assertTrue(page.lines.any { "08:00" in it.text })
            assertTrue(page.lines.any { "14:00" in it.text })
            val document = requireNotNull(withTimeout(20_000) {
                AndroidScheduleDocumentExtractor(app).extract(ScheduleResponse(url, file.readBytes(), "application/pdf"))
            })
            assertEquals("Reliable text pages must reach parsing without OCR corruption", listOf(page), document.pages)
            val events = ScheduleDocumentParser.parse(document, LocalDate.of(2026, 11, 11))
            assertTrue(events.any { it.date == "2026-11-14" && it.time == "07:30" && it.title.contains("Литургия") })
            assertTrue(events.any { it.date == "2026-11-14" && it.time == "14:00" && it.title.contains("Вечернее") })
            assertTrue(events.any { it.date == "2026-11-15" && it.time == "08:00" && it.title.contains("Литургия") })
            assertTrue(events.any { it.date == "2026-11-21" && it.time == "07:30" })
            assertFalse("Weekly Wednesday footer must not inherit the last Sunday date",
                events.any { it.date == "2026-11-29" && it.time == "09:00" })
        }
    }

    @Test fun aScannedPdfKeepsTheRenderedOcrFallback() {
        withFixture("helsinki-2026-10-12.pdf") { file ->
            val pages = PdfScheduleTextExtractor(app).extract(file) { false }
            assertTrue(pages.isNotEmpty())
            assertTrue(pages.all { it == null })
            // AndroidScheduleDocumentTest exercises the existing full native OCR/import/offline flow.
        }
    }

    @Test fun invisibleTextCannotReplaceTheRenderedSchedule() {
        withFixture("dnepr-2026-11.pdf") { file ->
            PDFBoxResourceLoader.init(app)
            val hidden = File.createTempFile("hidden-schedule-", ".pdf", app.cacheDir)
            try {
                PDDocument.load(file).use { document ->
                    val page = document.getPage(0)
                    val source = page.contents.use { it.readBytes().toString(Charsets.ISO_8859_1) }
                    val beginText = Regex("(?<!\\S)BT(?!\\S)")
                    assertTrue(beginText.containsMatchIn(source))
                    val invisible = source.replace(Regex("(?<!\\S)[0-7]\\s+Tr(?!\\S)"), "3 Tr")
                        .replace(beginText, "BT 3 Tr")
                    val stream = PDStream(document)
                    stream.createOutputStream(COSName.FLATE_DECODE).use {
                        it.write(invisible.toByteArray(Charsets.ISO_8859_1))
                    }
                    page.setContents(stream)
                    document.save(hidden)
                }
                assertNull(PdfScheduleTextExtractor(app).extract(hidden) { false }.single())
            } finally { hidden.delete() }
        }
    }

    @Test fun aBlankCoverDoesNotMoveTheFollowingTextOntoTheWrongPage() {
        withFixture("dnepr-2026-11.pdf") { file ->
            PDFBoxResourceLoader.init(app)
            val covered = File.createTempFile("blank-cover-", ".pdf", app.cacheDir)
            try {
                PDDocument.load(file).use { document ->
                    document.pages.insertBefore(PDPage(), document.getPage(0))
                    document.save(covered)
                }
                val pages = PdfScheduleTextExtractor(app).extract(covered) { false }
                assertEquals(2, pages.size)
                assertNull(pages[0])
                assertTrue(requireNotNull(pages[1]).lines.any { "07:30" in it.text })
            } finally { covered.delete() }
        }
    }

    @Test fun pageLimitAndCancellationAreEnforcedBeforeExtractionCanPublish() {
        PDFBoxResourceLoader.init(app)
        val oversized = File.createTempFile("many-pages-", ".pdf", app.cacheDir)
        try {
            PDDocument().use { document -> repeat(13) { document.addPage(PDPage()) }; document.save(oversized) }
            try { PdfScheduleTextExtractor(app).extract(oversized) { false }; fail("Thirteen pages must be rejected") }
            catch (_: IOException) { }
            try { PdfScheduleTextExtractor(app).extract(oversized) { true }; fail("Cancelled work must not publish") }
            catch (_: InterruptedIOException) { }
        } finally { oversized.delete() }
    }

    private inline fun <T> withFixture(name: String, action: (File) -> T): T {
        val file = File.createTempFile("pdf-text-test-", ".pdf", app.cacheDir)
        return try {
            instrumentation.context.assets.open("schedule/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            action(file)
        } finally { file.delete() }
    }
}

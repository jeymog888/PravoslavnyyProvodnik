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

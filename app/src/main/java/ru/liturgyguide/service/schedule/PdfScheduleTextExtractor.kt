package ru.liturgyguide.service.schedule

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/** Runs on the existing bounded document worker. A null page needs rendered OCR. */
internal class PdfScheduleTextExtractor(private val context: Context) {
    fun extract(file: File, cancelled: () -> Boolean): List<ScheduleDocumentPage?> {
        fun checkCancelled() { if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("PDF extraction cancelled") }
        checkCancelled()
        if (file.length() !in 1..AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES.toLong()) throw IOException("Invalid PDF size")
        synchronized(loaderLock) { PDFBoxResourceLoader.init(context.applicationContext) }
        // Scratch buffering is bounded; this is not a hard cap on every decoded font/resource allocation.
        val memory = MemoryUsageSetting.setupMixed(4L * 1024 * 1024, 32L * 1024 * 1024).setTempDir(context.cacheDir)
        PDDocument.load(file, memory).use { document ->
            checkCancelled()
            if (document.numberOfPages !in 1..MAX_PAGES) throw IOException("Schedule has too many pages")
            val pages = MutableList<ScheduleDocumentPage?>(document.numberOfPages) { null }
            val stripper = object : PDFTextStripper() {
                private var pageIndex = -1
                private var operators = 0
                private var glyphCount = 0
                private var rejected = false
                private var glyphs = mutableListOf<PdfScheduleGlyph>()
                override fun startPage(page: PDPage) {
                    // PDFTextStripper skips pages without content streams. Keep the renderer's physical indices.
                    checkCancelled(); pageIndex = document.pages.indexOf(page)
                    glyphs = mutableListOf(); operators = 0; glyphCount = 0; rejected = false
                }
                override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
                    checkCancelled()
                    if (++operators > MAX_OPERATORS) { rejected = true; throw IOException("PDF page is too complex") }
                    super.processOperator(operator, operands)
                }
                override fun processTextPosition(text: TextPosition) {
                    checkCancelled()
                    if (++glyphCount > MAX_GLYPHS) { rejected = true; throw IOException("PDF page has too much text") }
                    val rendering = graphicsState.textState.renderingMode
                    if (!rendering.isFill && !rendering.isStroke) return // Invisible OCR/clip-only layer.
                    val value = text.unicode ?: return
                    if (value.length > 32) { rejected = true; throw IOException("Invalid PDF glyph") }
                    glyphs += PdfScheduleGlyph(value, text.xDirAdj, text.yDirAdj, text.widthDirAdj, text.fontSizeInPt.toFloat())
                    // Do not build PDFTextStripper's second, unbounded copy of the page text.
                }
                override fun endPage(page: PDPage) {
                    checkCancelled()
                    if (rejected) return
                    val lines = PdfScheduleTextLayout.lines(glyphs)
                    if (PdfScheduleTextLayout.isReliable(lines)) pages[pageIndex] = ScheduleDocumentPage(lines)
                }
            }
            stripper.sortByPosition = true
            stripper.getText(document)
            checkCancelled()
            return pages
        }
    }

    companion object {
        const val MAX_PAGES = 12
        private const val MAX_GLYPHS = 40_000
        private const val MAX_OPERATORS = 100_000
        private val loaderLock = Any()
    }
}

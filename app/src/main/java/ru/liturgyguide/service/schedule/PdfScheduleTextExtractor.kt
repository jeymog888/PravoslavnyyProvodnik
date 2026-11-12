package ru.liturgyguide.service.schedule

import android.content.Context
import android.graphics.RectF
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType2
import com.tom_roush.pdfbox.pdmodel.font.PDTrueTypeFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDVectorFont
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Runs on the existing bounded document worker. A null page needs rendered OCR. */
internal class PdfScheduleTextExtractor(private val context: Context) {
    private data class MonthlyGraphics(val clip: PdfTableRect? = null, val components: Int = 1,
        val color: List<Float> = listOf(0f))

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
                private var monthlySafe = false
                private var pageHeight = 0f
                private var pageWidth = 0f
                private var rectangles = mutableListOf<PdfTableRect>()
                private val pendingRectangles = mutableListOf<PdfTableRect>()
                private var rectanglePath = true
                private var pendingClip: PdfTableRect? = null
                private var monthlyGraphics = MonthlyGraphics()
                private val graphicsStack = mutableListOf<MonthlyGraphics>()
                private var continuation: PdfMonthlyTable.Continuation? = null
                private var previousPage = -2
                override fun startPage(page: PDPage) {
                    // PDFTextStripper skips pages without content streams. Keep the renderer's physical indices.
                    checkCancelled(); pageIndex = document.pages.indexOf(page)
                    glyphs = mutableListOf(); operators = 0; glyphCount = 0; rejected = false
                    rectangles = mutableListOf(); pendingRectangles.clear(); rectanglePath = true
                    pendingClip = null; monthlyGraphics = MonthlyGraphics(); graphicsStack.clear()
                    val crop = page.cropBox; val media = page.mediaBox
                    pageHeight = crop.height; pageWidth = crop.width
                    monthlySafe = page.rotation == 0 && page.userUnit == 1f && crop.lowerLeftX == 0f && crop.lowerLeftY == 0f &&
                        media.lowerLeftX == 0f && media.lowerLeftY == 0f && media.width == pageWidth && media.height == pageHeight &&
                        pageWidth.isFinite() && pageHeight.isFinite() && pageWidth > 0 && pageHeight > 0
                    // A resource override can change the meaning of g/rg. This narrow path does not approximate it.
                    val spaces = page.resources?.cosObject?.getCOSDictionary(COSName.COLORSPACE)
                    if (spaces?.containsKey(COSName.DEFAULT_GRAY) == true || spaces?.containsKey(COSName.DEFAULT_RGB) == true) monthlySafe = false
                    if (pageIndex != previousPage + 1) continuation = null
                }
                override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
                    checkCancelled()
                    if (++operators > MAX_OPERATORS) { rejected = true; throw IOException("PDF page is too complex") }
                    if (pendingClip != null && operator.name != "n") monthlySafe = false
                    if (monthlySafe) when (operator.name) {
                        "Do", "BI", "sh" -> monthlySafe = false
                        "q" -> if (graphicsStack.size >= 64) monthlySafe = false else graphicsStack += monthlyGraphics
                        "Q" -> if (graphicsStack.isEmpty()) monthlySafe = false else monthlyGraphics = graphicsStack.removeAt(graphicsStack.lastIndex)
                        "cs" -> {
                            val name = operands.singleOrNull() as? COSName
                            val declared = name?.let { resources.cosObject.getCOSDictionary(COSName.COLORSPACE)?.getDictionaryObject(it) ?: it }
                            val components = colorComponents(declared)
                            if (components == null) monthlySafe = false
                            else monthlyGraphics = monthlyGraphics.copy(components = components, color = List(components) { 0f })
                        }
                        "g", "rg", "k", "sc", "scn" -> {
                            val components = when (operator.name) { "g" -> 1; "rg" -> 3; "k" -> 4; else -> monthlyGraphics.components }
                            val values = operands.mapNotNull { (it as? COSNumber)?.floatValue() }
                            if (components !in listOf(1, 3) || operands.size != components || values.size != components ||
                                values.any { !it.isFinite() || it !in 0f..1f }) monthlySafe = false
                            else monthlyGraphics = monthlyGraphics.copy(components = components, color = values)
                        }
                        "W", "W*" -> {
                            if (!rectanglePath || pendingRectangles.size != 1) monthlySafe = false
                            else pendingClip = pendingRectangles.single()
                        }
                        "re" -> {
                            val values = operands.mapNotNull { (it as? COSNumber)?.floatValue() }
                            val matrix = graphicsState.currentTransformationMatrix
                            if (operands.size != 4 || values.size != 4 || values.any { !it.isFinite() } ||
                                abs(matrix.shearX) > .001f || abs(matrix.shearY) > .001f || matrix.scaleX <= 0 || matrix.scaleY <= 0) {
                                monthlySafe = false
                            } else {
                                val a = matrix.transformPoint(values[0], values[1])
                                val b = matrix.transformPoint(values[0] + values[2], values[1] + values[3])
                                val rect = PdfTableRect(min(a.x, b.x), pageHeight - max(a.y, b.y),
                                    max(a.x, b.x), pageHeight - min(a.y, b.y))
                                if (pendingRectangles.size >= 4_000 || !listOf(rect.left, rect.top, rect.right, rect.bottom).all(Float::isFinite) ||
                                    rect.left < 0 || rect.top < 0 || rect.right > pageWidth || rect.bottom > pageHeight ||
                                    rect.right <= rect.left || rect.bottom <= rect.top) monthlySafe = false
                                else pendingRectangles += rect
                            }
                        }
                        "m", "l", "c", "v", "y" -> rectanglePath = false
                        "f", "f*", "F" -> {
                            val state = graphicsState
                            val opaque = state.nonStrokeAlphaConstant == 1.0 && state.softMask == null && state.blendMode == BlendMode.NORMAL
                            val clip = monthlyGraphics.clip
                            if (!rectanglePath || !opaque || (clip != null && pendingRectangles.any { !contains(clip, it) })) monthlySafe = false
                            else {
                                val dark = monthlyGraphics.color.all { it < 64f / 255f }
                                // A later opaque fill over earlier letters can hide a date or cancellation.
                                if (pendingRectangles.any { r -> r.right - r.left > 3f && r.bottom - r.top > 3f &&
                                        glyphs.any { g -> g.text.isNotBlank() && g.left + g.width / 2 in r.left..r.right &&
                                            g.baseline - g.fontSize * .4f in r.top..r.bottom } }) monthlySafe = false
                                if (dark) rectangles += pendingRectangles
                                if (rectangles.size > 4_000) monthlySafe = false
                            }
                            pendingRectangles.clear(); rectanglePath = true
                        }
                        "S", "s", "B", "B*", "b", "b*" -> monthlySafe = false
                        "n" -> {
                            pendingClip?.let { next ->
                                val old = monthlyGraphics.clip ?: PdfTableRect(0f, 0f, pageWidth, pageHeight)
                                val intersection = PdfTableRect(max(old.left, next.left), max(old.top, next.top),
                                    min(old.right, next.right), min(old.bottom, next.bottom))
                                if (intersection.right <= intersection.left || intersection.bottom <= intersection.top) monthlySafe = false
                                else monthlyGraphics = monthlyGraphics.copy(clip = intersection)
                            }
                            pendingClip = null; pendingRectangles.clear(); rectanglePath = true
                        }
                    }
                    super.processOperator(operator, operands)
                }
                override fun processTextPosition(text: TextPosition) {
                    checkCancelled()
                    if (++glyphCount > MAX_GLYPHS) { rejected = true; throw IOException("PDF page has too much text") }
                    val rendering = graphicsState.textState.renderingMode
                    val state = graphicsState
                    if ((!rendering.isFill && !rendering.isStroke) || state.softMask != null ||
                        state.blendMode != BlendMode.NORMAL || state.nonStrokeAlphaConstant != 1.0 ||
                        state.alphaConstant != 1.0 || text.dir != 0f) monthlySafe = false
                    if (!rendering.isFill && !rendering.isStroke) return // Invisible OCR/clip-only layer.
                    val value = text.unicode ?: return
                    if (value.length > 32) { rejected = true; throw IOException("Invalid PDF glyph") }
                    if (monthlySafe && value.isNotBlank()) {
                        if (rendering != RenderingMode.FILL || monthlyGraphics.color.all { it >= 240f / 255f }) monthlySafe = false
                        monthlyGraphics.clip?.let { clip ->
                            val bounds = visibleGlyphBounds(text, pageHeight)
                            checkCancelled()
                            if (bounds == null || !contains(clip, bounds)) monthlySafe = false
                        }
                    }
                    glyphs += PdfScheduleGlyph(value, text.xDirAdj, text.yDirAdj, text.widthDirAdj, text.fontSizeInPt.toFloat())
                    // Do not build PDFTextStripper's second, unbounded copy of the page text.
                }
                override fun endPage(page: PDPage) {
                    checkCancelled()
                    if (rejected) return
                    if (graphicsStack.isNotEmpty() || pendingClip != null) monthlySafe = false
                    val monthly = if (monthlySafe) PdfMonthlyTable.read(glyphs, rectangles, continuation) else null
                    continuation = monthly?.continuation; previousPage = pageIndex
                    if (monthly != null) { pages[pageIndex] = monthly.page; return }
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

    private fun contains(outer: PdfTableRect, inner: PdfTableRect): Boolean =
        inner.left >= outer.left - .01f && inner.top >= outer.top - .01f &&
            inner.right <= outer.right + .01f && inner.bottom <= outer.bottom + .01f

    /** Android PDFBox substitutes RGB for ICCBased, including one-component gray. Read the
     * published alternate/default instead; never let stale graphics-state black turn white into grid. */
    private fun colorComponents(declared: COSBase?): Int? {
        if (declared == COSName.DEVICEGRAY) return 1
        if (declared == COSName.DEVICERGB) return 3
        val array = declared as? COSArray ?: return null
        if (array.size() != 2 || array.getObject(0) != COSName.ICCBASED) return null
        val profile = array.getObject(1) as? COSStream ?: return null
        val components = profile.getInt(COSName.N)
        if (components != 1 && components != 3) return null
        val alternate = profile.getDictionaryObject(COSName.ALTERNATE)
        if (alternate != null && alternate != if (components == 1) COSName.DEVICEGRAY else COSName.DEVICERGB) return null
        profile.getDictionaryObject(COSName.RANGE)?.let { declaredRange ->
            val range = declaredRange as? COSArray ?: return null
            if (range.size() != components * 2 || (0 until range.size()).any { index ->
                    (range.getObject(index) as? COSNumber)?.floatValue() != (index % 2).toFloat()
                }) return null
        }
        return components
    }

    /** Check decoded TrueType ink, including accents/descenders. For a nonembedded font, PDFBox
     * supplies the substitute outline; also retain the published advance, without claiming exact rendering. */
    private fun visibleGlyphBounds(text: TextPosition, pageHeight: Float): PdfTableRect? {
        try {
            val font = text.font
            val vector = font as? PDVectorFont ?: return null
            val trueType = when (font) {
                is PDTrueTypeFont -> if (!font.isDamaged) font.trueTypeFont else null
                is PDType0Font -> (font.descendantFont as? PDCIDFontType2)?.takeIf { !it.isDamaged }?.trueTypeFont
                else -> null
            } ?: return null
            if (trueType is com.tom_roush.fontbox.ttf.OpenTypeFont && trueType.isPostScript) return null
            val units = trueType.unitsPerEm
            val matrix = text.textMatrix
            val fontMatrix = font.fontMatrix
            if (units !in 1..65_536 || font.isVertical || text.characterCodes.size != 1 ||
                abs(matrix.shearX) > .001f || abs(matrix.shearY) > .001f || matrix.scaleX <= 0 || matrix.scaleY <= 0 ||
                abs(fontMatrix.scaleX - .001f) > .000001f || abs(fontMatrix.scaleY - .001f) > .000001f ||
                fontMatrix.shearX != 0f || fontMatrix.shearY != 0f || fontMatrix.translateX != 0f || fontMatrix.translateY != 0f) return null
            val code = text.characterCodes.single()
            if (!vector.hasGlyph(code)) return null
            val path = vector.getPath(code)
            if (path.isEmpty) return null
            val ink = RectF()
            path.computeBounds(ink, true)
            val a = matrix.transformPoint(ink.left / units, ink.top / units)
            val b = matrix.transformPoint(ink.right / units, ink.bottom / units)
            var bounds = PdfTableRect(min(a.x, b.x), pageHeight - max(a.y, b.y), max(a.x, b.x), pageHeight - min(a.y, b.y))
            if (!font.isEmbedded) {
                if (!listOf(text.xDirAdj, text.yDirAdj, text.widthDirAdj).all(Float::isFinite) || text.widthDirAdj <= 0) return null
                bounds = bounds.copy(left = min(bounds.left, text.xDirAdj), right = max(bounds.right, text.xDirAdj + text.widthDirAdj),
                    bottom = max(bounds.bottom, text.yDirAdj))
            }
            return bounds.takeIf { listOf(it.left, it.top, it.right, it.bottom).all(Float::isFinite) &&
                it.right > it.left && it.bottom > it.top }
        } catch (e: InterruptedIOException) {
            throw e
        } catch (_: IOException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    }

    companion object {
        const val MAX_PAGES = 12
        private const val MAX_GLYPHS = 40_000
        private const val MAX_OPERATORS = 100_000
        private val loaderLock = Any()
    }
}

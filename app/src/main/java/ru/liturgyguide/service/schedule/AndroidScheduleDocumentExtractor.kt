package ru.liturgyguide.service.schedule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Runs the actual Russian OCR on Android. No service key or network model download is required. */
class AndroidScheduleDocumentExtractor(context: Context) : ScheduleDocumentExtractor {
    private val app = context.applicationContext

    override suspend fun extract(response: ScheduleResponse): ScheduleDocument? {
        response.html()?.let { return HtmlScheduleDocumentExtractor.fromHtml(it) }
        val pdf = response.bytes.take(5).toByteArray().toString(Charsets.US_ASCII) == "%PDF-"
        val image = response.contentType.substringBefore(';').lowercase().startsWith("image/") ||
            Regex("(?i)\\.(png|jpe?g|webp)(?:\\?|$)").containsMatchIn(response.url)
        if (!pdf && !image) return null
        if (response.bytes.size !in 1..MAX_DOCUMENT_BYTES) throw IOException("Schedule document too large")
        return suspendCancellableCoroutine { continuation ->
            val complete = AtomicBoolean(false)
            val active = AtomicReference<TessBaseAPI?>(null)
            val worker = AtomicReference<Future<*>?>(null)
            val deadline = AtomicReference<ScheduledFuture<*>?>(null)
            val lifetime = Any()
            fun stop() {
                synchronized(lifetime) { active.get()?.stop() }
                worker.get()?.cancel(true)
                deadline.getAndSet(null)?.cancel(false)
            }
            fun fail(error: Exception) {
                if (complete.compareAndSet(false, true)) {
                    continuation.resumeWithException(error)
                    stop()
                }
            }
            continuation.invokeOnCancellation { if (complete.compareAndSet(false, true)) stop() }
            val timer = deadlines.schedule({ fail(IOException("Schedule recognition timed out")) }, 120, TimeUnit.SECONDS)
            deadline.set(timer)
            if (complete.get()) timer.cancel(false)
            val task = runCatching { workers.submit {
                var api: TessBaseAPI? = null
                try {
                    if (complete.get()) return@submit
                    val directory = prepareModel()
                    val engine = TessBaseAPI()
                    api = engine
                    if (!engine.init(directory.absolutePath, "rus", TessBaseAPI.OEM_LSTM_ONLY)) throw IOException("Russian recognition model unavailable")
                    synchronized(lifetime) { active.set(engine) }
                    if (complete.get()) return@submit
                    engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                    fun recognize(bitmap: Bitmap): ScheduleDocumentPage {
                        try {
                            if (complete.get() || Thread.currentThread().isInterrupted) throw IOException("Recognition cancelled")
                            engine.setImage(bitmap)
                            // getHOCRText supports stop(), unlike the synchronous getUTF8Text convenience method.
                            val hocr = engine.getHOCRText(0)
                            if (complete.get()) throw IOException("Recognition cancelled")
                            return ScheduleDocumentPage(ScheduleOcrLayout.fromHocr(hocr.orEmpty()))
                        } finally { engine.clear(); bitmap.recycle() }
                    }
                    val pages = if (pdf) {
                        val file = File.createTempFile("schedule-", ".pdf", app.cacheDir)
                        try {
                            file.outputStream().use { it.write(response.bytes) }
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                                PdfRenderer(descriptor).use { renderer ->
                                    if (renderer.pageCount !in 1..12) throw IOException("Schedule has too many pages")
                                    (0 until renderer.pageCount).map { index ->
                                        if (complete.get()) throw IOException("Recognition cancelled")
                                        renderer.openPage(index).use { page ->
                                            val dimensions = dimensions(page.width, page.height, 1_800)
                                            val bitmap = Bitmap.createBitmap(dimensions.first, dimensions.second, Bitmap.Config.ARGB_8888)
                                            try {
                                                bitmap.eraseColor(Color.WHITE)
                                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                                recognize(bitmap)
                                            } catch (error: Exception) { if (!bitmap.isRecycled) bitmap.recycle(); throw error }
                                        }
                                    }
                                }
                            }
                        } finally { file.delete() }
                    } else {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(response.bytes, 0, response.bytes.size, bounds)
                        if (bounds.outWidth !in 1..30_000 || bounds.outHeight !in 1..30_000) throw IOException("Invalid schedule image")
                        var sample = 1
                        while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > MAX_PIXELS) sample *= 2
                        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        val bitmap = BitmapFactory.decodeByteArray(response.bytes, 0, response.bytes.size, options)
                            ?: throw IOException("Cannot read schedule image")
                        listOf(recognize(bitmap))
                    }
                    if (pages.all { it.lines.isEmpty() }) throw IOException("No readable schedule text")
                    if (complete.compareAndSet(false, true)) {
                        deadline.getAndSet(null)?.cancel(false)
                        continuation.resume(ScheduleDocument(response.url, if (pdf) "pdf" else "image", pages))
                    }
                } catch (error: Exception) { fail(error) }
                finally {
                    synchronized(lifetime) { active.set(null); api?.recycle() }
                }
            } }.getOrElse { fail(IOException("Recognition is busy", it)); return@suspendCancellableCoroutine }
            worker.set(task)
            if (complete.get()) task.cancel(true)
        }
    }

    private fun prepareModel(): File = synchronized(modelLock) {
        val directory = File(app.filesDir, "schedule-ocr-v1")
        val target = File(directory, "tessdata/rus.traineddata")
        if (!target.isFile || target.length() != MODEL_BYTES) {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "rus.pending")
            try {
                app.assets.open("ocr/rus.traineddata").use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                if (temp.length() != MODEL_BYTES) throw IOException("Incomplete Russian OCR model")
                if (!temp.renameTo(target)) throw IOException("Cannot install Russian OCR model")
            } finally { temp.delete() }
        }
        directory
    }

    companion object {
        const val MAX_DOCUMENT_BYTES = 12_582_912
        private const val MAX_PIXELS = 4_000_000
        private const val MODEL_BYTES = 3_861_738L
        private val modelLock = Any()
        private fun daemon(job: Runnable) = Thread(job, "feoria-document-ocr").apply { isDaemon = true }
        private val workers = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2), ::daemon, ThreadPoolExecutor.AbortPolicy())
        private val deadlines = Executors.newSingleThreadScheduledExecutor(::daemon)
        private fun dimensions(width: Int, height: Int, desiredWidth: Int): Pair<Int, Int> {
            require(width > 0 && height > 0)
            val scale = minOf(desiredWidth.toDouble() / width, sqrt(MAX_PIXELS.toDouble() / width / height))
            return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
        }
    }
}

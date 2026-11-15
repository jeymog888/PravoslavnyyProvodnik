package ru.liturgyguide.service.schedule

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.googlecode.tesseract.android.TessBaseAPI
import org.jsoup.Jsoup
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.Parish
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Opt-in, synchronous local diagnostic. Root owns the external deadline and process termination on hang. */
@RunWith(AndroidJUnit4::class)
class LocalOcrModelComparisonTest {
    private val gson = GsonBuilder().serializeNulls().setPrettyPrinting().create()

    @Test fun comparePinnedRussianModelsOnOneOwnedLocalPng() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit local-only OCR diagnostic opt-in",
            args.getString("ocrModelComparison") == "allow-local-ocr-comparison")
        val basename = requireNotNull(args.getString("ocrModelComparisonDir"))
        val prefix = "feoria12-ocr-model-"
        require(basename.startsWith(prefix) && UUID.fromString(basename.removePrefix(prefix)).toString() == basename.removePrefix(prefix))
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = app.cacheDir.canonicalFile
        val owned = fixed(cache, basename).also { require(it.isDirectory && it.parentFile == cache) }
        val marker = fixed(owned, "OWNER.txt")
        require(marker.isFile && marker.length() in 1..256 && marker.readText(Charsets.UTF_8) ==
            "FEORIA_LOCAL_OCR_MODEL_COMPARISON_V1\n$basename\n")
        val metadataFile = fixed(owned, "input.json")
        require(metadataFile.isFile && metadataFile.length() in 1..65_536)
        val metadata = JsonParser.parseString(metadataFile.readText(Charsets.UTF_8)).asJsonObject
        require(metadata.keySet() == setOf("schemaVersion", "sourceUrl", "date", "parish"))
        require(metadata.get("schemaVersion").isJsonPrimitive && metadata.get("schemaVersion").asJsonPrimitive.isNumber &&
            metadata.get("schemaVersion").asString == "1")
        val source = metadata.get("sourceUrl").asString.also { require(ScheduleUrl.publicSource(it) == it) }
        val date = metadata.get("date").asString.let { LocalDate.parse(it).also { parsed -> require(parsed.toString() == it) } }
        val parish = gson.fromJson(metadata.getAsJsonObject("parish"), Parish::class.java)
        require(parish.id.length in 1..200 && parish.name.length in 1..500)
        ZoneId.of(parish.timezone)
        val selectedFingerprint = ScheduleEntityIdentity.fingerprint(parish)
        val imageFile = fixed(owned, "source.png")
        require(imageFile.isFile && imageFile.length() in 1..AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES.toLong())
        val bytes = imageFile.readBytes()
        require(bytes.size.toLong() == imageFile.length() && bytes.size <= AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES)
        require(bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte))
        val imageSha = sha(bytes)
        val models = listOf(
            Model("fast", fixed(owned, "fast/tessdata/rus.traineddata"), 3_861_738L,
                "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225"),
            Model("best", fixed(owned, "best/tessdata/rus.traineddata"), 15_301_764L,
                "b617eb6830ffabaaa795dd87ea7fd251adfe9cf0efe05eb9a2e8128b7728d6b6"))
        models.forEach(::verifyModel)
        val output = fixed(owned, "results")
        check(!output.exists() && output.mkdir()) { "Refusing to overwrite local OCR evidence" }
        fixed(owned, "results").also { check(it.isDirectory) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..30_000 && bounds.outHeight in 1..30_000)
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > ScheduleDocumentExtractor.MAX_IMAGE_PIXELS) sample *= 2
        json(output, "input-receipt.json", linkedMapOf(
            "state" to "NOT_COMPLETED", "startedAt" to Instant.now().toString(), "ownerBasename" to basename,
            "sourceUrl" to source, "date" to date.toString(), "parish" to parish, "selectedFingerprint" to selectedFingerprint,
            "imageBytes" to bytes.size, "imageSha256" to imageSha,
            "sourceWidth" to bounds.outWidth, "sourceHeight" to bounds.outHeight, "inSampleSize" to sample,
            "models" to models.map { mapOf("name" to it.name, "bytes" to it.bytes, "sha256" to it.sha) },
            "limits" to mapOf("documentBytes" to AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES,
                "imagePixels" to ScheduleDocumentExtractor.MAX_IMAGE_PIXELS, "sourceDimension" to 30_000,
                "modelBytes" to MAX_MODEL_BYTES, "hocrBytes" to MAX_HOCR_BYTES),
            "scope" to "Initial OEM_LSTM_ONLY/PSM_AUTO only; not full extractor optional sparse repair or primary source oracle",
            "timeoutContract" to "No internal hard timeout, watchdog or background OCR worker. Root must externally bound this diagnostic; a hang/process kill is FAIL/NOT_COMPLETED",
            "primaryOracle" to "NOT_REVIEWED", "applicationSuccess" to false))
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
        try {
            require(bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.width.toLong() * bitmap.height <= ScheduleDocumentExtractor.MAX_IMAGE_PIXELS)
            json(output, "bitmap.json", mapOf("width" to bitmap.width, "height" to bitmap.height,
                "config" to bitmap.config?.name, "inSampleSize" to sample, "sameBitmapForBothModels" to true))
            // Deliberately sequential on the instrumentation caller thread: no future/coroutine worker survives the test.
            for (model in models) compare(model, bitmap, source, date, parish, output)
            require(fixed(owned, "source.png").inputStream().use(::sha) == imageSha)
            models.forEach(::verifyModel)
            json(output, "completed.json", mapOf("state" to "COMPLETED_DIAGNOSTIC_ONLY", "finishedAt" to Instant.now().toString(),
                "modelsCompleted" to models.map { it.name }, "primaryOracle" to "NOT_REVIEWED", "applicationSuccess" to false))
        } finally { bitmap.recycle() }
    }

    @Test fun recognizePinnedFastRussianDateAndTimeCellsOnOneOwnedLocalPng() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit local-only cell OCR diagnostic opt-in",
            args.getString("ocrCellComparison") == "allow-local-cell-ocr")
        val timeMode = args.getString("ocrCellTimePsm") ?: "block"
        require(timeMode in setOf("block", "sparse"))
        val timePsmName = if (timeMode == "sparse") "PSM_SPARSE_TEXT" else "PSM_SINGLE_BLOCK"
        val basename = requireNotNull(args.getString("ocrModelComparisonDir"))
        val prefix = "feoria12-ocr-model-"
        require(basename.startsWith(prefix) && UUID.fromString(basename.removePrefix(prefix)).toString() == basename.removePrefix(prefix))
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val owned = fixed(cache, basename).also { require(it.isDirectory && it.parentFile == cache) }
        val marker = fixed(owned, "OWNER.txt")
        require(marker.isFile && marker.length() in 1..256 && marker.readText(Charsets.UTF_8) ==
            "FEORIA_LOCAL_OCR_MODEL_COMPARISON_V1\n$basename\n")
        val cellsFile = fixed(owned, "cells.json")
        require(cellsFile.isFile && cellsFile.length() in 1..65_536)
        val cellsBytes = cellsFile.readBytes()
        require(cellsBytes.size in 1..65_536 && cellsBytes.size.toLong() == cellsFile.length())
        val metadata = JsonParser.parseString(cellsBytes.toString(Charsets.UTF_8)).asJsonObject
        require(metadata.keySet() == setOf("schemaVersion", "sourceUrl", "imageSha256", "width", "height",
            "insetPixels", "probeSha256", "coordinateSpace", "cells"))
        fun number(value: JsonObject, key: String): Int {
            val raw = value.get(key).also { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) }.asString
            return raw.toInt().also { require(it.toString() == raw) }
        }
        require(number(metadata, "schemaVersion") == 1 && number(metadata, "insetPixels") == 4 &&
            metadata.get("coordinateSpace").asString == "ORIGINAL_DECODED_PNG_PIXELS")
        val source = metadata.get("sourceUrl").asString.also { require(ScheduleUrl.publicSource(it) == it) }
        val imageSha = metadata.get("imageSha256").asString.also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        require(metadata.get("probeSha256").asString.matches(Regex("[0-9a-f]{64}")))
        val width = number(metadata, "width"); val height = number(metadata, "height")
        require(width in 1..30_000 && height in 1..30_000 && width.toLong() * height <= ScheduleDocumentExtractor.MAX_IMAGE_PIXELS)
        data class Cell(val band: Int, val kind: String, val outer: List<Int>, val crop: List<Int>)
        val declared = metadata.getAsJsonArray("cells").also { require(it.size() == 32) }
        val cells = declared.mapIndexed { index, entry ->
            val value = entry.asJsonObject.also { require(it.keySet() == setOf("band", "kind", "left", "top", "right", "bottom")) }
            val kind = if (index % 2 == 0) "date" else "time"
            require(number(value, "band") == index / 2 && value.get("kind").asString == kind)
            val outer = listOf("left", "top", "right", "bottom").map { number(value, it) }
            require(0 <= outer[0] && outer[0] < outer[2] && outer[2] <= width &&
                0 <= outer[1] && outer[1] < outer[3] && outer[3] <= height)
            require(outer[2] - outer[0] >= 16 && outer[3] - outer[1] >= 16)
            Cell(index / 2, kind, outer, listOf(outer[0] + 4, outer[1] + 4, outer[2] - 4, outer[3] - 4))
        }
        require(cells.chunked(2).all { (date, time) ->
            date.outer[1] == time.outer[1] && date.outer[3] == time.outer[3] && date.crop[2] <= time.crop[0]
        })
        for (index in cells.indices) for (other in index + 1 until cells.size) {
            val a = cells[index].crop; val b = cells[other].crop
            require(!(a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1]))
        }
        val imageFile = fixed(owned, "source.png")
        require(imageFile.isFile && imageFile.length() in 1..AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES.toLong())
        val bytes = imageFile.readBytes()
        require(bytes.size.toLong() == imageFile.length() && bytes.size <= AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES && sha(bytes) == imageSha &&
            bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte))
        val model = Model("fast", fixed(owned, "fast/tessdata/rus.traineddata"), 3_861_738L,
            "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225")
        verifyModel(model)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth == width && bounds.outHeight == height)
        val output = fixed(owned, "cell-results")
        check(!output.exists() && output.mkdir()) { "Refusing to overwrite cell OCR evidence" }
        write(output, "input-cells.json", cellsBytes)
        json(output, "input-receipt.json", mapOf("state" to "NOT_COMPLETED", "startedAt" to Instant.now().toString(),
            "ownerBasename" to basename, "sourceUrl" to source, "imageSha256" to imageSha, "cellsSha256" to sha(cellsBytes),
            "modelSha256" to model.sha, "modelBytes" to model.bytes, "sourceWidth" to width, "sourceHeight" to height,
            "inSampleSize" to 1, "datePsm" to "PSM_SINGLE_BLOCK", "timePsm" to timePsmName,
            "language" to "rus", "characterWhitelist" to null,
            "coordinateAuthority" to "Controlled experiment input, not production/source authority",
            "timeoutContract" to "Root external deadline only; hang/process kill is FAIL/NOT_COMPLETED",
            "sourceVerified" to false, "publicationVerified" to false, "applicationSuccess" to false))
        val started = System.nanoTime()
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = 1; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
        var initMillis = 0L
        val completedCells = mutableListOf<String>()
        try {
            require(bitmap.width == width && bitmap.height == height && bitmap.config == Bitmap.Config.ARGB_8888)
            val engine = TessBaseAPI()
            var initialized = false
            try {
                val initStarted = System.nanoTime()
                check(engine.init(model.file.parentFile!!.parentFile!!.absolutePath, "rus", TessBaseAPI.OEM_LSTM_ONLY))
                initialized = true; initMillis = (System.nanoTime() - initStarted) / 1_000_000
                check(engine.setVariable("hocr_char_boxes", "0"))
                val bbox = Regex("(?:^|[;\\s])bbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)")
                val confidence = Regex("(?:^|[;\\s])x_wconf\\s+(\\d+)")
                // One engine, caller thread, sequential crops. No worker, whitelist, rescale or word repair.
                for (cell in cells) {
                    val cellStarted = System.nanoTime(); val rectangle = cell.crop
                    val crop = Bitmap.createBitmap(bitmap, rectangle[0], rectangle[1],
                        rectangle[2] - rectangle[0], rectangle[3] - rectangle[1])
                    val name = cell.kind + "-" + cell.band.toString().padStart(2, '0')
                    try {
                        val sparseTime = cell.kind == "time" && timeMode == "sparse"
                        val psm = if (sparseTime) TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT else TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
                        engine.clear(); engine.setPageSegMode(psm); engine.setImage(crop)
                        val recognitionStarted = System.nanoTime()
                        val hocr = engine.getHOCRText(0).orEmpty()
                        val recognitionMillis = (System.nanoTime() - recognitionStarted) / 1_000_000
                        val hocrBytes = hocr.toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_HOCR_BYTES) }
                        val nodes = Jsoup.parse(hocr).select(".ocrx_word,.ocr_word")
                        val words = nodes.map { word ->
                            val title = word.attr("title")
                            mapOf("text" to word.wholeText(), "rawTitle" to title,
                                "bbox" to bbox.find(title)?.groupValues?.drop(1)?.map { it.toIntOrNull() },
                                "confidence" to confidence.find(title)?.groupValues?.get(1)?.toIntOrNull())
                        }
                        write(output, "$name.hocr", hocrBytes)
                        write(output, "$name.txt", nodes.joinToString("\n") { it.wholeText() }.toByteArray(Charsets.UTF_8))
                        val cropBytes = ByteArrayOutputStream().use { stream ->
                            check(crop.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.toByteArray()
                        }
                        write(output, "$name.png", cropBytes)
                        json(output, "$name.json", mapOf("state" to "COMPLETED_CELL_DIAGNOSTIC_ONLY", "band" to cell.band,
                            "kind" to cell.kind, "outerRectExclusive" to cell.outer, "cropRectExclusive" to cell.crop,
                            "psm" to if (sparseTime) "PSM_SPARSE_TEXT" else "PSM_SINGLE_BLOCK",
                            "insetPixels" to 4, "bitmapWidth" to crop.width, "bitmapHeight" to crop.height,
                            "wordCoordinateSpace" to "NATIVE_CROP_BITMAP_PIXELS", "words" to words,
                            "pngBytes" to cropBytes.size, "pngSha256" to sha(cropBytes),
                            "hocrBytes" to hocrBytes.size, "hocrSha256" to sha(hocrBytes),
                            "recognitionMillis" to recognitionMillis, "throughCaptureMillis" to (System.nanoTime() - cellStarted) / 1_000_000,
                            "accuracyOracle" to "NOT_REVIEWED", "applicationSuccess" to false))
                        completedCells += name
                    } finally { try { engine.clear() } finally { crop.recycle() } }
                }
            } finally { try { if (initialized) engine.clear() } finally { engine.recycle() } }
        } finally { bitmap.recycle() }
        require(imageFile.inputStream().use(::sha) == imageSha && cellsFile.inputStream().use(::sha) == sha(cellsBytes))
        verifyModel(model)
        json(output, "completed.json", mapOf("state" to "COMPLETED_DIAGNOSTIC_ONLY", "finishedAt" to Instant.now().toString(),
            "cellsCompleted" to completedCells, "model" to "fast", "initMillis" to initMillis,
            "totalMillis" to (System.nanoTime() - started) / 1_000_000, "engineRecycled" to true, "bitmapRecycled" to bitmap.isRecycled,
            "datePsm" to "PSM_SINGLE_BLOCK", "timePsm" to timePsmName,
            "httpCalls" to 0, "httpScope" to "Diagnostic contains no transport calls; background traffic not measured",
            "sourceVerified" to false, "publicationVerified" to false, "applicationSuccess" to false))
    }

    private data class Model(val name: String, val file: File, val bytes: Long, val sha: String)

    private fun compare(model: Model, bitmap: Bitmap, source: String, date: LocalDate, parish: Parish, output: File) {
        verifyModel(model)
        val started = System.nanoTime()
        val engine = TessBaseAPI()
        var initialized = false
        var initMillis = 0L
        var recognitionMillis = 0L
        val hocr: String
        try {
            val initStarted = System.nanoTime()
            check(engine.init(model.file.parentFile!!.parentFile!!.absolutePath, "rus", TessBaseAPI.OEM_LSTM_ONLY)) {
                "Pinned Russian model failed to initialize: ${model.name}"
            }
            initialized = true
            initMillis = (System.nanoTime() - initStarted) / 1_000_000
            engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            check(engine.setVariable("hocr_char_boxes", "0"))
            engine.setImage(bitmap)
            val recognitionStarted = System.nanoTime()
            hocr = engine.getHOCRText(0).orEmpty()
            recognitionMillis = (System.nanoTime() - recognitionStarted) / 1_000_000
        } finally {
            try { if (initialized) engine.clear() }
            finally { engine.recycle() }
        }
        val hocrBytes = hocr.toByteArray(Charsets.UTF_8)
        require(hocrBytes.size <= MAX_HOCR_BYTES)
        // This is the same observation point as the production observer's completed "initial" native pass.
        val capture = NativeScheduleOcrCapture("initial", source, bitmap.width, bitmap.height, hocr)
        write(output, "${model.name}-initial.hocr", hocrBytes)
        val document = ScheduleDocument(source, "image", listOf(ScheduleDocumentPage(ScheduleOcrLayout.fromHocr(capture.hocr))))
        val parsed = ScheduleDocumentParser.inspect(document, date, parish)
        json(output, "${model.name}-document.json", document)
        json(output, "${model.name}-parser.json", parsed)
        json(output, "${model.name}-completed.json", mapOf("state" to "COMPLETED_NATIVE_INITIAL_PASS", "model" to model.name,
            "modelRelativePath" to "${model.name}/tessdata/rus.traineddata", "modelBytes" to model.bytes,
            "modelSha256" to model.sha, "initMillis" to initMillis,
            "recognitionMillis" to recognitionMillis, "totalMillis" to (System.nanoTime() - started) / 1_000_000,
            "bitmapWidth" to capture.width, "bitmapHeight" to capture.height, "stage" to capture.stage,
            "hocrBytes" to hocrBytes.size, "hocrSha256" to sha(hocrBytes),
            "productionNeedsSupplement" to ScheduleSuperscriptTable.needsSupplement(hocr),
            "engineRecycled" to true, "scope" to "Actual native initial pass + production layout/parser observation; no accuracy oracle"))
    }

    /** All calls use fixed relative names; only the root basename is supplied by instrumentation. */
    private fun fixed(owner: File, relative: String): File {
        require(!relative.startsWith('/') && relative.split('/').none { it.isEmpty() || it == "." || it == ".." })
        var file = owner
        for (part in relative.split('/')) {
            file = File(file, part)
            require(!Files.isSymbolicLink(file.toPath()) && file.absoluteFile == file.canonicalFile) { "Symlink/noncanonical diagnostic path" }
        }
        require(file.canonicalPath.startsWith(owner.canonicalPath + File.separator))
        return file
    }
    private fun verifyModel(model: Model) {
        require(model.file.isFile && !Files.isSymbolicLink(model.file.toPath()) && model.file.absoluteFile == model.file.canonicalFile)
        require(model.file.length() == model.bytes && model.bytes in 1..MAX_MODEL_BYTES)
        require(model.file.inputStream().use(::sha) == model.sha) { "Model SHA mismatch: ${model.name}" }
    }
    private fun json(output: File, name: String, value: Any) = write(output, name, gson.toJson(value).toByteArray(Charsets.UTF_8))
    private fun write(output: File, name: String, bytes: ByteArray) {
        require(name.matches(Regex("[a-z0-9.-]{1,80}")) && bytes.size <= MAX_OUTPUT_BYTES)
        val file = fixed(output, name)
        Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { it.write(bytes) }
    }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    companion object {
        private const val MAX_MODEL_BYTES = 33_554_432L
        private const val MAX_HOCR_BYTES = 16_777_216
        private const val MAX_OUTPUT_BYTES = 33_554_432
    }
}

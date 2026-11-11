package ru.liturgyguide.service.schedule

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in diagnostic: retain public OCR geometry before the production layout flattens it. */
class NativeScheduleGeometryTest {
    @Test fun capturePublicMonthlyTablesWithTheBundledRussianModel() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureScheduleGeometry") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = context.filesDir.resolve("schedule-geometry-10").apply { mkdirs() }
        val model = output.resolve("model/tessdata/rus.traineddata").apply { parentFile!!.mkdirs() }
        context.assets.open("ocr/rus.traineddata").use { input -> model.outputStream().use { input.copyTo(it) } }
        val sources = listOf(
            "https://static.tildacdn.com/tila3733-6433-4166-b433-333039663365/__1.jpg",
            "https://static.tildacdn.com/tila6563-3962-4730-b334-656133333966/__2.jpg",
            "https://static.tildacdn.com/tila3563-3130-4934-a236-316330623066/_.jpg"
        )
        val api = TessBaseAPI()
        try {
            assertTrue(api.init(model.parentFile!!.parentFile!!.absolutePath, "rus", TessBaseAPI.OEM_LSTM_ONLY))
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            sources.forEachIndexed { index, source ->
                val data = UrlConnectionScheduleTransport(timeoutMillis = 20_000).fetch(source).bytes
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                var sample = 1
                while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 4_000_000) sample *= 2
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size,
                    BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
                try {
                    api.setImage(bitmap)
                    val hocr = api.getHOCRText(0).orEmpty()
                    assertTrue(hocr.contains("ocrx_word"))
                    output.resolve("page-${index + 1}.hocr").writeText(hocr)
                    output.resolve("page-${index + 1}.json").writeText(Gson().toJson(mapOf(
                        "source" to source, "width" to bitmap.width, "height" to bitmap.height,
                        "sample" to sample, "bytes" to data.size)))
                } finally { api.clear(); bitmap.recycle() }
            }
        } finally { api.recycle(); model.parentFile!!.parentFile!!.deleteRecursively() }
    }
}

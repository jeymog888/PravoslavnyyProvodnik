package ru.liturgyguide.service.book

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File

/** An explicit real-network check, separate from deterministic UI acceptance. */
@RunWith(AndroidJUnit4::class)
class LiveServiceBookTest {
    @Test fun wholeDatedBookDownloadsOnAndroidAndReopensOffline() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("feoriaLiveNetwork") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "live-book-${System.nanoTime()}")
        try {
            val translations = BookTranslationIndex.fromAssets(context.assets.open("liturgy_stages.json").bufferedReader().use { it.readText() },
                context.assets.open("book_russian_common.json").bufferedReader().use { it.readText() })
            val key = ServiceBookKey("2026-11-11")
            val online = ServiceBookRepository(FileBookCache(directory), translations = translations)
            val result = online.load(key)
            assertTrue("Live book: $result", result is BookLoadResult.Ready)
            val ready = result as BookLoadResult.Ready
            assertTrue(ready.saved)
            assertTrue(ready.book.blocks.size > 200)
            assertTrue(ready.book.chapters.any { BookText.normalized(it.title) == "отпуст" })
            val scripture = ready.book.blocks.filter { it.kind == BookBlockKind.SCRIPTURE }
            assertTrue("Missing Russian scripture: ${ready.warning}; ${scripture.map { it.scriptureReferences to (it.russian != null) }}",
                scripture.isNotEmpty() && scripture.all { it.russian != null })
            val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { error("Offline transport") })
            assertEquals(ready.book, (offline.load(key) as BookLoadResult.Ready).book)
        } finally { directory.deleteRecursively() }
    }
}

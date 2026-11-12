package ru.liturgyguide.service.book

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File

/** Optional real installed-app archive, copied to a test directory, never to the live book cache. */
@RunWith(AndroidJUnit4::class)
class ArchivedBookEnrichmentTest {
    @Test fun installedArchiveRetainsHashesAndOfflineImprovementsKeepOriginalEditions() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("savedBookArchiveDir")
        assumeTrue("Supply an isolated directory containing archived books and probe-corpus.json", path != null)
        val archive = File(requireNotNull(path))
        val files = archive.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{32}-[a-f0-9]{64}\\.json")) }
        assertTrue("Expected archived content-addressed books", files.isNotEmpty())
        val corpus = File(archive, "probe-corpus.json").readText()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val temporary = File(context.cacheDir, "archived-enrichment-${System.nanoTime()}")
        var additions = 0
        try {
            val cache = FileBookCache(temporary)
            assertTrue(cache.writeTranslationCorpus(corpus))
            val repository = ServiceBookRepository(cache, ScheduleTransport { error("Archive check must remain offline") })
            for (file in files) {
                val originalBytes = file.readBytes()
                val old = Gson().fromJson(file.readText(), ServiceBook::class.java)
                assertTrue("Original stored hash: ${file.name}", ServiceBookIntegrity.valid(old))
                assertTrue(cache.write(old))
                assertEquals(old, FileBookCache(temporary).readVersion(old.key, old.contentHash))
                val result = repository.enrichSaved(old, allowNetwork = false)
                if (result != null) {
                    assertTrue(result.saved)
                    assertEquals(old.blocks.map { it.id to it.original }, result.book.blocks.map { it.id to it.original })
                    assertEquals(old.fetchedAtMillis, result.book.fetchedAtMillis)
                    assertEquals(old.enrichment, result.book.enrichment)
                    assertEquals(result.book, FileBookCache(temporary).readVersion(old.key, result.book.contentHash))
                    additions += result.book.translatedPrayerCount - old.translatedPrayerCount
                }
                assertEquals(old, FileBookCache(temporary).readVersion(old.key, old.contentHash))
                assertArrayEquals(originalBytes, file.readBytes())
            }
            assertTrue("The new published corpus must add real missing pairs to the archived books", additions > 0)
        } finally { temporary.deleteRecursively() }
    }
}

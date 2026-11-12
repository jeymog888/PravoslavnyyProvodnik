package ru.liturgyguide.service.book

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FileBookCachePublicationTest {
    private val directory = Files.createTempDirectory("book-publication-").toFile()
    @After fun clean() { directory.deleteRecursively() }

    @Test fun competingInstancesCanPublishOnlyOneSuccessorOfTheSameEdition() {
        val old = BookFixtures.book()
        val first = old.copy(title = "Первая редакция").let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        val second = old.copy(title = "Вторая редакция").let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        val a = FileBookCache(directory)
        val b = FileBookCache(directory)
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(12) {
                assertTrue(a.write(old))
                val start = CountDownLatch(1)
                val jobs = listOf(a to first, b to second).map { (cache, book) -> pool.submit(Callable {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    cache.writeIfLatest(old.contentHash, book)
                }) }
                start.countDown()
                assertEquals(1, jobs.count { it.get(5, TimeUnit.SECONDS) })
                assertTrue(a.read(old.key) in listOf(first, second))
                assertEquals(old, b.readVersion(old.key, old.contentHash))
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun unconditionalWriterInvalidatesStaleExpectedHashAcrossInstances() {
        val old = BookFixtures.book()
        val newer = old.copy(title = "Обновленная служба").let { it.copy(contentHash = ServiceBookIntegrity.contentHash(it)) }
        val a = FileBookCache(directory)
        val b = FileBookCache(directory)
        assertTrue(a.writeIfLatest(null, old))
        assertFalse(b.writeIfLatest(null, newer))
        assertTrue(b.write(newer))
        assertFalse(a.writeIfLatest(old.contentHash, old))
        assertEquals(newer, a.read(old.key))
    }
}

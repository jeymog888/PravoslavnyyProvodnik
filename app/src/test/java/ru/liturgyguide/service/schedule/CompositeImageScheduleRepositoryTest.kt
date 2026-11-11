package ru.liturgyguide.service.schedule

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class CompositeImageScheduleRepositoryTest {
    private class Cache : ScheduleCacheStore {
        val entries = mutableMapOf<String, String>()
        override fun read(parishId: String) = entries[parishId]
        override fun write(parishId: String, value: String) { entries[parishId] = value }
    }
    private val now = Instant.parse("2026-11-12T07:00:00Z")
    private val documents = (1..3).associate { val document = ImageScheduleFixture.document(it); document.sourceUrl to document }
    private val parish = ImageScheduleFixture.parish.copy(website = ImageScheduleFixture.parent)
    private val page = "<html><h1>Расписание богослужений</h1>" + documents.keys.joinToString("") { "<img src='$it'>" } + "</html>"
    private fun repository(cache: Cache, fail: Set<String> = emptySet()) = InternetScheduleRepository(ScheduleTransport { url ->
        if (url in fail) throw IOException("test offline")
        if (url == ImageScheduleFixture.parent) ScheduleResponse(url, page.toByteArray(), "text/html")
        else ScheduleResponse(url, byteArrayOf(1, 2), "image/jpeg")
    }, cache, ScheduleDocumentExtractor { documents[it.url] })

    @Test fun directlyLinkedImagesPublishTheCombinedScheduleAndKeepItOffline() = runBlocking {
        val cache = Cache()
        val repository = repository(cache)
        val ready = repository.refresh(parish, now, force = true)
        assertEquals(InternetScheduleStatus.READY, ready.status)
        val publication = requireNotNull(ready.publication)
        assertEquals(ImageScheduleFixture.parent, publication.sourceUrl)
        assertEquals("image", publication.documentFormat)
        assertEquals(listOf("07:00", "09:30"), publication.events.filter { it.date == "2026-11-29" && it.kind == "liturgy" }.map { it.time })
        assertTrue(publication.events.any { it.date == "2026-11-15" && it.time == "17:00" })
        assertTrue(publication.events.any { it.date == "2026-11-21" && it.time == "08:00" })
        assertEquals(ready, repository.cached(parish))
        val offline = repository(cache, setOf(ImageScheduleFixture.parent)).refresh(parish, now.plusSeconds(60), force = true)
        assertEquals(InternetScheduleStatus.UNAVAILABLE, offline.status)
        assertEquals(publication, offline.publication)
        assertEquals(publication.sourceUrl, offline.sourceUrl)
        assertEquals(offline, repository.cached(parish))
    }

    @Test fun imagesFromDifferentHtmlPublicationsCannotSupplyEachOthersMissingDate() = runBlocking {
        val firstPage = "https://xn----7sbbgncsdakjgxi3cgfp8d.xn--p1ai/schedule-a"
        val secondPage = "https://xn----7sbbgncsdakjgxi3cgfp8d.xn--p1ai/schedule-b"
        val firstImage = ImageScheduleFixture.document(1).sourceUrl
        val secondImage = ImageScheduleFixture.document(2).sourceUrl
        val requested = mutableListOf<String>()
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            requested += url
            val html = when (url) {
                ImageScheduleFixture.parent -> "<a href='$firstPage'>Расписание А</a><a href='$secondPage'>Расписание Б</a>"
                firstPage -> "<h1>Расписание богослужений</h1><img src='$firstImage'>"
                secondPage -> "<h1>Расписание богослужений</h1><img src='$secondImage'>"
                else -> null
            }
            ScheduleResponse(url, (html ?: "image").toByteArray(), if (html == null) "image/jpeg" else "text/html")
        }, Cache(), ScheduleDocumentExtractor { documents[it.url] })
        val result = repo.refresh(parish, now, force = true)
        assertEquals(InternetScheduleStatus.READY, result.status)
        assertTrue(firstImage in requested && secondImage in requested)
        val publication = requireNotNull(result.publication)
        assertEquals(firstImage, publication.sourceUrl)
        assertEquals(firstPage, publication.linkedFrom)
        assertTrue(publication.events.any { it.date == "2026-11-15" })
        assertFalse(publication.events.any { it.date > "2026-11-15" })
    }

    @Test fun failedContinuationLeavesOnlyTheFirstVerifiedImage() = runBlocking {
        val result = repository(Cache(), setOf(ImageScheduleFixture.document(2).sourceUrl)).refresh(parish, now, force = true)
        assertEquals(InternetScheduleStatus.READY, result.status)
        val publication = requireNotNull(result.publication)
        assertEquals(ImageScheduleFixture.document(1).sourceUrl, publication.sourceUrl)
        assertTrue(publication.events.any { it.date == "2026-11-15" && it.time == "09:30" })
        assertFalse(publication.events.any { it.date > "2026-11-15" })
    }
}

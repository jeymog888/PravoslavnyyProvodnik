package ru.liturgyguide.service.book

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport

/** Genuine public responses for all three parts; this is not proof of a monastery's performed order. */
class RealPatronalEveningPartsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val proof = PatronalFixtures.proof()
    private val key = ServiceBookKey("2026-11-13", WorshipKind.VIGIL, "34", patronalId = proof.id)
    private val kinds = listOf(WorshipKind.VESPERS, WorshipKind.MATINS, WorshipKind.HOUR1)

    @Test fun vespersMatinsAndTheFirstHourAllConfirmTheSameConcreteSourceSelection() {
        for (kind in kinds) {
            val partKey = key.copy(worship = kind)
            val document = Jsoup.parse(html(kind))
            assertEquals(proof.id, document.select("#bg_worships_patronal option[selected]").single().attr("value"))
            assertTrue(document.select("#bg_worships_church").isEmpty())
            val parsed = ServiceBookParser().parse(document, partKey, 12345, patronalSelection = proof)
            assertEquals(partKey, parsed.key)
            assertEquals(proof, parsed.patronalSelection)
            assertTrue(kind.name, ServiceBookIntegrity.valid(parsed))
            assertTrue(parsed.blocks.size >= 15)
        }
    }

    @Test fun aVigilAssemblesAllThreeGenuinePartsAndReopensTheExactArchiveOffline() = runBlocking {
        val directory = temporary.newFolder()
        val cache = FileBookCache(directory)
        val calls = mutableListOf<String>()
        val expected = kinds.associateBy { key.copy(worship = it).sourceUrl() }
        val repository = ServiceBookRepository(cache, ScheduleTransport { url ->
            calls += url
            val body = expected[url]?.let(::html) ?: "<html></html>"
            ScheduleResponse(url, body.toByteArray(Charsets.UTF_8), "text/html; charset=UTF-8")
        }, downloadRussian = false, patronalProofs = PatronalProofs { proof })
        val ready = repository.load(key) as BookLoadResult.Ready
        assertTrue(ready.saved)
        assertEquals(kinds, calls.mapNotNull { expected[it] })
        assertEquals(proof, ready.book.patronalSelection)
        val parts = kinds.map { kind ->
            requireNotNull(cache.read(key.copy(worship = kind))).also {
                assertEquals(proof, it.patronalSelection)
                assertTrue(ServiceBookIntegrity.valid(it))
            }
        }
        assertEquals(parts.flatMap { it.blocks }, ready.book.blocks)
        assertTrue(ServiceBookIntegrity.valid(ready.book))
        assertEquals(ready.book, cache.readVersion(key, ready.book.contentHash))
        val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { error("No network during reopen") })
        assertEquals(ready.book, (offline.load(key) as BookLoadResult.Ready).book)
    }

    private fun html(kind: WorshipKind): String = when (kind) {
        WorshipKind.VESPERS -> resource("patronal-parts/vespers.html")
        WorshipKind.MATINS -> PatronalFixtures.text("matins-default-2026-11-13.html")
        WorshipKind.HOUR1 -> resource("patronal-parts/chas1.html")
        else -> error("Unexpected evening part")
    }
    private fun resource(name: String) = requireNotNull(javaClass.classLoader?.getResource(name)).readText()
}

package ru.liturgyguide.service.schedule

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish

class CompositeScheduleCandidateProviderTest {
    private val selected = Parish("test:1", "Храм Троицы", "Троицы", "", "", "", "", "Europe/Moscow", "", "", "", "", emptyList(), "", "", 55.0, 37.0)
    private fun candidate(provider: String, id: Int) = ScheduleSourceCandidate(
        selectedIdentity = ScheduleEntityIdentity.fingerprint(selected), provider = provider, providerLabel = provider,
        providerObjectId = id.toString(), name = selected.name, address = "", sourceUrl = "https://example.org/$id/",
        evidenceUrl = "https://example.org/evidence/$id/", evidenceSha256 = "a".repeat(64), checkedAt = "2026-11-13T16:00:00Z",
        latitude = 55.0, longitude = 37.0, outcome = ScheduleIdentityOutcome.INSUFFICIENT, reason = "Проверьте источник")
    private fun result(vararg candidates: ScheduleSourceCandidate, unavailable: Boolean = false) =
        ScheduleCandidateDiscovery(candidates.toList(), candidates.map { it.providerObjectId }, unavailable = unavailable)
    private fun fixed(value: ScheduleCandidateDiscovery) = ScheduleCandidateProvider { _, _ -> value }

    @Test fun `partial failure retains available fresh provider in either order and namespaces colliding observed IDs`() = runBlocking {
        val a = candidate("source_a", 12); val b = candidate("source_b", 12)
        for (entries in listOf(listOf("source_a" to fixed(result(a)), "source_b" to fixed(result(b, unavailable = true))),
            listOf("source_b" to fixed(result(b, unavailable = true)), "source_a" to fixed(result(a))))) {
            val found = CompositeScheduleCandidateProvider(entries).discover(selected, emptySet())
            assertEquals(setOf(a.id, b.id), found.candidates.map { it.id }.toSet())
            assertEquals(setOf("source_a:12", "source_b:12"), found.observedIds.toSet())
            assertTrue(found.unavailable)
            assertTrue(found.incomplete)
        }
    }

    @Test fun `twenty result cap interleaves providers without collapsing shared site provenance`() = runBlocking {
        val a = (1..20).map { candidate("source_a", it) }
        val b = (1..4).map { candidate("source_b", it) }
        val found = CompositeScheduleCandidateProvider(listOf("source_a" to fixed(result(*a.toTypedArray())),
            "source_b" to fixed(result(*b.toTypedArray())))).discover(selected, emptySet())
        assertEquals(20, found.candidates.size)
        assertEquals(a.first(), found.candidates.first())
        assertEquals(b.first(), found.candidates[1])
        assertEquals(4, found.candidates.count { it.provider == "source_b" })
        assertEquals(2, found.candidates.count { it.sourceUrl == a.first().sourceUrl })
    }

    @Test fun `failed provider exception or own timeout does not discard completed neighbor`() = runBlocking {
        val a = candidate("source_a", 12)
        for (bad in listOf(ScheduleCandidateProvider { _, _ -> error("Temporary failure") },
            ScheduleCandidateProvider { _, _ -> awaitCancellation() })) {
            val found = CompositeScheduleCandidateProvider(listOf("source_a" to fixed(result(a)), "source_b" to bad), timeoutMillis = 20)
                .discover(selected, emptySet())
            assertEquals(listOf(a), found.candidates)
            assertTrue(found.unavailable)
        }
    }

    @Test fun `outer cancellation cancels both workers rather than returning a successful empty collection`() = runBlocking {
        val both = CompletableDeferred<Unit>(); var started = 0; var cancelled = 0
        val worker = ScheduleCandidateProvider { _, _ ->
            if (++started == 2) both.complete(Unit)
            try { awaitCancellation() } finally { cancelled++ }
        }
        val result = async { CompositeScheduleCandidateProvider(listOf("source_a" to worker, "source_b" to worker)).discover(selected, emptySet()) }
        withTimeout(1_000) { both.await() }
        result.cancelAndJoin()
        assertEquals(2, started)
        assertEquals(2, cancelled)
        assertTrue(result.isCancelled)
    }

    @Test fun `two workers run concurrently receive same exclusions and observed cap does not starve either source`() = runBlocking {
        val exclusions = setOf("https://example.org/"); val barrier = CompletableDeferred<Unit>(); var started = 0
        fun worker(prefix: String) = ScheduleCandidateProvider { parish, excluded ->
            assertEquals(selected, parish); assertEquals(exclusions, excluded)
            if (++started == 2) barrier.complete(Unit)
            withTimeout(1_000) { barrier.await() }
            ScheduleCandidateDiscovery(observedIds = (1..200).map { "$prefix-$it" })
        }
        val found = CompositeScheduleCandidateProvider(listOf("source_a" to worker("a"), "source_b" to worker("b")))
            .discover(selected, exclusions)
        assertEquals(200, found.observedIds.size)
        assertEquals("source_a:a-1", found.observedIds[0])
        assertEquals("source_b:b-1", found.observedIds[1])
        assertEquals(100, found.observedIds.count { it.startsWith("source_a:") })
        assertFalse(found.unavailable)
    }
}

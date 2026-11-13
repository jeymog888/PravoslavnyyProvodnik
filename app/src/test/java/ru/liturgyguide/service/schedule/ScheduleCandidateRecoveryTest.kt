package ru.liturgyguide.service.schedule

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.io.IOException
import java.time.Instant

class ScheduleCandidateRecoveryTest {
    private val now = Instant.parse("2026-11-13T06:00:00Z")
    private val source = "https://parish.example/"
    private val selected = Parish("osm:way:42", "Храм святителя Николая", "Николая", "Москва", "Осенняя улица, 32 с1", "", "",
        "Europe/Moscow", "", "", "", "", emptyList(), "", "", 55.76964415, 37.4061332)
    private val html = """<html><body><h1>Храм святителя Николая</h1><article data-day="2026-11-13">
        <li><time>09:00</time><span>Литургия</span></li></article></body></html>"""
    private fun candidate(address: String = selected.address, outcome: ScheduleIdentityOutcome = ScheduleIdentityOutcome.INSUFFICIENT) =
        ScheduleSourceCandidate(selectedIdentity = ScheduleEntityIdentity.fingerprint(selected), provider = "other_public_index",
            providerLabel = "Публичный справочник", providerObjectId = "42", name = selected.name, address = address,
            sourceUrl = source, evidenceUrl = "https://index.example/church/42", evidenceSha256 = "a".repeat(64),
            checkedAt = now.minusSeconds(10).toString(), latitude = selected.latitude!!, longitude = selected.longitude!!,
            outcome = outcome, reason = "Проверьте принадлежность расписания выбранному храму")
    private class Cache : ScheduleCacheStore {
        val values = mutableMapOf<String, String>()
        override fun read(parishId: String) = values[parishId]
        override fun write(parishId: String, value: String) { values[parishId] = value }
    }
    private class Resolver(var result: ScheduleCandidateDiscovery) : ScheduleSiteResolver {
        override suspend fun resolve(parish: Parish): ScheduleSite? = null
        override suspend fun candidates(parish: Parish, excludedUrls: Set<String>) = result
    }
    private fun transport(content: String = html, calls: MutableList<String> = mutableListOf()) = ScheduleTransport { url ->
        calls += url
        check(url == source)
        ScheduleResponse(url, content.toByteArray(), "text/html")
    }

    @Test fun `generic suggestion needs one explicit confirmation then imports dated events and persists distinct intent`() = runBlocking {
        val cache = Cache(); val candidate = candidate(); val calls = mutableListOf<String>()
        val resolver = Resolver(ScheduleCandidateDiscovery(listOf(candidate), listOf("42")))
        val repo = InternetScheduleRepository(transport(calls = calls), cache, siteResolver = resolver)
        val pending = repo.refresh(selected, now)
        assertNull(pending.publication)
        assertEquals(candidate, pending.sourceCandidates.single())
        assertTrue(calls.isEmpty())
        val ready = repo.confirmCandidate(selected, candidate.id, now)
        assertEquals(InternetScheduleStatus.READY, ready.status)
        assertEquals(ScheduleBindingMethod.USER_CONFIRMED, ready.siteBinding?.method)
        assertEquals(ScheduleCandidateConfirmation.TYPE, ready.siteBinding?.candidateConfirmation?.type)
        assertEquals("", ready.customSource)
        assertEquals(listOf(source), calls)
        assertEquals("2026-11-13", ready.publication?.events?.single()?.date)
        assertEquals("09:00", ready.publication?.events?.single()?.time)
        assertEquals(ready, repo.cached(selected))
        val offline = InternetScheduleRepository(ScheduleTransport { throw IOException("offline") }, cache,
            siteResolver = Resolver(ScheduleCandidateDiscovery(unavailable = true)))
        val saved = offline.refresh(selected, now.plusSeconds(1), force = true)
        assertEquals(ready.publication, saved.publication)
        assertEquals(ready.siteBinding, saved.siteBinding)
        assertEquals(InternetScheduleStatus.UNAVAILABLE, saved.status)
        assertFalse(saved.sourceNeedsVerification)
    }

    @Test fun `fresh conflict in old accepted A does not block the first explicit confirmation of valid B`() = runBlocking {
        val cache = Cache(); val a = candidate(); val b = a.copy(providerObjectId = "43", sourceUrl = "https://new-own.example/")
        val resolver = Resolver(ScheduleCandidateDiscovery(listOf(a, b)))
        val calls = mutableListOf<String>()
        val repo = InternetScheduleRepository(ScheduleTransport { url -> calls += url
            ScheduleResponse(url, html.toByteArray(), "text/html")
        }, cache, siteResolver = resolver)
        repo.refresh(selected, now)
        assertEquals(source, repo.confirmCandidate(selected, a.id, now).publication?.website)
        calls.clear()
        resolver.result = ScheduleCandidateDiscovery(listOf(a.copy(address = "Осенняя улица, 33 к3",
            outcome = ScheduleIdentityOutcome.CONFLICT), b))
        val ready = repo.confirmCandidate(selected, b.id, now.plusSeconds(60))
        assertEquals(InternetScheduleStatus.READY, ready.status)
        assertEquals(listOf(b.sourceUrl), calls)
        assertEquals(b.sourceUrl, ready.website)
        assertEquals(b.id, ready.siteBinding?.candidateConfirmation?.candidate?.id)
        assertFalse(ready.sourceNeedsVerification)
        assertEquals(ready, repo.cached(selected))
    }

    @Test fun `known wrong house cannot be confirmed or bypassed by the URL editor`() = runBlocking {
        val blocked = candidate("Осенняя улица, 33 к3", ScheduleIdentityOutcome.CONFLICT)
        assertTrue(blocked.validFor(selected))
        assertFalse(blocked.canConfirm(selected))
        val cache = Cache(); val calls = mutableListOf<String>()
        val repo = InternetScheduleRepository(transport(calls = calls), cache,
            siteResolver = Resolver(ScheduleCandidateDiscovery(listOf(blocked))))
        val pending = repo.refresh(selected, now)
        assertEquals(pending, repo.confirmCandidate(selected, blocked.id, now))
        val manual = repo.refresh(selected, now, force = true, sourceOverride = source + "schedule/")
        assertNull(manual.publication)
        assertTrue(manual.sourceNeedsVerification)
        assertTrue(calls.isEmpty())
        assertTrue(repo.cached(selected).sourceNeedsVerification)
        assertFalse(blocked.copy(outcome = ScheduleIdentityOutcome.INSUFFICIENT).validFor(selected))
    }

    @Test fun `incomplete refresh cannot erase an old house conflict merely by omitting its address or feature`() {
        val blocked = candidate("Осенняя улица, 33 к3", ScheduleIdentityOutcome.CONFLICT)
        val weakened = candidate("")
        assertEquals(ScheduleIdentityOutcome.CONFLICT, mergeCandidateEvidence(selected, listOf(blocked), listOf(weakened)).single().outcome)
        assertEquals(blocked, mergeCandidateEvidence(selected, listOf(blocked), emptyList()).single())
    }

    @Test fun `denial is persisted and never activates a publication after recreation`() = runBlocking {
        val candidate = candidate(); val cache = Cache()
        val resolver = Resolver(ScheduleCandidateDiscovery(listOf(candidate)))
        val repo = InternetScheduleRepository(transport(), cache, siteResolver = resolver)
        repo.refresh(selected, now)
        val denied = repo.dismissCandidate(selected, candidate.id)
        val recreated = InternetScheduleRepository(ScheduleTransport { error("Denied candidate cannot import") }, cache, siteResolver = resolver)
        assertEquals(listOf(candidate.id), recreated.cached(selected).dismissedCandidateIds)
        assertEquals(denied, recreated.confirmCandidate(selected, candidate.id, now))
        assertNull(recreated.cached(selected).publication)
    }

    @Test fun `fingerprint changes and malformed consent cannot reopen accepted events`() = runBlocking {
        val candidate = candidate(); val cache = Cache()
        val repo = InternetScheduleRepository(transport(), cache, siteResolver = Resolver(ScheduleCandidateDiscovery(listOf(candidate))))
        repo.refresh(selected, now); repo.confirmCandidate(selected, candidate.id, now)
        for (changed in listOf(selected.copy(name = "Храм Петра и Павла"), selected.copy(address = "Осенняя улица, 33 с1"),
            selected.copy(latitude = selected.latitude!! + .0001), selected.copy(timezone = "Europe/Samara"))) {
            val state = repo.cached(changed)
            assertNull(state.publication)
            assertTrue(state.sourceCandidates.isEmpty())
        }
        val original = cache.values.getValue(selected.id)
        val json = JsonParser.parseString(original).asJsonObject
        json.getAsJsonObject("siteBinding").getAsJsonObject("candidateConfirmation").addProperty("type", "EXACT_MAP_LINK")
        cache.values[selected.id] = json.toString()
        assertNull(repo.cached(selected).publication)
        assertTrue(repo.cached(selected).sourceNeedsVerification)
    }

    @Test fun `a later observed identity conflict prevents new rows and invalidates the cached acceptance`() = runBlocking {
        val candidate = candidate(); val cache = Cache(); val calls = mutableListOf<String>()
        val resolver = Resolver(ScheduleCandidateDiscovery(listOf(candidate)))
        val repo = InternetScheduleRepository(transport(calls = calls), cache, siteResolver = resolver)
        repo.refresh(selected, now); repo.confirmCandidate(selected, candidate.id, now)
        resolver.result = ScheduleCandidateDiscovery(listOf(this@ScheduleCandidateRecoveryTest.candidate("Осенняя улица, 33 с1", ScheduleIdentityOutcome.CONFLICT)))
        val blocked = repo.refresh(selected, now.plusSeconds(60), force = true)
        assertNull(blocked.publication)
        assertTrue(blocked.sourceNeedsVerification)
        assertEquals(1, calls.size)
        assertNull(repo.cached(selected).publication)
    }

    @Test fun `an unavailable check cannot confirm stale suggestions or start an import`() = runBlocking {
        val candidate = candidate(); val cache = Cache(); val resolver = Resolver(ScheduleCandidateDiscovery(listOf(candidate)))
        val repo = InternetScheduleRepository(ScheduleTransport { error("No activation before a fresh check") }, cache, siteResolver = resolver)
        repo.refresh(selected, now)
        resolver.result = ScheduleCandidateDiscovery(unavailable = true)
        val failed = repo.confirmCandidate(selected, candidate.id, now.plusSeconds(60))
        assertNull(failed.publication)
        assertNull(failed.siteBinding)
        assertTrue(failed.candidateSearchUnavailable)
        assertEquals(candidate, failed.sourceCandidates.single())
    }

    @Test fun `parked HTTP 200 is not READY after the source was explicitly confirmed`() = runBlocking {
        val candidate = candidate(); val cache = Cache()
        val repo = InternetScheduleRepository(transport("<html><body>Домен зарегистрирован и припаркован</body></html>"), cache,
            siteResolver = Resolver(ScheduleCandidateDiscovery(listOf(candidate))))
        repo.refresh(selected, now)
        val state = repo.confirmCandidate(selected, candidate.id, now)
        assertEquals(InternetScheduleStatus.UNSUPPORTED, state.status)
        assertNull(state.publication)
        assertEquals(ScheduleBindingMethod.USER_CONFIRMED, state.siteBinding?.method)
    }

    @Test fun `observed cancellation remains authoritative after a later offline refresh`() = runBlocking {
        val candidate = candidate(); val cache = Cache(); var content = html; var offline = false
        val resolver = Resolver(ScheduleCandidateDiscovery(listOf(candidate)))
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            if (offline) throw IOException("offline")
            ScheduleResponse(url, content.toByteArray(), "text/html")
        }, cache, siteResolver = resolver)
        repo.refresh(selected, now); repo.confirmCandidate(selected, candidate.id, now)
        content = html.replace("Литургия", "Литургия отменена")
        val cancelled = repo.refresh(selected, now.plusSeconds(60), force = true)
        assertNull(cancelled.publication)
        assertTrue(cancelled.publicationRejected)
        offline = true
        val failed = repo.refresh(selected, now.plusSeconds(120), force = true)
        assertNull(failed.publication)
        assertTrue(failed.publicationRejected)
        assertNull(repo.cached(selected).publication)
    }

    @Test fun `confirmation binds the exact approved URL and cannot be recoded as another source`() = runBlocking {
        val candidate = candidate(); val cache = Cache()
        val repo = InternetScheduleRepository(transport(), cache, siteResolver = Resolver(ScheduleCandidateDiscovery(listOf(candidate))))
        repo.refresh(selected, now); repo.confirmCandidate(selected, candidate.id, now)
        val json = JsonParser.parseString(cache.values.getValue(selected.id)).asJsonObject
        json.getAsJsonObject("siteBinding").getAsJsonObject("candidateConfirmation")
            .getAsJsonObject("candidate").addProperty("sourceUrl", "https://different.example/")
        cache.values[selected.id] = json.toString()
        val restored = repo.cached(selected)
        assertNull(restored.publication)
        assertTrue(restored.sourceNeedsVerification)
        assertNull(restored.siteBinding)
    }


    @Test fun `confirmed alternative cannot resurrect a service withdrawn by the accepted source across recreation`() = runBlocking {
        val accepted = candidate()
        val alternative = accepted.copy(providerObjectId = "43", sourceUrl = "https://stale.example/",
            evidenceUrl = "https://index.example/church/43")
        val cache = Cache(); var acceptedContent = html; val fetched = mutableListOf<String>()
        val resolver = Resolver(ScheduleCandidateDiscovery(listOf(accepted, alternative)))
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            fetched += url
            ScheduleResponse(url, (if (url == source) acceptedContent else html).toByteArray(), "text/html")
        }, cache, siteResolver = resolver)
        repo.refresh(selected, now)
        val ready = repo.confirmCandidate(selected, accepted.id, now)
        assertNotNull(ready.publication)
        acceptedContent = html.replace("Литургия", "Литургия отменена")
        val withdrawn = repo.refresh(selected, now.plusSeconds(60), force = true)
        assertTrue(withdrawn.sourceHasWithdrawals)
        assertTrue(withdrawn.publicationRejected)
        assertNull(withdrawn.publication)

        val blocked = repo.confirmCandidate(selected, alternative.id, now.plusSeconds(120))
        assertTrue(blocked.sourceHasWithdrawals)
        assertTrue(blocked.publicationRejected)
        assertEquals(withdrawn.siteBinding, blocked.siteBinding)
        assertEquals(withdrawn.website, blocked.website)
        assertNull(blocked.publication)
        assertFalse("The stale alternative is not allowed to supply events", alternative.sourceUrl in fetched)

        val manual = repo.refresh(selected, now.plusSeconds(121), force = true, sourceOverride = alternative.sourceUrl)
        assertTrue(manual.sourceHasWithdrawals)
        assertTrue(manual.publicationRejected)
        assertEquals(withdrawn.siteBinding, manual.siteBinding)
        assertEquals(ScheduleSourceChangeBlock.HISTORY, manual.sourceChangeBlock)
        assertNull(manual.publication)
        assertFalse("URL entry cannot bypass the same withdrawal", alternative.sourceUrl in fetched)
        assertEquals(ScheduleSourceChangeBlock.HISTORY, repo.cached(selected).sourceChangeBlock)

        val offline = InternetScheduleRepository(ScheduleTransport { throw IOException("offline") }, cache,
            siteResolver = Resolver(ScheduleCandidateDiscovery(unavailable = true)))
        val restored = offline.refresh(selected, now.plusSeconds(180), force = true)
        assertTrue(restored.sourceHasWithdrawals)
        assertTrue(restored.publicationRejected)
        assertNull(restored.publication)
        assertEquals(withdrawn.siteBinding, restored.siteBinding)
        assertNull(offline.cached(selected).publication)
    }

    @Test fun `fresh conflict in a different unaccepted candidate preserves accepted publication and proof offline`() = runBlocking {
        for (otherUrl in listOf("https://other.example/", source + "other-church/")) {
            val accepted = candidate()
            val alternative = accepted.copy(providerObjectId = "43", sourceUrl = otherUrl,
                evidenceUrl = "https://index.example/church/43")
            val cache = Cache(); val fetched = mutableListOf<String>()
            val resolver = Resolver(ScheduleCandidateDiscovery(listOf(accepted, alternative)))
            val repo = InternetScheduleRepository(transport(calls = fetched), cache, siteResolver = resolver)
            repo.refresh(selected, now)
            val ready = repo.confirmCandidate(selected, accepted.id, now)
            val conflictingAlternative = alternative.copy(address = "Осенняя улица, 33 к3", outcome = ScheduleIdentityOutcome.CONFLICT)
            resolver.result = ScheduleCandidateDiscovery(listOf(accepted, conflictingAlternative))
            val failed = repo.confirmCandidate(selected, alternative.id, now.plusSeconds(60))
            assertEquals(ready.publication, failed.publication)
            assertEquals(ready.siteBinding, failed.siteBinding)
            assertEquals(ready.website, failed.website)
            assertFalse(failed.sourceNeedsVerification)
            assertEquals(ScheduleIdentityOutcome.CONFLICT, failed.sourceCandidates.single { it.id == alternative.id }.outcome)
            assertEquals(listOf(source), fetched)

            val manual = repo.refresh(selected, now.plusSeconds(61), force = true, sourceOverride = otherUrl)
            assertEquals(ready.publication, manual.publication)
            assertEquals(ready.siteBinding, manual.siteBinding)
            assertFalse(manual.sourceNeedsVerification)
            assertEquals(ScheduleSourceChangeBlock.CONFLICT, manual.sourceChangeBlock)
            assertEquals(listOf(source), fetched)

            val offline = InternetScheduleRepository(ScheduleTransport { throw IOException("offline") }, cache, siteResolver = resolver)
            assertEquals(ready.publication, offline.cached(selected).publication)
            val restored = offline.refresh(selected, now.plusSeconds(120), force = true)
            assertEquals(ready.publication, restored.publication)
            assertEquals(ready.siteBinding, restored.siteBinding)
            assertFalse(restored.sourceNeedsVerification)
        }
    }

    @Test fun `legacy URL editor cannot clear withdrawal history without any saved suggestions`() = runBlocking {
        val parish = selected.copy(website = source)
        val cache = Cache()
        var content = html
        val fetched = mutableListOf<String>()
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            fetched += url
            ScheduleResponse(url, (if (url == source) content else html).toByteArray(), "text/html")
        }, cache, siteResolver = ScheduleSiteResolver { null })
        assertNotNull(repo.refresh(parish, now, force = true).publication)
        content = html.replace("Литургия", "Литургия отменена")
        val cancelled = repo.refresh(parish, now.plusSeconds(60), force = true)
        assertTrue(cancelled.sourceHasWithdrawals)
        assertTrue(cancelled.sourceCandidates.isEmpty())
        val beforeRequests = fetched.size
        val blocked = repo.refresh(parish, now.plusSeconds(121), force = true, sourceOverride = "https://stale.example/")
        assertNull(blocked.publication)
        assertTrue(blocked.publicationRejected)
        assertTrue(blocked.sourceHasWithdrawals)
        assertEquals(source, blocked.website)
        assertEquals(beforeRequests, fetched.size)
        assertEquals(ScheduleSourceChangeBlock.HISTORY, repo.cached(parish).sourceChangeBlock)
        val retried = repo.refresh(parish, now.plusSeconds(180), force = true, sourceOverride = source)
        assertNull(retried.publication)
        assertTrue(retried.sourceHasWithdrawals)
        assertEquals("", retried.customSource)
        assertEquals(ScheduleSourceChangeBlock.NONE, retried.sourceChangeBlock)
    }

    @Test fun `a conflicting church on a shared host does not block a different manually accepted source`() = runBlocking {
        val accepted = candidate()
        val alternative = accepted.copy(providerObjectId = "43", sourceUrl = source + "other-church/",
            evidenceUrl = "https://index.example/church/43")
        val cache = Cache(); val fetched = mutableListOf<String>()
        val resolver = Resolver(ScheduleCandidateDiscovery(listOf(accepted, alternative)))
        val repo = InternetScheduleRepository(transport(calls = fetched), cache, siteResolver = resolver)
        repo.refresh(selected, now)
        val ready = repo.refresh(selected, now, force = true, sourceOverride = source)
        assertNotNull(ready.publication)
        resolver.result = ScheduleCandidateDiscovery(listOf(accepted,
            alternative.copy(address = "Осенняя улица, 33 к3", outcome = ScheduleIdentityOutcome.CONFLICT)))
        repo.confirmCandidate(selected, alternative.id, now.plusSeconds(60))
        val rejected = repo.refresh(selected, now.plusSeconds(61), force = true, sourceOverride = alternative.sourceUrl)
        assertEquals(ready.publication, rejected.publication)
        for (alias in listOf(source + "a/../other-church/", source + "a/%2e%2e/other-church/")) {
            val aliased = repo.refresh(selected, now.plusSeconds(62), force = true, sourceOverride = alias)
            assertEquals(ready.publication, aliased.publication)
            assertEquals(ready.siteBinding, aliased.siteBinding)
            assertEquals(ScheduleSourceChangeBlock.CONFLICT, aliased.sourceChangeBlock)
            assertEquals(listOf(source), fetched)
        }
        val refreshed = repo.refresh(selected, now.plusSeconds(120), force = true)
        assertEquals(InternetScheduleStatus.READY, refreshed.status)
        assertNotNull(refreshed.publication)
        assertEquals(listOf(source, source), fetched)
        assertEquals(ScheduleSourceChangeBlock.NONE, refreshed.sourceChangeBlock)
    }

    @Test fun `known wrong house cannot be reached by adding or removing its trailing slash`() = runBlocking {
        for (withSlash in listOf(false, true)) {
            val blockedUrl = source + "other-church" + if (withSlash) "/" else ""
            val requested = source + "other-church" + if (withSlash) "" else "/"
            val blocked = candidate("Осенняя улица, 33 к3", ScheduleIdentityOutcome.CONFLICT).copy(sourceUrl = blockedUrl)
            assertTrue(blocked.validFor(selected))
            val cache = Cache(); val fetched = mutableListOf<String>()
            val repo = InternetScheduleRepository(transport(calls = fetched), cache, siteResolver = ScheduleSiteResolver { null })
            val ready = repo.refresh(selected, now, force = true, sourceOverride = source)
            assertNotNull(ready.publication)
            cache.write(selected.id, ScheduleCacheCodec.encode(ready.copy(sourceCandidates = listOf(blocked),
                candidateSearchIdentity = ScheduleEntityIdentity.fingerprint(selected))))
            val rejected = repo.refresh(selected, now.plusSeconds(60), force = true, sourceOverride = requested)
            assertEquals(listOf(source), fetched)
            assertEquals(ready.publication, rejected.publication)
            assertEquals(ready.siteBinding, rejected.siteBinding)
            assertEquals(ready.customSource, rejected.customSource)
            assertEquals(ScheduleSourceChangeBlock.CONFLICT, rejected.sourceChangeBlock)
            assertFalse(rejected.sourceNeedsVerification)
            assertEquals(rejected, repo.cached(selected))
        }
    }

    @Test fun `initial PROVED source is not rejected by another conflicting church on the same host`() = runBlocking {
        val own = source + "own-church/"
        val blocked = candidate("Осенняя улица, 33 к3", ScheduleIdentityOutcome.CONFLICT).copy(sourceUrl = source + "other-church/")
        val binding = ScheduleEntityIdentity.bind(selected, own, "https://index.example/own-proof", selected.name,
            ScheduleBindingMethod.EXACT_MAP_LINK)
        assertTrue(binding.validFor(selected, own, binding.evidenceUrl))
        val resolver = object : ScheduleSiteResolver {
            override suspend fun resolve(parish: Parish): ScheduleSite? = null
            override suspend fun discover(parish: Parish, excludedUrls: Set<String>) = ScheduleSourceDiscovery(
                ScheduleIdentityOutcome.PROVED, ScheduleSite(own, binding.evidenceUrl, selected.name, binding),
                ScheduleCandidateDiscovery(listOf(blocked)))
        }
        val fetched = mutableListOf<String>()
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            fetched += url
            check(url == own)
            ScheduleResponse(url, html.toByteArray(), "text/html")
        }, Cache(), siteResolver = resolver)
        val ready = repo.refresh(selected, now)
        assertEquals(listOf(own), fetched)
        assertEquals(InternetScheduleStatus.READY, ready.status)
        assertEquals(own, ready.publication?.website)
        assertEquals(binding, ready.siteBinding)
        assertEquals(listOf(blocked), ready.sourceCandidates)
        assertFalse(ready.sourceNeedsVerification)
        assertEquals(ready, repo.cached(selected))
    }

    @Test fun `fallback PROVED path is not rejected by another conflicting church on its shared host`() = runBlocking {
        val obsolete = source + "obsolete/"
        val own = source + "own-church/"
        val parish = selected.copy(website = obsolete)
        val blocked = candidate("Осенняя улица, 33 к3", ScheduleIdentityOutcome.CONFLICT).copy(sourceUrl = source + "other-church/")
        val binding = ScheduleEntityIdentity.bind(parish, own, "https://index.example/own-proof", parish.name,
            ScheduleBindingMethod.EXACT_MAP_LINK)
        val excluded = mutableListOf<Set<String>>()
        val resolver = object : ScheduleSiteResolver {
            override suspend fun resolve(parish: Parish): ScheduleSite? = null
            override suspend fun discover(parish: Parish, excludedUrls: Set<String>): ScheduleSourceDiscovery {
                excluded += excludedUrls
                return ScheduleSourceDiscovery(ScheduleIdentityOutcome.PROVED,
                    ScheduleSite(own, binding.evidenceUrl, parish.name, binding), ScheduleCandidateDiscovery(listOf(blocked)))
            }
        }
        val fetched = mutableListOf<String>()
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            fetched += url
            check(url == obsolete || url == own)
            ScheduleResponse(url, (if (url == own) html else "<h1>Архив</h1>").toByteArray(), "text/html")
        }, Cache(), siteResolver = resolver)
        val ready = repo.refresh(parish, now)
        assertEquals(listOf(obsolete, own), fetched)
        assertEquals(1, excluded.size)
        assertTrue(obsolete in excluded.single())
        assertEquals(InternetScheduleStatus.READY, ready.status)
        assertEquals(own, ready.publication?.website)
        assertEquals(binding, ready.siteBinding)
        assertEquals(listOf(blocked), ready.sourceCandidates)
        assertFalse(ready.sourceNeedsVerification)
        assertEquals(ready, repo.cached(parish))
    }

    @Test fun `available fresh candidate can activate and recheck when another lookup made the aggregate unavailable`() = runBlocking {
        val candidate = candidate(); val cache = Cache(); val fetched = mutableListOf<String>()
        val resolver = Resolver(ScheduleCandidateDiscovery(listOf(candidate), listOf("initial"), incomplete = false))
        var content = html
        val repo = InternetScheduleRepository(ScheduleTransport { url ->
            fetched += url
            check(url == source)
            ScheduleResponse(url, content.toByteArray(), "text/html")
        }, cache, siteResolver = resolver)
        val pending = repo.refresh(selected, now)
        assertEquals(listOf(candidate), pending.sourceCandidates)
        assertFalse(pending.candidateSearchUnavailable)
        assertTrue(fetched.isEmpty())
        resolver.result = ScheduleCandidateDiscovery(listOf(candidate), listOf("42", "84"), incomplete = true, unavailable = true)
        val ready = repo.confirmCandidate(selected, candidate.id, now.plusSeconds(60))
        assertEquals(InternetScheduleStatus.READY, ready.status)
        assertEquals(ScheduleBindingMethod.USER_CONFIRMED, ready.siteBinding?.method)
        assertEquals(listOf("09:00"), ready.publication?.events?.map { it.time })
        assertEquals(listOf(source), fetched)
        assertTrue(ready.candidateSearchUnavailable)
        assertTrue(ready.candidateSearchIncomplete)
        assertEquals(listOf("42", "84"), ready.candidateObservedIds)
        content = html.replace("09:00", "10:00")
        resolver.result = ScheduleCandidateDiscovery(listOf(candidate), listOf("42", "85"), incomplete = false, unavailable = true)
        val refreshed = repo.refresh(selected, now.plusSeconds(120), force = true)
        assertEquals(InternetScheduleStatus.READY, refreshed.status)
        assertEquals(listOf("10:00"), refreshed.publication?.events?.map { it.time })
        assertEquals(now.plusSeconds(120).toString(), refreshed.publication?.fetchedAt)
        assertEquals(listOf(source, source), fetched)
        assertTrue(refreshed.candidateSearchUnavailable)
        assertFalse(refreshed.candidateSearchIncomplete)
        assertEquals(listOf("42", "85"), refreshed.candidateObservedIds)
        assertFalse(refreshed.sourceNeedsVerification)
        assertEquals(refreshed, repo.cached(selected))
    }

    private fun candidateWithAcceptedContact() = candidate("Осенняя улица, 33 к3").copy(
        currentContactAddress = selected.address, currentContactUrl = source + "contacts/",
        currentContactSha256 = "b".repeat(64), linkedEmail = "office@parish.example")

    private fun unavailableContactRow(accepted: ScheduleSourceCandidate) = accepted.copy(address = selected.address,
        currentContactAddress = "", currentContactUrl = "", currentContactSha256 = "", linkedEmail = "",
        verificationUnavailable = true, checkedAt = now.plusSeconds(60).toString())

    @Test fun `stale merged contact cannot turn a fresh verificationUnavailable row into activation`() = runBlocking {
        val accepted = candidateWithAcceptedContact()
        val unavailable = unavailableContactRow(accepted)
        assertTrue(accepted.canConfirm(selected))
        assertTrue(unavailable.validFor(selected))
        assertFalse(unavailable.canConfirm(selected))
        assertTrue(mergeCandidateEvidence(selected, listOf(accepted), listOf(unavailable)).single().canConfirm(selected))
        val resolver = Resolver(ScheduleCandidateDiscovery(listOf(accepted)))
        val fetched = mutableListOf<String>()
        val repo = InternetScheduleRepository(transport(calls = fetched), Cache(), siteResolver = resolver)
        repo.refresh(selected, now)
        resolver.result = ScheduleCandidateDiscovery(listOf(unavailable))
        val failed = repo.confirmCandidate(selected, accepted.id, now.plusSeconds(60))
        assertTrue(fetched.isEmpty())
        assertNull(failed.publication)
        assertNull(failed.siteBinding)
        assertEquals(InternetScheduleStatus.UNAVAILABLE, failed.status)
        assertEquals(accepted, failed.sourceCandidates.single())
        assertEquals(failed, repo.cached(selected))
    }

    @Test fun `stale merged contact on recheck preserves saved events and cannot import newly fetched rows`() = runBlocking {
        val accepted = candidateWithAcceptedContact()
        val unavailable = unavailableContactRow(accepted)
        val resolver = Resolver(ScheduleCandidateDiscovery(listOf(accepted)))
        val fetched = mutableListOf<String>(); val cache = Cache()
        val repo = InternetScheduleRepository(transport(calls = fetched), cache, siteResolver = resolver)
        repo.refresh(selected, now)
        val ready = repo.confirmCandidate(selected, accepted.id, now)
        assertNotNull(ready.publication)
        assertEquals(ScheduleBindingMethod.USER_CONFIRMED, ready.siteBinding?.method)
        resolver.result = ScheduleCandidateDiscovery(listOf(unavailable))
        val failed = repo.refresh(selected, now.plusSeconds(60), force = true)
        assertEquals(listOf(source), fetched)
        assertEquals(InternetScheduleStatus.UNAVAILABLE, failed.status)
        assertEquals(ready.publication, failed.publication)
        assertEquals(ready.siteBinding, failed.siteBinding)
        assertEquals(ready.publication?.fetchedAt, failed.publication?.fetchedAt)
        assertFalse(failed.sourceNeedsVerification)
        assertEquals(failed, repo.cached(selected))
    }

    @Test fun `empty unavailable discovery and thrown discovery both remain errors instead of a successful empty search`() = runBlocking {
        val throwsDuringDiscovery = object : ScheduleSiteResolver {
            override suspend fun resolve(parish: Parish): ScheduleSite? = null
            override suspend fun discover(parish: Parish, excludedUrls: Set<String>): ScheduleSourceDiscovery {
                throw IOException("index unavailable")
            }
        }
        for (resolver in listOf(Resolver(ScheduleCandidateDiscovery(unavailable = true)), throwsDuringDiscovery)) {
            val repo = InternetScheduleRepository(ScheduleTransport { error("There is no accepted source to import") }, Cache(), siteResolver = resolver)
            val failed = repo.refresh(selected, now)
            assertEquals(InternetScheduleStatus.UNAVAILABLE, failed.status)
            assertTrue(failed.candidateSearchUnavailable)
            assertTrue(failed.sourceCandidates.isEmpty())
            assertNull(failed.publication)
            assertEquals("Не удалось проверить источники расписания. Повторите поиск",
                ru.liturgyguide.ui.scheduleEmptyDescription(failed, hasWebsite = false))
            assertTrue(repo.cached(selected).candidateSearchUnavailable)
        }
    }

    @Test fun `completed empty discovery is distinct from an unavailable provider`() = runBlocking {
        val repo = InternetScheduleRepository(ScheduleTransport { error("There is no website") }, Cache(),
            siteResolver = Resolver(ScheduleCandidateDiscovery(unavailable = false)))
        val empty = repo.refresh(selected, now)
        assertEquals(InternetScheduleStatus.NO_WEBSITE, empty.status)
        assertFalse(empty.candidateSearchUnavailable)
        assertFalse(ru.liturgyguide.ui.scheduleEmptyDescription(empty, hasWebsite = false).contains("Не удалось проверить"))
    }

}

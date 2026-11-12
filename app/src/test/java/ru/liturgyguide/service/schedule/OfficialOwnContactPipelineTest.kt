package ru.liturgyguide.service.schedule

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OfficialOwnContactPipelineTest {
    private val f = OfficialOwnContactFixtures

    @Test fun `named actual map building without website discovers imports and survives cold offline cache`() = runBlocking<Unit> {
        val selected = f.nikolsky()
        val calls = mutableListOf<String>()
        val cache = OfficialOwnContactFixtures.Cache()
        val repository = InternetScheduleRepository(ScheduleTransport { url -> calls += url; f.response(url) }, cache)
        assertEquals("", selected.website)
        val ready = repository.refresh(selected, f.now, force = true)
        assertEquals(InternetScheduleStatus.READY, ready.status)
        assertEquals("", ready.configuredWebsite)
        assertEquals(f.home, ready.website)
        assertEquals(f.contact, ready.siteEvidenceUrl)
        assertEquals(f.schedule, ready.sourceUrl)
        assertEquals(ScheduleBindingMethod.DIRECTORY_OWN_CONTACT, ready.siteBinding?.method)
        val proof = requireNotNull(ready.siteBinding?.officialContactProof)
        assertEquals("t36237", proof.cardId)
        assertEquals(OfficialContactLayout.CONTACT_PAGE, proof.layout)
        assertTrue(requireNotNull(ready.siteBinding).validFor(selected, ready.website, ready.siteEvidenceUrl))
        assertFalse(ready.sourceNeedsVerification)
        assertEquals(listOf("2026-11-11T07:00", "2026-11-11T17:00", "2026-11-12T07:00", "2026-11-12T17:00",
            "2026-11-13T07:00", "2026-11-13T17:00", "2026-11-14T07:00", "2026-11-14T17:00", "2026-11-15T06:00",
            "2026-11-15T08:00", "2026-11-15T17:00"), requireNotNull(ready.publication).events.map { "${it.date}T${it.time}" })
        assertEquals(1, calls.count { it == f.profile.url })
        assertEquals(1, calls.count { it == f.httpHome })
        assertEquals(1, calls.count { it == f.home }) // Existing import reads the proved homepage once more.
        assertEquals(1, calls.count { it == f.contact })
        assertEquals(1, calls.count { it == f.schedule })
        val encoded = requireNotNull(cache.read(selected.id))
        assertTrue(encoded.length < 200_000)
        assertFalse(encoded.contains("<html"))
        assertEquals(ready, ScheduleCacheCodec.decode(encoded, selected))
        var offlineCalls = 0
        val offline = InternetScheduleRepository(ScheduleTransport { offlineCalls++; throw IOException("offline") }, cache)
        assertEquals(ready, offline.cached(selected))
        assertEquals(ready, offline.refresh(selected, f.now.plusSeconds(90)))
        assertEquals(0, offlineCalls)
        // Contact identity has no new TTL that discards an otherwise valid offline publication.
        val oldProof = proof.copy(directoryFetchedAt = f.now.minusSeconds(30 * 86_400L).toString(),
            checkedAt = f.now.minusSeconds(29 * 86_400L).toString())
        val oldIdentity = ready.copy(siteBinding = requireNotNull(ready.siteBinding).copy(officialContactProof = oldProof))
        assertEquals(oldIdentity, ScheduleCacheCodec.decode(ScheduleCacheCodec.encode(oldIdentity), selected))
        val changed = offline.cached(selected.copy(address = "Воронеж, улица Таранченко, 19"))
        assertTrue(changed.sourceNeedsVerification)
        assertNull(changed.publication)
    }

    @Test fun `missing or tampered proof requires verification and cannot downgrade under old method`() = runBlocking<Unit> {
        val selected = f.nikolsky()
        val ready = ready()
        val original = ScheduleCacheCodec.encode(ready)
        val mutations = linkedMapOf<String, (JsonObject) -> Unit>(
            "missing proof" to { it.remove("officialContactProof") },
            "null proof under old method" to { it.addProperty("method", "ARTICLE_SCOPE"); it.add("officialContactProof", null) },
            "wrong entity" to { it.proof().addProperty("publisherEntityId", "21964") },
            "wrong raw address" to { it.proof().addProperty("rawAddress", "Россия, г. Воронеж, ул. Таранченко, 19") },
            "wrong parsed address" to { it.proof().addProperty("house", "19") },
            "wrong dedication" to { it.proof().addProperty("ownName", "Храм Николая Японского г. Воронеж") },
            "wrong map dedication" to { it.proof().addProperty("mapName", "Храм Николая Чудотворца и Николая Японского") },
            "foreign evidence" to { it.proof().addProperty("evidenceFinalUrl", "https://other.cerkov.ru/kontakty/") },
            "malformed hash" to { it.proof().addProperty("evidenceSha256", "not-a-sha256") },
            "unknown layout" to { it.proof().addProperty("layout", "ARTICLE") },
            "new version" to { it.proof().addProperty("proofVersion", 999) },
            "invalid number" to { it.proof().addProperty("latitude", "NaN") },
            "oversized name" to { it.proof().addProperty("ownName", "я".repeat(301)) },
            "old method with bad proof" to { it.addProperty("method", "ARTICLE_SCOPE"); it.proof().addProperty("publisherEntityId", "21964") },
        )
        for ((reason, mutation) in mutations) {
            val changed = JsonParser.parseString(original).asJsonObject
            mutation(changed.getAsJsonObject("siteBinding"))
            val restored = requireNotNull(ScheduleCacheCodec.decode(changed.toString(), selected))
            assertTrue(reason, restored.sourceNeedsVerification)
            assertEquals(reason, InternetScheduleStatus.UNAVAILABLE, restored.status)
            assertNull(reason, restored.publication)
            assertNull(reason, restored.siteBinding)
            assertEquals(reason, f.home, restored.website)
            assertEquals(reason, f.schedule, restored.sourceUrl)
        }
    }

    @Test fun `cached map title address contradiction cannot keep an accepted own contact proof`() = runBlocking<Unit> {
        val selected = f.nikolsky()
        val ready = ready()
        val original = ScheduleCacheCodec.encode(ready)
        val originalName = requireNotNull(ready.siteBinding?.officialContactProof).mapName
        val house = "д. 19а"
        assertEquals(1, Regex(Regex.escape(house)).findAll(originalName).count())
        val accepted = mutableListOf<String>()
        val contradictions = listOf("д. 19", "д. 20", "д. 19а корпус 2").map { it to originalName.replace(house, it) } +
            listOf("переулок вместо улицы" to originalName.replace("ул. Таранченко", "пер. Таранченко"))
        for ((reason, wrongName) in contradictions) {
            val changed = JsonParser.parseString(original).asJsonObject
            val proof = changed.getAsJsonObject("siteBinding").proof()
            assertNotEquals(originalName, wrongName)
            proof.addProperty("mapName", wrongName)
            assertEquals(wrongName, proof["mapName"].asString)
            val restored = requireNotNull(ScheduleCacheCodec.decode(changed.toString(), selected))
            if (!restored.sourceNeedsVerification || restored.publication != null || restored.siteBinding != null)
                accepted += reason
            assertEquals(f.home, restored.website)
            assertEquals(f.schedule, restored.sourceUrl)
        }
        assertTrue("Conflicting cached map addresses accepted: $accepted", accepted.isEmpty())
    }

    @Test fun `old bindings without own contact proof still round trip unchanged`() = runBlocking<Unit> {
        val selected = f.nikolsky()
        val ready = ready()
        for (method in listOf(ScheduleBindingMethod.ARTICLE_SCOPE, ScheduleBindingMethod.EXACT_MAP_LINK)) {
            val old = ready.copy(siteBinding = requireNotNull(ready.siteBinding).copy(method = method, officialContactProof = null))
            val encoded = JsonParser.parseString(ScheduleCacheCodec.encode(old)).asJsonObject
            encoded.getAsJsonObject("siteBinding").remove("officialContactProof")
            val decoded = requireNotNull(ScheduleCacheCodec.decode(encoded.toString(), selected))
            assertEquals(old, decoded)
            assertFalse(decoded.sourceNeedsVerification)
        }
    }

    @Test fun `failed refresh keeps old accepted publication contact proof and evidence together`() = runBlocking<Unit> {
        val selected = f.nikolsky()
        val accepted = ready()
        val cache = OfficialOwnContactFixtures.Cache().apply { write(selected.id, ScheduleCacheCodec.encode(accepted)) }
        val calls = mutableListOf<String>()
        val repository = InternetScheduleRepository(ScheduleTransport { url -> calls += url; throw IOException("unavailable") }, cache)
        val failed = repository.refresh(selected, f.now.plusSeconds(60), force = true)
        assertEquals(InternetScheduleStatus.UNAVAILABLE, failed.status)
        assertEquals(accepted.publication, failed.publication)
        assertEquals(accepted.siteBinding, failed.siteBinding)
        assertEquals(accepted.website, failed.website)
        assertEquals(accepted.siteEvidenceUrl, failed.siteEvidenceUrl)
        assertFalse(failed.sourceNeedsVerification)
        assertEquals(1, calls.count { it == f.home })
        assertEquals(0, calls.count { it == f.contact })
        assertEquals(failed, repository.cached(selected))
    }

    @Test fun `failed alternative import cannot attach its binding to old own contact events`() = runBlocking<Unit> {
        val selected = f.nikolsky()
        val accepted = ready()
        val cache = OfficialOwnContactFixtures.Cache().apply { write(selected.id, ScheduleCacheCodec.encode(accepted)) }
        val alternativeHome = "https://other-parish.example/"
        val alternativeEvidence = "https://directory.example/another-card/"
        val alternative = ScheduleSite(alternativeHome, alternativeEvidence, selected.name,
            ScheduleEntityIdentity.bind(selected, alternativeHome, alternativeEvidence, selected.name, ScheduleBindingMethod.ARTICLE_SCOPE))
        var lookups = 0
        val calls = mutableListOf<String>()
        val resolver = object : ScheduleSiteResolver {
            override suspend fun resolve(parish: ru.liturgyguide.model.Parish): ScheduleSite? = error("Exclusions must be passed")
            override suspend fun resolve(parish: ru.liturgyguide.model.Parish, excludedUrls: Set<String>): ScheduleSite {
                lookups++
                assertTrue(f.home in excludedUrls)
                return alternative
            }
        }
        val repository = InternetScheduleRepository(ScheduleTransport { url -> calls += url; throw IOException("unavailable") },
            cache, siteResolver = resolver)
        val failed = repository.refresh(selected, f.now.plusSeconds(60), force = true)
        assertEquals(1, lookups)
        assertEquals(listOf(f.home, alternativeHome), calls)
        assertEquals(accepted.publication, failed.publication)
        assertEquals(accepted.siteBinding, failed.siteBinding)
        assertEquals(accepted.siteEvidenceUrl, failed.siteEvidenceUrl)
        assertEquals(accepted.website, failed.website)
    }

    @Test fun `withdrawal tombstones remain authoritative with valid and damaged contact proof`() = runBlocking<Unit> {
        val selected = f.nikolsky()
        val accepted = ready().copy(status = InternetScheduleStatus.UNSUPPORTED, publication = null,
            publicationRejected = true, sourceHasWithdrawals = true)
        for (damage in listOf(false, true)) {
            val encoded = JsonParser.parseString(ScheduleCacheCodec.encode(accepted)).asJsonObject
            if (damage) encoded.getAsJsonObject("siteBinding").remove("officialContactProof")
            val cache = OfficialOwnContactFixtures.Cache().apply { write(selected.id, encoded.toString()) }
            val calls = mutableListOf<String>()
            val repository = InternetScheduleRepository(ScheduleTransport { url -> calls += url; throw IOException("offline") }, cache)
            val cached = repository.cached(selected)
            assertEquals(damage, cached.sourceNeedsVerification)
            assertTrue(cached.publicationRejected)
            assertTrue(cached.sourceHasWithdrawals)
            val failed = repository.refresh(selected, f.now.plusSeconds(60), force = true)
            assertNull(failed.publication)
            assertTrue(failed.publicationRejected)
            assertTrue(failed.sourceHasWithdrawals)
            assertEquals(f.home, failed.website)
            if (damage) assertEquals(f.schedule, failed.sourceUrl)
            assertEquals(damage, failed.sourceNeedsVerification)
            if (!damage) {
                assertEquals(listOf(f.home), calls)
                assertEquals(accepted.siteBinding, failed.siteBinding)
            }
        }
    }

    private suspend fun ready(): InternetScheduleState {
        val cache = OfficialOwnContactFixtures.Cache()
        val state = InternetScheduleRepository(ScheduleTransport { f.response(it) }, cache).refresh(f.nikolsky(), f.now, force = true)
        assertEquals(InternetScheduleStatus.READY, state.status)
        return state
    }
    private fun JsonObject.proof() = getAsJsonObject("officialContactProof")
}

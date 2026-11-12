package ru.liturgyguide.service.schedule

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ParishService
import java.io.IOException
import java.time.Instant

class ScheduleSourceBindingTest {
    private val now = Instant.parse("2026-11-12T06:00:00Z")
    private val site = "https://church.example/raspisanie/"
    private val proof = "https://azbyka.ru/palomnik/index.php?curid=42"
    private val p = Parish("test:42", "Никольский храм", "Никольский", "Москва", "Садовая, 1", "", "",
        "Europe/Moscow", "", "", "", "", emptyList(), "", "", 55.7, 37.6)
    private val html = "<article data-day='2026-11-12'><li><time>08:00</time><span>Литургия</span></li></article>"
    private class Cache(var value: String?) : ScheduleCacheStore {
        override fun read(parishId: String) = value
        override fun write(parishId: String, value: String) { this.value = value }
    }
    private fun state(selected: Parish = p, rejected: Boolean = false, withdrawal: Boolean = false) =
        InternetScheduleState(selected.id, InternetScheduleStatus.READY, site, site, now.toString(),
            if (rejected) null else SchedulePublication(selected.id, selected.timezone, site, site, now.toString(),
                listOf(InternetService("2026-11-12", "08:00", "Литургия", "liturgy"))),
            configuredWebsite = selected.website, siteEvidenceUrl = proof, publicationRejected = rejected,
            sourceHasWithdrawals = withdrawal)
    private fun resolved(selected: Parish = p, url: String = site) = ScheduleSite(url, proof, selected.name,
        ScheduleEntityIdentity.bind(selected, url, proof, selected.name, ScheduleBindingMethod.ARTICLE_SCOPE))
    private fun legacy(value: InternetScheduleState): String = JsonParser.parseString(ScheduleCacheCodec.encode(value)).asJsonObject.apply {
        remove("siteBinding"); remove("sourceNeedsVerification")
    }.toString()

    @Test fun legacyFreshPublicationIsHiddenOfflineWithoutClaimingCancellationAndPreservesLink() {
        val cache = Cache(legacy(state()))
        val repo = InternetScheduleRepository(ScheduleTransport { error("No network on cached") }, cache)
        val pending = repo.cached(p)
        assertTrue(pending.sourceNeedsVerification)
        assertNull(pending.publication)
        assertFalse(pending.publicationRejected)
        assertEquals(site, pending.website)
        assertEquals("", pending.checkedAt)
        assertEquals(pending, repo.cached(p))
        val bundled = p.copy(schedule = listOf(ParishService("2026-11-12", "09:00", "Литургия", "liturgy")))
        assertTrue(bundled.withInternetSchedule(pending, now).schedule.isEmpty())
        assertTrue(bundled.withInternetSchedule(pending, now).scheduleNote.contains("пока не подтверждена"))
    }

    @Test fun firstLegacyRefreshBypassesSixHourShortcutAndNeverImportsOldUrlWithoutProof() = runBlocking {
        for (force in listOf(false, true)) for (timeout in listOf(false, true)) {
            val cache = Cache(legacy(state()))
            var lookups = 0; var imports = 0
            val repo = InternetScheduleRepository(ScheduleTransport { imports++; error("Unverified URL") }, cache,
                siteResolver = ScheduleSiteResolver { lookups++; if (timeout) throw java.net.SocketTimeoutException(); null })
            val result = repo.refresh(p, now.plusSeconds(60), force)
            assertEquals(1, lookups); assertEquals(0, imports)
            assertTrue(result.sourceNeedsVerification); assertNull(result.publication)
            assertEquals(site, result.website)
            assertEquals(result, repo.cached(p))
            repo.refresh(p, now.plusSeconds(61))
            assertEquals("Failed verification uses the normal five-minute retry interval", 1, lookups)
        }
    }

    @Test fun successfulReverificationBindsIdentityAndSurvivesOfflineRecreation() = runBlocking {
        val cache = Cache(legacy(state()))
        var lookups = 0
        val repo = InternetScheduleRepository(ScheduleTransport { ScheduleResponse(it, html.toByteArray(), "text/html") }, cache,
            siteResolver = ScheduleSiteResolver { lookups++; resolved(it) })
        val ready = repo.refresh(p, now.plusSeconds(60))
        assertEquals(1, lookups); assertFalse(ready.sourceNeedsVerification)
        assertEquals(InternetScheduleStatus.READY, ready.status)
        assertNotNull(ready.publication); assertNotNull(ready.siteBinding)
        val offline = InternetScheduleRepository(ScheduleTransport { throw IOException() }, cache,
            siteResolver = ScheduleSiteResolver { null })
        assertEquals(ready, offline.cached(p))
        val failed = offline.refresh(p, now.plusSeconds(120), true)
        assertEquals(ready.publication, failed.publication)
        assertEquals(ready.siteBinding, failed.siteBinding)
        assertEquals(failed, offline.cached(p))
    }

    @Test fun changedSelectedEntityOrAddressInvalidatesProofButFormattingAndWebsiteDoNotChangeFingerprint() {
        val binding = resolved().binding!!
        assertEquals(binding.selectedIdentity, ScheduleEntityIdentity.fingerprint(p.copy(name = "  НИКОЛЬСКИЙ  ХРАМ ", shortName = "Иное", website = site)))
        val cache = Cache(ScheduleCacheCodec.encode(state().copy(siteBinding = binding)))
        val repo = InternetScheduleRepository(ScheduleTransport { error("Offline") }, cache)
        assertFalse(repo.cached(p).sourceNeedsVerification)
        for (selected in listOf(p.copy(name = "Никольский монастырь"), p.copy(address = "Садовая, 2"), p.copy(latitude = 55.8))) {
            assertTrue(repo.cached(selected).sourceNeedsVerification)
            assertNull(repo.cached(selected).publication)
        }
    }

    @Test fun legacyAndNewWithdrawalsCannotBeRevivedFromAlternativeOriginOrAfterFailure() = runBlocking {
        for (legacy in listOf(true, false)) for (full in listOf(true, false)) {
            val accepted = state(rejected = full, withdrawal = true).let { if (legacy) it else it.copy(siteBinding = resolved().binding) }
            val cache = Cache(if (legacy) legacy(accepted) else ScheduleCacheCodec.encode(accepted))
            var imports = 0
            val repo = InternetScheduleRepository(ScheduleTransport { imports++; throw IOException() }, cache,
                siteResolver = ScheduleSiteResolver { resolved(it, "https://alternative.example/") })
            val failed = repo.refresh(p, now.plusSeconds(60), true)
            assertEquals(if (legacy) 0 else 1, imports)
            assertTrue(failed.sourceHasWithdrawals)
            assertEquals(full, failed.publicationRejected)
            if (legacy || full) assertNull(failed.publication)
            else assertEquals(accepted.publication, failed.publication)
            assertEquals(site, failed.website)
            assertEquals(failed, repo.cached(p))
        }
    }

    @Test fun legacyWithdrawalCanOnlyRecoverAfterIdentityProofForSameOriginAndNewPublication() = runBlocking {
        val cache = Cache(legacy(state(rejected = true, withdrawal = true)))
        val repo = InternetScheduleRepository(ScheduleTransport { ScheduleResponse(it, html.toByteArray(), "text/html") }, cache,
            siteResolver = ScheduleSiteResolver { resolved(it) })
        val ready = repo.refresh(p, now.plusSeconds(60), true)
        assertNotNull(ready.publication); assertFalse(ready.publicationRejected); assertFalse(ready.sourceNeedsVerification)
        assertEquals(ready, repo.cached(p))
    }

    @Test fun manualAndConfiguredSourcesRemainUsableWithoutArticleProof() {
        for (manual in listOf(false, true)) {
            val selected = if (manual) p else p.copy(website = site)
            val original = state(selected).copy(customSource = if (manual) site else "", siteEvidenceUrl = "")
            assertEquals(original, ScheduleCacheCodec.decode(legacy(original), selected))
        }
    }

    @Test fun malformedProofDoesNotDiscardWithdrawalHistory() {
        val cached = JsonParser.parseString(ScheduleCacheCodec.encode(state(rejected = true, withdrawal = true))).asJsonObject
        cached.addProperty("siteBinding", "malformed")
        val restored = ScheduleCacheCodec.decode(cached.toString(), p)!!
        assertTrue(restored.sourceNeedsVerification); assertTrue(restored.publicationRejected); assertTrue(restored.sourceHasWithdrawals)
    }

    @Test fun explicitKindsAndFirstEntityNounKeepChurchAndComplexSeparate() {
        assertTrue(ScheduleEntityIdentity.compatible("Храм Новомучеников", "Храм Новомучеников при монастыре", ScheduleEntityScope.CHURCH))
        assertFalse(ScheduleEntityIdentity.compatible("Храм Новомучеников", "Храм Новомучеников при монастыре", ScheduleEntityScope.MONASTERY))
        assertFalse(ScheduleEntityIdentity.compatible("Храм Новомучеников", "Монастырь Новомучеников"))
        assertTrue(ScheduleEntityIdentity.compatible("Сретенский монастырь", "Сретенский монастырь (Москва)"))
        assertFalse(ScheduleEntityIdentity.compatible("Никольский храм", "Никольская часовня"))
        assertTrue(OpenScheduleSiteResolver.namesMatch("Храм в честь Святых Новомучеников и Исповедников Российских",
            "Храм Новомучеников Российских при монастыре (Новосибирск)"))
        assertTrue("Only the preposition, not words starting with it", "прине" in OpenScheduleSiteResolver.nameTokens("Принесения"))
        assertEquals(ScheduleEntityScope.CHURCH, ScheduleEntityIdentity.scope("Монастырский храм Новомучеников"))
        assertEquals(ScheduleEntityScope.MONASTERY, ScheduleEntityIdentity.scope("Нило-Столобенская пустынь"))
        assertFalse(ScheduleEntityIdentity.compatible("Гефсиманский Черниговский скит", "Гефсиманский Черниговский монастырь"))
        assertFalse(ScheduleEntityIdentity.compatible("Никольский скит Введено-Оятского монастыря", "Введено-Оятский монастырь"))
        // Mixed church/chapel naming is intentionally not treated as proof of either type.
        assertFalse(ScheduleEntityIdentity.compatible("Храм-часовня Николая", "Храм-часовня Николая", ScheduleEntityScope.CHAPEL))
    }

    @Test fun actualOptinaAndGethsemaneCatalogNamesRemainCompatibleWithPublishedArticleKinds() {
        val source = javaClass.getResource("/schedule/palomnik/entity-scope-facts.json")!!.readText()
        val facts = JsonParser.parseString(source).asJsonObject.getAsJsonArray("facts")
        assertEquals(2, facts.size())
        for (fact in facts) {
            val row = fact.asJsonObject
            val title = row.get("title").asString
            val selected = row.get("selectedName").asString
            val template = row.get("locationTemplate").asString
            val kind = ScheduleEntityIdentity.scope(Regex("вид=([^|}]+)").find(template)!!.groupValues[1])
            assertEquals(ScheduleEntityScope.MONASTERY, kind)
            assertTrue(title, ScheduleEntityIdentity.compatible(selected, title, kind))
            val effectiveScope = ScheduleEntityIdentity.articleScope(title, kind)!!
            val expected = if (title.endsWith("скит")) ScheduleEntityScope.SKETE else ScheduleEntityScope.MONASTERY
            assertEquals(expected, effectiveScope)
            val selectedParish = p.copy(id = row.get("selectedId").asString, name = selected)
            val binding = ScheduleEntityIdentity.bind(selectedParish, site, proof, title, ScheduleBindingMethod.ARTICLE_SCOPE, effectiveScope)
            assertTrue(binding.validFor(selectedParish, site, proof))
        }
    }
}

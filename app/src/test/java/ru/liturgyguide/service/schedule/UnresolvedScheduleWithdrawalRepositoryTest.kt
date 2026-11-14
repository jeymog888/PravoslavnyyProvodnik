package ru.liturgyguide.service.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import java.time.Instant
import java.time.LocalDate

class UnresolvedScheduleWithdrawalRepositoryTest {
    private val now = Instant.parse("2026-11-13T06:00:00Z")
    private val source = "https://accepted.example/"
    private val alternative = "https://other.example/"
    private val image = source + "schedule.png"
    private val selected = Parish("osm:way:42", "Храм святителя Николая", "Николая", "Москва", "Осенняя улица, 32 с1", "", "",
        "Europe/Moscow", "", "", "", "", emptyList(), "", "", 55.76964415, 37.4061332)
    private val valid = """<html><body><h1>Храм святителя Николая</h1><article data-day="2026-11-13">
        <li><time>09:00</time><span>Литургия</span></li></article></body></html>"""
    private fun candidate(id: String = "42", url: String = source) = ScheduleSourceCandidate(
        selectedIdentity = ScheduleEntityIdentity.fingerprint(selected), provider = "other_public_index",
        providerLabel = "Публичный справочник", providerObjectId = id, name = selected.name, address = selected.address,
        sourceUrl = url, evidenceUrl = "https://index.example/church/$id", evidenceSha256 = "a".repeat(64),
        checkedAt = now.minusSeconds(10).toString(), latitude = selected.latitude!!, longitude = selected.longitude!!,
        outcome = ScheduleIdentityOutcome.INSUFFICIENT, reason = "Проверьте принадлежность расписания выбранному храму")
    private class Cache : ScheduleCacheStore {
        private var value: String? = null
        val safety = mutableListOf<String>()
        var afterSafetyWrite: ((String) -> Unit)? = null
        override fun read(parishId: String) = value
        override fun write(parishId: String, value: String) { this.value = value }
        override fun writeSafety(parishId: String, value: String) {
            write(parishId, value)
            safety += value
            afterSafetyWrite?.invoke(value)
        }
    }
    private class Resolver(val rows: List<ScheduleSourceCandidate>) : ScheduleSiteResolver {
        var confirmations = 0
        override suspend fun resolve(parish: Parish): ScheduleSite? = null
        override suspend fun candidates(parish: Parish, excludedUrls: Set<String>) = ScheduleCandidateDiscovery(rows)
        override suspend fun candidatesForConfirmation(parish: Parish, excludedUrls: Set<String>,
            expected: ScheduleSourceCandidate): ScheduleCandidateDiscovery {
            confirmations++
            return ScheduleCandidateDiscovery(rows)
        }
    }
    private fun response(url: String, html: String = valid) = ScheduleResponse(url, html.toByteArray(), "text/html")

    @Test fun reversedAndMultipleRangeCancellationPersistBeforeInterruptionAndBlockBothSourceSwitchesAfterRecreation() = runBlocking {
        val notices = listOf(
            "С 15 по 14 ноября 2026 - все службы отменены",
            "С 14 по 15 ноября 2026 и с 21 по 22 ноября 2026 - все службы отменены")
        for (notice in notices) for (interruptAfterSafety in listOf(false, true)) {
            val a = candidate(); val b = candidate("43", alternative)
            assertTrue(a.canConfirm(selected)); assertTrue(b.canConfirm(selected))
            val cache = Cache(); val resolver = Resolver(listOf(a, b)); val fetched = mutableListOf<String>()
            var content = valid
            val transport = ScheduleTransport { url -> fetched += url; response(url, if (url == source) content else valid) }
            val repo = InternetScheduleRepository(transport, cache, siteResolver = resolver)
            repo.refresh(selected, now)
            val accepted = repo.confirmCandidate(selected, a.id, now)
            assertNotNull(accepted.publication)
            assertFalse(accepted.sourceHasWithdrawals)
            content = "<h1>Храм святителя Николая</h1><p>$notice</p>"
            val parsed = ScheduleHtmlParser.inspect(Jsoup.parse(content, source), LocalDate.of(2026, 11, 13), selected)
            assertTrue(parsed.unresolvedWithdrawal)
            assertTrue(parsed.safetyConflict)
            assertTrue(parsed.withdrawnDates.isEmpty())
            assertTrue(parsed.withdrawnStarts.isEmpty())
            assertFalse(parsed.withdrawsAll)
            var persistedBeforeReturn = false
            cache.afterSafetyWrite = { raw ->
                val persisted = requireNotNull(ScheduleCacheCodec.decode(raw, selected))
                assertTrue(persisted.sourceHasWithdrawals)
                assertTrue(persisted.publicationRejected)
                assertNull(persisted.publication)
                assertEquals(accepted.siteBinding, persisted.siteBinding)
                assertEquals(source, persisted.website)
                persistedBeforeReturn = true
                if (interruptAfterSafety) throw CancellationException("Stop after the completed safety write")
            }
            if (interruptAfterSafety) {
                try {
                    repo.refresh(selected, now.plusSeconds(60), force = true)
                    fail("The completed safety write must survive interruption")
                } catch (_: CancellationException) { }
            } else {
                val withdrawn = repo.refresh(selected, now.plusSeconds(60), force = true)
                assertTrue(withdrawn.sourceHasWithdrawals)
                assertTrue(withdrawn.publicationRejected)
                assertNull(withdrawn.publication)
            }
            assertTrue(persistedBeforeReturn)
            assertTrue(cache.safety.isNotEmpty())
            cache.afterSafetyWrite = null
            val recreated = InternetScheduleRepository(transport, cache, siteResolver = resolver)
            val restored = recreated.cached(selected)
            assertTrue(restored.sourceHasWithdrawals)
            assertEquals(accepted.siteBinding, restored.siteBinding)
            assertEquals(source, restored.website)
            fetched.clear()
            val beforeConfirmation = resolver.confirmations
            val manual = recreated.refresh(selected, now.plusSeconds(120), force = true, sourceOverride = alternative)
            assertEquals(ScheduleSourceChangeBlock.HISTORY, manual.sourceChangeBlock)
            assertTrue(manual.sourceHasWithdrawals)
            assertEquals(restored.siteBinding, manual.siteBinding)
            assertTrue(fetched.isEmpty())
            val other = recreated.confirmCandidate(selected, b.id, now.plusSeconds(121))
            assertEquals(ScheduleSourceChangeBlock.HISTORY, other.sourceChangeBlock)
            assertTrue(other.sourceHasWithdrawals)
            assertEquals(beforeConfirmation, resolver.confirmations)
            assertTrue(fetched.isEmpty())
            assertEquals(ScheduleSourceChangeBlock.HISTORY, recreated.cached(selected).sourceChangeBlock)
            content = valid
            val reconciled = recreated.refresh(selected, now.plusSeconds(180), force = true, sourceOverride = source)
            assertEquals(listOf(source), fetched)
            assertEquals(InternetScheduleStatus.READY, reconciled.status)
            assertFalse(reconciled.sourceHasWithdrawals)
            assertFalse(reconciled.publicationRejected)
            assertEquals(listOf("2026-11-13"), reconciled.publication?.events?.map { it.date })
            assertEquals(a.id, reconciled.siteBinding?.candidateConfirmation?.candidate?.id)
            assertEquals(reconciled, recreated.cached(selected))
        }
    }

    @Test fun venueAndOcrConflictsWithoutCancellationDoNotBecomeWithdrawalHistory() = runBlocking {
        for (ocrConflict in listOf(false, true)) {
            val configured = selected.copy(website = source)
            val cache = Cache(); val fetched = mutableListOf<String>(); var content = valid
            val conflictingOcr = ScheduleDocument(image, "image", listOf(ScheduleDocumentPage(emptyList(),
                ocrFailure = ScheduleOcrFailure.CONFLICTING_EVIDENCE)))
            val inspectedOcr = ScheduleDocumentParser.inspect(conflictingOcr, LocalDate.of(2026, 11, 13), configured)
            assertTrue(inspectedOcr.safetyConflict)
            assertFalse(inspectedOcr.unresolvedWithdrawal)
            val repo = InternetScheduleRepository(ScheduleTransport { url ->
                fetched += url
                if (url == image) ScheduleResponse(url, byteArrayOf(1), "image/png")
                else response(url, if (url == source) content else valid)
            }, cache, documentExtractor = ScheduleDocumentExtractor { conflictingOcr })
            assertNotNull(repo.refresh(configured, now, force = true).publication)
            content = if (ocrConflict) "<h1>Храм святителя Николая</h1><a href='$image'>Расписание</a>" else
                "<h1>Покровский храм</h1><h2>Ноябрь 2026</h2><p>13 ноября 09:00 Литургия</p>"
            val failed = repo.refresh(configured, now.plusSeconds(60), force = true)
            assertTrue(failed.publicationRejected)
            assertFalse(failed.sourceHasWithdrawals)
            assertTrue(cache.safety.isNotEmpty())
            cache.safety.forEach { assertFalse(requireNotNull(ScheduleCacheCodec.decode(it, configured)).sourceHasWithdrawals) }
            fetched.clear()
            val recreated = InternetScheduleRepository(ScheduleTransport { url -> fetched += url; response(url) }, cache)
            assertFalse(recreated.cached(configured).sourceHasWithdrawals)
            val changed = recreated.refresh(configured, now.plusSeconds(120), force = true, sourceOverride = alternative)
            assertEquals(listOf(alternative), fetched)
            assertEquals(ScheduleSourceChangeBlock.NONE, changed.sourceChangeBlock)
            assertNotNull(changed.publication)
            assertFalse(changed.sourceHasWithdrawals)
        }
    }
}

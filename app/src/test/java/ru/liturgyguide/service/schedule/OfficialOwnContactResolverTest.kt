package ru.liturgyguide.service.schedule

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OfficialOwnContactResolverTest {
    private val f = OfficialOwnContactFixtures

    @Test fun `old strict full directory successes do not perform new identity requests`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val resolver = OfficialDirectoryResolver(ScheduleTransport { url -> calls += url; f.response(url) },
            MemoryOfficialDirectoryCacheStore(), f.clock)
        val spassky = requireNotNull(resolver.resolve(f.spassky(), ScheduleSourceExclusions(emptySet()), mutableMapOf()))
        assertEquals("http://voronezh-nikolskoe-14.cerkov.ru/", spassky.url)
        assertEquals(ScheduleBindingMethod.ARTICLE_SCOPE, spassky.binding?.method)
        assertNull(spassky.binding?.officialContactProof)
        val iliya = f.nikolsky().copy(id = "osm:way:239273353", name = "Церковь Илии Пророка",
            address = "Воронеж, улица Севастьяновский съезд, 26", latitude = 51.65438305, longitude = 39.20722645)
        val other = requireNotNull(resolver.resolve(iliya, ScheduleSourceExclusions(emptySet()), mutableMapOf()))
        assertEquals("http://prorok-iliya.cerkov.ru/", other.url)
        assertEquals(ScheduleBindingMethod.ARTICLE_SCOPE, other.binding?.method)
        assertNull(other.binding?.officialContactProof)
        assertEquals(listOf(f.profile.url), calls)
    }

    @Test fun `a second full same building card blocks before URL exclusions name or availability`() = runBlocking<Unit> {
        val original = f.directoryBytes().toString(Charsets.UTF_8)
        for (kind in listOf("same website", "missing website", "excluded website", "blank name")) {
            val changed = f.document(original, f.profile.url) { document ->
                val clone = document.getElementById("t36237")!!.closest("li")!!.clone()
                clone.select("h1").single().attr("id", "t999999")
                val website = clone.select("dl").single { it.select(".ch,.ttl").text().trim() == "Сайт:" }
                when (kind) {
                    "missing website" -> website.remove()
                    "excluded website" -> website.select("a[href]").single().attr("href", "https://excluded.example/")
                    "blank name" -> clone.select("h1").single().text("")
                }
                document.select("#parent_site_list > ul").single().appendChild(clone)
            }
            val snapshot = f.snapshot(changed)
            assertEquals(234, snapshot.entries.size)
            var identityCalls = 0
            val resolver = OfficialDirectoryResolver(ScheduleTransport { url ->
                if (url == f.profile.url) f.html(changed, url) else { identityCalls++; f.response(url) }
            }, MemoryOfficialDirectoryCacheStore(), f.clock)
            assertNull(kind, resolver.resolve(f.nikolsky(), ScheduleSourceExclusions(setOf("https://excluded.example/")),
                mutableMapOf(), mutableMapOf()))
            assertEquals(kind, 0, identityCalls)
            val direct = OfficialSiteContactVerifier(ScheduleTransport { identityCalls++; f.response(it) }, f.clock)
            assertNull(kind, direct.verify(f.nikolsky(), f.profile, snapshot, snapshot.entries.single { it.id == "t36237" }))
            assertEquals(kind, 0, identityCalls)
        }
    }

    @Test fun `verifier refuses mismatched snapshot and entry before network`() = runBlocking<Unit> {
        val snapshot = f.snapshot()
        val entry = snapshot.entries.single { it.id == "t36237" }
        var calls = 0
        val verifier = OfficialSiteContactVerifier(ScheduleTransport { calls++; f.response(it) }, f.clock)
        assertNull(verifier.verify(f.nikolsky(), f.profile, snapshot.copy(profileId = "another-publisher"), entry))
        assertNull(verifier.verify(f.nikolsky(), f.profile, snapshot.copy(directoryUrl = "https://other.cerkov.ru/xramy/"), entry))
        assertNull(verifier.verify(f.nikolsky(), f.profile, snapshot, entry.copy(title = "Храм Николая Японского")))
        assertNull(verifier.verify(f.nikolsky(), f.profile, snapshot.copy(entries = snapshot.entries - entry), entry))
        assertEquals(0, calls)
    }

    @Test fun `primary and alternative discovery share completed contact failure while new refresh retries`() = runBlocking<Unit> {
        var contactWorks = false
        val calls = mutableListOf<String>()
        val open = OpenScheduleSiteResolver(ScheduleTransport { url ->
            calls += url
            if (url == f.contact && !contactWorks) throw IOException("contact is temporarily unavailable")
            f.response(url)
        })
        val session = open.forRefresh()
        assertNull(session.resolve(f.nikolsky()))
        contactWorks = true
        assertNull(session.resolve(f.nikolsky(), setOf("https://unrelated-failed.example/")))
        assertEquals(1, calls.count { it == f.profile.url })
        assertEquals(1, calls.count { it == f.httpHome })
        assertEquals(1, calls.count { it == f.contact })
        assertEquals(f.home, open.forRefresh().resolve(f.nikolsky())?.url)
        assertEquals(1, calls.count { it == f.profile.url })
        assertEquals(2, calls.count { it == f.httpHome })
        assertEquals(2, calls.count { it == f.contact })
    }

    @Test fun `completed successful proof is reused and excluded candidate never triggers another identity fetch`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val session = OpenScheduleSiteResolver(ScheduleTransport { url -> calls += url; f.response(url) }).forRefresh()
        val primary = requireNotNull(session.resolve(f.nikolsky()))
        assertEquals(primary, session.resolve(f.nikolsky(), setOf("https://unrelated-failed.example/")))
        assertNull(session.resolve(f.nikolsky(), setOf(f.home)))
        assertEquals(1, calls.count { it == f.httpHome })
        assertEquals(1, calls.count { it == f.contact })
    }

    @Test fun `failed primary import and alternative lookup do not repeat identity fetch within refresh`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val repository = InternetScheduleRepository(ScheduleTransport { url ->
            calls += url
            if (url == f.home) throw IOException("publication import is unavailable")
            f.response(url)
        }, OfficialOwnContactFixtures.Cache())
        val failed = repository.refresh(f.nikolsky(), f.now, force = true)
        assertEquals(InternetScheduleStatus.UNAVAILABLE, failed.status)
        assertNull(failed.publication)
        assertEquals(1, calls.count { it == f.httpHome })
        assertEquals(1, calls.count { it == f.contact })
        assertEquals(1, calls.count { it == f.home })
        assertEquals(1, calls.count { it == f.profile.url })
    }

    @Test fun `cancellation during home or contacts does not memoize partial failure`() = runBlocking<Unit> {
        for (suspendAt in listOf(f.httpHome, f.contact)) {
            val entered = CompletableDeferred<Unit>()
            var shouldSuspend = true
            val calls = mutableListOf<String>()
            val open = OpenScheduleSiteResolver(ScheduleTransport { url ->
                calls += url
                if (url == suspendAt && shouldSuspend) {
                    shouldSuspend = false
                    entered.complete(Unit)
                    awaitCancellation()
                }
                f.response(url)
            })
            val session = open.forRefresh()
            val job = launch { session.resolve(f.nikolsky()) }
            withTimeout(3_000) { entered.await() }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(f.home, withTimeout(3_000) { session.resolve(f.nikolsky()) }?.url)
            assertEquals(suspendAt, 2, calls.count { it == f.httpHome })
            assertEquals(suspendAt, if (suspendAt == f.contact) 2 else 1, calls.count { it == f.contact })
            assertEquals(suspendAt, 1, calls.count { it == f.profile.url })
        }
    }

    @Test fun `contact memo separates selected identity and snapshot evidence`() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val resolver = OfficialDirectoryResolver(ScheduleTransport { url -> calls += url; f.response(url) },
            MemoryOfficialDirectoryCacheStore(), f.clock)
        val directoryMemo = mutableMapOf<String, OfficialDirectorySnapshot?>()
        val contactMemo = mutableMapOf<String, ScheduleSite?>()
        assertNotNull(resolver.resolve(f.nikolsky(), ScheduleSourceExclusions(emptySet()), directoryMemo, contactMemo))
        val otherIdentity = f.nikolsky().copy(id = "osm:way:77503843")
        val other = requireNotNull(resolver.resolve(otherIdentity, ScheduleSourceExclusions(emptySet()), directoryMemo, contactMemo))
        assertEquals(ScheduleEntityIdentity.fingerprint(otherIdentity), other.binding?.selectedIdentity)
        assertEquals(2, calls.count { it == f.contact })
        val newerSnapshot = f.snapshot(f.directoryBytes().toString(Charsets.UTF_8).replace("</body>", "<!-- new capture -->\n</body>"))
        directoryMemo[f.profile.id] = newerSnapshot
        val fresh = requireNotNull(resolver.resolve(f.nikolsky(), ScheduleSourceExclusions(emptySet()), directoryMemo, contactMemo))
        assertEquals(newerSnapshot.sourceSha256, fresh.binding?.officialContactProof?.directorySha256)
        assertEquals(3, calls.count { it == f.contact })
    }
}

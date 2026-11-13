package ru.liturgyguide.service.book

import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.liturgyguide.model.*
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.File
import java.util.UUID

/** Opt-in real Android HTTP, calendar identity, whole book and exact disk reopen. */
@RunWith(AndroidJUnit4::class)
class LivePatronalBookTest {
    @Test fun realDedicationsResolveToTheirOwnCalendarEventsAndReopenWithoutNetwork() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("feoriaLiveNetwork") == "true")
        val context = instrumentation.targetContext
        val name = "live-patronal-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, name)
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val choices = PatronalChoiceStore(preferences)
        val catalog = requireNotNull(PatronalCatalog.decode(context.assets.open(PatronalCatalog.ASSET_NAME)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }))
        val resolver = PatronalCalendarResolver(choices)
        val online = ServiceBookRepository(FileBookCache(directory), patronalProofs = choices)
        val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { error("Network is disabled") })
        val proofs = mutableListOf<PatronalEventProof>()
        try {
            for ((minea, expectedId, expectedType) in listOf(
                Triple("11183", "111300", "34"), Triple("9906", "129540", "34"),
                Triple("14581", "128390", "20"), Triple("9369", "115980", "30"),
                Triple("9197", "101360", "10")
            )) {
                val entry = catalog.entries.single { it.mineaId == minea }
                val resolution = resolver.resolve(entry, 2026)
                assertTrue("Calendar $minea: $resolution", resolution is PatronalResolution.Confirmed)
                val proof = (resolution as PatronalResolution.Confirmed).proof
                assertEquals(expectedId, proof.id)
                assertEquals(expectedType, proof.churchType)
                assertEquals(minea, proof.mineaId)
                assertTrue(proof.isValid())
                proofs += proof
                val key = ServiceBookKey("2026-11-11", WorshipKind.LITURGY, proof.churchType, patronalId = proof.id)
                val fetched = online.load(key)
                assertTrue("Book $minea: $fetched", fetched is BookLoadResult.Ready)
                val ready = fetched as BookLoadResult.Ready
                assertTrue("Exact book must be on disk", ready.saved)
                assertEquals(key, ready.book.key)
                assertEquals(proof, ready.book.patronalSelection)
                assertTrue(ServiceBookIntegrity.valid(ready.book))
                assertTrue(ready.book.blocks.size > 200)
                assertEquals(ready.book, FileBookCache(directory).readVersion(key, ready.book.contentHash))
                val restored = offline.load(key)
                assertTrue("Offline $minea: $restored", restored is BookLoadResult.Ready)
                assertEquals(ready.book, (restored as BookLoadResult.Ready).book)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream",
                    "\nPATRONAL $minea event=${proof.id} feast=${proof.calendarDate} book=${key.canonical()} hash=${ready.book.contentHash} blocks=${ready.book.blocks.size} offline=exact\n") })
            }
            assertNotEquals(proofs[0].id, proofs[1].id)
            assertNotEquals(proofs[0].calendarDate, proofs[1].calendarDate)
        } finally {
            context.deleteSharedPreferences(name)
            directory.deleteRecursively()
        }
    }
}

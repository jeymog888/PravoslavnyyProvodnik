package ru.liturgyguide.ui

import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ReadingContext
import ru.liturgyguide.service.ReadingContextCodec
import ru.liturgyguide.service.ReadingContextSource
import java.time.Instant

class ArrivalDraftTest {
    private val parish = Parish("church", "Храм", "Храм", "Москва", "", "", "", "Europe/Moscow",
        "", "", "2026-11-01", "2026-11-30", emptyList(), "", "")
    private val pending = ReadingContext(parish.id, "2026-11-08", parish.timezone, ReadingContextSource.USER,
        Instant.parse("2026-11-08T20:00:00Z").toEpochMilli())
    private val draft = ArrivalDraft(parish.id, parish.name, ArrivalMode.NEW_SERVICE, ArrivalSource.MANUAL,
        stageId = "creed", query = "Верую", pendingServiceContext = pending)

    @Test fun `scheduled draft round trip keeps the exact occurrence and selected query`() {
        val restored = requireNotNull(ArrivalDraftCodec.decode(ArrivalDraftCodec.encode(draft)))
        assertEquals(draft, restored)
        assertEquals(pending, restored.pendingServiceContext)
        assertTrue(restored.isValidFor(parish))
        assertEquals("Литургия 8 ноября 2026 · начало в 23:00", restored.pendingServiceLabel())
    }

    @Test fun `legacy eleven fields remain manual without inventing a scheduled context`() {
        val manual = draft.copy(pendingServiceContext = null)
        val legacy = ArrivalDraftCodec.encode(manual).take(11)
        val restored = requireNotNull(ArrivalDraftCodec.decode(legacy))
        assertEquals(manual, restored)
        assertNull(restored.pendingServiceContext)
        assertNull(restored.pendingServiceLabel())
        assertTrue(restored.isValidFor(parish))
        val withoutChurch = manual.copy(churchId = null, churchName = "Храм не выбран")
        assertTrue(requireNotNull(ArrivalDraftCodec.decode(ArrivalDraftCodec.encode(withoutChurch))).isValidFor(null))
    }

    @Test fun `malformed saved state never becomes an ordinary manual draft`() {
        val valid = ArrivalDraftCodec.encode(draft)
        fun altered(index: Int, value: Any?): List<Any?> = valid.toMutableList<Any?>().also { it[index] = value }
        val invalid = listOf(
            valid.take(3), valid + "unexpected", altered(2, "UNKNOWN"), altered(3, 4), altered(6, "false"),
            altered(8, 0), altered(8, -1L), altered(10, "true"), altered(11, "broken"), altered(11, null),
            altered(11, " "), altered(11, "x".repeat(ReadingContextCodec.MAX_JSON_CHARS + 1)),
            altered(9, "x".repeat(8_193)), altered(0, " church")
        )
        invalid.forEachIndexed { index, value -> assertNull("Malformed case $index", ArrivalDraftCodec.decode(value)) }
        assertNull(ArrivalDraftCodec.decode(emptyList()))
        assertTrue(ArrivalDraftCodec.encode(null).isEmpty())
    }

    @Test fun `well typed but incompatible pending context cannot bypass draft invariants`() {
        val raw = ArrivalDraftCodec.encode(draft)
        fun altered(index: Int, value: Any): List<Any?> = raw.toMutableList<Any?>().also { it[index] = value }
        val manualContext = pending.copy(source = ReadingContextSource.MANUAL, startEpochMillis = null)
        for (value in listOf(altered(2, ArrivalMode.CORRECTION.name), altered(3, ArrivalSource.SCHEDULE.name),
            altered(6, true), altered(11, ReadingContextCodec.encode(manualContext)),
            altered(11, ReadingContextCodec.encode(pending.copy(parishId = "another"))))) {
            assertNull(ArrivalDraftCodec.decode(value))
        }
        assertFalse(draft.isValidFor(parish.copy(id = "another")))
        assertFalse(draft.isValidFor(null))
        assertFalse(draft.isValidFor(parish.copy(timezone = "Asia/Tokyo")))
        assertFalse(draft.isValidFor(parish.copy(timezone = "Mars/Nowhere")))
        val changedZone = draft.copy(pendingServiceContext = pending.copy(timezone = "Europe/Berlin"))
        val restored = requireNotNull(ArrivalDraftCodec.decode(ArrivalDraftCodec.encode(changedZone)))
        assertFalse(restored.isValidFor(parish))
    }

    @Test fun `published seconds stay visible and personal time is not rounded into a new occurrence`() {
        val published = draft.copy(pendingServiceContext = pending.copy(source = ReadingContextSource.PUBLISHED,
            startEpochMillis = Instant.parse("2026-11-08T20:00:15.500Z").toEpochMilli()))
        val restored = requireNotNull(ArrivalDraftCodec.decode(ArrivalDraftCodec.encode(published)))
        assertEquals(published.pendingServiceContext, restored.pendingServiceContext)
        assertEquals("Литургия 8 ноября 2026 · начало в 23:00:15.5", restored.pendingServiceLabel())
        assertFalse(published.copy(pendingServiceContext = published.pendingServiceContext!!.copy(
            source = ReadingContextSource.USER)).isValidFor(parish))
    }
}

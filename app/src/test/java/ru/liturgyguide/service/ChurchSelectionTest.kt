package ru.liturgyguide.service

import org.junit.Assert.*
import org.junit.Test

class ChurchSelectionTest {
    private val directory = ParishRepository(requireNotNull(javaClass.classLoader?.getResourceAsStream("churches.json"))
        .bufferedReader().use { it.readText() }).directory
    private val first = directory.churches.first { it.id == directory.defaultChurchId }
    private val other = directory.churches.first { it.id != first.id }
    private val context = ReadingContext(first.id, "2026-11-08", "Europe/Moscow", ReadingContextSource.MANUAL)

    private fun resolve(hasSelection: Boolean = false, id: String? = null,
        hasContext: Boolean = false, reading: ReadingContext? = null,
        hasSnapshot: Boolean = false, legacy: Boolean = false) = ChurchSelection.resolve(
        directory.churches, hasSelection, id, hasContext, reading, hasSnapshot, legacy, directory.defaultChurchId)

    @Test fun `fresh installation has no parish and cannot receive a default schedule`() {
        assertNull(resolve())
    }

    @Test fun `an explicit known choice wins over reading and legacy addresses`() {
        assertEquals(other, resolve(true, other.id, true, context, true, true))
    }

    @Test fun `present invalid selection never silently falls back`() {
        listOf(null, "", " ", "unknown").forEach { id ->
            assertNull(resolve(true, id, true, context, true, true))
            assertNull(resolve(true, id, legacy = true))
        }
    }

    @Test fun `valid modern context restores only its known parish`() {
        assertEquals(first, resolve(hasContext = true, reading = context, hasSnapshot = true))
        assertEquals(other, resolve(hasContext = true, reading = context.copy(parishId = other.id)))
        assertNull(resolve(hasContext = true, reading = context.copy(parishId = "unknown"), legacy = true))
    }

    @Test fun `modern manual reading without parish remains without parish`() {
        assertNull(resolve(hasContext = true, reading = context.copy(parishId = null), legacy = true))
    }

    @Test fun `corrupt modern context and orphan snapshot prohibit legacy recovery`() {
        assertNull(resolve(hasContext = true, legacy = true))
        assertNull(resolve(hasContext = true, reading = context.copy(serviceDate = "invalid"), legacy = true))
        assertNull(resolve(hasSnapshot = true, legacy = true))
    }

    @Test fun `legacy fallback needs positive evidence and never uses first directory item`() {
        assertEquals(first, resolve(legacy = true))
        assertNull(ChurchSelection.resolve(directory.churches, false, null, false, null, false, true, "missing"))
    }
}

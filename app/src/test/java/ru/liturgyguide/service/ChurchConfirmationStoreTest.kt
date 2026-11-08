package ru.liturgyguide.service

import org.junit.Assert.*
import org.junit.Test

class ChurchConfirmationStoreTest {
    private fun seeded() = AcceptanceMemoryPreferences().apply {
        values[ReadingContextStore.CONTEXT_KEY] = "accepted context"
        values[ServicePlaybackStore.PLAYBACK_KEY] = "accepted snapshot"
        values["session"] = "legacy session"
        values["stage_id"] = "creed"
        values["lyric_phrase_creed"] = "phrase 4"
        values["dark"] = true
    }

    @Test fun `confirming a recovered parish changes only selection in one commit`() {
        val prefs = seeded()
        val before = prefs.values.toMap()
        assertTrue(ReadingAcceptanceStore(prefs).confirmChurch("elisavetinsky_opalikha"))
        assertEquals(before + ("church_id" to "elisavetinsky_opalikha"), prefs.values)
        assertEquals(listOf(setOf("church_id")), prefs.committedKeys)
    }

    @Test fun `failed confirmation rolls back an absent or existing selection without touching reading`() {
        for (existing in listOf(null, "old")) {
            val prefs = seeded()
            if (existing != null) prefs.values["church_id"] = existing
            val before = prefs.values.toMap()
            prefs.outcomes += MemoryCommit.FALSE_AFTER_MEMORY
            assertFalse(ReadingAcceptanceStore(prefs).confirmChurch("elisavetinsky_opalikha"))
            assertEquals(before, prefs.values)
            assertEquals(listOf(setOf("church_id"), setOf("church_id")), prefs.committedKeys)
        }
    }

    @Test fun `invalid confirmation opens no editor`() {
        val prefs = seeded()
        listOf("", " ", " id", "id\n", "x".repeat(161)).forEach {
            assertFalse(ReadingAcceptanceStore(prefs).confirmChurch(it))
        }
        assertEquals(0, prefs.editCount)
    }
}

package ru.liturgyguide.service.book

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.text.Normalizer

class BookCommonTranslationsTest {
    private val entries = requireNotNull(javaClass.classLoader?.getResourceAsStream("book_russian_common.json"))
        .bufferedReader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonArray.map { value -> value.asJsonObject } }

    @Test fun eachExactOriginalHasOneDistinctRussianRenderingAndEditorialProvenance() {
        val ids = entries.map { it.get("id").asString }
        val originals = entries.map { normalized(it.get("original").asString) }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(originals.size, originals.toSet().size)
        entries.forEach { entry ->
            val original = entry.get("original").asString
            val russian = entry.get("russian").asString
            assertTrue(entry.get("id").asString, original.isNotBlank() && russian.isNotBlank())
            assertNotEquals(entry.get("id").asString, normalized(original), normalized(russian))
            assertEquals("Смысловой перевод Феории", entry.get("source").asString)
            assertTrue(entry.get("originalSourceUrl").asString.startsWith("https://azbyka.ru/worships/"))
            assertFalse(entry.get("id").asString, Regex("^(Чтец|Священник|Диакон|Хор|Лик):").containsMatchIn(original))
        }
    }

    @Test fun previouslyMissingCatechumenPrayerIncludesBaptismAndJoiningTheChurch() {
        val prayer = entries.single { it.get("id").asString == "prayer-catechumens" }
        val russian = prayer.get("russian").asString
        assertTrue(russian.contains("Крещения"))
        assertTrue(russian.contains("прощение грехов"))
        assertTrue(russian.contains("Церковью"))
    }

    @Test fun anaphoraDoesNotTranslateConsecrationAsASymbolicReminder() {
        val bread = entries.single { it.get("id").asString == "anaphora-bread" }.get("russian").asString
        val spirit = entries.single { it.get("id").asString == "anaphora-spirit" }.get("russian").asString
        assertTrue(bread.contains("Телом"))
        assertFalse(bread.contains("символ"))
        assertTrue(spirit.contains("на нас и на эти предложенные дары"))
    }

    @Test fun eveningPrayerFragmentDoesNotImportTheUnmatchedRestOfThePrayer() {
        val opening = entries.single { it.get("id").asString == "vespers-keep" }
        val original = normalized(opening.get("original").asString)
        val russian = normalized(opening.get("russian").asString)
        assertTrue(original.contains("в вечер сеи без греха"))
        assertTrue(russian.contains("провести этот вечер без греха"))
        assertTrue(original.endsWith("во веки аминь"))
        assertTrue(russian.endsWith("вовеки аминь"))
        // The next source paragraph starts "Буди, Господи, милость Твоя на нас".
        // Its petition and the following "научи мя" must not appear in this fragment's translation.
        assertFalse(original.contains("буди господи милость"))
        assertFalse(russian.contains("пусть твоя милость"))
        assertFalse(russian.contains("научи"))
        assertFalse(russian.contains("созданное твоими руками"))
    }

    private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

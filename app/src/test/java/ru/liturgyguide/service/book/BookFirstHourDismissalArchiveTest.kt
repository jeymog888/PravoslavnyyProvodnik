package ru.liturgyguide.service.book

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.WorshipKind

/** Untouched actual Native86 bytes, independent of the fresh-parser reconstruction tests.
 * Android/Dex and JVM reflect fields in different orders. Native tests own Integrity.valid
 * and scope positives on these exact hashes; this test checks the archived representation. */
class BookFirstHourDismissalArchiveTest {
    private fun resource(name: String) = requireNotNull(javaClass.classLoader
        ?.getResource("book/first-hour-dismissal/$name")).readText()

    @Test fun actualHourAndComposedVigilArchivesRetainTheirOriginalRepresentationAndWholeOwner() {
        val gson = Gson()
        val provenance = JsonParser.parseString(resource("native86-provenance.json")).asJsonObject["entries"].asJsonArray
        val books = provenance.map { item ->
            val row = item.asJsonObject
            val raw = resource(row["file"].asString)
            assertEquals(row["sha256"].asString, BookText.sha256(raw))
            assertEquals(row["bytes"].asLong, raw.toByteArray().size.toLong())
            val representation = JsonParser.parseString(raw).asJsonObject
            val archivedHash = representation["contentHash"].asString
            val hashInput = representation.deepCopy().apply {
                addProperty("fetchedAtMillis", 0)
                addProperty("contentHash", "")
            }
            assertEquals(row["contentHash"].asString, archivedHash)
            assertEquals(archivedHash, BookText.sha256(gson.toJson(hashInput)))
            val book = gson.fromJson(raw, ServiceBook::class.java)
            assertEquals(row["chapterCount"].asInt, book.chapters.size)
            assertEquals(row["blockCount"].asInt, book.blocks.size)
            assertEquals(book.chapters.size, book.chapters.map { it.id }.distinct().size)
            assertEquals(book.blocks.size, book.blocks.map { it.id }.distinct().size)
            val chapter = book.chapters.last()
            val body = chapter.blocks.first()
            assertEquals("Отпуст", chapter.title)
            assertEquals(2, chapter.blocks.size)
            assertEquals(row["bodyId"].asString, body.id)
            assertEquals(row["originalSha256"].asString, BookText.sha256(body.original))
            assertEquals(BookBlockKind.RUBRIC, body.kind)
            assertNull(body.speaker)
            assertFalse(body.isAltarText)
            assertTrue(body.scriptureReferences.isEmpty())
            assertEquals(body.original, body.russian)
            assertEquals(body.original, body.meaning)
            assertNull(body.translationSource)
            assertEquals(BookBlockKind.PRAYER, chapter.blocks.last().kind)
            assertEquals("Хор", chapter.blocks.last().speaker)
            assertEquals("господи помилуй трижды", BookText.normalized(chapter.blocks.last().original))
            assertEquals(row["sha256"].asString, BookText.sha256(resource(row["file"].asString)))
            book
        }
        val hour = books.single { it.key.worship == WorshipKind.HOUR1 }
        val vigil = books.single { it.key.worship == WorshipKind.VIGIL }
        assertEquals(hour.chapters.last(), vigil.chapters.last())
        assertEquals(hour.chapters.drop(1), vigil.chapters.filter { it.id.startsWith("chas1-") }.drop(1))
        assertEquals(listOf("vespers", "matins", "chas1"), vigil.chapters.map { it.id.substringBefore('-') }
            .fold(emptyList<String>()) { groups, family -> if (groups.lastOrNull() == family) groups else groups + family })
        assertEquals(listOf(WorshipKind.VESPERS, WorshipKind.MATINS, WorshipKind.HOUR1).map {
            vigil.key.copy(worship = it).sourceUrl()
        }, vigil.sources.filter { it.title == "Азбука веры: Богослужения сегодня" }.map { it.url })
    }
}

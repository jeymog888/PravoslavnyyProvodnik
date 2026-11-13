package ru.liturgyguide.service.book

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Document
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.*
import ru.liturgyguide.service.AcceptanceMemoryPreferences
import ru.liturgyguide.service.schedule.ScheduleResponse
import ru.liturgyguide.service.schedule.ScheduleTransport
import java.io.IOException
import java.nio.file.Files

class FeoriaTranslationCatalogTest {
    private val directory = Files.createTempDirectory("feoria-catalog-").toFile()
    private val cache = FileBookCache(directory)
    private val own = BookTranslationIndex.fromFeoriaCatalog()
    private val source = BookSource("Перевод Феории", "https://github.com/jeymog888/PravoslavnyyProvodnik")
    private val published = BookTranslationIndex.TIMROT_SOURCE
    private val plain by lazy { MatinsRankFixtures.parse() }
    private val target by lazy { plain.blocks.single { it.original == approved.single { row -> row.id == "come_worship_christ_himself" }.original } }
    private data class Approved(val id: String, val original: String, val russian: String, val count: Int)
    // Independent accepted DRAFT c34d2fb8..., not read back from the production catalog.
    private val approved = listOf(
        Approved("apostles_refrain", "Припев: Святи́и апóстоли, моли́те Бо́га о на́с.", "Припев: Святые апостолы, молитесь Богу о нас.", 48),
        Approved("alleluia_refrain_three_times", "Припев: Аллилу́ия (трижды).", "Припев: Хвалите Господа (трижды).", 43),
        Approved("come_worship_god_our_king", "Прииди́те, поклони́мся Царе́ви на́шему Бо́гу.", "Придите, поклонимся Богу, нашему Царю.", 1),
        Approved("come_worship_christ_our_king_and_god", "Прииди́те, поклони́мся и припаде́м Христу́, Царе́ви на́шему Бо́гу.", "Придите, поклонимся и падем ниц перед Христом, нашим Царем и Богом.", 1),
        Approved("come_worship_christ_himself", "Прииди́те, поклони́мся и припаде́м Самому́ Христу́, Царе́ви и Бо́гу на́шему.", "Придите, поклонимся и падем ниц перед Самим Христом, нашим Царем и Богом.", 1),
        Approved("apostles_magnification", "Велича́ем ва́с, святи́и апо́столи, ве́сь ми́р уче́ньми свои́ми просвети́вшыя, и вся́ концы́ ко Христу́ приве́дшыя.", "Прославляем вас, святые апостолы, которые своим учением просветили весь мир и привели ко Христу все края земли.", 6),
        Approved("for_you_are_our_god", "Я́ко Ты еси́ Бог наш, и Тебе́ сла́ву возсыла́ем, Отцу́, и Сы́ну, и Свято́му Ду́ху, ны́не и при́сно, и во ве́ки веко́в. Ами́нь.", "Ибо Ты - наш Бог, и Тебе мы воздаем славу: Отцу, Сыну и Святому Духу, сейчас, всегда и вечно. Аминь.", 2),
        Approved("their_voice_all_the_earth", "Во всю зе́млю изы́де веща́ние их / и в концы́ вселе́нныя глаго́лы их.", "Их голос прозвучал по всей земле, и их слова достигли краев мира.", 2)
    )
    @After fun clean() { directory.deleteRecursively() }

    @Test fun previousEightApprovedPairsStayUnchangedAndLegacyParaphrasesRemainSeparate() {
        assertEquals(2, FeoriaTranslationCatalog.REVISION)
        assertEquals(11, own.size)
        assertEquals(source, BookTranslationIndex.FEORIA_TRANSLATION_SOURCE)
        assertEquals(BookSource("Русское изложение: редакция Феории", source.url), BookTranslationIndex.EDITORIAL_SOURCE)
        assertNotEquals(source, BookTranslationIndex.EDITORIAL_SOURCE)
        assertEquals(approved.map { it.id }, FeoriaTranslationCatalog.entries.take(8).map { it.id })
        approved.zip(FeoriaTranslationCatalog.entries).forEach { (expected, entry) ->
            assertEquals(expected.original, entry.original); assertEquals(expected.russian, entry.russian)
            assertEquals(1, entry.revision); assertEquals(MatinsRankFixtures.url(MatinsRankFixtures.key()), entry.originalSourceUrl)
            val pair = BookTranslation(expected.russian, source)
            assertEquals(pair, own.find(expected.original)); assertTrue(FeoriaTranslationCatalog.recognizes(expected.original, pair))
            for (changed in listOf("Не " + expected.original, expected.original + " Иной текст", expected.original.substringBeforeLast(' ')))
                assertNull(changed, own.find(changed))
            assertFalse(FeoriaTranslationCatalog.recognizes(expected.original, pair.copy(meaning = "Старое объяснение")))
            assertFalse(FeoriaTranslationCatalog.recognizes(expected.original, pair.copy(source = BookTranslationIndex.EDITORIAL_SOURCE)))
            assertNull(index(expected.original, "Произвольный текст с правильной подписью", source).find(expected.original))
        }
        assertNull(own.find(approved[0].original.replace('ó', 'о')))
        assertNull(own.find(approved[1].original.replace("трижды", "дважды")))
        assertNull(own.find(approved[2].original + " " + approved[3].original))
        assertNull(SavedBookEnrichment.apply(plain, index(target.original, "Старое краткое изложение", BookTranslationIndex.EDITORIAL_SOURCE)))
        assertNull(SavedBookEnrichment.apply(plain, index(target.original, "Поддельный перевод", source)))
    }

    @Test fun realRankedSourceGainsExactly104ReviewedBlocksWithoutChangingItsStructure() {
        val after = ServiceBookParser().parse(MatinsRankFixtures.document(ranked = true), plain.key, plain.fetchedAtMillis,
            translations = own, rankRequest = MatinsRankSource.resolve(MatinsRankFixtures.document(), plain.key))
        val changed = plain.blocks.zip(after.blocks).filter { (old, new) -> old != new }
        assertEquals(104, changed.size)
        for (row in approved) {
            val blocks = after.blocks.filter { it.original == row.original }
            assertEquals(row.id, row.count, blocks.size)
            assertTrue(blocks.all { it.kind == BookBlockKind.PRAYER && it.russian == row.russian && it.translationSource == source })
        }
        assertOnlyTranslationChanges(plain, after)
        assertEquals(listOf(source), after.sources.filter { it == source })
        assertFalse(BookTranslationIndex.ALL_PARALLEL_SOURCES.contains(source))
    }

    @Test fun freshAndSavedPublishedTextWinOverLocalFallbackAndChangedOriginalNeverInheritsIt() = runBlocking {
        // These published strings are synthetic protocol fixtures, not editorial translations.
        val oldPublished = replace(plain, target.id) { it.copy(russian = "Прежний опубликованный перевод QA", translationSource = published) }
        assertTrue(cache.write(oldPublished))
        val fresh = loadRanked(freshPublished = "Свежий опубликованный перевод QA")
        val freshTarget = fresh.blocks.single { it.id == target.id }
        assertEquals("Свежий опубликованный перевод QA", freshTarget.russian); assertEquals(published, freshTarget.translationSource)
        // FileBookCache rejects an empty corpus. Remove only this test's saved corpus file
        // and prove the failed refresh can recover the pair only from the preserved book.
        assertNotNull(BookTranslationCorpus.decode(cache.readTranslationCorpus()).index.find(target.original))
        val corpusFile = directory.resolve("russian-concordance.json")
        assertTrue(corpusFile.isFile); assertTrue(corpusFile.delete())
        assertNull(cache.readTranslationCorpus())
        assertEquals(fresh, cache.read(fresh.key))
        val failedRefresh = loadRanked()
        val retained = failedRefresh.blocks.single { it.id == target.id }
        assertEquals(freshTarget.russian, retained.russian); assertEquals(freshTarget.translationSource, retained.translationSource)
        assertTrue(failedRefresh.blocks.any { it.translationSource == source })
        val changed = MatinsRankFixtures.document(ranked = true)
        val paragraph = MatinsRankFixtures.body(changed).select("p").single { BookText.clean(it.text()) == target.original }
        val changedOriginal = target.original + " Иное окончание QA"
        paragraph.text(changedOriginal)
        val changedBook = loadRanked(document = changed)
        val changedBlock = changedBook.blocks.single { it.original == changedOriginal }
        assertNull(changedBlock.russian); assertNull(changedBlock.translationSource)
        assertEquals(fresh, cache.readVersion(fresh.key, fresh.contentHash)?.copy(fetchedAtMillis = fresh.fetchedAtMillis))
    }

    @Test fun actualLocalRepositoryPublishesOfflineOnceAndLaterPublishedPairCanSupersedeOwn() = runBlocking {
        assertTrue(cache.write(plain))
        val oldFile = directory.listFiles()!!.single { it.name.endsWith("-${plain.contentHash}.json") }
        val oldBytes = oldFile.readBytes()
        val requests = mutableListOf<String>()
        val repository = ServiceBookRepository(cache, ScheduleTransport { url -> requests += url; error("Local candidate must precede network") }, translations = own)
        // allowNetwork=true is intentional: an available local candidate must return first.
        val result = requireNotNull(repository.enrichSaved(plain))
        assertTrue(result.saved); assertTrue(result.fromCache); assertTrue(requests.isEmpty())
        assertEquals(104, result.book.blocks.count { it.translationSource == source })
        assertOnlyTranslationChanges(plain, result.book)
        assertArrayEquals(oldBytes, oldFile.readBytes())
        assertNull(repository.enrichSaved(result.book, allowNetwork = false))
        assertTrue(requests.isEmpty())
        val offline = ServiceBookRepository(FileBookCache(directory), ScheduleTransport { url -> requests += url; error("Offline") }, translations = own)
        assertEquals(result.book, (offline.load(plain.key) as BookLoadResult.Ready).book); assertTrue(requests.isEmpty())
        val publishedPair = index(target.original, "Позднее опубликованный перевод QA", published)
        assertTrue(cache.writeTranslationCorpus(BookTranslationCorpus(publishedPair).encode()))
        val upgraded = requireNotNull(offline.enrichSaved(result.book, allowNetwork = false)).book
        val newTarget = upgraded.blocks.single { it.id == target.id }
        assertEquals("Позднее опубликованный перевод QA", newTarget.russian); assertEquals(published, newTarget.translationSource)
        assertEquals(103, upgraded.blocks.count { it.translationSource == source })
        assertEquals(result.book, cache.readVersion(result.book.key, result.book.contentHash))
        assertArrayEquals(oldBytes, oldFile.readBytes()); assertTrue(requests.isEmpty())
        assertNull(offline.enrichSaved(upgraded, allowNetwork = false))
    }

    @Test fun acceptingLocalEditionKeepsEveryCursorFieldAndResetsOnlyChangedVisibleRussianOffset() {
        val next = requireNotNull(SavedBookEnrichment.apply(plain, own))
        val chapter = plain.chapters.single { target in it.blocks }
        for (mode in listOf(BookReadingMode.RUSSIAN, BookReadingMode.ORIGINAL)) {
            val store = BookReadingStore(AcceptanceMemoryPreferences())
            val before = BookReadingPosition(plain.key, plain.contentHash, "catalog-qa", target.id, chapter.id,
                fraction = .37f, mode = mode, wordsPerMinute = 96, showAltar = true, lastTextMode = mode)
            assertTrue(store.save(before))
            assertEquals(before, store.last("catalog-qa"))
            assertTrue(store.acceptVersion(plain, next, "catalog-qa", before))
            assertEquals(before.copy(contentHash = next.contentHash,
                fraction = if (mode == BookReadingMode.RUSSIAN) 0f else .37f), store.last("catalog-qa"))
        }
    }

    private suspend fun loadRanked(freshPublished: String? = null, document: Document? = null): ServiceBook {
        val result = ServiceBookRepository(cache, ScheduleTransport { url ->
            val html = when (url) {
                plain.key.copy(topLevel = null).sourceUrl() -> MatinsRankFixtures.text("default-${plain.key.date}.html")
                plain.sourceUrl -> document?.outerHtml() ?: MatinsRankFixtures.text("rank3-${plain.key.date}.html")
                published.url -> freshPublished?.let { "<html><table><tr><td>${target.original}</td><td>$it</td></tr></table></html>" }
                    ?: throw IOException("Published supplement unavailable in this scenario")
                else -> throw IOException("Optional supplement unavailable in this scenario: $url")
            }
            ScheduleResponse(url, html.toByteArray(), "text/html;charset=UTF-8")
        }, translations = own).load(plain.key, force = true)
        assertTrue(result.toString(), result is BookLoadResult.Ready)
        return (result as BookLoadResult.Ready).book
    }
    private fun index(original: String, russian: String, source: BookSource): BookTranslationIndex =
        BookTranslationIndex.decode(JsonArray().apply { add(JsonObject().apply {
            addProperty("original", original); addProperty("russian", russian)
            addProperty("source", source.title); addProperty("url", source.url)
        }) }.toString())
    private fun replace(book: ServiceBook, id: String, change: (BookBlock) -> BookBlock): ServiceBook {
        val chapters = book.chapters.map { chapter -> chapter.copy(blocks = chapter.blocks.map { if (it.id == id) change(it) else it }) }
        val next = book.copy(chapters = chapters, sources = (book.sources + chapters.flatMap { it.blocks }.mapNotNull { it.translationSource }).distinct())
        return next.copy(contentHash = ServiceBookIntegrity.contentHash(next))
    }
    private fun assertOnlyTranslationChanges(before: ServiceBook, after: ServiceBook) {
        assertTrue(ServiceBookIntegrity.valid(after))
        assertEquals(before.blocks.size, after.blocks.size)
        before.blocks.zip(after.blocks).forEach { (old, new) ->
            if (new.translationSource == source) assertEquals(old, new.copy(russian = old.russian, translationSource = old.translationSource))
            else assertEquals(old, new)
        }
        assertEquals(before, after.copy(chapters = before.chapters, sources = before.sources, contentHash = before.contentHash))
    }
}

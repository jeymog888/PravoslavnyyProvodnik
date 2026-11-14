package ru.liturgyguide.service.book

import ru.liturgyguide.model.BookBlock
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.BookChapter
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.WorshipKind

/** A verified display role owns no source title, search proof, accepted edition or reading cursor. */
class BookMatinsOpeningDisplay private constructor(private val book: ServiceBook, private val opening: BookChapter?) {
    internal fun ownsSource(candidate: ServiceBook): Boolean = candidate === book
    fun title(chapter: BookChapter): String = if (chapter == opening) TITLE else chapter.title
    fun meaning(chapter: BookChapter): String? = if (chapter == opening) DETAIL else null

    /** A result can borrow a display title only from its exact source edition and validated source location. */
    fun title(located: BookLocatedSearchHit): String {
        val checked = located.validatedFor(book) ?: return located.hit.chapterTitle
        val chapter = book.chapters.singleOrNull { it.id == checked.hit.chapterId } ?: return located.hit.chapterTitle
        return title(chapter)
    }

    companion object {
        private const val TITLE = "Начало утрени"
        private const val DETAIL = "Это начало утрени: после благословения и приглашения к молитве читают псалмы 19 и 20, затем начальные молитвы и тропари. В тексте этого начала указано каждение храма. Далее следуют сугубая ектения и шестопсалмие"
        private data class Signature(val sha256: String, val kind: BookBlockKind, val speaker: String?)
        private val prefix = listOf(
            Signature("0c9295867e04e17545b738bc180055c1d0c882895d7f38d202a86df8ccf9b030", BookBlockKind.PRAYER, null),
            Signature("87f1a85f6e06ae8cfa52c547bc29007d09f9f2258b68ee2de8f37af2019a4a32", BookBlockKind.PRAYER, null),
            Signature("aa1d2ea0e1ed5ca53de675a72838b7d0f0ef893d1964e19bf3c0520ae3f1b7bd", BookBlockKind.PRAYER, "Чтец"),
            Signature("5ee0ed49345d3be42d0c292dd0ecadc6ca8b981dba0c67440d4f27b707708567", BookBlockKind.PRAYER, null),
            Signature("29cb0c35c36cacf5f097d2406bd655b55e8f8aeae1bd350c7d97f621297f5ab2", BookBlockKind.PRAYER, null),
            Signature("d22aa99d1e607a8341635f3efc68cfcc17d56b5042e818595ed7a5ae05630c04", BookBlockKind.PRAYER, null),
            Signature("f5fd8b20a36e8fd14c7ff385799618c3ee698d5e5694abdea03731863d521224", BookBlockKind.RUBRIC, null),
            Signature("085e2091d29fde16fae425c43dca7e217bfe950d5781e356fd6171680aa5e4e9", BookBlockKind.RUBRIC, null),
            Signature("734070219f6fa509dcec5e030e43117116162106af8896121be07d3111f0268a", BookBlockKind.PRAYER, null),
            Signature("d4908f181d397ab656f371cf996fbd977bade73cc64eb6c9879850696eb5ea9d", BookBlockKind.RUBRIC, null),
            Signature("afda6fbc3bd55bbbd4a8bfb9bb6548add37dcd591e9b3378585263adab8d006e", BookBlockKind.PRAYER, null)
        )
        private const val ECTENIA = "a0d99757ef385e8faa5771fd7d75ea90174b4d0399d06a746fabd743ebba1fe1"
        private const val SIX_PSALMS = "89cac41087141b9c0b71118a5cc38ed14da84a2fe1e764a8959886fa731ed0eb"

        /** Resolve once for an immutable book, then reuse the result for its visible rows. */
        fun of(book: ServiceBook): BookMatinsOpeningDisplay = BookMatinsOpeningDisplay(book, verifiedOpening(book))

        private fun verifiedOpening(book: ServiceBook): BookChapter? {
            if (book.key.worship != WorshipKind.MATINS || book.chapters.size < 3) return null
            val chapterIds = book.chapters.map { it.id }
            if (chapterIds.any(String::isBlank) || chapterIds.toSet().size != chapterIds.size) return null
            val blockIds = book.blocks.map { it.id }
            if (blockIds.any(String::isBlank) || blockIds.toSet().size != blockIds.size) return null
            val first = book.chapters.first()
            if (BookText.normalized(first.title) != "перед началом" || first.blocks.size < prefix.size) return null
            val visibility = BookVisibilityScope.of(book)
            if (first.blocks.any(visibility::isPrivate)) return null
            for ((index, expected) in prefix.withIndex()) {
                val block = first.blocks[index]
                val permittedKind = block.kind == expected.kind || index == 0 && block.kind == BookBlockKind.RUBRIC
                if (!permittedKind || block.speaker != expected.speaker || block.scriptureReferences.isNotEmpty() ||
                    BookText.sha256(block.original) != expected.sha256) return null
            }
            val ectenia = book.chapters[1]
            val sixPsalms = book.chapters[2]
            if (BookText.normalized(ectenia.title) != "ектения сугубая" ||
                BookText.normalized(sixPsalms.title) != "шестопсалмие") return null
            val invocation = ectenia.blocks.firstOrNull() ?: return null
            if (invocation.kind != BookBlockKind.PRAYER || invocation.speaker != "Священник" ||
                !publicWitness(invocation, ECTENIA, visibility)) return null
            val instruction = sixPsalms.blocks.firstOrNull() ?: return null
            if (!verifiedSixPsalmsInstruction(instruction, visibility)) return null
            return first
        }

        /** Only this full published instruction is legacy PRAYER and current verifiedRussianRubrics RUBRIC. */
        private fun verifiedSixPsalmsInstruction(block: BookBlock, visibility: BookVisibilityScope): Boolean =
            block.kind in setOf(BookBlockKind.PRAYER, BookBlockKind.RUBRIC) && block.speaker == null &&
                publicWitness(block, SIX_PSALMS, visibility)

        private fun publicWitness(block: BookBlock, sha256: String, visibility: BookVisibilityScope): Boolean =
            !visibility.isPrivate(block) && block.scriptureReferences.isEmpty() && BookText.sha256(block.original) == sha256
    }
}

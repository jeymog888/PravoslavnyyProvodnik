package ru.liturgyguide.service.book

import ru.liturgyguide.model.BookBlock
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.BookChapter
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.WorshipKind

data class BookQuietPrayerGroup(val chapterId: String, val introductionId: String, val blockIds: Set<String>) {
    val title: String get() = "Тихие молитвы священника"
    val explanation: String get() = "Священник читает тихо во время шестопсалмия"
}

/** Contextual presentation only. No accepted text, flag, ID or hash is rewritten. */
class BookVisibilityScope private constructor(val quietGroup: BookQuietPrayerGroup?) {
    fun isQuiet(blockId: String): Boolean = quietGroup?.blockIds?.contains(blockId) == true
    fun isPrivate(block: BookBlock): Boolean = block.isAltarText || isQuiet(block.id)

    fun isVisible(block: BookBlock, showAltar: Boolean = false, showQuiet: Boolean = showAltar): Boolean =
        if (isQuiet(block.id)) showQuiet else showAltar || !block.isAltarText

    fun firstVisible(chapter: BookChapter, showAltar: Boolean = false, showQuiet: Boolean = showAltar): BookBlock? =
        chapter.blocks.firstOrNull { isVisible(it, showAltar, showQuiet) }

    companion object {
        private const val INTRODUCTION = "Священник выходит из алтаря северной дверью и с непокрытой головой перед Царскими вратами тайно читает 12 утренних молитв."
        private val ordinals = listOf("первая", "вторая", "третия", "четвертая", "пятая", "шестая", "седмая",
            "осмая", "девятая", "десятая", "перваянадесять", "втораянадесять")
        private val psalm = Regex("псалом [0-9]+")

        fun of(book: ServiceBook): BookVisibilityScope {
            if (book.key.worship != WorshipKind.MATINS) return BookVisibilityScope(null)
            val chapter = book.chapters.singleOrNull { BookText.normalized(it.title) == "шестопсалмие" }
                ?: return BookVisibilityScope(null)
            val introduction = chapter.blocks.indices.singleOrNull { index ->
                chapter.blocks[index].kind == BookBlockKind.RUBRIC && !chapter.blocks[index].isAltarText &&
                    BookText.clean(chapter.blocks[index].original) == INTRODUCTION
            } ?: return BookVisibilityScope(null)
            val earlier = chapter.blocks.take(introduction).filter { psalm.matches(BookText.normalized(it.original)) }
            if (earlier.map { BookText.normalized(it.original) } != listOf("псалом 3", "псалом 37", "псалом 62") ||
                earlier.any { it.kind != BookBlockKind.RUBRIC || it.isAltarText }) return BookVisibilityScope(null)
            val following = chapter.blocks.getOrNull(introduction + 37) ?: return BookVisibilityScope(null)
            if (following.kind != BookBlockKind.RUBRIC || following.isAltarText ||
                BookText.clean(following.original) != "Псалом 87") return BookVisibilityScope(null)
            val group = chapter.blocks.subList(introduction + 1, introduction + 37)
            if (ordinals.indices.all { group[it * 3].kind == BookBlockKind.PRAYER }) {
                if (!isPublishedLegacyGroup(book, group)) return BookVisibilityScope(null)
            } else {
                for ((index, ordinal) in ordinals.withIndex()) {
                    val label = group[index * 3]
                    if (label.kind != BookBlockKind.RUBRIC || BookText.clean(label.original) != "Молитва $ordinal:" ||
                        group.subList(index * 3 + 1, index * 3 + 3).any {
                            it.kind != BookBlockKind.PRAYER || BookText.clean(it.original).isEmpty()
                        }) return BookVisibilityScope(null)
                }
            }
            val ids = group.map { it.id }.toSet()
            if (ids.size != 36) return BookVisibilityScope(null)
            return BookVisibilityScope(BookQuietPrayerGroup(chapter.id, chapter.blocks[introduction].id, ids))
        }

        /**
         * Compatibility for byte-preserved accepted editions whose labels were
         * PRAYER. Unlike the existing RUBRIC form, this legacy shape needs the
         * complete published bodies and source identity before it can hide text.
         * Enrichment, fetch time and the accepted edition hash do not define role.
         */
        private fun isPublishedLegacyGroup(book: ServiceBook, group: List<BookBlock>): Boolean {
            if (!isPublishedLegacySource(book)) return false
            if (group.any { it.kind != BookBlockKind.PRAYER || it.isAltarText ||
                    it.speaker != null || it.scriptureReferences.isNotEmpty() }) return false
            for ((index, ordinal) in ordinals.withIndex()) {
                if (BookText.clean(group[index * 3].original) != "Молитва $ordinal:") return false
                for (part in 0..1) {
                    if (BookText.sha256(group[index * 3 + part + 1].original) != legacyBodyHashes[index * 2 + part])
                        return false
                }
            }
            val ids = group.map { it.id }.toSet()
            return ids.size == 36 && ids.none(String::isBlank) && book.blocks.count { it.id in ids } == 36
        }

        private fun isPublishedLegacySource(book: ServiceBook): Boolean = runCatching {
            val key = book.key
            if (!key.isValid() || key.worship != WorshipKind.MATINS ||
                key.patronalId != null || book.patronalSelection != null) return@runCatching false
            if (key.topLevel == null) {
                book.rankSelection == null && book.sourceUrl == key.sourceUrl()
            } else MatinsRankSource.validBook(book)
        }.getOrDefault(false)

        // Occurrences are ordered, including the repeated conclusion of prayers 5 and 12.
        private val legacyBodyHashes = listOf(
            "4605b6a2a017b9f598dcb2cc17b72bded9cd19cbac4d677d87bee1baad61537e",
            "28ec5456960b8484c50043f77818ca1e3d7e9cae020d1a858f62823322934672",
            "bd82efcc2290c5c077d172d39603f0f9f3b428f32de0527c3c4032692eb837bf",
            "7dfbe095db7b0c5b2cbed57fce027aeaec6b5c76309d189ef90e3759f6840de9",
            "cb3acd18290768ebc50c69a65cad2cc93316c22d26cb3f8d38a4264a1cb5278b",
            "e37aac8e53dcf6d35c08653c7ee843e67d7c189c93c2417298f64377f666a008",
            "8ab6d6bfbf6eb3015919d46dd195cdd1f670f121875250ece5f0d4bc9e5bf9b4",
            "0c7f3ecb45b8fceeec77ce39c1900ca1ad3a169340ce55559158b5c1f20c536c",
            "ef54453507d7b8e545326d516516a25b9a07dfb1d2f32f670674808fe965610a",
            "ddf6919d1cce9bbfa345ab5f484a3e7875b37158ab5819f180436c9341d0ad7c",
            "e4e820e3b8beca1dd01807d1559f874506faaa74012da2e9d858167b5f558087",
            "91b97770f9da4355fa0f34487ba2383095b82bc35db71d9e72fc0c44b04d9bc5",
            "9d5e75f175e1f745f1e46e47c3e06a5b87d79b19cbe888f1a36ca9b1d0cd8965",
            "3dc68aab489d2142158a31f0a704aa0f653f4b84b33f147d31799eddc0a42a0c",
            "23a421f99f810bc6cc3edefa661f52b8fe88afa3ee5567735b0d92672e79097e",
            "0d49b46c725247fdba8516133f09dd53e1703abd84ea79ec404e28009ddce92c",
            "b3ab28c8d66b4838cbb5169cdabd8e7c2b3af924f23709bb34e61bf65e96ee88",
            "35ae9a3ca836d73e45b382c2ab82db6185dcc6b93e3924dca1f6e5f4e6713c80",
            "a7280fe6b6416fb248337266a238742d91cf77d433e5542b130a13d2a1c3b8e2",
            "9a270e2d62d701c13df5d1d2fdd093da6ab3037926006cd17388b0cadb1652f7",
            "045c2880bbae4c501365a83c2e1ca8d9e7930fe4ab8fbc236b0c53b303ce19fc",
            "542846263f13d8ff92191b25a952ac90406e0e692e074d44a8493a56abd62871",
            "7b525faede49cae00a2beeb7422cb3130978b89bd27135f4b94d293db626bf54",
            "ddf6919d1cce9bbfa345ab5f484a3e7875b37158ab5819f180436c9341d0ad7c"
        )
    }
}

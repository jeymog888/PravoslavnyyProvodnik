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
            for ((index, ordinal) in ordinals.withIndex()) {
                val label = group[index * 3]
                if (label.kind != BookBlockKind.RUBRIC || BookText.clean(label.original) != "Молитва $ordinal:" ||
                    group.subList(index * 3 + 1, index * 3 + 3).any {
                        it.kind != BookBlockKind.PRAYER || BookText.clean(it.original).isEmpty()
                    }) return BookVisibilityScope(null)
            }
            val ids = group.map { it.id }.toSet()
            if (ids.size != 36) return BookVisibilityScope(null)
            return BookVisibilityScope(BookQuietPrayerGroup(chapter.id, chapter.blocks[introduction].id, ids))
        }
    }
}

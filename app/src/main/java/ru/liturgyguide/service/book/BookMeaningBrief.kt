package ru.liturgyguide.service.book

import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.BookChapter
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.WorshipKind

enum class BookMeaningCueKind { HEAR, SEE, QUIET }
data class BookMeaningCue(val kind: BookMeaningCueKind, val text: String)
data class BookMeaningBrief(val topicId: String, val whatHappens: String, val why: String,
    val cue: BookMeaningCue? = null, val sourceUrl: String, val title: String? = null, val detail: String? = null)

/** A temporary explanation owns no reading cursor and cannot rewrite an accepted edition. */
data class BookMeaningContent(val position: BookReadingPosition, val chapterTitle: String,
    val excerpt: String?, val detail: String, val brief: BookMeaningBrief?, val privateText: Boolean) {
    companion object {
        /** The virtual card and its real introduction deliberately share only the persisted cursor. */
        fun captureQuietGroup(book: ServiceBook, position: BookReadingPosition,
            dismissal: BookFirstHourDismissalScope = BookFirstHourDismissalScope.of(book)): BookMeaningContent? {
            val group = BookVisibilityScope.of(book).quietGroup ?: return null
            if (position.chapterId != group.chapterId || position.blockId != group.introductionId) return null
            return capture(book, position, dismissal)?.copy(chapterTitle = group.title, excerpt = null,
                detail = group.explanation, brief = null, privateText = true)
        }

        fun capture(book: ServiceBook, position: BookReadingPosition,
            dismissal: BookFirstHourDismissalScope = BookFirstHourDismissalScope.of(book)): BookMeaningContent? {
            if (position.key != book.key || position.contentHash != book.contentHash) return null
            val chapter = book.chapters.singleOrNull { it.id == position.chapterId } ?: return null
            val block = chapter.blocks.singleOrNull { it.id == position.blockId } ?: return null
            val textPosition = position.forTextReading()
            val visibility = BookVisibilityScope.of(book)
            val openingDisplay = BookMatinsOpeningDisplay.of(book)
            val privateText = visibility.isPrivate(block)
            val excerpt = if (position.anchorKind == BookAnchorKind.END) null else
                (if (textPosition.mode == BookReadingMode.RUSSIAN)
                    BookBlockPresentation.effectiveRussian(book, block, dismissal) ?: block.original else block.original)
                    .takeIf { it.isNotBlank() }
            val candidateBrief = (if (privateText || block.kind == BookBlockKind.LOCAL_VARIATION) null
                else BookMeaningBriefCatalog.forPlace(book, chapter, block.id))?.takeUnless {
                    it.topicId == BookMeaningBriefCatalog.HOURS_COMMON_PRAYER_TOPIC && position.anchorKind != BookAnchorKind.BLOCK
                }
            // Only the exact public Hours paragraph can precede a heading-based unknown fragment.
            val hoursPrayer = candidateBrief?.topicId == BookMeaningBriefCatalog.HOURS_COMMON_PRAYER_TOPIC
            // Old accepted Matins copied the public six-psalms help onto every quiet
            // prayer. That block.meaning cannot identify this private paragraph.
            val fragment = if (hoursPrayer || privateText || block.kind == BookBlockKind.LOCAL_VARIATION) null
                else BookAfterCommunionMeaning.forPlace(book, chapter, block)
            val dismissalMeaning = if (!privateText && position.anchorKind == BookAnchorKind.BLOCK)
                dismissal.meaning(book, chapter, block.id) else null
            val detail = when {
                dismissalMeaning != null -> dismissalMeaning
                fragment != null -> fragment.detail
                visibility.isQuiet(block.id) -> requireNotNull(visibility.quietGroup).explanation
                privateText -> BookMeaningCatalog.meaningFor("В алтаре").orEmpty()
                else -> BookMeaningCatalog.displayMeaning(book, chapter, openingDisplay, dismissal,
                    block.id.takeIf { position.anchorKind == BookAnchorKind.BLOCK } ?: "")
            }
            val brief = if (fragment != null) fragment.brief else candidateBrief
            val scopedDetail = brief?.detail ?: if (brief?.topicId == "vespers-psalm-103" && BookText.normalized(chapter.title) == "перед началом")
                BookMeaningCatalog.meaningFor("Псалом 103", block.original, "vespers").orEmpty() else detail
            return BookMeaningContent(textPosition, fragment?.title ?: brief?.title ?: openingDisplay.title(chapter), excerpt, scopedDetail, brief, privateText)
        }
    }
}

/** Narrow reviewed help. A familiar title without a positively checked body is insufficient. */
object BookMeaningBriefCatalog {
    internal const val HOURS_COMMON_PRAYER_TOPIC = "hours-common-prayer"

    fun forPlace(book: ServiceBook, chapter: BookChapter, blockId: String): BookMeaningBrief? {
        hoursPrayerForPlace(book, chapter, blockId)?.let { return it }
        forChapter(book, chapter)?.let { return it }
        // The real Vespers source marks Psalm 103 with h4 inside "Перед началом".
        // Only its positively checked public body (or its immediately preceding label)
        // gets this help; other opening prayers keep their own chapter explanation.
        if (book.key.worship != WorshipKind.VESPERS || BookText.normalized(chapter.title) != "перед началом") return null
        val index = chapter.blocks.indexOfFirst { it.id == blockId }.takeIf { it >= 0 } ?: return null
        val selected = chapter.blocks[index]
        val candidate = if (selected.kind == BookBlockKind.RUBRIC && BookText.normalized(selected.original) == "псалом 103")
            chapter.blocks.getOrNull(index + 1) ?: return null else selected
        if (candidate.kind != BookBlockKind.PRAYER || candidate.isAltarText) return null
        return forChapter(book, chapter.copy(title = "Псалом 103", blocks = listOf(candidate)))?.copy(title = "Псалом 103")
    }

    /** Full public prayer only. Heading, translation availability and a familiar prefix do not identify it. */
    private fun hoursPrayerForPlace(book: ServiceBook, chapter: BookChapter, blockId: String): BookMeaningBrief? {
        if (!book.key.isValid()) return null
        val source = when (book.key.worship) {
            WorshipKind.HOUR1 -> BookTranslationIndex.HOUR1_SOURCE
            WorshipKind.HOUR3 -> BookTranslationIndex.HOUR3_SOURCE
            WorshipKind.HOUR6 -> BookTranslationIndex.HOUR6_SOURCE
            WorshipKind.HOUR9 -> BookTranslationIndex.HOUR9_SOURCE
            else -> return null
        }
        val owned = book.chapters.singleOrNull { it.id == chapter.id }?.takeIf { it == chapter } ?: return null
        val block = owned.blocks.singleOrNull { it.id == blockId } ?: return null
        if (book.blocks.count { it.id == blockId } != 1 || block.kind != BookBlockKind.PRAYER ||
            BookVisibilityScope.of(book).isPrivate(block) || BookText.sha256(BookText.normalized(block.original)) !=
            "b34617e4f9f0b3d91df6d72415ffcb814b020f7e58791f6aa4083105f3f3b4aa") return null
        return BookMeaningBrief(HOURS_COMMON_PRAYER_TOPIC,
            "Эту молитву Христу читают на Часах - коротких службах из псалмов и молитв",
            "Просим Христа направить нашу жизнь по Его заповедям, очистить мысли и сохранить нас от зла",
            BookMeaningCue(BookMeaningCueKind.HEAR, "Иже на всякое время и на всякий час"),
            source.url, title = "Молитва о нашей жизни", detail = "Молитва обращена ко Христу, Который любит праведных, милует грешников и зовет всех к спасению. Просьба \"исправи живот наш\" означает: направь нашу жизнь по Твоим заповедям. Мы просим освятить душу, очистить тело и мысли, избавить от бед и болезней. Христа просят оградить нас Ангелами - то есть даровать через них защиту и наставление. Завершение говорит о единстве в вере и познании Божьей славы")
    }

    fun forChapter(book: ServiceBook, chapter: BookChapter): BookMeaningBrief? {
        val heading = BookText.normalized(chapter.title)
        val visibility = BookVisibilityScope.of(book)
        val public = chapter.blocks.filter { !visibility.isPrivate(it) }
        val prayers = public.filter { it.kind == BookBlockKind.PRAYER }
            .map { BookText.normalized(BookText.withoutSpeaker(it.original).second) }
        fun starts(phrase: String) = prayers.any { it == phrase || it.startsWith("$phrase ") }
        fun contains(phrase: String) = prayers.any { " $it ".contains(" $phrase ") }
        val ordinaryLiturgy = book.key.worship == WorshipKind.LITURGY &&
            BookText.normalized(book.title) in setOf("литургия", "литургия иоанна златоуста",
                "литургия святителя иоанна златоуста", "литургия василия великого", "литургия святителя василия великого")
        val replacements = listOf("ныне силы небесныя", "да молчит всякая плоть", "вечери твоея тайныя")
        return when {
            book.key.worship in setOf(WorshipKind.LITURGY, WorshipKind.VESPERS, WorshipKind.MATINS) &&
                heading in setOf("великая ектения", "ектения великая") &&
                starts("миром господу помолимся") && contains("о свышнем мире") && contains("о мире всего мира") ->
                BookMeaningBrief("great-litany",
                    "Диакон или священник произносит просьбы, а хор отвечает от лица молящихся",
                    "Вместе молимся о мире, Церкви и людях, которым нужна помощь",
                    BookMeaningCue(BookMeaningCueKind.HEAR, "Миром Господу помолимся"),
                    "https://www.oca.org/orthodoxy/the-orthodox-faith/worship/the-divine-liturgy/great-litany")
            ordinaryLiturgy && heading in setOf("херувимская", "херувимская песнь") &&
                starts("иже херувимы тайно образующе") &&
                chapter.blocks.none { block -> replacements.any { BookText.normalized(block.original).contains(it) } } ->
                BookMeaningBrief("cherubic-ordinary",
                    "Во время этой песни хлеб и вино переносят на престол - главный стол в алтаре",
                    "Песня приглашает отложить повседневные заботы и вместе с ангелами прославить Бога",
                    BookMeaningCue(BookMeaningCueKind.HEAR, "Иже Херувимы"),
                    "https://www.oca.org/orthodoxy/the-orthodox-faith/worship/the-divine-liturgy/offertory-great-entrance")
            ordinaryLiturgy && heading in setOf("святое возношение", "анафора", "евхаристическая молитва",
                "евхаристический канон", "канон евхаристический") &&
                contains("станем добре") && contains("твоя от твоих") && contains("тебе поем") ->
                BookMeaningBrief("anaphora-ordinary",
                    "Церковь благодарит Бога, вспоминает Тайную вечерю и молится об освящении Даров",
                    "По вере Церкви хлеб и вино становятся Телом и Кровью Христовыми",
                    BookMeaningCue(BookMeaningCueKind.HEAR, "Тебе поем"),
                    "https://www.oca.org/orthodoxy/the-orthodox-faith/worship/the-divine-liturgy/eucharistic-canon-anaphora")
            book.key.worship == WorshipKind.VESPERS && heading == "псалом 103" &&
                starts("благослови душе моя господа господи боже мой") && contains("вся премудростию сотворил еси") ->
                BookMeaningBrief("vespers-psalm-103",
                    "В начале вечерни читают или поют псалом о сотворенном Богом мире",
                    "Благодарим Бога как Творца и Подателя жизни", sourceUrl =
                    "https://www.oca.org/orthodoxy/the-orthodox-faith/worship/the-daily-cycles-of-prayer/vespers")
            book.key.worship == WorshipKind.MATINS && heading == "шестопсалмие" &&
                public.filter { it.kind == BookBlockKind.RUBRIC }.map { BookText.normalized(it.original) }
                    .containsAll(listOf(3, 37, 62, 87, 102, 142).map { "псалом $it" }) &&
                starts("господи что ся умножиша стужающии ми") && starts("господи услыши молитву мою") ->
                BookMeaningBrief("matins-six-psalms",
                    "В начале утрени читают шесть псалмов о беде, покаянии и надежде на помощь Бога",
                    "Эти молитвы помогают обратиться к Богу и с тревогой, и с надеждой", sourceUrl =
                    "https://www.oca.org/orthodoxy/the-orthodox-faith/worship/the-daily-cycles-of-prayer/matins")
            else -> null
        }
    }
}

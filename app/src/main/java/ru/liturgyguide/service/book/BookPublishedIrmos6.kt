package ru.liturgyguide.service.book

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.BookSource
import ru.liturgyguide.model.ServiceBook

/** Eight reviewed whole irmoi, not a parser for arbitrary hymns or a synonym dictionary. */
internal object BookPublishedIrmos6 {
    val SATURDAY = BookSource("Перевод иеромонаха Амвросия (Тимрота), Азбука веры",
        "https://azbyka.ru/bogosluzhenie/strastnaya-sedmicza-velikaya-subbota/")
    val FRIDAY = SATURDAY.copy(url = "https://azbyka.ru/bogosluzhenie/strastnaya-sedmicza-velikaya-pyatnicza/")
    val SOURCES = listOf(SATURDAY, FRIDAY)
    private data class Entry(val song: Int, val target: String, val published: String, val originalSha: String, val russianSha: String, val source: BookSource)
    private val entries = listOf(
        Entry(1, "Волно́ю морско́ю Скры́вшаго дре́вле гони́теля мучи́теля под земле́ю скры́ша спасе́нных о́троцы: но мы́ я́ко отрокови́цы, Го́сподеви пои́м, сла́вно бо просла́вися.", "Волною морскою / Скрывшаго древле, / гонителя мучителя, под землею скрыша / спасенных отроцы; / но мы, яко отроковицы, / Господеви поим, / славно бо прославися.", "f8ad74410338bfa3f0ecf0a7fdc2bdd1e8eb77f5e27b7cb2b506875505c29ad5", "b80adf186530b64c6c8ce55d3a70bef637f706a6c9e99207c748b94acdd3b47f", SATURDAY),
        Entry(3, "Тебе́ на вода́х пове́сившаго всю́ зе́млю неодержи́мо, тва́рь ви́девши на ло́бнем ви́сима, у́жасом мно́гим содрага́шеся, не́сть свя́т ра́зве Тебе́, Го́споди, взыва́ющи.", "Тебе, на водах / повесившаго / всю землю неодержимо, / тварь видевши / на лобнем висима, / ужасом многим содрогашеся, / несть свят, / разве Тебе Господи, взывающи.", "1eb313f8cc194000dd16f91fbfcee03dc9d08d5d2978ee21ca6fe86851ab042a", "a7e601a4f071949f2a2bf02cc6e70c613bf68f306352dece158ca61ba2b122f2", SATURDAY),
        Entry(4, "На Кресте́ Твое́ Боже́ственное истоща́ние прови́дя Авваку́м, ужа́сся вопия́ше: Ты́ си́льных пресе́кл еси́ держа́ву, Бла́же, приобща́яся су́щым во а́де, я́ко всеси́лен.", "На Кресте Твое / Божественное истощание / провидя Аввакум, / ужасся, вопияше: / Ты сильных пресекл еси / державу Блаже, / приобщаяся сущим во аде, яко Всесилен.", "c860eae471f8a11f825b270c3018c7f58065cc526247dffbe181a1e440f0091d", "bb33e73d84bbc931ff69320cdef1b14bfa99a34089dd89b021ba15d6c561f3d2", SATURDAY),
        Entry(5, "К Тебе́ у́тренюю, милосе́рдия ра́ди себе́ истощи́вшему непрело́жно, и до страсте́й безстра́стно прекло́ньшемуся, Сло́ве Бо́жий, ми́р пода́ждь ми́ па́дшему, Человеколю́бче.", "К Тебе утренюю, / милосердия ради Себе истощившему непреложно / и до страстей безстрастно преклоншемуся, / Слове Божий, / мир подаждь ми падшему, Человеколюбче.", "fec4268caa5e084ef6815f708c02fd48ac9b5473813a0c77775b124ac6a691f8", "0a32cbb7163ac37361e71e995d52acc6ffcaa6eeaea1f52977ca03f04a6d0ca5", FRIDAY),
        Entry(6, "Я́т бы́сть, но неудержа́н в пе́рсех ки́товых Ио́на: Тво́й бо о́браз нося́, Страда́вшаго и погребе́нию да́вшагося, я́ко от черто́га от зве́ря изы́де: приглаша́ше же кустоди́и: храня́щии су́етная и ло́жная, ми́лость сию́ оста́вили есте́.", "Ят бысть, / но не удержан в персех китовых Иона: / Твой бо образ нося, / Страдавшаго, и погребению давшагося, / яко от чертога от зверя изыде. / Приглашаше же кустодии, / хранящии суетная и ложная, / милость сию оставили есте.", "510e5a1f04d83aef4d67b68ff146c9da9b37c21b3d7a6cd1dde6271f68dd0833", "82948a6fadbab075e4bcd4bb285e596936ee6c354ff0d77770aa069e74cccedb", SATURDAY),
        Entry(7, "Неизрече́нное чу́до, в пещи́ изба́вивый преподо́бныя о́троки из пла́мене, во гро́бе ме́ртв бездыха́нен полага́ется, во спасе́ние на́с пою́щих: Изба́вителю Бо́же, благослове́н еси́.", "Неизреченное чудо, / в пещи избавивый / преподобныя отроки из пламене, / во гробе мертв / бездыханен полагается, / во спасение нас поющих: / Избавителю Боже, / благословен еси.", "f50ddf8b0fa7a2db2f6981c27fac9f1d7acabc06dbd817a25c4572181e9ceeb2", "45341a59b5acb7112b46b525eb752fa3d2fd1e7b177a5be8bd945f2402331025", SATURDAY),
        Entry(8, "Ужасни́ся боя́йся не́бо, и да подви́жатся основа́ния земли́: се́ бо в мертвеце́х вменя́ется в Вы́шних Живы́й, и во Гро́б ма́л странноприе́млется: Его́же о́троцы благослови́те, свяще́нницы воспо́йте, лю́дие превозноси́те во вся́ ве́ки.", "Ужаснися бояйся небо, / и да подвижатся основания земли: / се бо в мертвецех вменяется в вышних Живый, / и во гроб мал странноприемлется. / Егоже отроцы благословите, / священницы воспойте, / людие превозносите во вся веки.", "22888878ff1570ee278c944c8449b4a5af6e12b466e43e3f6dd1b9e56973817f", "4cfe1838589f2c0fce8eb666998b1716e7851ecc402be29d7a0995addfa57169", SATURDAY),
        Entry(9, "Не рыда́й мене́, Ма́ти, зря́щи во гро́бе, Его́же во чре́ве без се́мене зачала́ еси́ Сы́на: воста́ну бо и просла́влюся, и вознесу́ со сла́вою непреста́нно я́ко Бо́г, ве́рою и любо́вию Тя́ велича́ющыя.", "Не рыдай Мене Мати, / зрящи во гробе, / Егоже во чреве без семене зачала еси Сына: / востану бо и прославлюся, / и вознесу со славою, непрестанно яко Бог, / верою и любовию Тя величающия.", "4a68bf084969a9d0e2b1fcae8edad33e03ff064d00b3ecb6bf64acede298cd08", "4dbd9ba634b2150c11ff4ae2d42052668bcb0ce8ba4b4be346081b4c5bcf86d9", SATURDAY),
    )
    private val heading = Regex("^Ирмос, глас 6:\\s+(.+)$", RegexOption.DOT_MATCHES_ALL)
    // Route aliases through the strict contract; extract/recognizesBody never trust them.
    fun registered(source: BookSource) = SOURCES.any {
        it.url == source.url.substringBefore('?').substringBefore('#')
    }

    fun findBody(original: String): String? {
        val body = heading.matchEntire(BookText.clean(original))?.groupValues?.get(1) ?: return null
        return body.takeIf { BookText.withoutSpeaker(it).first == null && entries.any { entry ->
            BookTranslationIndex.normal(it) in setOf(BookTranslationIndex.normal(entry.target), BookTranslationIndex.normal(entry.published))
        } }
    }

    fun recognizesBody(original: String, pair: BookTranslation): Boolean = entries.any { entry ->
        BookText.withoutSpeaker(original).first == null && pair.source == entry.source && BookText.sha256(BookText.clean(pair.russian)) == entry.russianSha &&
            BookTranslationIndex.normal(original) in setOf(BookTranslationIndex.normal(entry.target), BookTranslationIndex.normal(entry.published))
    }

    fun sourcesForMissing(book: ServiceBook): List<BookSource> = entries.filter { entry ->
        book.blocks.any { block -> block.kind == BookBlockKind.PRAYER && block.russian == null &&
            findBody(block.original)?.let { BookTranslationIndex.normal(it) in setOf(BookTranslationIndex.normal(entry.target), BookTranslationIndex.normal(entry.published)) } == true }
    }.map { it.source }.distinct()

    fun extract(document: Document, source: BookSource): Map<String, BookTranslation> {
        if (source !in SOURCES || document.location() != source.url) return emptyMap()
        val expectedTitle = if (source == SATURDAY) "страстная седмица великая суббота" else "страстная седмица великая пятница"
        if (document.select("h1").singleOrNull()?.text()?.let(BookText::normalized) != expectedTitle) return emptyMap()
        val article = document.select(".article-single-content.main-page-content").singleOrNull() ?: return emptyMap()
        val rows = article.select("table tr").mapNotNull { row ->
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.size != 2 || cells.any { it.attr("colspan") !in setOf("", "1") || it.attr("rowspan") !in setOf("", "1") }) null
            else cells[0] to cells[1]
        }
        val result = linkedMapOf<String, BookTranslation>()
        for (entry in entries.filter { it.source == source }) {
            val candidates = rows.mapNotNull { (left, right) ->
                val a = body(left, entry.song, source == FRIDAY, true) ?: return@mapNotNull null
                if (BookText.sha256(a) != entry.originalSha) return@mapNotNull null
                val b = body(right, entry.song, source == FRIDAY, false) ?: return@mapNotNull null
                a to b
            }
            val expectedOccurrences = if (source == SATURDAY) 2 else 1
            if (candidates.size != expectedOccurrences || candidates.any { BookText.sha256(it.second) != entry.russianSha }) continue
            val pair = BookTranslation(candidates.first().second, source)
            result[BookTranslationIndex.normal(entry.target)] = pair
            result[BookTranslationIndex.normal(entry.published)] = pair
        }
        return result
    }

    private fun body(cell: Element, song: Int, friday: Boolean, slavonic: Boolean): String? {
        val copy = cell.clone()
        fun meaningful(node: Node) = node !is TextNode || node.text().isNotBlank()
        var nodes = copy.childNodes().filter(::meaningful)
        val title = nodes.firstOrNull() as? Element
        if (title?.tagName() == "h3") {
            if (BookText.normalized(title.text()) != "песнь $song") return null
            title.remove(); nodes = copy.childNodes().filter(::meaningful)
        }
        val container = if (nodes.size == 1 && (nodes.single() as? Element)?.tagName() == "p") nodes.single() as Element else copy
        nodes = container.childNodes().filter(::meaningful)
        if (!friday && slavonic && song in 6..9) {
            val acrostich = nodes.firstOrNull() as? Element ?: return null
            val symbol = mapOf(6 to "{Σ}", 7 to "{Α}", 8 to "{Ε}", 9 to "{Μ}")[song]
            if (acrostich.tagName() != "span" || acrostich.classNames() != setOf("n") || BookText.clean(acrostich.text()) != symbol) return null
            acrostich.remove(); nodes = container.childNodes().filter(::meaningful)
        }
        val label = nodes.firstOrNull() as? Element ?: return null
        val expected = if (friday && slavonic) "{Π} Ирмос:" else "Ирмос:"
        if (label.tagName() != "span" || label.classNames() != setOf("k") || BookText.clean(label.text()) != expected) return null
        label.remove()
        if (container.select("h1,h2,h3,h4,h5,h6,span.k,span.n,table").isNotEmpty()) return null
        return BookText.clean(container.text()).takeIf { it.isNotBlank() }
    }
}

package ru.liturgyguide.service.book

/** Reviewed project translations, separate from the older explanatory paraphrases. */
internal object FeoriaTranslationCatalog {
    const val REVISION = 2
    private const val ORIGINAL_SOURCE_URL = "https://azbyka.ru/worships/?date=2026-11-13&worship=matins&size=full&church=34&main_event=0&top_level=3"

    data class Entry(val id: String, val original: String, val russian: String,
        // Entry revisions describe their wording; adding new entries does not revise the old eight.
        val revision: Int = 1, val originalSourceUrl: String = ORIGINAL_SOURCE_URL)

    val entries = listOf(
        Entry("apostles_refrain",
            "Припев: Святи́и апóстоли, моли́те Бо́га о на́с.",
            "Припев: Святые апостолы, молитесь Богу о нас."),
        Entry("alleluia_refrain_three_times",
            "Припев: Аллилу́ия (трижды).",
            "Припев: Хвалите Господа (трижды)."),
        Entry("come_worship_god_our_king",
            "Прииди́те, поклони́мся Царе́ви на́шему Бо́гу.",
            "Придите, поклонимся Богу, нашему Царю."),
        Entry("come_worship_christ_our_king_and_god",
            "Прииди́те, поклони́мся и припаде́м Христу́, Царе́ви на́шему Бо́гу.",
            "Придите, поклонимся и падем ниц перед Христом, нашим Царем и Богом."),
        Entry("come_worship_christ_himself",
            "Прииди́те, поклони́мся и припаде́м Самому́ Христу́, Царе́ви и Бо́гу на́шему.",
            "Придите, поклонимся и падем ниц перед Самим Христом, нашим Царем и Богом."),
        Entry("apostles_magnification",
            "Велича́ем ва́с, святи́и апо́столи, ве́сь ми́р уче́ньми свои́ми просвети́вшыя, и вся́ концы́ ко Христу́ приве́дшыя.",
            "Прославляем вас, святые апостолы, которые своим учением просветили весь мир и привели ко Христу все края земли."),
        Entry("for_you_are_our_god",
            "Я́ко Ты еси́ Бог наш, и Тебе́ сла́ву возсыла́ем, Отцу́, и Сы́ну, и Свято́му Ду́ху, ны́не и при́сно, и во ве́ки веко́в. Ами́нь.",
            "Ибо Ты - наш Бог, и Тебе мы воздаем славу: Отцу, Сыну и Святому Духу, сейчас, всегда и вечно. Аминь."),
        Entry("their_voice_all_the_earth",
            "Во всю зе́млю изы́де веща́ние их / и в концы́ вселе́нныя глаго́лы их.",
            "Их голос прозвучал по всей земле, и их слова достигли краев мира."),
        Entry("refrain_cross_glory",
            "Припев: Сла́ва, Го́споди, Кресту́ Твоему́ честно́му.",
            "Припев: Слава, Господи, Твоему Кресту, достойному почитания.", revision = 2,
            originalSourceUrl = "https://azbyka.ru/worships/?date=2026-11-13&worship=matins&size=full&church=20"),
        Entry("holy_martyrs_refrain",
            "Припев: Святи́и му́ченицы, моли́те Бо́га о нас.",
            "Припев: Святые мученики, молитесь Богу о нас.", revision = 2,
            originalSourceUrl = "https://azbyka.ru/worships/?date=2026-11-13&worship=matins&size=full&church=20"),
        Entry("saint_epimachus_refrain",
            "Припев: Святы́й му́чениче Епимáше, моли́ Бо́га о на́с.",
            "Припев: Святой мученик Епимах, молись Богу о нас.", revision = 2,
            originalSourceUrl = "https://azbyka.ru/worships/?date=2026-11-13&worship=matins&size=full&church=20")
    )

    fun recognizes(original: String, translation: BookTranslation): Boolean =
        translation.source == BookTranslationIndex.FEORIA_TRANSLATION_SOURCE && translation.meaning == null &&
            entries.any { BookTranslationIndex.normal(it.original) == BookTranslationIndex.normal(original) &&
                it.russian == translation.russian }
}

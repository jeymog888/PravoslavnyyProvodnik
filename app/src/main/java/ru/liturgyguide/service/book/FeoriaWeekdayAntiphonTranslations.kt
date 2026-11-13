package ru.liturgyguide.service.book

/** Fifteen independently reviewed whole weekday antiphon bodies, with no date-based matching. */
internal object FeoriaWeekdayAntiphonTranslations {
    const val SOURCE_CANDIDATE_SHA256 = "95daeebe48044e4336bfcc1a620f69682bf88616014e480aa7e5e38690aae817"
    const val SEMANTIC_REVIEW_SHA256 = "041ccf1476bddce80506c518425a237b2aba13b04e23cfadedfc098c789e905a"
    const val APPROVED_FINAL_PROJECTION_SHA256 = "31aef9237132aeed30b7d9b14561149859913e148820ff9cf29196fb611de32e"
    const val SOURCE_DOCUMENT_SHA256 = "9edee9dea1ba23d3c468b48ff8471b7641618d10e760f80f25817885f7145354"
    // Preserved measurement provenance, never substituted for a newly constructed book hash.
    const val MEASURED_SOURCE_CONTENT_HASH = "10b3808b40aacbb86ebf1008a062cdfd2154569d4fbc6fcb13d74cef28a7aff3"
    private const val ORIGINAL_SOURCE_URL = "https://azbyka.ru/worships/?date=2026-11-13&worship=liturgy&size=full&church=30"

    data class Record(val entry: FeoriaTranslationCatalog.Entry, val originalSha256: String,
        val normalizedOriginalSha256: String, val russianSha256: String,
        val sourceBlockId: String, val sourceChapterId: String, val sourceSpeaker: String)

    val records = listOf(
        Record(FeoriaTranslationCatalog.Entry("weekday-antiphon-29db7c9aa74f199c450b",
            "Я́ко Того́ есть мо́ре, и Той сотвори́ е́,/ и су́шу ру́це Его́ созда́сте. Спаси́ ны, Сы́не Бо́жий, во святы́х ди́вен сый, пою́щия ти: аллилу́иа.",
            "Ибо море принадлежит Ему, и Он создал его, и сушу создали Его руки. Сын Божий, дивный в святых, спаси нас, поющих Тебе: аллилуия", revision = 5, originalSourceUrl = ORIGINAL_SOURCE_URL),
            "340eaa641b7f130bdb54388a7788ad8b8ef9326765ee8dd68fd929f4fed7dfc9",
            "29db7c9aa74f199c450b19dabd6407f85c021bfd95d452e082b8ac9c3416f3ab",
            "42b27188826cc1779329986b45f9798a9e8ea691a9694c07e74338f3b3b5832e",
            "liturgy-411b1751d4ae-1:21473e7a5b173c41:1", "liturgy-411b1751d4ae-1", "1 Лик"),
        Record(FeoriaTranslationCatalog.Entry("weekday-antiphon-55f6fdc0f76f2eb6a6ac",
            "Я́ко в ру́це Его́ вси концы́ земли́, / и высо́ты гор Того́ суть. Спаси ны, Сы́не Бо́жий, во святы́х ди́вен сый, пою́щия Ти: аллилу́иа.",
            "Ибо в Его руке все пределы земли и Ему принадлежат горные вершины. Сын Божий, дивный в святых, спаси нас, поющих Тебе: аллилуия", revision = 5, originalSourceUrl = ORIGINAL_SOURCE_URL),
            "72d5a4cffac51f0d34c9127923b1d612046a64cee399e36a5395a2847536eb2e",
            "55f6fdc0f76f2eb6a6acc87d25db5e16abff291e4a05ced6afac1feb9ff3cae9",
            "d96dca3476566d45e05257264ebca87650c8c730309ba8321f50eb49504cb215",
            "liturgy-411b1751d4ae-1:d37be8606965812f:1", "liturgy-411b1751d4ae-1", "2 Лик"),
        Record(FeoriaTranslationCatalog.Entry("weekday-antiphon-748ff0cf2a7c12c5a3df",
            "Предвари́м лице́ Его́ во испове́дании,/ и во псалме́х воскли́кнем Ему: Спаси́ ны, Сы́не Бо́жий, во святы́х ди́вен сый, пою́щия Ти: аллилу́иа.",
            "Придем к Нему с благодарением и псалмами воскликнем Ему. Сын Божий, дивный в святых, спаси нас, поющих Тебе: аллилуия", revision = 5, originalSourceUrl = ORIGINAL_SOURCE_URL),
            "8e856b779109090ea16975f9e369b36f50e48548af8a5446df0ab08abfae07f5",
            "748ff0cf2a7c12c5a3df8989e659280804940166a3b09bd84dbe434da9046bf4",
            "fdd294482daf2bd5b5861c43698c875f2ece3a084608f9726e13f7ef7678774e",
            "liturgy-411b1751d4ae-1:8782b06eb312c000:1", "liturgy-411b1751d4ae-1", "2 Лик"),
        Record(FeoriaTranslationCatalog.Entry("weekday-antiphon-a2b121bf5a34a6b0f132",
            "Я́ко Бог Ве́лий Госпо́дь,/ и Царь Ве́лий по всей земли. Спаси ны, Сы́не Бо́жий, во святы́х ди́вен сый, пою́щия Ти: аллилу́иа.",
            "Ибо Господь - великий Бог и великий Царь над всей землей. Сын Божий, дивный в святых, спаси нас, поющих Тебе: аллилуия", revision = 5, originalSourceUrl = ORIGINAL_SOURCE_URL),
            "7a09616e09252aedadf42649314f09b091b1b3be4b08ee3001f20aa2e3b22d04",
            "a2b121bf5a34a6b0f13215f2899ecc6d0a22b3d18d6fa89fb7a3b2909f928d9a",
            "b72dca68f30113f159acbe188619acec67af55dbf0034c1de830f30b6ca5f5ae",
            "liturgy-411b1751d4ae-1:fd3cebddcde386ea:1", "liturgy-411b1751d4ae-1", "1 Лик"),
        Record(FeoriaTranslationCatalog.Entry("weekday-antiphon-22eb1005a7a75bc7a0a7",
            "Прииди́те, возра́дуемся Го́сподеви,/ воскли́кнем Бо́гу Спаси́телю на́шему. Спаси́ ны, Сы́не Бо́жий, во святы́х ди́вен сый, пою́щия Ти: аллилу́иа.",
            "Придите, возрадуемся Господу и воскликнем Богу, нашему Спасителю. Сын Божий, дивный в святых, спаси нас, поющих Тебе: аллилуия", revision = 5, originalSourceUrl = ORIGINAL_SOURCE_URL),
            "b987d9fd6dc85ca2e556b5da5bfb3f9e321950de7aaef851f61685571b1fed5c",
            "22eb1005a7a75bc7a0a7e04b7bde56d33ec3c9a8dd8f32b348f457b71205d2b6",
            "4712206af610e07980e3885678b4d8489cad9ac35e071026e7b603fb031274e2",
            "liturgy-411b1751d4ae-1:708ff4190370c6f7:1", "liturgy-411b1751d4ae-1", "1 Лик"),
        Record(FeoriaTranslationCatalog.Entry("weekday-antiphon-5543106d372f209b166b",
            "Свиде́ния Твоя́ уве́ришася зело́,/ до́му Твоему́ подоба́ет святы́ня, Го́споди, в долготу́ дний. Моли́твами святы́х Твои́х, Спа́се, спаси́ нас.",
            "Твои свидетельства несомненно верны; Твоему дому подобает святость, Господи, на долгие дни. Молитвами Твоих святых, Спаситель, спаси нас", revision = 5, originalSourceUrl = ORIGINAL_SOURCE_URL),
            "d86501dd0378663f0fd517b056bba1ece7b2d43965930e82875b51f8a27cc617",
            "5543106d372f209b166bec36c70ec7d15e89e047c8f513beb7434d4d100c9bec",
            "ce0ac5110c522cf5f5eca0c3f80fad69a47a234bfa67fcdd14ea7814c6f4ef3a",
            "liturgy-97a243011110-1:e819a2810e99aa6c:1", "liturgy-97a243011110-1", "2 Лик"),
        Record(FeoriaTranslationCatalog.Entry("weekday-antiphon-2f76da458593d5d48d1c",
            "Госпо́дь воцари́ся, в ле́поту облече́ся:/ облече́ся Госпо́дь в си́лу и препоя́сася. Моли́твами святы́х Твои́х, Спа́се, спаси́ нас.",
            "Господь воцарился, облекся величием; Господь облекся силой и ею опоясался. Молитвами Твоих святых, Спаситель, спаси нас", revision = 5, originalSourceUrl = ORIGINAL_SOURCE_URL),
            "12d4ab7c677e541f7e1c6e438cac7c32a4fad3cf1f4461d2bebd64904cedf0b2",
            "2f76da458593d5d48d1ca964d3c0cf4b2a3c6348fab7afd7f42e1ae4d9020a00",
            "13257f325c82ce4707a21f867468179d377a59afa47386713dff6cad881b09aa",
            "liturgy-97a243011110-1:17c885b1748271a3:1", "liturgy-97a243011110-1", "2 Лик"),
        Record(FeoriaTranslationCatalog.Entry("weekday-antiphon-0dbed5b6b7e01a97429a",
            "Возвеща́ти зау́тра ми́лость Твою́/ и и́стину Твою́ на вся́ку нощь. Моли́твами Богоро́дицы, Спа́се, спаси́ нас.",
            "Возвещать утром Твою милость и Твою верность каждую ночь. Молитвами Богородицы, Спаситель, спаси нас", revision = 5, originalSourceUrl = ORIGINAL_SOURCE_URL),
            "13a50869952e97e6970a09369b73163243a69a60f65d3e2293fc4dc8587ac10c",
            "0dbed5b6b7e01a97429a51ed2eb1951d12cd0ef6cee318c0c5981f901be668b6",
            "cdcfb4bf48be25ef7e4d23a47c5e0314ff46d81a162937342e9a994ed9063099",
            "liturgy-e42b4db7ac63-1:d6b4328759fa4f36:1", "liturgy-e42b4db7ac63-1", "1 Лик"),
        Record(FeoriaTranslationCatalog.Entry("weekday-antiphon-377e4a087bec5fca2211",
            "Я́ко прав Госпо́дь Бог наш,/ и несть непра́вды в Нем. Моли́твами Богоро́дицы, Спа́се, спаси́ нас.",
            "Ибо праведен Господь, Бог наш, и нет в Нем неправды. Молитвами Богородицы, Спаситель, спаси нас", revision = 5, originalSourceUrl = ORIGINAL_SOURCE_URL),
            "c0282f4512bd7b3d3a05fed68d587d114217d791dc3065f84040c24918eab05f",
            "377e4a087bec5fca2211c573e8bb2ffc15eebe494e34ef3fae832cd79878ccc2",
            "bad264569e9b9eca8925101327638d8dd41c413986d257e26f32d5a89315c5eb",
            "liturgy-e42b4db7ac63-1:bcb3bdc26ede56e6:1", "liturgy-e42b4db7ac63-1", "2 Лик"),
        Record(FeoriaTranslationCatalog.Entry("weekday-antiphon-4d9502658bfb29a29a64",
            "Благо есть исповедатися Господеви, и пети имени Твоему, Вышний. Моли́твами Богоро́дицы, Спа́се, спаси́ нас.",
            "Хорошо благодарить Господа и воспевать Твое имя, Всевышний. Молитвами Богородицы, Спаситель, спаси нас", revision = 5, originalSourceUrl = ORIGINAL_SOURCE_URL),
            "b5f23ee76d562dc3a982e8ef4e4b0e40d20196d2aadff6e044b93e31d0b53be9",
            "4d9502658bfb29a29a640cc3a758c9f58bcc52e0d0d2f2955ea24f318565c40b",
            "13f74e3d9df5a28495196ea32ce68b4d416ff001d30cb50a2d0aecf369972fa7",
            "liturgy-e42b4db7ac63-1:69f515080db13d42:1", "liturgy-e42b4db7ac63-1", "2 Лик"),
        Record(FeoriaTranslationCatalog.Entry("weekday-antiphon-18c4bc52203be0e445f5",
            "И ны́не и при́сно/ и во ве́ки веко́в. Ами́нь. Моли́твами Богоро́дицы, Спа́се, спаси́ нас.",
            "И теперь, и всегда, и во веки веков. Аминь. Молитвами Богородицы, Спаситель, спаси нас", revision = 5, originalSourceUrl = ORIGINAL_SOURCE_URL),
            "e0b33efd8b61b9aa14a43e0e978d40e824e40e7cd2ca0bb3e9cd9ac23a32c6ac",
            "18c4bc52203be0e445f5e2753c4655a5d4921c4b6ab4cb3c1db1c64856fc5d4a",
            "bb4d21810cbc4eb7d23fad1a3484cbe2f1ae63561dce42384aaf4cf7691d21db",
            "liturgy-e42b4db7ac63-1:4c618e9a8fcd0438:1", "liturgy-e42b4db7ac63-1", "2 Лик"),
        Record(FeoriaTranslationCatalog.Entry("weekday-antiphon-877e2b2dc1ef74831f67",
            "И́бо утверди́ вселе́нную,/ я́же не подви́жится. Моли́твами святы́х Твои́х, Спа́се, спаси́ нас.",
            "Ибо Он утвердил вселенную, и она не поколеблется. Молитвами Твоих святых, Спаситель, спаси нас", revision = 5, originalSourceUrl = ORIGINAL_SOURCE_URL),
            "7d8fba463deae47d70cc1480b0f44d264d48ec5fe05535d073cdbb44225f3a9b",
            "877e2b2dc1ef74831f67df15779babf6d5dcb6e57e4738121e098d1e957b2bea",
            "1db41d6ff9715b0b860551a94b353d31c81af624ce09210319ab66d75afab5b6",
            "liturgy-97a243011110-1:5d8d981698897289:1", "liturgy-97a243011110-1", "1 Лик"),
        Record(FeoriaTranslationCatalog.Entry("weekday-antiphon-a8136d91498dce9dab7d",
            "Сла́ва Отцу́ и Сы́ну/ и Свято́му Ду́ху: Моли́твами Богоро́дицы, Спа́се, спаси́ нас.",
            "Слава Отцу, и Сыну, и Святому Духу. Молитвами Богородицы, Спаситель, спаси нас", revision = 5, originalSourceUrl = ORIGINAL_SOURCE_URL),
            "0caa4a0cef196b07ab682239838917f5268286d41de9eada764d6eacdf529ced",
            "a8136d91498dce9dab7d5304b5893f7ef9f82a5dfb49c4294c10cf81693c1512",
            "3e524f36cc4df8a5aa6ec212e4aada03240d364abd2e00f7157accc70a7fa92f",
            "liturgy-e42b4db7ac63-1:e04713318485d400:1", "liturgy-e42b4db7ac63-1", "1 Лик"),
        Record(FeoriaTranslationCatalog.Entry("weekday-antiphon-8c13e61472dfd0994fa2",
            "Госпо́дь воцари́ся,/ в ле́поту облече́ся. Моли́твами святы́х Твои́х, Спа́се, спаси́ нас.",
            "Господь воцарился, облекся величием. Молитвами Твоих святых, Спаситель, спаси нас", revision = 5, originalSourceUrl = ORIGINAL_SOURCE_URL),
            "54cdc25f196ce12d487b6e2edea32cb66d3dfbf5595484b5fece31674a91e8f4",
            "8c13e61472dfd0994fa2246832e95ca87f1bb5e86f7cc71803449cd930168d9a",
            "fa962dd5893220606b0e8bdf50664b669c5978ffb48f86a9f2fbddad79fbf069",
            "liturgy-97a243011110-1:e7c43dd11f935be7:1", "liturgy-97a243011110-1", "1 Лик"),
        Record(FeoriaTranslationCatalog.Entry("weekday-antiphon-d6bdada58030250cd7da",
            "Бла́го есть/ испове́датися Го́сподеви. Молитвами Богородицы, Спасе, спаси нас.",
            "Хорошо благодарить Господа. Молитвами Богородицы, Спаситель, спаси нас", revision = 5, originalSourceUrl = ORIGINAL_SOURCE_URL),
            "83b16ced65d6ad11433c2b98839d5e49251526019f3254e3c5317b68e85360e6",
            "d6bdada58030250cd7daf5f237b3c3da61dc69522af872de47d0e725ffc877fa",
            "b4be560cc504b26f1b266bb6af5e5cb47759d39ae83a4c254bcb2b06da140dee",
            "liturgy-e42b4db7ac63-1:517118f8cc2dd440:1", "liturgy-e42b4db7ac63-1", "1 Лик")
    )
    val entries: List<FeoriaTranslationCatalog.Entry> = records.map { it.entry }

    init {
        require(records.size == 15 && records.map { it.entry.id }.distinct().size == 15)
        require(records.map { it.normalizedOriginalSha256 }.distinct().size == 15)
        require(records.all { record ->
            BookText.sha256(record.entry.original) == record.originalSha256 &&
                BookText.sha256(BookText.normalized(record.entry.original)) == record.normalizedOriginalSha256 &&
                BookText.sha256(record.entry.russian) == record.russianSha256 &&
                record.sourceBlockId.isNotBlank() && record.sourceChapterId.isNotBlank() &&
                record.sourceSpeaker in setOf("1 Лик", "2 Лик")
        })
    }
    private val byNormalizedOriginal = records.associateBy { it.normalizedOriginalSha256 }

    /** Every word and the full refrain must match. Speaker/count labels are never stripped here. */
    fun recognizes(original: String, translation: BookTranslation): Boolean {
        if (translation.source != BookTranslationIndex.FEORIA_TRANSLATION_SOURCE || translation.meaning != null) return false
        val record = byNormalizedOriginal[BookText.sha256(BookText.normalized(original))] ?: return false
        return translation.russian == record.entry.russian && BookText.sha256(translation.russian) == record.russianSha256
    }
}

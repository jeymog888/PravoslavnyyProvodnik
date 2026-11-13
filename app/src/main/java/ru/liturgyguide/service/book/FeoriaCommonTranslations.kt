package ru.liturgyguide.service.book

/** Reviewed complete common prayers. Dates and archive hashes record provenance only. */
internal object FeoriaCommonTranslations {
    const val SOURCE_CANDIDATE_SHA256 = "61b71a4402eb7b7bf309e55fa4a25e5653cf9207d4b487b322b04cb66358fa75"
    const val SEMANTIC_REVIEW_SHA256 = "2d21d40c112ff6037c9b630aed57530a96c55cbadf98e01e91c82fbc36a1c2d5"

    data class Record(val entry: FeoriaTranslationCatalog.Entry, val originalSha256: String,
        val normalizedOriginalSha256: String, val russianSha256: String,
        val sourceDocumentSha256: String, val sourceContentHash: String, val sourceBlockId: String)

    val records = listOf(
        Record(FeoriaTranslationCatalog.Entry("common-7532412164a77b33",
            "Еще́ мо́лимся о блаже́нных и приснопа́мятных созда́телех свята́го хра́ма сего́, и о всех преждепочи́вших отце́х и бра́тиях, зде лежа́щих и повсю́ду, правосла́вных.",
            "Еще молимся о блаженных и достойных вечной памяти создателях этого святого храма и обо всех прежде усопших православных отцах и братьях, погребенных здесь и повсюду", revision = 4,
            originalSourceUrl = "https://azbyka.ru/worships/?date=2026-11-13&worship=matins&size=full&church=20"),
            "bbb461c0e2e332d9a7c94003d1df1e5624b30b4c314f3fe73f2293ba475a972d",
            "7532412164a77b33e463c325cb68b83889dc755662056137d074aba6109f7b02",
            "b50371b1591c31595682d4e02a4f62b99e51f6550ae901a397b8b048f71ce8c7",
            "c1a622cb4fad548172bf44179d0c34433cff89a6e7b377fb8a8d14b3adbe7ca1",
            "bfa4f3adad38e8df5ae64dce5100b9e92e4c00d75919ff3eb0029e12037c9587", "matins-e8e07c76cf8b-1:8af92034e5d73624:1"),
        Record(FeoriaTranslationCatalog.Entry("common-03200f623b20f352",
            "Еще́ мо́лимся о ми́лости, жи́зни, ми́ре, здра́вии, спасе́нии, посеще́нии, проще́нии и оставле́нии грехо́в рабо́в Бо́жиих, бра́тии свята́го хра́ма сего́.",
            "Еще молимся о милости, жизни, мире, здоровье, спасении, Божией помощи, прощении и отпущении грехов рабов Божиих, братии этого святого храма", revision = 4,
            originalSourceUrl = "https://azbyka.ru/worships/?date=2026-11-13&worship=matins&size=full&church=20"),
            "9cc4fff712d9c4dd5a516b81ec2a1c33d8945cf837e5b066433c885cac835e77",
            "03200f623b20f352f9a4d426bcbea1611b830b90d4ef2a66dfbc1fcdb11d6af6",
            "b0ea53a9b9430422fb4351212c0fd528362ce9ffa6016f43cae265c0c1b22735",
            "c1a622cb4fad548172bf44179d0c34433cff89a6e7b377fb8a8d14b3adbe7ca1",
            "bfa4f3adad38e8df5ae64dce5100b9e92e4c00d75919ff3eb0029e12037c9587", "matins-e8e07c76cf8b-1:1bb987d9f42d2fc1:1"),
        Record(FeoriaTranslationCatalog.Entry("common-b7414ef05fc24c42",
            "О гра́де сем, вся́ком гра́де, стране́ и ве́рою живу́щих в ни́х, Го́споду помо́лимся.",
            "Об этом городе, о каждом городе и каждой местности и о людях, живущих в них с верой, Господу помолимся", revision = 4,
            originalSourceUrl = "https://azbyka.ru/worships/?date=2026-11-13&worship=matins&size=full&church=20"),
            "708fd024a4fc35eb0f826f14661f9579a89004ebdb29903766d7000106accbf6",
            "b7414ef05fc24c42f8cbd3b2392a5df4a32384e98478eca25cfeccf5508be4ae",
            "112337096c25d8b35cbf0d515596cdcd52c00658cc03c81beed1f5d961d673db",
            "c1a622cb4fad548172bf44179d0c34433cff89a6e7b377fb8a8d14b3adbe7ca1",
            "bfa4f3adad38e8df5ae64dce5100b9e92e4c00d75919ff3eb0029e12037c9587", "matins-af63642abcec-1:b0137a55428e3fee:1"),
        Record(FeoriaTranslationCatalog.Entry("common-9a387510cbb06063",
            "От небе́с прия́л еси́ Боже́ственную благода́ть, и твои́ма устна́ма вся́ учи́ши покланя́тися в Тро́ице еди́ному Бо́гу, Иоа́нне Златоу́сте всеблаже́нне, преподо́бне, досто́йно хва́лим тя́: еси́ бо наста́вник, я́ко боже́ственная явля́я.",
            "С небес ты принял Божественную благодать и своими устами учишь всех поклоняться единому в Троице Богу. Всеблаженный и святой Иоанн Златоуст, мы достойно восхваляем тебя: ведь ты наставник, раскрывающий Божественное", revision = 4,
            originalSourceUrl = "https://azbyka.ru/worships/?date=2026-11-13&worship=liturgy&size=full&church=30"),
            "092c07b0b364fa53e52a3455821099f651b6c71c32e32277903bd3bcc7647a00",
            "9a387510cbb0606388d04e13c7747a53ee5785d98a744a67663834a34b74b94c",
            "eac7cac02d2af96ada9ccd27c70c065000bf48036eb68a8cbfe8bb166f948ea4",
            "9edee9dea1ba23d3c468b48ff8471b7641618d10e760f80f25817885f7145354",
            "10b3808b40aacbb86ebf1008a062cdfd2154569d4fbc6fcb13d74cef28a7aff3", "liturgy-ddd29a930086-1:ea95590da07114a8:1"),
        Record(FeoriaTranslationCatalog.Entry("common-bc9a8fc5da90f956",
            "Ты бо еси́ Царь ми́ра, и Спас душ на́ших, и Тебе́ сла́ву возсыла́ем, Отцу́, и Сы́ну, и Свято́му Ду́ху, ны́не и при́сно, и во ве́ки веко́в.",
            "Ибо Ты - Царь, дарующий мир, и Спаситель наших душ, и Тебе возносим славу, Отцу, и Сыну, и Святому Духу, ныне и всегда, и во веки веков", revision = 4,
            originalSourceUrl = "https://azbyka.ru/worships/?date=2026-11-15&worship=matins&size=full&church=30"),
            "76076beb9973bda883ba08b2beba206aa2faff6c60558f3fc8718852a6251447",
            "bc9a8fc5da90f956c20e6fa9969faf55c3925f7a5aec8d85435e88c4547e879b",
            "d88df8cfd49d0289035d25e398fae63105156beef9890e7d9dd7591881034540",
            "7aad49732d2d77a4ab412ddd2b8fd8f06209269be14ac5aee637aa33ba9842ae",
            "78d392902b26d64de79851ecbfda93684422fd5c2fd2adb21bbabe39f82038cf", "matins-ed716e9f74f9-3:bd9a2c0be83014da:1"),
        Record(FeoriaTranslationCatalog.Entry("common-c56cb13ca7031e8f",
            "Я́ко Бог ми́лости и щедро́т и человеколю́бия еси́, и Тебе́ сла́ву возсыла́ем, Отцу́ и Сы́ну и Свято́му Ду́ху, ны́не и при́сно и во ве́ки веко́в.",
            "Ибо Ты - Бог милости, сострадания и любви к людям, и Тебе возносим славу, Отцу и Сыну и Святому Духу, ныне и всегда и во веки веков", revision = 4,
            originalSourceUrl = "https://azbyka.ru/worships/?date=2026-11-15&worship=matins&size=full&church=30"),
            "c81db29de12035f02448100fad631bf124e2f5e302ef3e26f63f797c98a0aff8",
            "c56cb13ca7031e8f178c9b990d4296dddb19fc40cdefecad448b8b810b647adc",
            "b49de321b27bb0462bc1c9ada6064b4d61479c5f8faa08098066a3c56013d5ca",
            "7aad49732d2d77a4ab412ddd2b8fd8f06209269be14ac5aee637aa33ba9842ae",
            "78d392902b26d64de79851ecbfda93684422fd5c2fd2adb21bbabe39f82038cf", "matins-d66ea8339379-1:71c86f807a1ad07f:1"),
        Record(FeoriaTranslationCatalog.Entry("common-362b76c5662ff465",
            "Я́ко Тя хва́лят вся Си́лы Небе́сныя, и Тебе́ сла́ву возсыла́ем, Отцу́, и Сы́ну, и Свято́му Ду́ху, ны́не и при́сно, и во ве́ки веко́в.",
            "Ибо Тебя восхваляют все Небесные Силы, и Тебе возносим славу, Отцу, и Сыну, и Святому Духу, ныне и всегда, и во веки веков", revision = 4,
            originalSourceUrl = "https://azbyka.ru/worships/?date=2026-11-15&worship=matins&size=full&church=30"),
            "745123e8b37c242f6926152b7c26d6f225204a7e6fbf603b823dce537b8d9e14",
            "362b76c5662ff465670c34c0dccf2dd618eb7407cc1111631fd68ca8ad431a9b",
            "c9edafc52d6e26e8c857acb2abb0e36710ae96a200fca4bda011ffe0ce9b0eb4",
            "7aad49732d2d77a4ab412ddd2b8fd8f06209269be14ac5aee637aa33ba9842ae",
            "78d392902b26d64de79851ecbfda93684422fd5c2fd2adb21bbabe39f82038cf", "matins-ed716e9f74f9-4:e51dba5cd98252bc:1"),
        Record(FeoriaTranslationCatalog.Entry("common-71c20cf2e6e610d7",
            "Я́ко Ты еси́ Бог наш, и Тебе́ сла́ву воссыла́ем, Отцу́ и Сы́ну, и Свято́му Ду́ху, ны́не и при́сно, и во ве́ки веко́в.",
            "Ибо Ты - Бог наш, и Тебе возносим славу, Отцу и Сыну и Святому Духу, ныне и всегда и во веки веков", revision = 4,
            originalSourceUrl = "https://azbyka.ru/worships/?date=2026-11-15&worship=matins&size=full&church=30"),
            "6c1ebbb9032f5bf118529c836b3b3cdb742a7790afdf0a5e830d3b41f72aeb88",
            "71c20cf2e6e610d75230ae8ed1b0359ffadf86be7a960d1e375b5db47b7bfa86",
            "fe4cdec09387e4141c21f4f40a73d8317f4a4378295376a1ddcf69e31d17c07d",
            "7aad49732d2d77a4ab412ddd2b8fd8f06209269be14ac5aee637aa33ba9842ae",
            "78d392902b26d64de79851ecbfda93684422fd5c2fd2adb21bbabe39f82038cf", "matins-ed716e9f74f9-2:d7e82f91d2676546:1"),
        Record(FeoriaTranslationCatalog.Entry("common-d396c308884de400",
            "Го́споди Иису́се Христе́, Сы́не Бо́жий, молитв ра́ди Пречи́стыя Твоея́ Ма́тере, преподо́бных и богоно́сных оте́ц на́ших и всех святы́х, поми́луй нас. Ами́нь.",
            "Господи Иисусе Христе, Сын Божий, по молитвам Твоей Пречистой Матери, наших преподобных и богоносных отцов и всех святых помилуй нас. Аминь", revision = 4,
            originalSourceUrl = "https://azbyka.ru/worships/?date=2026-11-13&worship=liturgy&size=full&church=30"),
            "87fb3192586619d879d27cd466a94902e812f1ef8ecdb3b3283b4f88d90fb13c",
            "d396c308884de4004822c7e2815034f0f49f962b03a3de7c9959e4b2f19bbe3c",
            "34d1c03753e7f3bf4963572404199fefde6ec9036825b69e233123165f24f78a",
            "9edee9dea1ba23d3c468b48ff8471b7641618d10e760f80f25817885f7145354",
            "10b3808b40aacbb86ebf1008a062cdfd2154569d4fbc6fcb13d74cef28a7aff3", "liturgy-ddd29a930086-1:2de615226cb7cd77:1"),
        Record(FeoriaTranslationCatalog.Entry("common-d9e5da2f4fbba110",
            "Те́ло Христо́во приими́те, / Исто́чника безсме́ртнаго вкуси́те.",
            "Примите Тело Христа, / вкусите от бессмертного Источника", revision = 4,
            originalSourceUrl = "https://azbyka.ru/worships/?date=2026-11-13&worship=liturgy&size=full&church=30"),
            "2aa36e00dc5e3fccd05665780a34a352dc38c7cbb1a4863b2951a9f6fdb73cc9",
            "d9e5da2f4fbba1107f0e7e69ca0dc3c1e065a39629626ab1594c037cdce72248",
            "a90d782719b5a471e280d49d8b5a83d8c1322b05a9c6cdac648d2750cb55b469",
            "9edee9dea1ba23d3c468b48ff8471b7641618d10e760f80f25817885f7145354",
            "10b3808b40aacbb86ebf1008a062cdfd2154569d4fbc6fcb13d74cef28a7aff3", "liturgy-f310daaa5360-1:fc6cff1de3ca567a:1")
    )
    val entries: List<FeoriaTranslationCatalog.Entry> = records.map { it.entry }

    init {
        require(records.size == 10 && records.map { it.entry.id }.distinct().size == 10)
        require(records.map { it.normalizedOriginalSha256 }.distinct().size == 10)
        require(records.all { record ->
            BookText.sha256(record.entry.original) == record.originalSha256 &&
                BookText.sha256(BookText.normalized(record.entry.original)) == record.normalizedOriginalSha256 &&
                BookText.sha256(record.entry.russian) == record.russianSha256 &&
                record.sourceDocumentSha256.matches(Regex("[a-f0-9]{64}")) &&
                record.sourceContentHash.matches(Regex("[a-f0-9]{64}")) && record.sourceBlockId.isNotBlank()
        })
    }

    private val byNormalizedOriginal = records.associateBy { it.normalizedOriginalSha256 }

    /** Match every lexical word of the whole body; never remove roles, counts or labels. */
    fun recognizes(original: String, translation: BookTranslation): Boolean {
        if (translation.source != BookTranslationIndex.FEORIA_TRANSLATION_SOURCE || translation.meaning != null) return false
        val record = byNormalizedOriginal[BookText.sha256(BookText.normalized(original))] ?: return false
        return translation.russian == record.entry.russian && BookText.sha256(translation.russian) == record.russianSha256
    }
}

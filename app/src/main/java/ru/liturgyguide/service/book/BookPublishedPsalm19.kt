package ru.liturgyguide.service.book

import com.google.gson.JsonParser

/** One reviewed published whole pair. It corrects no general alphabet and reads no runtime corpus. */
internal object BookPublishedPsalm19 {
    // Raw published row 2-10 and service target differ only in one normalized Latin e.
    // Verse 1 is a separate inscription and is deliberately absent from this body.
    // Provenance: test-only published-psalm19/evidence.json and psalter.html.gz.
    internal const val JSON = """[{"original":"Услы́шит тя Госпо́дь в день печа́ли, защи́тит тя и́мя Бо́га Иа́ковля. По́слет ти по́мощь от Свята́го и от Сио́на засту́пит тя. Помяне́т вся́ку же́ртву твою́, и всесожже́ние твое́ ту́чно бу́ди. Даст ти Госпо́дь по се́рдцу твоему́ и весь сове́т твой испо́лнит. Возра́дуемся о спасе́нии твое́м и во и́мя Го́спода Бо́га на́шего возвели́чимся. Испо́лнит Госпо́дь вся проше́ния твоя́. Ны́не позна́х, я́ко спасе́ Госпо́дь христа́ Своего́, услы́шит его с Небесе́ Свята́го Своего́, в си́лах спасе́ние десни́цы Его́. Си́и на колесни́цах и си́и на ко́нех, мы же во и́мя Го́спода Бо́га на́шего призове́м. Ти́и спя́ти бы́ша и падо́ша, мы же воста́хом и испра́вихомся. Го́споди, спаси́ царя́ и услы́ши ны, во́ньже а́ще день призове́м Тя.","russian":"Да услышит тебя Господь в день печали, да защитит тебя имя Бога Иаковлева! Да пошлет Он тебе помощь от святилища, с высот Сиона да охранит тебя! Да помянет Он все жертвы твои, и всесожжение твое да будет обильно! Да сотворит тебе Господь по сердцу твоему и всяк замысел твой да исполнит! Возрадуемся мы о спасении твоем, возвеличит нас имя Господа Бога нашего. Да исполнит Господь все прошения твои. Ныне познал я, что спас Господь помазанника Своего; услышал его с неба святого Своего. Могуча спасающая десница Его. Одни на боевых колесницах, другие на конях, а мы имя Господа Бога нашего призовем. Они споткнулись и пали, а мы встали и укрепились. Господи, спаси царя и услышь нас в день, когда призовем Тебя!","source":"Азбука веры · Псалтирь с переводом","url":"https://azbyka.ru/molitvoslov/psaltir-po-kafizmam-s-perevodom.html"}]"""
    private const val BUNDLE_SHA256 = "894ecb36a0ee5f60663c8311598594f0d0559c233050e7006f2ddf5674ec07a1"
    private const val TARGET_SHA256 = "734070219f6fa509dcec5e030e43117116162106af8896121be07d3111f0268a"
    private const val TARGET_KEY_SHA256 = "706929ac1923dbd437e3032090e978ea709cbf527a8a23a62b87b9c5065d6bd9"
    private const val RUSSIAN_SHA256 = "d29c88e33e5f3725f0cc34105ff44c059cb6a4ce2d0fafa10fa65477d140d2ff"
    internal data class Pair(val original: String, val russian: String)

    /** Validate the closed singleton before the general concordance decoder can collapse duplicates. */
    fun read(json: String?): Pair? = runCatching {
        require(json != null && json.length in 1..8_192 && BookText.sha256(json) == BUNDLE_SHA256)
        val root = JsonParser.parseString(json)
        require(root.isJsonArray && root.asJsonArray.size() == 1)
        val row = root.asJsonArray.single().asJsonObject
        require(row.keySet() == setOf("original", "russian", "source", "url"))
        require(row.entrySet().all { (_, value) -> value.isJsonPrimitive && value.asJsonPrimitive.isString })
        val original = row["original"].asString
        val russian = row["russian"].asString
        require(BookText.sha256(original) == TARGET_SHA256 &&
            BookText.sha256(BookTranslationIndex.normal(original)) == TARGET_KEY_SHA256 &&
            BookText.sha256(russian) == RUSSIAN_SHA256)
        require(row["source"].asString == BookTranslationIndex.PSALTER_SOURCE.title &&
            row["url"].asString == BookTranslationIndex.PSALTER_SOURCE.url)
        Pair(original, russian)
    }.getOrNull()
}

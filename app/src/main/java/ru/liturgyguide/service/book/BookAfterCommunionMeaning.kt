package ru.liturgyguide.service.book

import ru.liturgyguide.model.BookBlock
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.BookChapter
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.WorshipKind

/** Presentation only: source paragraphs, IDs, edition hashes and reading positions stay intact. */
internal object BookAfterCommunionMeaning {
    data class Fragment(val title: String, val detail: String, val brief: BookMeaningBrief?)
    private const val SOURCE = "https://azbyka.ru/bogosluzhenie/molitvy-posle-svyatogo-prichashheniya/"
    private const val CONTEXT = "В источнике этот текст помещен среди молитв после Причащения. Их можно читать после Причащения самостоятельно; в храме порядок чтения может отличаться"
    private const val UNKNOWN = "Для выбранного фрагмента отдельное пояснение пока не подготовлено"
    private val headings = listOf("благодарственная молитва 1 я", "молитва 2 я святого василия великого",
        "молитва 3 я прп симеона метафраста", "молитва 4 я", "молитва 5 я ко пресвятой богородице",
        "песнь симеона богоприимца")

    // Exact whole normalized paragraphs from three independently retained source documents.
    // These hashes identify explanatory subjects, never supply a prayer or translation.
    private val subjects = mapOf(
        "38ced5b4f7028fc9aa3d8ed3eb798e0dae5d186ff3bbe03aa45c6c26b545860e" to "glory-to-god",
        "949442878e936703d8eb8020fa19ec5e6f6869e1f334e02d6262abd87c97980b" to "thanks1",
        "d18396db96c270e7aa62f11ae4abdb3fddeb634819fc9372707f2c655645c975" to "thanks2",
        "5455db312fb56471ed67538869d3ab4058db2f1172ff4db5c5d13ddf5b8f2663" to "thanks3",
        "3833459359a0fa7639d9d21150f1fa15ca29ed182687eea6955eb7799a99bedc" to "thanks4",
        "c3cc9b7d268b01a27d817d283f9de087d94fa5521f7dc503c4deeecadd847c57" to "thanks5",
        "876ab4ec5db1767050523d54829d9c6570505a11d81338f221103f957dd8366d" to "simeon",
        "eff377cf3100ed34e3d9124ef11e2c0b0034ec9994aea5713e21261a04e8ed26" to "trisagion",
        "aaa524f538f6a6e08ee1c5b4aacbb356e434fb8d9120322c076c4fcba44b9c74" to "doxology",
        "37fd692e25b633a56fbb2614d4a34890f9b90eb5755536e23ac23afe4b18d676" to "trinity",
        "a8c712feb5654eb2a0bba2067af7472e7dc1624f5d85fdf7a5e6df0ad645298d" to "lord-have-mercy",
        "f58576bc3d0da2801c2d8cfdca5776f77b9a20608d3e4512a54417c77f85896a" to "our-father",
        "270c9720f8a589c1f0417a6fa61b318b0a0ebb5deed53f8a03b0e66ac230c17f" to "label-troparion",
        "e9e0387b8bc497df75ba43434e72065ff1566e67bd99069d1d3b94b2b7157c0c" to "chrysostom-troparion",
        "209324c9301ddd6918fc81f3cede390c9b19fdb0eb46d1d011cabd52dffb5e78" to "doxology",
        "040efa484255c3542f8ba10fb3943cce64d500db54229724a86cf4929dcd25c2" to "label-kontakion",
        "9a387510cbb0606388d04e13c7747a53ee5785d98a744a67663834a34b74b94c" to "chrysostom-kontakion",
        "09dd2b38491dec2ba4fbb604b25b81a25b7b85e5e203c052607f7eb060ff957c" to "doxology",
        "d5c5e1e25aa045b4b14b86d75091e74602fd58d3e8c1700493fa402d9bbb742b" to "theotokos-intercession",
        "aab158670a0abec7ca7196a5f187ecffa24cf4702730bd0b54a30f83e4fcb0da" to "lord-have-mercy",
        "ebede337eb60658242af4ca86c21cc7bf764b232ff6bdc2e5e393cc12d483349" to "theotokos-magnification",
        "d396c308884de4004822c7e2815034f0f49f962b03a3de7c9959e4b2f19bbe3c" to "dismissal",
        "8bf7e157685fae4c8b1c08d7e8ba93e029508f8a4b1ffb29770c906ae2b6cab1" to "closing",
    )

    fun forPlace(book: ServiceBook, chapter: BookChapter, block: BookBlock): Fragment? {
        if (block.isAltarText || block.kind == BookBlockKind.LOCAL_VARIATION ||
            book.chapters.none { it.id == chapter.id && it == chapter } || chapter.blocks.none { it.id == block.id && it == block }) return null
        val after = isAfterCommunion(book, block.id)
        val simeonChapter = BookText.normalized(chapter.title) == headings.last()
        if (!after && !simeonChapter) return null
        val subject = subjects[BookText.sha256(BookText.normalized(block.original))]
        // A familiar chapter title alone cannot identify either the chosen prayer or its setting.
        if (!after) {
            if (subject == "simeon" && block.kind == BookBlockKind.PRAYER) return fragment("simeon", false)
            return Fragment("О выбранном фрагменте", UNKNOWN, null)
        }
        if (subject == "label-troparion" || subject == "label-kontakion") {
            if (block.kind != BookBlockKind.RUBRIC) return Fragment("Молитвы после Причащения", CONTEXT, null)
            val next = chapter.blocks.getOrNull(chapter.blocks.indexOfFirst { it.id == block.id } + 1)
            val expected = if (subject == "label-troparion") "chrysostom-troparion" else "chrysostom-kontakion"
            val verified = next != null && next.kind == BookBlockKind.PRAYER && !next.isAltarText &&
                subjects[BookText.sha256(BookText.normalized(next.original))] == expected
            return if (verified) Fragment(
                if (subject == "label-troparion") "Тропарь Иоанну Златоусту" else "Кондак Иоанну Златоусту",
                CONTEXT + "\n\nДалее приведено краткое песнопение в честь святителя Иоанна Златоуста", null)
            else Fragment("Молитвы после Причащения", CONTEXT, null)
        }
        if (subject == null || block.kind != BookBlockKind.PRAYER) return Fragment("Молитвы после Причащения", CONTEXT, null)
        return fragment(subject, true)
    }

    private fun isAfterCommunion(book: ServiceBook, blockId: String): Boolean {
        if (book.key.worship != WorshipKind.LITURGY || book.chapters.size < headings.size + 1) return false
        val tail = book.chapters.takeLast(headings.size)
        if (tail.map { BookText.normalized(it.title) } != headings) return false
        val markers = book.chapters.withIndex().filter { BookText.normalized(it.value.title) ==
            "благодарственные молитвы по святом причащении" }
        val marker = markers.singleOrNull() ?: return false
        if (marker.index + 1 != book.chapters.size - headings.size) return false
        val introduction = marker.value.blocks.singleOrNull() ?: return false
        if (introduction.kind != BookBlockKind.PRAYER || introduction.isAltarText ||
            BookText.normalized(introduction.original) != "слава тебе боже слава тебе боже слава тебе боже") return false
        return book.chapters.drop(marker.index).any { chapter -> chapter.blocks.any { it.id == blockId } }
    }

    private fun fragment(subject: String, after: Boolean): Fragment {
        val text = when (subject) {
            "thanks1" -> Triple("Благодарность за Причащение",
                "В этой молитве благодарят Христа за Причащение и просят исцеления, света для сердца и помощи жить по Его заповедям",
                "Благодарность должна продолжиться в жизни: в искренней любви и верности Богу")
            "thanks2" -> Triple("Молитва после Причащения",
                "В молитве святого Василия Великого благодарят Христа за Его дары и просят сохранить человека под Божией защитой",
                "Просят с чистой совестью приобщаться Христу и надеются на прощение и вечную жизнь")
            "thanks3" -> Triple("Молитва об очищении",
                "Молитва Симеона Метафраста говорит о Причащении как об огне, который очищает человека от греха",
                "Просят освятить мысли, чувства и поступки, чтобы весь человек был обращен к Богу")
            "thanks4" -> Triple("Молитва о вечной жизни",
                "Христа просят, чтобы Причащение послужило вечной жизни, прощению грехов и радости",
                "Надежда на будущую встречу со Христом соединяется с молитвами Богородицы и всех святых")
            "thanks5" -> Triple("Молитва Богородице после Причащения",
                "Богородицу благодарят за помощь и просят просветить сердце, привести к покаянию и сохранить человека в вере",
                "Молитва связывает благодарность за Причащение с надеждой на очищение и новую жизнь")
            "simeon" -> Triple("Песнь Симеона",
                "Это евангельские слова праведного Симеона, который увидел во Младенце Христе спасение для всех народов",
                "Они выражают мир и доверие Богу" + if (after) " после встречи со Христом в Причащении" else "")
            "glory-to-god" -> Triple("Слава Тебе, Боже",
                "Кратко благодарим и прославляем Бога",
                "В начале этих молитв благодарят за дар Причащения")
            "trisagion" -> Triple("Трисвятое",
                "Трижды называем Бога святым и просим Его милости",
                "Прославление Бога соединяется с просьбой о помощи")
            "doxology" -> Triple("Славословие Троице",
                "Прославляем Отца, Сына и Святого Духа",
                "Краткие слова исповедуют веру в единого Бога в Троице")
            "trinity" -> Triple("Молитва Пресвятой Троице",
                "Просим Бога о милости, очищении от грехов, прощении и исцелении",
                "Признаем свою нужду в Божией помощи и надеемся на нее")
            "lord-have-mercy" -> Triple("Господи, помилуй",
                "Кратко просим Господа о милости",
                "Эта просьба выражает доверие Богу, Который знает нужды человека")
            "dismissal" -> Triple("Заключительная молитва ко Христу",
                "Христа просят помиловать нас по молитвам Богородицы и святых",
                "Так завершают этот порядок молитв после Причащения")
            "our-father" -> Triple("Отче наш",
                "Обращаемся к Богу как к Отцу словами, которым научил Христос",
                "Просим о хлебе, прощении и избавлении от зла, связывая свою молитву с прощением другим людям")
            "chrysostom-troparion" -> Triple("Тропарь Иоанну Златоусту",
                "Песнопение прославляет учение и смирение святителя Иоанна Златоуста и просит его молиться Христу о нас",
                "Благодарят Бога за святителя, чьи молитвы и учение помогают Церкви")
            "chrysostom-kontakion" -> Triple("Кондак Иоанну Златоусту",
                "Песнопение называет святителя наставником, который учит поклоняться единому Богу в Троице",
                "Вспоминают, как его учение помогает людям обращаться к Богу")
            "theotokos-intercession" -> Triple("Молитва о заступничестве Богородицы",
                "Богородицу просят не оставить молитвы людей и поспешить им на помощь",
                "Надеются на ее постоянное заступничество перед Богом")
            "theotokos-magnification" -> Triple("Величание Богородицы",
                "Прославляем Богородицу, родившую Бога Слово, и называем ее достойной большей чести, чем ангелы",
                "Почитание Богородицы связано с верой в то, что Христос истинно стал человеком")
            else -> Triple("Молитвы после Причащения",
                "В этом разделе благодарение сопровождают знакомые краткие молитвы к Богу",
                "Они помогают продолжить молитву после Причащения")
        }
        val brief = BookMeaningBrief("after-communion-$subject", text.second, text.third, sourceUrl = SOURCE, title = text.first)
        return Fragment(text.first, if (after) CONTEXT else text.second + ". " + text.third, brief)
    }
}

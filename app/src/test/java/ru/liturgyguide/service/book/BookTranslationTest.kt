package ru.liturgyguide.service.book

import com.google.gson.Gson
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import ru.liturgyguide.model.ServiceBookKey

class BookTranslationTest {
    @Test fun accentsAndSpellingDoNotDestroyShortI() {
        assertEquals(BookText.normalized("Го́споди, поми́луй!"), BookText.normalized("Господи помилуй"))
        assertNotEquals(BookText.normalized("мой"), BookText.normalized("мои"))
    }

    @Test fun missingEnumInCacheKeyCannotValidate() {
        val key = Gson().fromJson("{\"date\":\"2026-11-11\",\"churchType\":\"30\"}", ServiceBookKey::class.java)
        assertFalse(key.isValid())
    }

    @Test fun absentTranslationRemainsAbsent() {
        val index = BookTranslationIndex.fromAssets(null, """[{"original":"Господи, помилуй","russian":"Господи, помилуй"}]""")
        assertNotNull(index.find("Го́споди, поми́луй."))
        assertNull(index.find("Господи, спаси"))
    }

    @Test fun bilingualCellIsNotAssignedToItsShorterUnalignedPiece() {
        val index = BookTranslationIndex.fromParallelPage(Jsoup.parse("""
            <table><tr><td>Миром Господу помолимся. Господи, помилуй.</td><td>Помолимся Господу в мире. Господи, помилуй.</td></tr></table>
        """))
        assertNull(index.find("Миром Господу помолимся"))
        assertNotNull(index.find("Миром Господу помолимся. Господи, помилуй."))
    }

    @Test fun speakerAlignedRepliesCanBeMatchedIndependently() {
        val index = BookTranslationIndex.fromParallelPage(Jsoup.parse("""
            <table><tr><td>Диакон: Благослови, владыко. Иерей: Мир всем.</td><td>Диакон: Благослови, владыка. Священник: Мир всем.</td></tr></table>
        """))
        assertEquals("Благослови, владыка.", index.find("Благослови, владыко")?.russian)
        assertEquals("Мир всем.", index.find("Мир всем")?.russian)
    }

    @Test fun scriptureRequiresEveryRequestedVerse() {
        val index = BookScripture.fromPage(Jsoup.parse("""
            <div class=verse data-lang=r data-verse=Lk.11:9>Просите, и дано будет вам</div>
            <div class=verse data-lang=r data-verse=Lk.11:11>Какой из вас отец</div>
        """))
        assertNull(index.russian("Lk.11:9-11"))
        assertNotNull(index.russian("Lk.11:9"))
        assertNull(index.russian("Mt.11:9"))
    }

    @Test fun crossChapterTextNeedsWholeDeclaredPassage() {
        val html = """
            <div class=days__book-title><a href="/biblia/?Gal.5:26-6:2">Гал.5:26-6:2</a></div>
            <div class=tbl-content data-book-slug=Gal style="grid-template-rows: repeat(4, auto)">
            <div class=verse data-lang=r data-verse=Gal.5:26>Не будем тщеславиться</div>
            <div class=verse data-lang=r data-verse=Gal.6:1>Братия</div>
            <div class=verse data-lang=r data-verse=Gal.6:2>Носите бремена друг друга</div></div>
        """
        assertNotNull(BookScripture.fromPage(Jsoup.parse(html)).russian("Gal.5:26-6:2"))
        assertNull(BookScripture.fromPage(Jsoup.parse(html.replace("repeat(4", "repeat(5"))).russian("Gal.5:26-6:2"))
    }

    @Test fun realDailyReadingsKeepBibleTextWithoutHiddenGlosses() {
        val html = requireNotNull(javaClass.getResourceAsStream("/book/readings-2026-11-11.html"))
            .bufferedReader().use { it.readText() }
        val index = BookScripture.fromPage(Jsoup.parse(html))
        for (reference in listOf("1Thes.4:1-12", "Lk.11:9-13", "Gal.5:22-6:2", "Mt.11:27-30"))
            assertNotNull("Entire dated passage $reference", index.russian(reference))
        assertEquals("Ибо воля Божия есть освящение ваше, чтобы вы воздерживались от блуда;", index.russian("1Thes.4:3")?.russian)
        assertFalse(index.russian("1Thes.4:5")!!.russian.contains("греховное вожделение"))
    }
}

package ru.liturgyguide.service.book

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import ru.liturgyguide.model.PatronalEventProof
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey

/** Only public calendar evidence is needed; Android never receives a private API credential. */
fun interface PatronalProofs {
    fun find(id: String): PatronalEventProof?
}

/** HTTP 200 with an ordinary church control is not a confirmed dedication. */
internal class UnconfirmedPatronalSelection : IllegalArgumentException()

internal object PatronalSourceSelection {
    fun validate(document: Document, key: ServiceBookKey, proof: PatronalEventProof?, strictLegacy: Boolean = false) {
        if (key.patronalId == null) {
            if (proof != null || document.select("#bg_worships_patronal").isNotEmpty()) reject()
            val church = if (strictLegacy) strictSelected(document, "bg_worships_church")
                else document.selectFirst("#bg_worships_church option[selected]")
                    ?: document.selectFirst("#bg_worships_church option")
            if (church?.attr("value") != key.churchType) throw InvalidServiceBook("Источник вернул другой вариант храма")
            return
        }
        if (proof?.matches(key) != true || document.select("#bg_worships_church").isNotEmpty()) reject()
        val selected = try { strictSelected(document, "bg_worships_patronal") }
            catch (_: InvalidServiceBook) { reject() }
        if (selected.attr("value") != key.patronalId) reject()
    }

    /** Used by the rank protocol too, so every selected option obeys the same ownership rules. */
    fun strictSelected(document: Document, id: String): Element {
        val control = document.select("#$id").singleOrNull()
            ?: throw InvalidServiceBook("Источник вернул неоднозначные параметры службы")
        if (control.tagName() != "select") throw InvalidServiceBook("Источник вернул неизвестный параметр службы")
        val option = control.select("option[selected]").singleOrNull()
            ?: throw InvalidServiceBook("Источник не подтвердил выбранный параметр службы")
        val parent = option.parent()
        if (parent !== control && !(parent?.tagName() == "optgroup" && parent.parent() === control))
            throw InvalidServiceBook("Выбранный параметр не относится к своему полю")
        if (!option.hasAttr("value")) throw InvalidServiceBook("Источник не указал значение выбранного параметра")
        return option
    }

    fun validBook(book: ServiceBook): Boolean = runCatching {
        if (book.key.patronalId == null) return book.patronalSelection == null
        if (book.patronalSelection?.matches(book.key) != true) return false
        val expectedUrl = book.rankSelection?.let { ResolvedMatinsRankRequest(book.key, it.mainEvent).url }
            ?: book.key.sourceUrl()
        book.sourceUrl == expectedUrl
    }.getOrDefault(false)

    fun reject(): Nothing = throw UnconfirmedPatronalSelection()
}

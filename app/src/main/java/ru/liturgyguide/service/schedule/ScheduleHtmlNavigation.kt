package ru.liturgyguide.service.schedule

import org.jsoup.nodes.Element

/** DOM menu proof, not a blacklist of church/brand words. Original labels remain in the document. */
internal object ScheduleHtmlNavigation {
    private val service = Regex("(?iu)(литурги|всенощ|вечерн|утрен|исповед|молеб|панихид|часы|лития|акафист)")
    private val negative = Regex("(?iu)(?<![а-яё])(?:не|нет|ни|без|только|лишь|кроме|вместо|или|либо|если|после|перед|до|соответственно)(?![а-яё])")
    private val qualifier = Regex("(?iu)(?<![а-яё])(?:нижн|верхн|стар|нов|мал|больш|припис|больнич|госпитал)[а-яё]*(?![а-яё])")
    private val located = Regex("(?iu)(?<![а-яё])(?:в|во|при|на)\\s+(?:[а-яё-]+\\s+){0,7}(?:храм[а-яё]*|церк[а-яё]*|собор[а-яё]*|часовн[а-яё]*|придел[а-яё]*)(?![а-яё])")
    private val date = Regex("(?iu)(?<!\\d)(?:\\d{1,2}\\s*[./]\\s*\\d{1,2}|\\d{1,2}\\s+(?:январ|феврал|март|апрел|ма[йяе]|июн|июл|август|сентябр|октябр|ноябр|декабр)[а-яё]*|(?:19|20|21)\\d{2})(?!\\d)")

    fun neutral(value: String): Boolean = value.length in 1..2_000 && !value.codePoints().anyMatch {
        Character.isISOControl(it) && it != 9 && it != 10 && it != 13 ||
            Character.getType(it) == Character.FORMAT.toInt() || Character.getType(it) == Character.SURROGATE.toInt()
    } && !ScheduleClocks.pattern.containsMatchIn(value) && !ScheduleCancellation.containsMatchIn(value) &&
        !service.containsMatchIn(value) && !negative.containsMatchIn(value) && !qualifier.containsMatchIn(value) &&
        !located.containsMatchIn(value) && !date.containsMatchIn(value)

    fun isProvenMenuContainer(menu: Element): Boolean {
        if (menu.tagName() != "nav" && menu.attr("role") !in setOf("menu", "menubar", "navigation") && !menu.hasAttr("data-menu")) return false
        val anchors = menu.select("a[href]")
        if (anchors.size !in 2..100 || anchors.map { it.attr("href") }.filter(String::isNotBlank).distinct().size < 2) return false
        // A data-* flag alone is insufficient. A shared menu must also expose real menu semantics.
        return !menu.hasAttr("data-menu") || menu.attr("role") in setOf("menu", "menubar", "navigation") ||
            menu.tagName() == "nav" || menu.select("[role=menu],[role=menubar],a[role=menuitem]").isNotEmpty()
    }

    fun isPureMenuLabel(element: Element): Boolean {
        val menu = element.closest("[role=menu],[role=menubar],[role=navigation],nav,[data-menu]") ?: return false
        if (!isProvenMenuContainer(menu)) return false
        val anchors = menu.select("a[href]")
        val text = element.wholeText()
        if (!neutral(text)) return false
        val nodes = element.getAllElements().flatMap { it.textNodes() }.filter { it.wholeText.isNotBlank() }
        return nodes.isNotEmpty() && nodes.all { node ->
            val anchor = (node.parent() as? Element)?.closest("a[href]")
            anchor != null && anchor in anchors
        }
    }
}

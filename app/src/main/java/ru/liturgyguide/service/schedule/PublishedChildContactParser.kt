package ru.liturgyguide.service.schedule

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** One observed contact layout. No global page-name/first-coordinate join. */
internal object PublishedChildContactParser {
    data class Child(val name: String, val locality: String, val latitude: Double, val longitude: Double,
        val phone: String?, val blockKey: String)
    data class Result(val children: List<Child>, val ambiguous: Boolean, val observedKeys: List<String>)

    fun parse(response: ScheduleResponse): Result {
        val document = response.html() ?: return Result(emptyList(), true, emptyList())
        val parsed = mutableListOf<Child>()
        val observed = mutableListOf<String>()
        val headings = mutableListOf<OwnContactChildIdentity.NameKey>()
        var invalid = false
        for (block in document.select(".t195__text")) {
            if (block.closest("nav,aside,footer,#comments,.comments,blockquote,[role=complementary]") != null ||
                block.parents().any { it.hasClass("t195__text") }) continue
            val lines = lines(block)
            if (lines.none { ScheduleEntityIdentity.normalize(it) == "приписные храмы" }) continue
            val heading = lines.getOrNull(1).orEmpty()
            OwnContactChildIdentity.nameKey(heading)?.let { headings += it }
            val semantic = "child:" + ScheduleSourceCandidate.sha(ScheduleEntityIdentity.normalize(heading)).take(40)
            observed += semantic
            if (lines.firstOrNull()?.let(ScheduleEntityIdentity::normalize) != "приписные храмы" ||
                lines.count { ScheduleEntityIdentity.normalize(it) == "приписные храмы" } != 1 ||
                !OwnContactChildIdentity.fullName(heading) || block.select(".t195__text").any { it !== block }) { invalid = true; continue }
            val extraHeading = lines.drop(2).any { line ->
                ScheduleEntityIdentity.scope(line) != ScheduleEntityScope.UNKNOWN && !openingHours.matches(line)
            } || block.select("h1,h2,h3,h4,h5,h6").any {
                ScheduleEntityIdentity.normalize(it.text()) !in setOf("приписные храмы", ScheduleEntityIdentity.normalize(heading))
            }
            val point = dms(lines.joinToString(" "))
            val locality = lines.getOrNull(2)?.takeIf { it.startsWith("в ") && it.length <= 180 }
            val phones = lines.filter { it.startsWith("Телефон ") }
            val phone = phones.singleOrNull()?.removePrefix("Телефон ")?.let(OwnContactChildIdentity::phone)
            val id = block.id()
            val key = if (id.isEmpty()) semantic else "dom:$id"
            if (extraHeading || point == null || locality == null || phones.size > 1 || phones.isNotEmpty() && phone == null ||
                id.isNotEmpty() && (!id.matches(Regex("[A-Za-z0-9_-]{1,100}")) || document.getAllElements().count { it.id() == id } != 1)) {
                invalid = true; continue
            }
            parsed += Child(heading, locality, point.first, point.second, phone, key)
        }
        val duplicateNames = headings.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return Result(parsed.filter { OwnContactChildIdentity.nameKey(it.name) !in duplicateNames },
            invalid || duplicateNames.isNotEmpty(), observed.distinct().take(200))
    }

    private fun lines(block: Element): List<String> {
        val out = StringBuilder()
        fun walk(node: Node) {
            if (node is TextNode) out.append(node.wholeText)
            else if (node is Element) {
                val split = node !== block && node.tagName() in setOf("br", "p", "h1", "h2", "h3", "h4", "h5", "h6", "div")
                if (split) out.append('\n')
                node.childNodes().forEach(::walk)
                if (split) out.append('\n')
            }
        }
        walk(block)
        return out.toString().split('\n').map { it.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim() }.filter(String::isNotBlank)
    }

    private val openingHours = Regex("(?iu)^храм открыт ежедневно с (?:[01]?[0-9]|2[0-3])[.:][0-5][0-9] до " +
        "(?:[01]?[0-9]|2[0-3])[.:][0-5][0-9]\\.?$")

    /** Label, axis, hemisphere, sign and every DMS range must agree. Only ° and observed ASCII o. */
    internal fun dms(text: String): Pair<Double, Double>? {
        val field = Regex("(?iu:широта|долгота)\\s*:\\s*([NSEW])\\s*([+-]?[0-9]{1,3})[°o]\\s*([0-9]{1,2})'\\s*([0-9]{1,2}(?:\\.[0-9]{1,8})?)''(?![A-Za-z0-9°'])")
        val matches = field.findAll(text).toList()
        if (matches.size != 2 || Regex("(?iu)широта\\s*:").findAll(text).count() != 1 ||
            Regex("(?iu)долгота\\s*:").findAll(text).count() != 1 ||
            Regex("[NSEW]\\s*[+-]?[0-9]{1,3}[°o]").findAll(text).count() != 2) return null
        val result = mutableMapOf<String, Double>()
        for (match in matches) {
            val axis = match.value.substringBefore(':').trim().lowercase(java.util.Locale.ROOT)
            val hemisphere = match.groupValues[1].single()
            val rawDegrees = match.groupValues[2]
            val degree = rawDegrees.toIntOrNull()?.let { kotlin.math.abs(it) } ?: return null
            val minutes = match.groupValues[3].toIntOrNull() ?: return null
            val seconds = match.groupValues[4].toDoubleOrNull() ?: return null
            val latitude = axis == "широта"
            if (latitude && hemisphere !in "NS" || !latitude && hemisphere !in "EW") return null
            val negative = hemisphere in "SW"
            if (rawDegrees.startsWith('-') && !negative || rawDegrees.startsWith('+') && negative) return null
            val maximum = if (latitude) 90 else 180
            if (degree > maximum || minutes !in 0..59 || !seconds.isFinite() || seconds !in 0.0..<60.0 ||
                degree == maximum && (minutes != 0 || seconds != 0.0)) return null
            result[axis] = (degree + minutes / 60.0 + seconds / 3600.0) * if (negative) -1 else 1
        }
        return (result["широта"] ?: return null) to (result["долгота"] ?: return null)
    }
}

package ru.liturgyguide.service.schedule

import ru.liturgyguide.model.Parish
import java.time.LocalDate
import java.util.Locale

/** A building named in a table or heading is evidence, not part of the service title. */
internal object ScheduleVenue {
    private val building = Regex("(?iu)(?<![а-яё])(?:храм[а-яё]*|церк[а-яё]*|собор[а-яё]*|часовн[а-яё]*|придел[а-яё]*)(?![а-яё])")
    private val service = Regex("(?iu)(литурги|всенощ|вечерн|утрен|исповед|молеб|панихид|часы|лития|акафист)")
    private val clock = Regex("\\d{1,2}\\s*[.:]\\s*\\d{2}")
    private val location = Regex("(?iu)\\bв\\s+(?:[а-яё.-]+\\s+){0,6}(?:храме|соборе|церкви|часовне|приделе)\\b")
    // This complete calendar feast name is not the building word "собор". Do not exempt
    // other cathedral names, or an explicit location "в соборе ...", from identity checking.
    private val calendarFeast = Regex("(?iu)(?<![а-яё])собор\\s+архистратига\\s+михаила\\s+и\\s+прочих\\s+небесных\\s+сил\\s+бесплотных(?![а-яё])")
    private val shortMichaelFeast = Regex("(?iu)(?<![а-яё])собор(?=\\s+архистратига\\s+михаила(?![а-яё]))")
    private val martyrsFeast = Regex("(?iu)(?<![а-яё])собор(?=\\s+новомучеников(?![а-яё]))")
    private val nameWord = Regex("(?iu)[а-яёa-z-]+")
    private val nameBoundary = setOf("в", "во", "или", "при", "на", "у", "для", "после", "перед")
    private val generic = listOf("храм", "церк", "собор", "часов", "придел", "свят", "право", "русск", "имени", "честь",
        "распис", "богосл", "служб", "проход", "соверш", "месяц", "наход", "адрес", "все", "прихо", "наш",
        "отмен", "перене", "перено", "будет", "состо", "провод", "январ", "феврал", "март", "апрел", "июн", "июл",
        "август", "сентябр", "октябр", "ноябр", "декабр", "года")
    private val qualifiers = listOf("верхн", "нижн", "стар", "нов", "мал", "больш", "припис", "больнич", "госпитал")

    fun isLabel(text: String): Boolean {
        val candidate = text.replace(calendarFeast, " ")
        return building.containsMatchIn(candidate) && !service.containsMatchIn(candidate) &&
            !clock.containsMatchIn(candidate) && tokens(candidate).isNotEmpty()
    }

    fun inlineLabel(text: String, calendarDate: LocalDate? = null): String? {
        var candidate = text.replace(calendarFeast, " ")
        // A calendar preface before the first clock in a dated service row is not a venue.
        // Standalone building headings, separate venue cells and locations after the clock
        // remain subject to identity checks, including churches dedicated to the same feast.
        if (calendarDate != null) {
            val firstClock = clock.find(candidate)?.range?.first
            if (firstClock != null) {
                val prefix = candidate.substring(0, firstClock).replace(martyrsFeast, " ")
                candidate = (if (calendarDate.monthValue == 11 && calendarDate.dayOfMonth == 21)
                    prefix.replace(shortMichaelFeast, " ") else prefix) + candidate.substring(firstClock)
            }
        }
        val labels = location.findAll(candidate).map { it.value }.toMutableList()
        fun namePart(word: String): Boolean = nameWord.matches(word) && !service.containsMatchIn(word) &&
            !building.containsMatchIn(word) && word.lowercase(Locale.ROOT) !in nameBoundary
        building.findAll(candidate).forEach { marker ->
            // OCR removes cell borders: "Литургия | Казанский храм" becomes one text row.
            // Recover an adjacent building name, stopping at service words and clock/date tokens.
            val before = candidate.substring(0, marker.range.first).trim().split(Regex("\\s+"))
                .asReversed().takeWhile(::namePart).asReversed().joinToString(" ")
            val after = candidate.substring(marker.range.last + 1).trim().split(Regex("\\s+"))
                .takeWhile(::namePart).joinToString(" ")
            labels += if (tokens(before).isNotEmpty()) "$before ${marker.value}" else "${marker.value} $after"
        }
        // Every mentioned building must match. The first matching name cannot hide a second one.
        return labels.distinct().joinToString(" | ").takeIf(String::isNotBlank)
    }

    fun matches(label: String?, parish: Parish?): Boolean {
        if (label.isNullOrBlank()) return true
        if (parish == null) return false
        val target = "${parish.name} ${parish.shortName} ${parish.city} ${parish.address}"
        val wanted = tokens(label)
        val available = tokens(target)
        // Relative building names are meaningful only when explicitly present in the selection.
        val relative = qualifiers.filter { prefix -> wanted.any { it.startsWith(prefix) } }
        if (relative.any { prefix -> available.none { it.startsWith(prefix) } }) return false
        val distinctive = wanted.filterNot { token -> qualifiers.any(token::startsWith) }.toSet()
        return if (distinctive.isNotEmpty()) distinctive.all { it in available }
        else relative.isNotEmpty()
    }

    private fun tokens(text: String): Set<String> = Regex("[а-яёa-z]{3,}").findAll(text.lowercase(Locale.ROOT))
        .map { it.value.replace('ё', 'е') }.filter { word -> generic.none(word::startsWith) }.map { it.take(5) }.toSet()
}

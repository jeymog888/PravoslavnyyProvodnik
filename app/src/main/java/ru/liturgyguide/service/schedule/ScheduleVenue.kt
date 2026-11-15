package ru.liturgyguide.service.schedule

import ru.liturgyguide.model.Parish
import java.time.LocalDate
import java.util.Locale

/** A building named in a table or heading is evidence, not part of the service title. */
internal object ScheduleVenue {
    private val building = Regex("(?iu)(?<![а-яё])(?:храм[а-яё]*|церк[а-яё]*|собор[а-яё]*|часовн[а-яё]*|придел[а-яё]*)(?![а-яё])")
    private val service = Regex("(?iu)(литурги|всенощ|вечерн|утрен|исповед(?!ни[кч])|молеб|панихид|часы|лития|акафист)")
    // In this complete dedication, "Церкви" denotes the institution, not another building.
    private val confessorsDedication = Regex("(?iu)(?<![а-яё])новомучеников\\s+и\\s+исповедников\\s+церкви\\s+русской(?![а-яё])")
    private fun buildingMarkers(text: String): List<MatchResult> {
        val institutional = confessorsDedication.findAll(text).map { it.range }.toList()
        return building.findAll(text).filter { marker ->
            !(marker.value.equals("Церкви", true) && institutional.any { marker.range.first in it })
        }.toList()
    }
    private val clock = Regex("\\d{1,2}\\s*[.:]\\s*\\d{2}")
    // This complete calendar feast name is not the building word "собор". Do not exempt
    // other cathedral names, or an explicit location "в соборе ...", from identity checking.
    private val calendarFeast = Regex("(?iu)(?<![а-яё])собор\\s+архистратига\\s+михаила\\s+и\\s+прочих\\s+небесных\\s+сил\\s+бесплотных(?![а-яё])")
    // Full name of the council commemoration, including its historical years. An
    // explicit building marker outside this phrase remains subject to venue matching.
    private val localCouncilFeast = Regex("(?iu)(?<![а-яё])отцов\\s+поместного\\s+собора\\s+церкви\\s+русской\\s+1917[./-]1918\\s+годов(?![а-яё])")
    private val shortMichaelFeast = Regex("(?iu)(?<![а-яё])собор(?=\\s+архистратига\\s+михаила(?![а-яё]))")
    private val martyrsFeast = Regex("(?iu)(?<![а-яё])собор(?=\\s+новомучеников(?![а-яё]))")
    private val nameWord = Regex("(?iu)[а-яёa-z-]+")
    private val nameBoundary = setOf("в", "во", "или", "при", "на", "у", "для", "после", "перед")
    private val generic = listOf("храм", "церк", "собор", "часов", "придел", "свят", "право", "русск", "имени", "честь",
        "распис", "богосл", "служб", "проход", "соверш", "месяц", "наход", "адрес", "прихо", "наш",
        "отмен", "перене", "перено", "будет", "состо", "провод", "январ", "феврал", "март", "апрел", "июн", "июл",
        "август", "сентябр", "октябр", "ноябр", "декабр", "года")
    private val functional = setOf("все", "всех", "всем", "всеми", "всего")
    private val qualifiers = listOf("верхн", "нижн", "стар", "нов", "мал", "больш", "припис", "больнич", "госпитал")
    // Older parish sites put the registered organisation's name in non-semantic menus.
    // Keep the parish identity, including its locality, but not the legal/diocesan suffix
    // as a fictional second building. Unknown signatures still go through strict matching.
    private val legalParish = Regex("(?iu)^(?:местная\\s+)?религиозная\\s+организация\\s+(?:православный\\s+)?приход\\s+(.+?)\\s+[а-яё-]+\\s+епархии\\s+русской\\s+православной\\s+церкви(?:\\s*\\([^)]*\\))?\\s*$")
    private val pairedApostles = Regex("(?iu)(?<![а-яё])(?:(?:святых|свв\\.?|первоверховных|верховных|славных|всехвальных)\\s+)*(?:апостолов\\s+)?петра\\s+и\\s+павла(?![а-яё])|(?<![а-яё])петропавловск(?:ий|ого|ом|ая|ой|ую|ое|ому|ими|их)(?![а-яё])")
    private val namedLocation = Regex("(?iu)(?:^|\\s)(?:на|в(?!\\s+честь)|при|у)\\s+")
    private val unqualifiedAction = Regex("(?iu)\\b(?:в\\s+храме\\s+совершаются\\s+согласно\\s+расписанию|проводятся\\s+в\\s+храме\\s+в\\s+дни)\\b")

    private fun isUnqualifiedStatement(text: String) =
        building.findAll(text).count() == 1 && unqualifiedAction.containsMatchIn(text)

    private fun dedicationAliases(text: String): String {
        // Only the dedication before an address/associated-site suffix is normalized.
        val split = namedLocation.find(text)?.range?.first ?: text.length
        return text.substring(0, split).replace(pairedApostles, "pairedapostles") + text.substring(split)
    }

    private fun parishInLegalName(text: String): String? = legalParish.matchEntire(text.trim())
        ?.groupValues?.get(1)?.takeIf { building.containsMatchIn(it) && !service.containsMatchIn(it) && !clock.containsMatchIn(it) }

    /** Exact already-supported calendar names, for reconstruction within a proven OCR column. */
    internal fun calendarRanges(text: String): List<IntRange> =
        (calendarFeast.findAll(text) + localCouncilFeast.findAll(text)).map { it.range }.toList()

    fun isLabel(text: String): Boolean {
        if (isUnqualifiedStatement(text)) return false
        val candidate = (parishInLegalName(text) ?: text).replace(calendarFeast, " ").replace(localCouncilFeast, " ")
        val markers = buildingMarkers(candidate)
        val hasIntroducer = markers.any { introducer.containsMatchIn(candidate.substring(it.range.last + 1)) }
        // A clock must not turn an incomplete named heading into an unscoped section.
        val incompleteIntroducer = hasIntroducer && slices(candidate)?.any { !it.valid } == true
        return markers.isNotEmpty() && !service.containsMatchIn(candidate) &&
            (!clock.containsMatchIn(candidate) || incompleteIntroducer) &&
            (tokens(candidate).isNotEmpty() || hasIntroducer)
    }

    fun inlineLabel(text: String, calendarDate: LocalDate? = null, datedPreface: Boolean = false): String? {
        if (isUnqualifiedStatement(text)) return null
        parishInLegalName(text)?.let { return it }
        var candidate = text.replace(calendarFeast, " ").replace(localCouncilFeast, " ")
        // A calendar preface before the first clock in a dated service row is not a venue.
        // Standalone building headings, separate venue cells and locations after the clock
        // remain subject to identity checks, including churches dedicated to the same feast.
        if (calendarDate != null) {
            val firstClock = clock.find(candidate)?.range?.first
            if (firstClock != null || datedPreface) {
                val split = firstClock ?: candidate.length
                val prefix = candidate.substring(0, split).replace(martyrsFeast, " ")
                candidate = (if (calendarDate.monthValue == 11 && calendarDate.dayOfMonth == 21)
                    prefix.replace(shortMichaelFeast, " ") else prefix) + candidate.substring(split)
            }
        }
        val parsed = slices(candidate) ?: return candidate
        if (parsed.any { !it.valid }) return candidate
        // Retain every marker, including a nameless/incomplete one: null means unscoped.
        return parsed.joinToString(" | ") { it.label }.takeIf(String::isNotBlank)
    }

    private enum class Kind { CHURCH, CHAPEL, AISLE }
    private data class Slice(val label: String, val name: String, val locality: String, val kind: Kind, val valid: Boolean)
    private data class Identity(val words: Set<String>, val kind: Kind?)
    private data class Word(val raw: String, val start: Int, val end: Int)
    private val quotes = setOf('"', '«', '»', '“', '”')
    private val introducer = Regex("(?iu)^\\s+(?:во\\s+имя|в\\s+честь)(?=\\s|$)")
    private val localMarker = setOf("г", "город", "города", "село", "села", "пос", "поселок", "поселка", "деревня", "деревни")
    // The existing paired-apostle phrase is one lexical unit, including its supported свв. abbreviation.
    private val scopeWord = Regex("(?iu)(?:${pairedApostles.pattern.removePrefix("(?iu)")})|[^\\s/|.]+|[/|.]")

    private fun words(text: String, offset: Int = 0) = scopeWord.findAll(text).map {
        Word(it.value, offset + it.range.first, offset + it.range.last + 1)
    }.toList()

    private fun lexical(word: Word): String? {
        // A single trailing comma is normal Russian name punctuation, not a scope separator.
        // Periods, slashes, pipes and unsupported quotes remain strict boundaries.
        val plain = word.raw.removeSuffix(",").trim { it in quotes }
        if (pairedApostles.matches(plain)) return "pairedapostles"
        return plain.takeIf { nameWord.matches(it) }?.lowercase(Locale.ROOT)
    }

    private fun namePart(word: Word): Boolean {
        val plain = lexical(word) ?: return false
        return plain !in nameBoundary && plain !in localMarker &&
            !service.containsMatchIn(plain) && !building.containsMatchIn(plain)
    }

    private fun balancedQuotes(text: String): Boolean {
        var closing: Char? = null
        for (char in text) when (char) {
            '"' -> if (closing == '"') closing = null else if (closing == null) closing = '"' else return false
            '«' -> if (closing == null) closing = '»' else return false
            '“' -> if (closing == null) closing = '”' else return false
            '»', '”' -> if (closing == char) closing = null else return false
        }
        return closing == null
    }

    private fun kind(noun: String) = when {
        noun.startsWith("часов", true) -> Kind.CHAPEL
        noun.startsWith("придел", true) -> Kind.AISLE
        else -> Kind.CHURCH
    }

    private fun distinctiveName(name: String): Set<String> = tokens(dedicationAliases(name))
        .filterNot { word -> qualifiers.any(word::startsWith) }.toSet()

    private fun completeIntroducedName(name: String): Boolean {
        val distinctive = distinctiveName(name)
        if (distinctive.isEmpty()) return false
        // An unfinished generic icon phrase cannot supply its missing distinguishing name.
        return !distinctive.all { it.startsWith("икон") || it in setOf("божие", "божья", "матер", "богор") }
    }

    private fun incompleteGenericDedication(name: String): Boolean {
        val distinctive = distinctiveName(name)
        return distinctive.isNotEmpty() && !completeIntroducedName(name)
    }

    /** Per-marker scope. Location words survive in label, but never enter the name-only guard. */
    private fun slices(text: String): List<Slice>? {
        if (text.length > 2_000) return null
        val markers = buildingMarkers(text)
        if (markers.size > 12) return null
        return markers.mapIndexed { index, marker ->
            val left = markers.getOrNull(index - 1)?.range?.last?.plus(1) ?: 0
            var before = words(text.substring(left, marker.range.first), left).takeLastWhile(::namePart)
            if (index > 0) {
                // An "и" inside the existing paired-apostle lexical unit is never split.
                val connection = before.indexOfLast { lexical(it) == "и" }
                if (connection >= 0) before = before.drop(connection + 1)
            }
            val end = markers.getOrNull(index + 1)?.range?.first ?: text.length
            val tailStart = marker.range.last + 1
            val tail = text.substring(tailStart, end)
            val introduced = introducer.find(tail)
            val payload = tailStart + (introduced?.range?.last?.plus(1) ?: 0)
            val following = words(text.substring(payload, end), payload)
            var after = following.takeWhile(::namePart)
            if (index + 1 < markers.size && after.lastOrNull()?.let(::lexical) == "и") after = after.dropLast(1)
            fun original(parts: List<Word>) = if (parts.isEmpty()) "" else text.substring(parts.first().start, parts.last().end)
            val beforeText = original(before)
            val afterText = original(after)
            val useBefore = tokens(beforeText).isNotEmpty()
            val name = if (introduced != null) listOf(beforeText, afterText).filter(String::isNotBlank).joinToString(" ")
                else if (useBefore) beforeText else afterText
            val selectedBefore = beforeText.takeIf { introduced != null || useBefore }.orEmpty()
            val selectedAfter = afterText.takeIf { introduced != null || !useBefore }.orEmpty()
            val stop = following.getOrNull(after.size)
            val boundary = stop?.let(::lexical)
            val locationTail = if (boundary in setOf("в", "во", "на", "при", "у") || boundary in localMarker) {
                val kept = mutableListOf<Word>()
                for (word in following.drop(after.size)) {
                    if (word.raw in setOf("/", "|") || clock.containsMatchIn(word.raw) ||
                        service.containsMatchIn(word.raw) || word.raw.any(Char::isDigit)) break
                    if (word.raw == "." && kept.lastOrNull()?.let(::lexical) !in localMarker) break
                    kept += word
                }
                original(kept)
            } else ""
            // Preserve the introducer for callers that pass inlineLabel back into matches.
            val label = listOf(selectedBefore, marker.value, introduced?.value?.trim().orEmpty(), selectedAfter, locationTail)
                .filter(String::isNotBlank).joinToString(" ")
            val invalidBoundary = introduced != null && boundary in setOf("или", "для", "после", "перед")
            Slice(label, name, locationTail, kind(marker.value), balancedQuotes(name) && !invalidBoundary &&
                !incompleteGenericDedication(name) &&
                (introduced == null || completeIntroducedName(afterText)))
        }
    }

    private fun ownIdentity(value: String): Identity? {
        if (value.isBlank() || value.length > 2_000) return null
        val source = parishInLegalName(value) ?: value
        val markers = buildingMarkers(source)
        if (markers.size > 12) return null
        val introducedRanges = markers.mapNotNull { marker ->
            val offset = marker.range.last + 1
            introducer.find(source.substring(offset))?.range?.let { (offset + it.first)..(offset + it.last) }
        }
        // Target identity ends before a real location/associated-site suffix. Its buildings
        // must not become aliases of the selected building, including when its noun is omitted.
        val boundary = words(source).firstOrNull { word ->
            val plain = lexical(word)
            (plain in setOf("в", "во", "на", "при", "у") || plain in localMarker) &&
                introducedRanges.none { word.start in it }
        }
        val prefix = source.substring(0, boundary?.start ?: source.length)
        val parsed = slices(prefix) ?: return null
        val primary = parsed.firstOrNull()
        if (primary != null) return primary.takeIf { it.valid }
            ?.let { Identity(tokens(dedicationAliases(it.name)), it.kind) }
        // Markerless own names supply words, but cannot assert a church/chapel/aisle type.
        return prefix.takeIf { balancedQuotes(it) }?.let { Identity(tokens(dedicationAliases(it)), null) }
    }

    fun matches(label: String?, parish: Parish?): Boolean {
        if (label.isNullOrBlank()) return true
        if (parish == null || label.length > 2_000) return false
        val source = parishInLegalName(label) ?: label
        val parsed = slices(source) ?: return false
        if (parsed.any { !it.valid }) return false
        val ownScopes = listOfNotNull(ownIdentity(parish.name), ownIdentity(parish.shortName))
        val scopes = if (parsed.isNotEmpty()) parsed else listOf(Slice(source, source, "", Kind.CHURCH, true))
        for (scope in scopes) {
            val wantedName = distinctiveName(scope.name)
            val nameWords = tokens(scope.name)
            val relative = qualifiers.filter { prefix -> nameWords.any { it.startsWith(prefix) } }
            // Every marker needs identity evidence, including a nameless second marker.
            if (wantedName.isEmpty() && relative.isEmpty()) return false
            // Type, dedication and relative qualifiers belong to one complete own identity.
            if (ownScopes.none { own -> (parsed.isEmpty() || own.kind == scope.kind) &&
                    own.words.containsAll(wantedName) && relative.all { prefix -> own.words.any { it.startsWith(prefix) } } })
                return false
        }
        // Retain full locality coverage in addition to the narrower name/qualifier guard.
        val target = "${dedicationAliases(parish.name)} ${dedicationAliases(parish.shortName)} ${parish.city} ${parish.address}"
        val wanted = tokens(if (parsed.isEmpty()) dedicationAliases(source) else parsed.joinToString(" | ") {
            dedicationAliases("${it.name} ${it.locality}")
        })
        val available = tokens(target)
        val relative = wanted.filter { word -> qualifiers.any(word::startsWith) }
        val distinctive = wanted.filterNot { word -> qualifiers.any(word::startsWith) }.toSet()
        return if (distinctive.isNotEmpty()) distinctive.all { it in available }
        else relative.isNotEmpty()
    }


    private val sharedRestriction = Regex("(?iu)(?<![а-яё])(?:не|нет|ни|без|только|лишь|кроме|вместо|или|либо|соответственно|отмен[а-яё]*|перен[а-яё]*|исключ[а-яё]*|бывш[а-яё]*|ранее|истор[а-яё]*|припис[а-яё]*|упомин[а-яё]*|фото[а-яё]*|видео[а-яё]*|репортаж[а-яё]*|рассказ[а-яё]*|отч[её]т[а-яё]*|запис[а-яё]*|трансляц[а-яё]*|воспомин[а-яё]*|экскурс[а-яё]*|паломнич[а-яё]*|посещ[а-яё]*|вчера|прошл[а-яё]*|был[а-яё]*|состоя[а-яё]*|проводил[а-яё]*|совершал[а-яё]*|служил[а-яё]*)(?![а-яё])")
    private val sharedRelation = Regex("(?iu)(?<![а-яё])(?:при|у|на|из|для|после|перед|рядом|возле|около|с|к|по|во|в|через)(?![а-яё])")
    private val concreteServiceStart = Regex("(?iu)^(?:(?:часы|(?:(?:божественная|ранняя|поздняя)\\s+)*литургия|исповедь|утреня(?:\\s+с\\s+полиелеем)?|вечерня|всенощное\\s+бдение|молебен|панихида|акафист)(?:\\s*\\.\\s*|$))+$")
    private val generalSharedScope = Regex("(?iu)(?<![а-яё])(?:все|всех|богослужени[а-яё]*|служб[а-яё]*|расписани[а-яё]*)(?![а-яё])")
    private val sharedServiceStart = Regex("(?iu)^(?:часы\\s*\\.\\s*)?(?:(?:божественная|ранняя|поздняя)\\s+)*литургия(?=\\s|\\.|$)")
    private val sharedSuffix = Regex("(?iu)\\(\\s*в\\s+([^()]+)\\s*\\)\\s*\\.?$")
    private val sharedClause = Regex("(?iu)^([а-яё-]+(?:\\s+[а-яё-]+){0,7})\\s+(храме|церкви|соборе|часовне|приделе)\\s+(д\\.|деревне|деревня|с\\.|селе|село|г\\.|городе|город|пос\\.|поселке|поселок)\\s+([а-яё-]+(?:\\s+[а-яё-]+){0,3})$")

    fun hasUnsafeSharedInput(value: String): Boolean = value.codePoints().anyMatch {
        Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() ||
            Character.getType(it) == Character.SURROGATE.toInt()
    }

    /** Inspect original active scope text before inlineLabel can discard a negation. */
    fun hasSharedScopeRestriction(value: String): Boolean = value.length > 2_000 ||
        hasUnsafeSharedInput(value) || sharedRestriction.containsMatchIn(value)

    /** One complete foreign concrete service, not a general/exclusive venue notice. */
    fun isConcreteForeignEvent(raw: String, parish: Parish?): Boolean {
        if (parish == null || raw.length > 2_000 || hasUnsafeSharedInput(raw) || sharedRestriction.containsMatchIn(raw) ||
            generalSharedScope.containsMatchIn(raw) || ScheduleCancellation.containsMatchIn(raw)) return false
        val times = ScheduleClocks.pattern.findAll(raw).take(2).toList()
        if (times.size != 1 || raw.substring(0, times.single().range.first).isNotBlank() ||
            runCatching { java.time.LocalTime.of(times.single().groupValues[1].toInt(), times.single().groupValues[2].toInt()) }.isFailure) return false
        val suffix = sharedSuffix.find(raw) ?: return false
        if (times.single().range.last >= suffix.range.first) return false
        val prefix = raw.substring(times.single().range.last + 1, suffix.range.first).trim()
        if (!concreteServiceStart.matches(prefix) || buildingMarkers(prefix).isNotEmpty() || prefix.any { it == '(' || it == ')' }) return false
        val match = sharedClause.matchEntire(suffix.groupValues[1].trim()) ?: return false
        if (Regex("(?iu)(?<![а-яё])и(?![а-яё])").containsMatchIn(match.groupValues[1]) ||
            sharedRelation.containsMatchIn(match.groupValues[1]) || sharedRelation.containsMatchIn(match.groupValues[4]) ||
            match.groupValues[4].split(Regex("\\s+")).any { !it.first().isUpperCase() }) return false
        val clause = "${match.groupValues[1]} ${match.groupValues[2]} в ${match.groupValues[4]}"
        val parsed = slices(clause)?.singleOrNull() ?: return false
        return parsed.valid && distinctiveName(parsed.name).isNotEmpty() && parsed.locality.isNotBlank() && !matches(clause, parish)
    }

    /** A bounded positive event statement, never a general building alias. */
    fun sharedEventVenue(raw: String, parish: Parish?): String? {
        if (parish == null || raw.length > 2_000 || hasUnsafeSharedInput(raw) || raw.contains('|') ||
            sharedRestriction.containsMatchIn(raw) || ScheduleCancellation.containsMatchIn(raw)) return null
        val times = ScheduleClocks.pattern.findAll(raw).take(2).toList()
        if (times.size != 1 || runCatching { java.time.LocalTime.of(times.single().groupValues[1].toInt(),
                times.single().groupValues[2].toInt()) }.isFailure) return null
        val suffix = sharedSuffix.find(raw) ?: return null
        val prefix = raw.substring(0, suffix.range.first)
        if (prefix.any { it == '(' || it == ')' } || buildingMarkers(prefix).isNotEmpty() || !service.containsMatchIn(prefix) ||
            times.single().range.last >= suffix.range.first) return null
        if (raw.substring(0, times.single().range.first).isNotBlank() ||
            !sharedServiceStart.containsMatchIn(prefix.substring(times.single().range.last + 1).trim())) return null
        val members = suffix.groupValues[1].trim().split(Regex("(?iu)\\s+и\\s+"))
        if (members.size !in 2..4) return null
        val clauses = members.map { member ->
            val match = sharedClause.matchEntire(member.trim()) ?: return null
            if (sharedRelation.containsMatchIn(match.groupValues[1]) || sharedRelation.containsMatchIn(match.groupValues[4]) ||
                match.groupValues[4].split(Regex("\\s+")).any { !it.first().isUpperCase() }) return null
            // A proven administrative marker becomes the existing location introducer.
            // Preserve every locality word without requiring absent address-format tokens.
            val clause = "${match.groupValues[1]} ${match.groupValues[2]} в ${match.groupValues[4]}"
            val parsed = slices(clause)?.singleOrNull() ?: return null
            if (!parsed.valid || distinctiveName(parsed.name).isEmpty() || parsed.locality.isBlank()) return null
            clause to match.groupValues[4]
        }
        return clauses.firstOrNull { matches(it.first, parish) && exactSharedLocality(it.second, parish) }?.first
    }

    private fun exactSharedLocality(locality: String, parish: Parish): Boolean {
        fun completePlace(value: String): String? {
            if (hasUnsafeSharedInput(value)) return null
            val normalized = value.lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("[ ]+"), " ").trim(' ')
            val place = normalized.replace(Regex("^(?:д\\.|деревня |деревне |с\\.|село |селе |г\\.|город |городе |п\\.|пос\\.|поселок |поселке )\\s*"), "")
            return place.takeIf { Regex("[а-я]+(?:-[а-я]+)*(?: [а-я]+(?:-[а-я]+)*){0,3}").matches(it) }
        }
        val wanted = completePlace(locality) ?: return false
        // Validate the entire typed city: digits/punctuation/suffixes never disappear.
        return completePlace(parish.city) == wanted
    }

    private fun tokens(text: String): Set<String> = Regex("[а-яёa-z]{3,}").findAll(text.lowercase(Locale.ROOT))
        .map { it.value.replace('ё', 'е') }.filter { word -> word !in functional && generic.none(word::startsWith) }.map { it.take(5) }.toSet()
}

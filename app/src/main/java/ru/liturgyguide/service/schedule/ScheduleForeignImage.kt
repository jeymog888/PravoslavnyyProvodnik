package ru.liturgyguide.service.schedule

import ru.liturgyguide.model.Parish
import java.time.LocalDate

/** A deliberately small certificate for a completely foreign, directly published image. */
internal object ScheduleForeignImage {
    // Conservative potential markers: a calendar feast or an unfamiliar spelling is a refusal,
    // never permission to erase that line while certifying a foreign-only document.
    private val building = Regex("(?iu)(?<![а-яё])(?:храм[а-яё]*|церк[а-яё]*|собор[а-яё]*|часовн[а-яё]*|придел[а-яё]*)(?![а-яё])")
    private val scheduleHeading = Regex("(?iu)^\\s*расписание(?=\\s|:|$)")
    private val neutralScheduleHeading = Regex("(?iu)^\\s*расписание\\s+богослужений(?:\\s+на\\s+" +
        "(?:январь|февраль|март|апрель|май|июнь|июль|август|сентябрь|октябрь|ноябрь|декабрь)" +
        "\\s+20\\d{2}\\s*(?:г\\.?|года)?)?\\s*$")

    fun certify(document: ScheduleDocument, today: LocalDate, selected: Parish, website: String, parent: String,
        witness: KnownOtherScheduleVenue?, selectedResult: ScheduleParseResult): ScheduleParseResult {
        if (witness == null || !witness.validFor(selected, website, parent) || document.format != "image" ||
            ScheduleUrl.publicSource(document.sourceUrl) == null ||
            document.pages.size != 1 || selectedResult.disposition != ScheduleParseDisposition.REJECTED ||
            selectedResult.events.isNotEmpty() || unsafe(selectedResult)) return selectedResult
        val page = document.pages.single()
        val lines = page.lines
        if (page.sourceTruncated || page.ocrFailure != null || !page.rawOcrEvidence.isNullOrEmpty() ||
            lines.size !in 2..2_000 || lines.sumOf { it.text.length } > 200_000) return selectedResult
        if (lines.any { line ->
                line.struck || line.confidence !in 45..100 || line.temporalConfidence !in 55..100 ||
                    line.text.length !in 1..2_000 || line.text.any { it.isISOControl() && it != '\t' } ||
                    line.headingLevel !in 0..6 || line.startsDayContainer || line.endsDayContainer || line.endsTable ||
                    line.venue != null || line.venueEvidence != null ||
                    ScheduleCancellation.containsMatchIn(line.text) ||
                    line.serviceText?.let { it.length > 2_000 || it.any(Char::isISOControl) ||
                        ScheduleCancellation.containsMatchIn(it) || building.containsMatchIn(it) } == true
            }) return selectedResult
        var scheduleHeadings = 0
        var bodyHasClock = false
        for (line in lines.drop(1)) {
            val headingText = line.text.map { if (it.isWhitespace()) ' ' else it }.joinToString("")
            // Ordinary OCR does not preserve headingLevel. A second plain schedule
            // title, or one after timed rows, can start another building's section.
            if (scheduleHeading.containsMatchIn(headingText) && (++scheduleHeadings > 1 || bodyHasClock ||
                    !neutralScheduleHeading.matches(headingText)))
                return selectedResult
            if (ScheduleClocks.pattern.containsMatchIn(line.text)) bodyHasClock = true
        }
        // The first stage accepts exactly one primary heading. A second marker, even in
        // the same line, or another section heading makes scope unknown again.
        val venueLines = lines.withIndex().filter { building.containsMatchIn(it.value.text) }
        val primary = venueLines.singleOrNull() ?: return selectedResult
        if (primary.index != 0 || building.findAll(primary.value.text).count() != 1 || !ScheduleVenue.isLabel(primary.value.text) ||
            !ScheduleVenue.matches(primary.value.text, witness.parish) || ScheduleVenue.matches(primary.value.text, selected) ||
            lines.withIndex().any { it.index != primary.index && it.value.headingLevel > 0 })
            return selectedResult
        val other = ScheduleDocumentParser.inspect(document, today, witness.parish)
        if (other.disposition != ScheduleParseDisposition.PARSED || other.events.isEmpty() || unsafe(other)) return selectedResult
        return selectedResult.copy(rejectionReason = ScheduleRejectionReason.OTHER_ENTITY_ONLY)
    }

    private fun unsafe(result: ScheduleParseResult) = result.safetyConflict || result.withdrawsAll ||
        result.withdrawnDates.isNotEmpty() || result.withdrawnStarts.isNotEmpty()
}

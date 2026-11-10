package ru.liturgyguide.ui

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onNodeWithTag
import ru.liturgyguide.di.AppContainer
import ru.liturgyguide.model.PlayerLine
import ru.liturgyguide.model.PrayerTrack
import ru.liturgyguide.service.ServicePlaybackPlan
import ru.liturgyguide.service.StoredServicePlayback
import java.time.LocalDate

/** Canonical content identities only; neither viewport indices nor visible text select a node. */
internal fun feedLineTag(line: PlayerLine) = "feed_line_${line.id}"
internal fun feedSpeakerTag(line: PlayerLine) = "feed_speaker_${line.id}"
internal fun feedHeaderTag(track: PrayerTrack) = "feed_header_${track.id}"
internal fun feedTitleTag(track: PrayerTrack) = "feed_header_${track.id}"
internal fun feedLanguageTag(track: PrayerTrack) = "feed_language_${track.id}"
internal fun feedContextTag(track: PrayerTrack) = "feed_context_${track.id}"
internal fun SemanticsNodeInteractionsProvider.onFeedLine(line: PlayerLine) = onNodeWithTag(feedLineTag(line))
internal fun SemanticsNodeInteractionsProvider.onFeedLine(track: PrayerTrack, index: Int) = onFeedLine(track.lines[index])

/** Resolve a stored accepted track for old integration tests that already assert its bookmark. */
internal fun AppContainer.recordedFeedTrack(saved: StoredServicePlayback): PrayerTrack {
    val plan = ServicePlaybackPlan.build(getStageMatcher().getAllStages(), LocalDate.parse(saved.context.serviceDate),
        readings?.days?.singleOrNull { it.date == saved.context.serviceDate })
    val text = requireNotNull(plan.retainedTextAt(saved.snapshot.segmentIndex))
    return requireNotNull(plan.track(text.trackId))
}

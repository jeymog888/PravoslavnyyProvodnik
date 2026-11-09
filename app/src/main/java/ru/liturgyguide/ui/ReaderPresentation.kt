package ru.liturgyguide.ui

import ru.liturgyguide.service.PlaybackSegment
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ReaderStateKind { TEXT, WAIT, BEFORE_START, MEANING, MISSING_READING, END }

enum class ReaderPrimaryAction { PLAY, PAUSE, BEGIN_LITURGY, RETURN_TO_TEXT, CONTINUE_AFTER_MISSING, FINISH }

/** Presentation only: the accepted player remains the single owner of position and time. */
data class ReaderPresentation(
    val kind: ReaderStateKind,
    val status: String,
    val primaryAction: ReaderPrimaryAction,
    val primaryLabel: String
)

/** The compact shell must keep both the live state and the basis of the accepted place visible. */
internal fun readerVisibleStatus(reading: ActiveServicePlayback, presentation: ReaderPresentation,
    restored: Boolean, original: Boolean): String = buildList {
    add(presentation.status)
    if (restored) add("сохраненное чтение")
    if (reading.isApproximate && !presentation.status.contains("примерн", ignoreCase = true)) add("примерное место")
    if (presentation.kind == ReaderStateKind.TEXT && original && reading.track?.lines?.none { it.original != null } == true) add("по-русски")
}.joinToString(" · ")

/** Short visible labels; the action and status retain their full accessible descriptions. */
internal fun readerCompactPrimary(presentation: ReaderPresentation): String = when (presentation.primaryAction) {
    ReaderPrimaryAction.PLAY -> if (presentation.kind == ReaderStateKind.WAIT) "Продолжить" else "Двигать текст"
    ReaderPrimaryAction.PAUSE -> "Пауза"
    ReaderPrimaryAction.BEGIN_LITURGY -> "Началась"
    ReaderPrimaryAction.RETURN_TO_TEXT -> if (presentation.primaryLabel == "К ходу службы") "К службе" else "К тексту"
    ReaderPrimaryAction.CONTINUE_AFTER_MISSING -> "Прочитано"
    ReaderPrimaryAction.FINISH -> "Закончить"
}

/** Keep the live state in the narrow shell; provenance remains in controls and reading details. */
internal fun readerCompactStatus(reading: ActiveServicePlayback, presentation: ReaderPresentation): String = when (presentation.kind) {
    ReaderStateKind.TEXT -> if (reading.state.isPlaying) "Примерно"
        else if (reading.isApproximate) "Пауза · примерно" else "Пауза"
    ReaderStateKind.WAIT -> if (reading.state.isPlaying) "Примерно" else "Пауза · примерно"
    ReaderStateKind.BEFORE_START -> if (reading.state.isPlaying) "Примерно" else "До начала"
    ReaderStateKind.MEANING -> "Пояснение"
    ReaderStateKind.MISSING_READING -> "Нет текста"
    ReaderStateKind.END -> "Конец порядка"
}

fun readerPresentation(reading: ActiveServicePlayback, meaning: Boolean): ReaderPresentation {
    val playing = reading.state.isPlaying
    if (meaning) return ReaderPresentation(
        ReaderStateKind.MEANING,
        if (playing) "Пояснение · движение останавливается" else "Пояснение · на паузе",
        ReaderPrimaryAction.RETURN_TO_TEXT,
        if (reading.track != null) "К тексту" else "К ходу службы"
    )
    return when (val segment = reading.segment) {
        is PlaybackSegment.Text -> ReaderPresentation(
            ReaderStateKind.TEXT,
            if (playing) "Движение по времени · примерно" else "На паузе",
            if (playing) ReaderPrimaryAction.PAUSE else ReaderPrimaryAction.PLAY,
            if (playing) "Пауза" else "Двигать текст"
        )
        is PlaybackSegment.Wait -> if (segment.stageId == "preparation") ReaderPresentation(
            ReaderStateKind.BEFORE_START,
            if (playing) "До начала литургии · примерный переход включен" else "До начала литургии",
            ReaderPrimaryAction.BEGIN_LITURGY,
            "Литургия началась"
        ) else ReaderPresentation(
            ReaderStateKind.WAIT,
            if (playing) "Примерный переход включен" else "Примерный переход остановлен",
            if (playing) ReaderPrimaryAction.PAUSE else ReaderPrimaryAction.PLAY,
            if (playing) "Пауза" else "Продолжить по времени"
        )
        is PlaybackSegment.MissingReading -> ReaderPresentation(
            ReaderStateKind.MISSING_READING,
            "Текст на ${segment.serviceDate.format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru")))} пока не добавлен · пауза",
            ReaderPrimaryAction.CONTINUE_AFTER_MISSING,
            "Чтение в храме закончилось"
        )
        is PlaybackSegment.End -> ReaderPresentation(
            ReaderStateKind.END,
            "Порядок в приложении завершен",
            ReaderPrimaryAction.FINISH,
            "Закончить чтение"
        )
    }
}

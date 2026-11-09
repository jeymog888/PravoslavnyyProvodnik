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

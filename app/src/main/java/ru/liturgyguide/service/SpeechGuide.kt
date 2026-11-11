package ru.liturgyguide.service

import android.content.Context
import android.speech.SpeechRecognizer
import androidx.annotation.MainThread
import java.util.Locale
import kotlin.math.roundToInt

enum class SpeechStatus { IDLE, CHECKING, STARTING, LISTENING, PROCESSING, RESULT, ERROR, UNAVAILABLE }

data class SpeechGuideState(
    val status: SpeechStatus = SpeechStatus.IDLE,
    val transcript: String = "",
    val message: String = "Коротко послушать службу на этом телефоне",
    val canTryWithoutSupport: Boolean = false,
    val audioLevel: Float? = null,
    val heardSpeech: Boolean = false,
    val alternatives: List<String> = emptyList()
) {
    val isActive: Boolean get() = status == SpeechStatus.CHECKING || status == SpeechStatus.STARTING ||
        status == SpeechStatus.LISTENING || status == SpeechStatus.PROCESSING
    val isCapturing: Boolean get() = status == SpeechStatus.STARTING || status == SpeechStatus.LISTENING
}

/** One explicit on-device attempt. No network fallback, saved audio or automatic restart. */
class SpeechGuide internal constructor(
    private val backend: SpeechBackend,
    private val scheduler: SpeechScheduler,
    private val onStateChanged: (SpeechGuideState) -> Unit
) {
    constructor(context: Context, onStateChanged: (SpeechGuideState) -> Unit) :
        this(AndroidSpeechBackend(context.applicationContext), AndroidSpeechScheduler(), onStateChanged)

    private class Attempt { var recognizer: SpeechAttempt? = null }
    private var active: Attempt? = null
    private var deadline: SpeechCancellation? = null
    private var deadlineToken: Any? = null
    private var disposed = false

    var state: SpeechGuideState = availability()
        private set
    val initialState: SpeechGuideState get() = state

    /** Checks only the service's presence. Never requests permission or opens a recognizer. */
    @MainThread
    fun refreshAvailability() {
        if (!disposed && !state.isActive && state.status != SpeechStatus.RESULT) update(availability())
    }

    @MainThread
    fun start(skipSupportCheck: Boolean = false) {
        if (disposed || state.isActive || state.status == SpeechStatus.RESULT) return
        if (!available()) { update(availability()); return }
        if (!backend.hasPermission()) {
            update(SpeechGuideState(SpeechStatus.ERROR, message = permissionMessage))
            return
        }
        release()
        val attempt = Attempt().also { active = it }
        val checkSupport = backend.apiLevel >= 33 && !skipSupportCheck
        update(SpeechGuideState(if (checkSupport) SpeechStatus.CHECKING else SpeechStatus.STARTING,
            message = if (checkSupport) "Проверяем русский язык на телефоне" else "Включаем микрофон"))
        if (!current(attempt)) return
        try {
            val recognizer = backend.create(events(attempt))
            if (!current(attempt)) { recognizer.release(); return }
            attempt.recognizer = recognizer
            if (checkSupport) {
                arm(attempt, 5_000) { supportUnknown("Телефон не ответил на проверку русского языка") }
                recognizer.checkSupport({ support ->
                    if (current(attempt) && state.status == SpeechStatus.CHECKING) acceptSupport(attempt, support)
                }, { error ->
                    if (current(attempt) && state.status == SpeechStatus.CHECKING) {
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) fail(permissionMessage)
                        else supportUnknown("Не удалось проверить русский язык на телефоне")
                    }
                })
            } else beginCapture(attempt)
        } catch (_: SecurityException) {
            if (current(attempt)) fail(permissionMessage)
        } catch (_: RuntimeException) {
            if (current(attempt)) {
                if (checkSupport) supportUnknown("Не удалось проверить русский язык на телефоне")
                else fail("Локальное распознавание недоступно. Выберите этап вручную")
            }
        }
    }

    private fun acceptSupport(attempt: Attempt, support: SpeechSupport) {
        when {
            support.installed.any(::isRussian) -> beginCapture(attempt)
            support.pending.any(::isRussian) -> unavailable("Русская модель ожидает загрузки. Проверьте настройки распознавания речи телефона")
            support.supported.any(::isRussian) -> unavailable("Русская модель не установлена. Добавьте ее в настройках распознавания речи телефона")
            else -> unavailable("Сервис телефона не поддерживает локальное распознавание русского языка. Выберите этап вручную")
        }
    }

    private fun beginCapture(attempt: Attempt) {
        if (!current(attempt)) return
        update(SpeechGuideState(SpeechStatus.STARTING, message = "Включаем микрофон"))
        if (!current(attempt)) return
        arm(attempt, 15_000) { finishListening() }
        try {
            attempt.recognizer?.start()
        } catch (_: SecurityException) {
            if (current(attempt)) fail(permissionMessage)
        } catch (_: RuntimeException) {
            if (current(attempt)) fail("Не удалось включить локальное распознавание. Выберите этап вручную")
        }
    }

    /** Ends capture once, then allows at most five seconds for the final transcript. */
    @MainThread
    fun finishListening() {
        val attempt = active ?: return
        if (!capturing(attempt)) return
        beginProcessing(attempt)
        if (!current(attempt)) return
        try { attempt.recognizer?.finish() }
        catch (_: RuntimeException) { if (current(attempt)) fail("Не удалось завершить распознавание. Выберите этап вручную") }
    }

    @MainThread
    fun stop() {
        if (disposed) return
        release()
        val next = availability()
        update(if (next.status == SpeechStatus.IDLE) next.copy(message = "Микрофон выключен") else next)
    }

    @MainThread
    fun destroy() {
        if (disposed) return
        disposed = true
        release()
    }

    private fun events(attempt: Attempt) = object : SpeechEvents {
        override fun ready() {
            if (current(attempt) && state.status == SpeechStatus.STARTING) update(state.copy(
                status = SpeechStatus.LISTENING, message = "Слушаю службу"))
        }
        override fun beginning() {
            if (capturing(attempt)) update(state.copy(status = SpeechStatus.LISTENING,
                heardSpeech = true, message = "Слушаю службу"))
        }
        override fun level(rmsDb: Float) {
            if (!capturing(attempt) || !rmsDb.isFinite()) return
            // Relative feedback from the recognizer, not a calibrated sound meter.
            // Quantization avoids recomposing the sheet for every tiny callback change.
            val level = (((rmsDb + 2f) / 12f).coerceIn(0f, 1f) * 20).roundToInt() / 20f
            if (state.audioLevel != level) update(state.copy(audioLevel = level))
        }
        override fun end() { if (capturing(attempt)) beginProcessing(attempt) }
        override fun error(code: Int) { if (recognizing(attempt)) fail(errorMessage(code)) }
        override fun partial(text: String) {
            if (recognizing(attempt) && text.isNotBlank()) update(state.copy(transcript = text.trim().take(300)))
        }
        override fun result(text: String) = alternatives(listOf(text))
        override fun alternatives(texts: List<String>) {
            if (!recognizing(attempt)) return
            val candidates = texts.take(5).map { it.trim().take(300) }.filter(String::isNotBlank).distinct()
            release()
            if (candidates.isEmpty()) update(SpeechGuideState(SpeechStatus.ERROR, message = noFinalMessage))
            else update(SpeechGuideState(SpeechStatus.RESULT, candidates.first(),
                "Проверьте услышанные слова и выберите подходящий фрагмент", alternatives = candidates))
        }
    }

    private fun beginProcessing(attempt: Attempt) {
        if (!capturing(attempt)) return
        update(state.copy(status = SpeechStatus.PROCESSING, audioLevel = null, message = "Распознаю фразу"))
        if (current(attempt)) arm(attempt, 5_000) { fail(noFinalMessage) }
    }

    private fun current(attempt: Attempt) = !disposed && active === attempt
    private fun capturing(attempt: Attempt) = current(attempt) &&
        (state.status == SpeechStatus.STARTING || state.status == SpeechStatus.LISTENING)
    private fun recognizing(attempt: Attempt) = capturing(attempt) ||
        (current(attempt) && state.status == SpeechStatus.PROCESSING)

    private fun available() = backend.apiLevel >= 31 && runCatching { backend.isAvailable() }.getOrDefault(false)
    private fun availability() = if (available()) SpeechGuideState() else SpeechGuideState(SpeechStatus.UNAVAILABLE,
        message = "На телефоне нет локального распознавания. Выберите этап вручную")
    private fun update(next: SpeechGuideState) {
        if (disposed) return
        state = next
        onStateChanged(next)
    }
    private fun fail(message: String) { release(); update(SpeechGuideState(SpeechStatus.ERROR, message = message)) }
    private fun unavailable(message: String) { release(); update(SpeechGuideState(SpeechStatus.UNAVAILABLE, message = message)) }
    private fun supportUnknown(message: String) {
        release()
        update(SpeechGuideState(SpeechStatus.ERROR, message = "$message. Можно повторить проверку или явно попробовать без нее",
            canTryWithoutSupport = true))
    }
    private fun arm(attempt: Attempt, delay: Long, action: () -> Unit) {
        deadline?.cancel()
        val token = Any().also { deadlineToken = it }
        deadline = scheduler.schedule(delay) {
            if (current(attempt) && deadlineToken === token) {
                deadline = null
                deadlineToken = null
                action()
            }
        }
    }
    private fun release() {
        val old = active
        active = null // Invalidate before cancel/destroy, which may deliver callbacks synchronously.
        deadlineToken = null
        deadline?.cancel()
        deadline = null
        old?.recognizer?.let { runCatching { it.release() } }
    }
    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> permissionMessage
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Сервис телефона не поддерживает русский язык. Выберите этап вручную"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Русская модель недоступна. Проверьте настройки распознавания речи телефона"
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "Не удалось разобрать фразу. Пение и эхо могут мешать - выберите этап вручную"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято. Попробуйте еще раз чуть позже"
        SpeechRecognizer.ERROR_AUDIO -> "Не удалось включить микрофон. Возможно, он занят другим приложением"
        else -> "Локальное распознавание не сработало. Выберите этап вручную"
    }
    private companion object {
        const val permissionMessage = "Нет доступа к микрофону. Разрешите его в настройках приложения"
        const val noFinalMessage = "Не удалось разобрать фразу. Можно выбрать этап вручную"
        fun isRussian(tag: String): Boolean = runCatching {
            Locale.Builder().setLanguageTag(tag.trim().replace('_', '-')).build().toLanguageTag()
                .lowercase(Locale.ROOT) in setOf("ru", "ru-ru")
        }.getOrDefault(false)
    }
}

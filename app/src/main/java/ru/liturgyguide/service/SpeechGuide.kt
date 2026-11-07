package ru.liturgyguide.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.MainThread

enum class SpeechStatus { IDLE, STARTING, LISTENING, PROCESSING, RESULT, ERROR, UNAVAILABLE }

data class SpeechGuideState(
    val status: SpeechStatus = SpeechStatus.IDLE,
    val transcript: String = "",
    val message: String = "Коротко послушать службу на этом телефоне"
) {
    val isActive: Boolean
        get() = status == SpeechStatus.STARTING || status == SpeechStatus.LISTENING ||
            status == SpeechStatus.PROCESSING
}

/**
 * One explicitly started, bounded, on-device listening attempt. Never falls back
 * to a network recognizer, writes audio, or starts another attempt automatically.
 * The owner requests RECORD_AUDIO, calls stop() when leaving the foreground,
 * and destroy() when disposed. All methods and callbacks run on the main thread.
 */
class SpeechGuide(
    context: Context,
    private val onStateChanged: (SpeechGuideState) -> Unit
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var sessionId = 0
    private var disposed = false
    private var timeout: Runnable? = null

    var state: SpeechGuideState = if (isOnDeviceAvailable()) {
        SpeechGuideState()
    } else {
        SpeechGuideState(
            status = SpeechStatus.UNAVAILABLE,
            message = "На телефоне нет локального распознавания. Выберите этап по подсказкам"
        )
    }
        private set

    val initialState: SpeechGuideState get() = state

    @MainThread
    fun start() {
        if (disposed || state.isActive) return
        if (!isOnDeviceAvailable()) {
            update(SpeechGuideState(
                SpeechStatus.UNAVAILABLE,
                message = "На телефоне нет локального распознавания. Выберите этап по подсказкам"
            ))
            return
        }
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            update(SpeechGuideState(
                SpeechStatus.ERROR,
                message = "Для прослушивания разрешите доступ к микрофону"
            ))
            return
        }

        val activeSession = ++sessionId
        releaseRecognizer()
        update(SpeechGuideState(SpeechStatus.STARTING, message = "Включаем микрофон"))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            }
            recognizer?.setRecognitionListener(listener(activeSession))
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // The on-device factory above enforces the processing mode.
                // This extra alone would not guarantee offline processing.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
            scheduleTimeout(15_000L) { finishListening() }
        } catch (_: SecurityException) {
            fail("Нет доступа к микрофону. Разрешите его в настройках приложения")
        } catch (_: RuntimeException) {
            fail("Локальное распознавание недоступно. Выберите этап по подсказкам")
        }
    }

    /** Ends capture and briefly waits for the recognizer's final transcript. */
    @MainThread
    fun finishListening() {
        if (disposed || !state.isActive || state.status == SpeechStatus.PROCESSING) return
        update(state.copy(status = SpeechStatus.PROCESSING, message = "Проверяем услышанную фразу"))
        try {
            recognizer?.stopListening()
            scheduleTimeout(5_000L) {
                fail("Не удалось разобрать фразу. Можно выбрать этап вручную")
            }
        } catch (_: RuntimeException) {
            fail("Не удалось завершить распознавание. Можно выбрать этап вручную")
        }
    }

    /** Cancels capture and discards the transient transcript. No result is used. */
    @MainThread
    fun stop() {
        if (disposed) return
        ++sessionId
        releaseRecognizer()
        val available = isOnDeviceAvailable()
        update(SpeechGuideState(
            status = if (available) SpeechStatus.IDLE else SpeechStatus.UNAVAILABLE,
            message = if (available) "Микрофон выключен" else
                "На телефоне нет локального распознавания. Выберите этап по подсказкам"
        ))
    }

    @MainThread
    fun destroy() {
        if (disposed) return
        disposed = true
        ++sessionId
        releaseRecognizer()
    }

    private fun listener(activeSession: Int) = object : RecognitionListener {
        private fun active() = !disposed && activeSession == sessionId

        override fun onReadyForSpeech(params: Bundle?) {
            // A delayed ready callback must not reactivate an attempt that the
            // user or capture timeout has already moved into processing.
            if (active() && state.status == SpeechStatus.STARTING) update(state.copy(
                status = SpeechStatus.LISTENING, message = "Слушаем короткую фразу. Микрофон включен"
            ))
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            // stopListening() can trigger this callback too. In that case the
            // existing processing deadline must not be restarted or extended.
            if (!active() || state.status == SpeechStatus.PROCESSING) return
            update(state.copy(status = SpeechStatus.PROCESSING, message = "Проверяем услышанную фразу"))
            scheduleTimeout(5_000L) {
                if (active()) fail("Не удалось разобрать фразу. Можно выбрать этап вручную")
            }
        }

        override fun onError(error: Int) {
            if (active()) fail(errorMessage(error))
        }

        override fun onResults(results: Bundle?) {
            if (!active()) return
            val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty().trim()
            ++sessionId
            releaseRecognizer()
            if (transcript.isBlank()) {
                update(SpeechGuideState(SpeechStatus.ERROR,
                    message = "Не удалось разобрать фразу. Можно выбрать этап вручную"))
            } else {
                update(SpeechGuideState(SpeechStatus.RESULT, transcript,
                    "Распознанная фраза может содержать ошибки"))
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!active()) return
            val transcript = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty().trim()
            if (transcript.isNotBlank()) update(state.copy(transcript = transcript))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun isOnDeviceAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        } catch (_: RuntimeException) {
            false
        }

    private fun update(next: SpeechGuideState) {
        if (disposed) return
        state = next
        onStateChanged(next)
    }

    private fun fail(message: String) {
        ++sessionId
        releaseRecognizer()
        update(SpeechGuideState(SpeechStatus.ERROR, message = message))
    }

    private fun scheduleTimeout(delay: Long, action: () -> Unit) {
        timeout?.let(handler::removeCallbacks)
        timeout = Runnable { if (!disposed) action() }.also { handler.postDelayed(it, delay) }
    }

    private fun releaseRecognizer() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        recognizer?.let {
            try { it.cancel() } catch (_: RuntimeException) { /* Already disconnected. */ }
            try { it.destroy() } catch (_: RuntimeException) { /* Already disconnected. */ }
        }
        recognizer = null
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону. Разрешите его в настройках приложения"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Для локального распознавания нужен русский язык в настройках распознавания речи телефона"
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не удалось разобрать фразу. Пение и эхо могут мешать - выберите этап вручную"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято. Попробуйте еще раз чуть позже"
        SpeechRecognizer.ERROR_AUDIO -> "Не удалось включить микрофон. Возможно, он занят другим приложением"
        else -> "Локальное распознавание не сработало. Можно выбрать этап по подсказкам"
    }
}

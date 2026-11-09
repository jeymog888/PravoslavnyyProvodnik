package ru.liturgyguide.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/** Narrow Android boundary, so cancellation and deadlines can be tested without a microphone. */
internal data class SpeechSupport(val installed: List<String>, val supported: List<String>, val pending: List<String>)
internal interface SpeechEvents {
    fun ready()
    fun end()
    fun error(code: Int)
    fun partial(text: String)
    fun result(text: String)
}
internal interface SpeechAttempt {
    fun checkSupport(result: (SpeechSupport) -> Unit, error: (Int) -> Unit)
    fun start()
    fun finish()
    fun release()
}
internal interface SpeechBackend {
    val apiLevel: Int
    fun isAvailable(): Boolean
    fun hasPermission(): Boolean
    fun create(events: SpeechEvents): SpeechAttempt
}
internal fun interface SpeechCancellation { fun cancel() }
internal fun interface SpeechScheduler { fun schedule(delayMillis: Long, action: () -> Unit): SpeechCancellation }

internal class AndroidSpeechScheduler : SpeechScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun schedule(delayMillis: Long, action: () -> Unit): SpeechCancellation {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return SpeechCancellation { handler.removeCallbacks(runnable) }
    }
}

internal class AndroidSpeechBackend(private val context: Context) : SpeechBackend {
    override val apiLevel get() = Build.VERSION.SDK_INT
    override fun isAvailable() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    override fun hasPermission() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    // SpeechGuide checks RECORD_AUDIO immediately before creating an attempt and handles revocation.
    @SuppressLint("MissingPermission")
    override fun create(events: SpeechEvents): SpeechAttempt {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) throw IllegalStateException("On-device recognition needs API 31")
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = events.ready()
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = events.end()
                override fun onError(error: Int) = events.error(error)
                override fun onResults(results: Bundle?) = events.result(transcript(results))
                override fun onPartialResults(partialResults: Bundle?) = events.partial(transcript(partialResults))
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        } catch (failure: RuntimeException) {
            runCatching { recognizer.destroy() }
            throw failure
        }
        // Exactly the same ru-RU request is used for support and capture; the factory enforces local processing.
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        return object : SpeechAttempt {
            override fun checkSupport(result: (SpeechSupport) -> Unit, error: (Int) -> Unit) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    error(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
                    return
                }
                SpeechSupportApi33.check(recognizer, intent, context.mainExecutor, result, error)
            }
            override fun start() = recognizer.startListening(intent)
            override fun finish() = recognizer.stopListening()
            override fun release() {
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
            }
        }
    }
    private fun transcript(bundle: Bundle?) = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull().orEmpty()
}

/** Keep API 33 callback types out of the attempt used on Android 12. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object SpeechSupportApi33 {
    fun check(
        recognizer: SpeechRecognizer,
        intent: Intent,
        executor: Executor,
        result: (SpeechSupport) -> Unit,
        error: (Int) -> Unit,
    ) {
        recognizer.checkRecognitionSupport(intent, executor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) = result(SpeechSupport(
                support.installedOnDeviceLanguages.toList(), support.supportedOnDeviceLanguages.toList(),
                support.pendingOnDeviceLanguages.toList()))
            override fun onError(code: Int) = error(code)
        })
    }
}

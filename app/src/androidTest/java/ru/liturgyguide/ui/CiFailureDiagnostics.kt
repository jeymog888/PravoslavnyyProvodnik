package ru.liturgyguide.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** No retry, changed expectation or new Espresso handler: rethrow the original failure. */
internal fun withCiFailureDiagnostics(source: String, block: () -> Unit): Unit = try {
    block()
} catch (failure: Throwable) {
    CiFailureDiagnostics.capture(source, failure)
    throw failure
}

internal fun closeSoftKeyboardWithCiDiagnostics() =
    withCiFailureDiagnostics("Espresso.closeSoftKeyboard") { Espresso.closeSoftKeyboard() }

internal fun pressBackWithCiDiagnostics() =
    withCiFailureDiagnostics("Espresso.pressBack") { Espresso.pressBack() }

/** androidTest-only, opt-in, one first-failure packet per instrumentation process. */
private object CiFailureDiagnostics {
    private val captured = AtomicBoolean(false)

    fun capture(source: String, original: Throwable) {
        try {
            val arguments = InstrumentationRegistry.getArguments()
            if (arguments.getString("feoriaCiDiagnostics") != "true" || !captured.compareAndSet(false, true)) return
            val run = requireNotNull(arguments.getString("feoriaCiDiagnosticsRun"))
            require(run.matches(Regex("[A-Za-z0-9_-]{1,100}")))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val directory = File(requireNotNull(context.getExternalFilesDir("ci-diagnostics")),
                "$run/first-failure-${System.currentTimeMillis()}")
            check(directory.mkdirs())
            File(directory, "failure.txt").writeText(original.stackTraceToString())
            val test = original.stackTrace.firstOrNull { frame ->
                frame.className.startsWith("ru.liturgyguide.ui.") && runCatching {
                    Class.forName(frame.className, false, javaClass.classLoader).declaredMethods.any {
                        it.name == frame.methodName && it.isAnnotationPresent(Test::class.java)
                    }
                }.getOrDefault(false)
            }?.let { "${it.className}#${it.methodName}" } ?: "UNRESOLVED-see-failure.txt"
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val record = JSONObject().put("test", test).put("source", source).put("run", run)
                .put("wallTimeMillis", System.currentTimeMillis()).put("uptimeMillis", SystemClock.uptimeMillis())
                .put("isInteractive", power.isInteractive).put("isKeyguardLocked", keyguard.isKeyguardLocked)
                .put("isDeviceLocked", keyguard.isDeviceLocked).put("verdict", "DIAGNOSTIC_ONLY")
            File(directory, "failure-context.json").writeText(record.toString(2))
            val deadline = SystemClock.uptimeMillis() + 30_000L
            val commands = JSONArray()
            // Focus owner first, before the failed test's finally block or ActivityScenario.close.
            val reads = listOf(
                "windows.txt" to "dumpsys -t 2 window windows",
                "displays.txt" to "dumpsys -t 2 window displays",
                "screen.png" to "screencap -p",
                "power.txt" to "dumpsys -t 2 power",
                "policy.txt" to "dumpsys -t 2 window policy",
                "activity.txt" to "dumpsys -t 2 activity activities",
                "input-method.txt" to "dumpsys -t 2 input_method",
                "screen-off-timeout.txt" to "settings get system screen_off_timeout",
                "stay-on.txt" to "settings get global stay_on_while_plugged_in",
                "show-ime.txt" to "settings get secure show_ime_with_hard_keyboard"
            )
            reads.forEach { (name, command) ->
                commands.put(read(directory, name, command, deadline))
            }
            record.put("finishedUptimeMillis", SystemClock.uptimeMillis()).put("commands", commands)
            File(directory, "capture.json").writeText(record.toString(2))
            Log.e("FeoriaCiDiagnostics", "First failure evidence: ${directory.absolutePath}")
        } catch (diagnosticFailure: Throwable) {
            // A collection problem must not replace the failure that triggered this packet.
            Log.e("FeoriaCiDiagnostics", "Could not finish first failure evidence", diagnosticFailure)
        }
    }

    private fun read(directory: File, name: String, command: String, deadline: Long): JSONObject {
        val started = SystemClock.uptimeMillis()
        val record = JSONObject().put("file", name).put("command", command).put("startedUptimeMillis", started)
        val budget = minOf(3_000L, deadline - started)
        if (budget <= 0) return record.put("outcome", "BUDGET_EXHAUSTED")
        val descriptor = AtomicReference<ParcelFileDescriptor?>()
        val cancelled = AtomicBoolean(false)
        val task = FutureTask<ByteArray> {
            val pipe = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            descriptor.set(pipe)
            if (cancelled.get()) { pipe.close(); throw IllegalStateException("diagnostic read cancelled") }
            ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
        }
        Thread(task, "feoria-ci-diagnostic").apply { isDaemon = true; start() }
        try {
            val data = task.get(budget, TimeUnit.MILLISECONDS)
            File(directory, name).writeBytes(data)
            record.put("outcome", if (data.isEmpty()) "EMPTY" else "RECORDED").put("bytes", data.size)
        } catch (failure: Exception) {
            record.put("outcome", "ERROR").put("error", failure.toString())
        } finally {
            cancelled.set(true)
            runCatching { descriptor.get()?.close() }
            task.cancel(true)
        }
        return record.put("finishedUptimeMillis", SystemClock.uptimeMillis())
    }
}

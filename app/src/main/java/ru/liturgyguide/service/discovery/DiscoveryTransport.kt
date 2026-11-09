package ru.liturgyguide.service.discovery

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A null body means GET; otherwise the transport sends an encoded POST form. */
data class DiscoveryRequest(val url: String, val postBody: String? = null)
class DiscoveryException(val reason: DiscoveryFailure) : IOException(reason.name)

fun interface DiscoveryTransport {
    suspend fun fetch(request: DiscoveryRequest): String
}

/**
 * The deadline bounds the entire operation, including connection setup and body reads.
 * Cancellation immediately completes the coroutine and schedules socket cleanup off the UI thread.
 * A platform DNS lookup can outlive cancellation; workers are bounded and late results are discarded.
 */
class UrlConnectionDiscoveryTransport(
    private val timeoutMillis: Long = 25_000,
    private val responseLimitBytes: Int = DefaultPlaceDiscovery.MAX_RESPONSE_BYTES,
    private val minimumRequestIntervalMillis: Long = 1_000,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val monotonicNanos: () -> Long = System::nanoTime
) : DiscoveryTransport {
    init {
        require(timeoutMillis in 1..25_000)
        require(responseLimitBytes in 1..DefaultPlaceDiscovery.MAX_RESPONSE_BYTES)
        require(minimumRequestIntervalMillis in 0..60_000)
    }
    private val active = AtomicBoolean(false)
    private val lastStartedNanos = AtomicReference<Long?>(null)

    override suspend fun fetch(request: DiscoveryRequest): String {
        if (!active.compareAndSet(false, true)) throw DiscoveryException(DiscoveryFailure.BUSY)
        val started = monotonicNanos()
        val previous = lastStartedNanos.get()
        if (previous != null && started >= previous && started - previous < minimumRequestIntervalMillis * 1_000_000) {
            active.set(false)
            throw DiscoveryException(DiscoveryFailure.RATE_LIMITED)
        }
        lastStartedNanos.set(started)
        return suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val connection = AtomicReference<HttpURLConnection?>(null)
            val worker = AtomicReference<Future<*>?>(null)
            val deadline = AtomicReference<ScheduledFuture<*>?>(null)

            fun cleanup() {
                deadline.getAndSet(null)?.cancel(false)
                worker.get()?.cancel(true)
                connection.getAndSet(null)?.let { open -> cleanupExecutor.execute { runCatching { open.disconnect() } } }
            }
            fun fail(reason: DiscoveryFailure) {
                if (!completed.compareAndSet(false, true)) return
                active.set(false)
                continuation.resumeWithException(DiscoveryException(reason))
                cleanup()
            }
            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    active.set(false)
                    cleanup()
                }
            }
            val timeout = deadlineExecutor.schedule({ fail(DiscoveryFailure.TIMEOUT) }, timeoutMillis, TimeUnit.MILLISECONDS)
            deadline.set(timeout)
            if (completed.get()) timeout.cancel(false)

            val task = try { networkExecutor.submit {
                var opened: HttpURLConnection? = null
                try {
                    if (completed.get()) return@submit
                    val uri = URL(request.url)
                    if (uri.protocol != "https" || uri.host.isBlank() || uri.userInfo != null) {
                        fail(DiscoveryFailure.INVALID_QUERY)
                        return@submit
                    }
                    val current = connectionFactory(uri)
                    opened = current
                    connection.set(current)
                    if (completed.get()) return@submit
                    fun remainingMillis(): Int {
                        val elapsed = TimeUnit.NANOSECONDS.toMillis((monotonicNanos() - started).coerceAtLeast(0))
                        val remaining = timeoutMillis - elapsed
                        if (remaining <= 0 || completed.get()) throw SocketTimeoutException()
                        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                    }
                    current.instanceFollowRedirects = false
                    current.connectTimeout = remainingMillis()
                    current.readTimeout = remainingMillis()
                    current.setRequestProperty("User-Agent", USER_AGENT)
                    current.setRequestProperty("Accept", "application/json")
                    current.setRequestProperty("Accept-Encoding", "identity")
                    if (request.postBody != null) {
                        val bytes = request.postBody.toByteArray(StandardCharsets.UTF_8)
                        current.requestMethod = "POST"
                        current.doOutput = true
                        current.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        current.setFixedLengthStreamingMode(bytes.size)
                        current.outputStream.use { it.write(bytes) }
                    }
                    current.readTimeout = remainingMillis()
                    val status = current.responseCode
                    if (status != HttpURLConnection.HTTP_OK) {
                        fail(when (status) {
                            429 -> DiscoveryFailure.RATE_LIMITED
                            in 500..599 -> DiscoveryFailure.SERVER_UNAVAILABLE
                            else -> DiscoveryFailure.UNAVAILABLE
                        })
                        return@submit
                    }
                    if (current.contentLengthLong > responseLimitBytes) {
                        fail(DiscoveryFailure.TOO_LARGE)
                        return@submit
                    }
                    val output = ByteArrayOutputStream()
                    current.inputStream.use { input ->
                        val buffer = ByteArray(8_192)
                        while (!completed.get()) {
                            current.readTimeout = remainingMillis()
                            val count = input.read(buffer)
                            if (count == -1) break
                            if (count > responseLimitBytes - output.size()) {
                                fail(DiscoveryFailure.TOO_LARGE)
                                return@submit
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                    if (completed.compareAndSet(false, true)) {
                        active.set(false)
                        deadline.getAndSet(null)?.cancel(false)
                        continuation.resume(output.toString(StandardCharsets.UTF_8.name()))
                    }
                } catch (_: SocketTimeoutException) {
                    fail(DiscoveryFailure.TIMEOUT)
                } catch (_: Exception) {
                    fail(DiscoveryFailure.UNAVAILABLE)
                } finally {
                    connection.compareAndSet(opened, null)
                    opened?.let { runCatching { it.disconnect() } }
                }
            } } catch (_: RejectedExecutionException) {
                fail(DiscoveryFailure.BUSY)
                return@suspendCancellableCoroutine
            }
            worker.set(task)
            if (completed.get()) task.cancel(true)
        }
    }

    companion object {
        const val USER_AGENT = "Feoria/0.6 (+https://github.com/jeymog888/PravoslavnyyProvodnik)"
        // Shared, bounded daemon pools avoid creating threads every time a search screen is opened.
        private val threadCounter = AtomicLong()
        private fun daemonThread(job: Runnable) = Thread(job, "feoria-discovery-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
        private val networkExecutor = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(4), ::daemonThread, ThreadPoolExecutor.AbortPolicy())
        private val cleanupExecutor = Executors.newSingleThreadExecutor(::daemonThread)
        private val deadlineExecutor = Executors.newSingleThreadScheduledExecutor(::daemonThread)
    }
}

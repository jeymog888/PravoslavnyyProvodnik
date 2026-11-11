package ru.liturgyguide.service.schedule

import kotlinx.coroutines.suspendCancellableCoroutine
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ScheduleUrl {
    fun normalized(value: String): String? = runCatching {
        val trimmed = value.trim()
        val uri = URI(if (trimmed.startsWith("http://")) "https://" + trimmed.removePrefix("http://") else trimmed)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443))
        val host = requireNotNull(uri.host).lowercase()
        require(host.contains('.') && host != "localhost" && !host.endsWith(".local") && !host.endsWith(".localhost"))
        require(!Regex("^[0-9.]+$").matches(host) && !host.contains(':'))
        require(trimmed.length <= 2_048 && trimmed.none(Char::isISOControl))
        // Preserve escaped query delimiters; decoding then rebuilding would change ?page=a%26b.
        URI("https://$host${uri.rawPath.ifEmpty { "/" }}${uri.rawQuery?.let { "?$it" }.orEmpty()}").toASCIIString()
    }.getOrNull()

    fun sameOrigin(first: String, second: String): Boolean = runCatching {
        val a = URI(first); val b = URI(second)
        a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
    }.getOrDefault(false)

    internal fun permittedRedirect(first: String, second: String): Boolean = runCatching {
        val a = URI(first).host.removePrefix("www.")
        val b = URI(second).host.removePrefix("www.")
        a == b
    }.getOrDefault(false)
}

data class ScheduleResponse(val url: String, val bytes: ByteArray, val contentType: String) {
    fun html(): Document? {
        if (contentType.substringBefore(';').trim().lowercase() !in setOf("text/html", "application/xhtml+xml")) return null
        val charset = Regex("(?i)charset\\s*=\\s*[\"']?([^;\\s\"']+)").find(contentType)?.groupValues?.get(1)
        return runCatching { Jsoup.parse(ByteArrayInputStream(bytes), charset, url) }.getOrNull()
    }
}

fun interface ScheduleTransport { suspend fun fetch(url: String): ScheduleResponse }

/** Separate from map search: accepts HTML and its charset, with bounded same-site redirects. */
class UrlConnectionScheduleTransport(
    private val timeoutMillis: Long = 12_000,
    private val limitBytes: Int = 12_582_912,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName
) : ScheduleTransport {
    init { require(timeoutMillis in 1..20_000); require(limitBytes in 1..12_582_912) }

    override suspend fun fetch(url: String): ScheduleResponse = suspendCancellableCoroutine { continuation ->
        val complete = AtomicBoolean(false)
        val socket = AtomicReference<HttpURLConnection?>(null)
        val worker = AtomicReference<Future<*>?>(null)
        val deadline = AtomicReference<ScheduledFuture<*>?>(null)
        fun cleanup() {
            deadline.getAndSet(null)?.cancel(false)
            worker.get()?.cancel(true)
            socket.getAndSet(null)?.let { open -> cleanupPool.execute { runCatching { open.disconnect() } } }
        }
        fun fail() {
            if (complete.compareAndSet(false, true)) {
                continuation.resumeWithException(IOException("Schedule source unavailable"))
                cleanup()
            }
        }
        continuation.invokeOnCancellation { if (complete.compareAndSet(false, true)) cleanup() }
        val timeout = deadlinePool.schedule(::fail, timeoutMillis, TimeUnit.MILLISECONDS)
        deadline.set(timeout)
        if (complete.get()) timeout.cancel(false)
        val task = runCatching { networkPool.submit {
            try {
                var target = ScheduleUrl.normalized(url) ?: throw IOException()
                var redirects = 0
                while (!complete.get()) {
                    val addresses = resolve(URI(target).host)
                    if (addresses.isEmpty() || addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress }) throw IOException()
                    if (complete.get()) return@submit
                    val current = connectionFactory(URL(target))
                    socket.set(current)
                    try {
                        if (complete.get()) return@submit
                        current.instanceFollowRedirects = false
                        current.connectTimeout = timeoutMillis.toInt()
                        current.readTimeout = timeoutMillis.toInt()
                        current.setRequestProperty("User-Agent", "Feoria/0.8 (+https://github.com/jeymog888/PravoslavnyyProvodnik)")
                        current.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,application/pdf,image/*;q=0.9")
                        current.setRequestProperty("Accept-Encoding", "identity")
                        val status = current.responseCode
                        if (status in setOf(301, 302, 303, 307, 308)) {
                            if (++redirects > 2) throw IOException()
                            val location = current.getHeaderField("Location") ?: throw IOException()
                            val next = ScheduleUrl.normalized(URI(target).resolve(location).toString()) ?: throw IOException()
                            if (!ScheduleUrl.permittedRedirect(target, next)) throw IOException()
                            target = next
                            continue
                        }
                        if (status != 200 || current.contentLengthLong > limitBytes) throw IOException()
                        val output = ByteArrayOutputStream()
                        current.inputStream.use { input ->
                            val buffer = ByteArray(8_192)
                            while (!complete.get()) {
                                val count = input.read(buffer)
                                if (count == -1) break
                                if (count > limitBytes - output.size()) throw IOException()
                                output.write(buffer, 0, count)
                            }
                        }
                        val bytes = decodeBody(output.toByteArray(), current.getHeaderField("Content-Encoding"))
                        if (complete.compareAndSet(false, true)) {
                            deadline.getAndSet(null)?.cancel(false)
                            continuation.resume(ScheduleResponse(target, bytes, current.contentType.orEmpty()))
                        }
                        return@submit
                    } finally {
                        socket.compareAndSet(current, null)
                        runCatching { current.disconnect() }
                    }
                }
            } catch (_: Exception) { fail() }
        } }.getOrElse { fail(); return@suspendCancellableCoroutine }
        worker.set(task)
        if (complete.get()) task.cancel(true)
    }

    private fun decodeBody(bytes: ByteArray, contentEncoding: String?): ByteArray {
        // Some sources send gzip even after Accept-Encoding: identity. Bound the
        // expanded body too, so a tiny compressed response cannot exhaust memory.
        val encoding = contentEncoding.orEmpty().trim().lowercase()
        val gzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val input = when {
            gzip || encoding == "gzip" -> GZIPInputStream(ByteArrayInputStream(bytes))
            encoding == "deflate" -> InflaterInputStream(ByteArrayInputStream(bytes))
            encoding.isBlank() || encoding == "identity" -> return bytes
            else -> throw IOException("Unsupported response encoding")
        }
        return input.use { decoded ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw IOException("Response cancelled")
                val count = decoded.read(buffer)
                if (count == -1) break
                if (count > limitBytes - output.size()) throw IOException("Expanded response too large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    companion object {
        private fun daemon(job: Runnable) = Thread(job, "feoria-schedule").apply { isDaemon = true }
        private val networkPool = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2), ::daemon, ThreadPoolExecutor.AbortPolicy())
        private val cleanupPool = Executors.newSingleThreadExecutor(::daemon)
        private val deadlinePool = Executors.newSingleThreadScheduledExecutor(::daemon)
    }
}

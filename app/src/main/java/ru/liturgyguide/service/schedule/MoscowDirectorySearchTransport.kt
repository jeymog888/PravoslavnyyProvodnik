package ru.liturgyguide.service.schedule

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder
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

/** One anonymous session, not an account login; tokens and cookies never leave this operation. */
fun interface MoscowDirectorySearchTransport {
    suspend fun search(queries: List<String>): List<ScheduleResponse>
}

class UrlConnectionMoscowDirectorySearchTransport(
    private val timeoutMillis: Long = 10_000,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) : MoscowDirectorySearchTransport {
    init { require(timeoutMillis in 1..15_000) }

    override suspend fun search(queries: List<String>): List<ScheduleResponse> {
        require(queries.size in 1..2 && queries.distinct().size == queries.size)
        require(queries.all { it.length in 2..160 && it.none(Char::isISOControl) })
        return suspendCancellableCoroutine { continuation ->
            val complete = AtomicBoolean(false)
            val socket = AtomicReference<HttpURLConnection?>(null)
            val worker = AtomicReference<Future<*>?>(null)
            val deadline = AtomicReference<ScheduledFuture<*>?>(null)
            val started = System.nanoTime()
            fun cleanup() {
                deadline.getAndSet(null)?.cancel(false)
                worker.get()?.cancel(true)
                socket.getAndSet(null)?.let { open -> cleanupPool.execute { runCatching { open.disconnect() } } }
            }
            fun fail() {
                if (complete.compareAndSet(false, true)) {
                    continuation.resumeWithException(IOException("Official directory unavailable"))
                    cleanup()
                }
            }
            continuation.invokeOnCancellation { if (complete.compareAndSet(false, true)) cleanup() }
            val timer = deadlinePool.schedule(::fail, timeoutMillis, TimeUnit.MILLISECONDS)
            deadline.set(timer)
            if (complete.get()) timer.cancel(false)
            val task = runCatching { networkPool.submit {
                try {
                    // URLConnection consults the process-wide handler automatically. Refuse
                    // one rather than temporarily replacing it or mixing another session in.
                    if (CookieHandler.getDefault() != null) throw IOException()
                    val jar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
                    val uri = URI(ENDPOINT)
                    fun remaining(): Int {
                        val left = timeoutMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                        if (left <= 0 || complete.get() || Thread.currentThread().isInterrupted) throw IOException()
                        return left.toInt().coerceAtLeast(1)
                    }
                    fun request(form: ByteArray?, token: String?, maximum: Int): ScheduleResponse {
                        remaining()
                        val addresses = resolve(uri.host)
                        if (addresses.isEmpty() || addresses.any { !UrlConnectionScheduleTransport.publicAddress(it) }) throw IOException()
                        remaining()
                        if (CookieHandler.getDefault() != null) throw IOException()
                        val current = connectionFactory(uri.toURL())
                        socket.set(current)
                        try {
                            current.instanceFollowRedirects = false
                            current.connectTimeout = remaining()
                            current.readTimeout = remaining()
                            current.useCaches = false
                            current.requestMethod = if (form == null) "GET" else "POST"
                            current.setRequestProperty("User-Agent", "Feoria/1.0 (+https://github.com/jeymog888/PravoslavnyyProvodnik)")
                            current.setRequestProperty("Accept", "text/html")
                            current.setRequestProperty("Accept-Encoding", "identity")
                            for ((name, values) in jar.get(uri, emptyMap())) {
                                if (!name.equals("Cookie", true) && !name.equals("Cookie2", true)) throw IOException()
                                val value = values.joinToString("; ")
                                if (value.length > 12_288 || value.any(Char::isISOControl)) throw IOException()
                                current.setRequestProperty(name, value)
                            }
                            if (form != null) {
                                current.doOutput = true
                                current.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                                current.setRequestProperty("X-CSRF-TOKEN", requireNotNull(token))
                                current.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                                current.setRequestProperty("Referer", ENDPOINT)
                                current.setFixedLengthStreamingMode(form.size)
                                current.outputStream.use { it.write(form) }
                            }
                            current.readTimeout = remaining()
                            // In particular, no POST redirect and no automatic 419 retry.
                            if (current.responseCode != 200 || current.contentLengthLong > maximum) throw IOException()
                            jar.put(uri, current.headerFields)
                            if (jar.cookieStore.cookies.size > 8 || jar.cookieStore.cookies.any {
                                    it.name.length > 100 || it.value.length > 4_096 || (it.path?.length ?: 0) > 200
                                }) throw IOException()
                            val raw = current.inputStream.use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8_192)
                                while (true) {
                                    current.readTimeout = remaining()
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    if (count > maximum - output.size()) throw IOException()
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                            val bytes = decode(raw, current.getHeaderField("Content-Encoding"), maximum, ::remaining)
                            return ScheduleResponse(ENDPOINT, bytes, current.contentType.orEmpty())
                        } finally {
                            socket.compareAndSet(current, null)
                            runCatching { current.disconnect() }
                        }
                    }
                    val bootstrap = request(null, null, BOOTSTRAP_LIMIT).html() ?: throw IOException()
                    val token = bootstrap.select("meta[name=csrf-token]").singleOrNull()?.attr("content")
                        ?.takeIf { it.length in 16..200 && it.all { c -> c.isLetterOrDigit() || c in "_-" } } ?: throw IOException()
                    if (jar.cookieStore.cookies.isEmpty()) throw IOException()
                    val results = queries.map { query ->
                        val body = "query=${URLEncoder.encode(query, "UTF-8")}&area=all&parent_id=".toByteArray(Charsets.UTF_8)
                        request(body, token, SEARCH_LIMIT)
                    }
                    if (complete.compareAndSet(false, true)) {
                        deadline.getAndSet(null)?.cancel(false)
                        continuation.resume(results)
                    }
                } catch (_: Exception) { fail() }
            } }.getOrElse { fail(); return@suspendCancellableCoroutine }
            worker.set(task)
            if (complete.get()) task.cancel(true)
        }
    }

    private fun decode(bytes: ByteArray, encoding: String?, maximum: Int, active: () -> Int): ByteArray {
        val gzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val input = when {
            gzip || encoding.equals("gzip", true) -> GZIPInputStream(ByteArrayInputStream(bytes))
            encoding.equals("deflate", true) -> InflaterInputStream(ByteArrayInputStream(bytes))
            encoding.isNullOrBlank() || encoding.equals("identity", true) -> return bytes
            else -> throw IOException()
        }
        return input.use { decoded ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                active()
                val count = decoded.read(buffer)
                if (count == -1) break
                if (count > maximum - output.size()) throw IOException()
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    companion object {
        const val ENDPOINT = "https://sprav.moseparh.ru/monasteries"
        internal const val BOOTSTRAP_LIMIT = 131_072
        internal const val SEARCH_LIMIT = 524_288
        private fun daemon(job: Runnable) = Thread(job, "feoria-moscow-directory").apply { isDaemon = true }
        private val networkPool = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2), ::daemon, ThreadPoolExecutor.AbortPolicy())
        private val cleanupPool = Executors.newSingleThreadExecutor(::daemon)
        private val deadlinePool = Executors.newSingleThreadScheduledExecutor(::daemon)
    }
}

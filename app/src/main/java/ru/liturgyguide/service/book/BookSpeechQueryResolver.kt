package ru.liturgyguide.service.book

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.liturgyguide.model.ServiceBook

/** Owned by one search sheet. Calls and publication run on its UI scope; book work runs on Default. */
internal class BookSpeechQueryResolver(
    private val scope: CoroutineScope,
    private val select: (suspend (ServiceBook, List<String>) -> String)? = null
) {
    private var revision = 0L
    private var job: Job? = null

    fun cancel() {
        revision++
        job?.cancel()
        job = null
    }

    fun resolve(book: ServiceBook, hypotheses: List<String>, publish: (String) -> Unit): Job? {
        return resolve(listOf(book), hypotheses, publish)
    }

    fun resolve(books: List<ServiceBook>, hypotheses: List<String>, publish: (String) -> Unit): Job? {
        cancel()
        val candidates = hypotheses.take(5).map { it.trim().take(300) }.filter(String::isNotBlank).distinct()
        if (candidates.size <= 1) {
            publish(candidates.firstOrNull().orEmpty())
            return null
        }
        val current = revision
        return scope.launch {
            val query = withContext(Dispatchers.Default) {
                val work = select
                if (work != null) work(books.first(), candidates) else {
                    val context = currentCoroutineContext()
                    BookSpeechSearch.query(books, candidates) { context.ensureActive() }
                }
            }
            // Explicit ownership also protects against a non-cooperative replacement worker.
            if (revision == current) publish(query)
        }.also { job = it }
    }
}

package ru.liturgyguide.service.book

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.liturgyguide.model.*
import ru.liturgyguide.service.schedule.ScheduleTransport
import ru.liturgyguide.service.schedule.UrlConnectionScheduleTransport
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class BookLoadProgress(val label: String) {
    SOURCE("Загружаем службу"), READINGS("Подбираем чтения дня"),
    RUSSIAN("Добавляем русский текст"), SAVING("Сохраняем на телефоне")
}

enum class BookFailure { INVALID_KEY, NETWORK, INVALID_SOURCE, STORAGE }

sealed interface BookLoadResult {
    data class Ready(val book: ServiceBook, val fromCache: Boolean, val warning: String? = null, val saved: Boolean = true) : BookLoadResult
    data class Failed(val reason: BookFailure, val message: String, val cachedBook: ServiceBook? = null) : BookLoadResult
}

interface ServiceBooks {
    fun cached(key: ServiceBookKey): ServiceBook?
    fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? = cached(key)?.takeIf { it.contentHash == contentHash }
    suspend fun load(key: ServiceBookKey, force: Boolean = false,
        onProgress: (BookLoadProgress) -> Unit = {}): BookLoadResult
    /** Optional saved-edition improvement. It never changes the reader's accepted edition. */
    suspend fun enrichSaved(accepted: ServiceBook, allowNetwork: Boolean = true): BookLoadResult.Ready? = null
}

/** Network books never replace a reading object already accepted by the reader. */
class ServiceBookRepository(
    private val cache: BookCache,
    private val transport: ScheduleTransport = UrlConnectionScheduleTransport(timeoutMillis = 15_000),
    private val translations: BookTranslationIndex = BookTranslationIndex.empty(),
    private val clock: Clock = Clock.systemUTC(),
    private val downloadRussian: Boolean = true,
    private val calendar: CalendarSupplements? = null,
    private val patronalProofs: PatronalProofs = PatronalProofs { null }
) : ServiceBooks {
    private val mutex = Mutex()
    private val parser = ServiceBookParser()
    private val backgroundAttemptAt = mutableMapOf<String, Long>()
    private val backgroundJob = AtomicReference<Job?>(null)
    private val foregroundLoads = AtomicInteger(0)

    override fun cached(key: ServiceBookKey): ServiceBook? = if (key.isValid()) cache.read(key) else null
    override fun cachedVersion(key: ServiceBookKey, contentHash: String): ServiceBook? =
        if (key.isValid()) cache.readVersion(key, contentHash) else null

    override suspend fun enrichSaved(accepted: ServiceBook, allowNetwork: Boolean): BookLoadResult.Ready? = coroutineScope {
        // A private child scope lets a foreground load cancel optional work without cancelling
        // the caller's UI/test parent. The second foreground check closes the claim/lock race.
        val job = currentCoroutineContext()[Job]!!
        if (foregroundLoads.get() > 0 || !backgroundJob.compareAndSet(null, job)) return@coroutineScope null
        try { enrichSavedLocked(accepted, allowNetwork) }
        finally { backgroundJob.compareAndSet(job, null) }
    }

    private suspend fun enrichSavedLocked(accepted: ServiceBook, allowNetwork: Boolean): BookLoadResult.Ready? = try {
        mutex.withLock {
            if (foregroundLoads.get() > 0) return@withLock null
            if (!accepted.key.isValid()) return@withLock null
            val pinned = withContext(Dispatchers.IO) {
                cache.readVersion(accepted.key, accepted.contentHash)?.takeIf {
                    it.key == accepted.key && it.contentHash == accepted.contentHash && ServiceBookIntegrity.valid(it)
                }
            } ?: return@withLock null
            val corpus = withContext(Dispatchers.IO) { BookTranslationCorpus.decode(cache.readTranslationCorpus()) }
            // Offer newly added own translations immediately. Published-only cached changes
            // retain the existing optional-source continuation and its latest-edition check.
            val localPublication = publishEnrichment(pinned, corpus.index.plus(translations))
            val local = localPublication?.result
            if (localPublication?.addedOwnTranslations == true) return@withLock local
            val available = local?.book ?: pinned
            if (!allowNetwork || !downloadRussian) return@withLock local
            val additional = withContext(Dispatchers.Default) {
                if (available.blocks.none { it.kind == BookBlockKind.PRAYER &&
                        (it.russian == null || it.translationSource == BookTranslationIndex.FEORIA_TRANSLATION_SOURCE) }) null
                else BookSharedRussianSources.forBook(available)
            } ?: return@withLock local
            val required = (BookTranslationIndex.requiredSources(available.key.worship) + additional).distinctBy { it.url }
            if (corpus.completeFor(required)) return@withLock local
            val now = clock.millis()
            val completed = if (corpus.current && corpus.index.size > 0) corpus.completedSources else emptySet()
            // Unattempted sources go first: repeated failures must not starve later pages.
            val pending = required.filter { source -> source.url !in completed &&
                backgroundAttemptAt[source.url]?.let { now - it !in 0 until BACKGROUND_RETRY_MILLIS } != false
            }.sortedBy { backgroundAttemptAt[it.url] ?: Long.MIN_VALUE }
            if (pending.isEmpty()) return@withLock local
            val session = RussianLoadSession(refresh = false, corpus = corpus)
            // Only shared published parallel pages. Original, daily readings and calendar
            // remain unchanged and are never requested by this optional background path.
            withTimeoutOrNull(BACKGROUND_BUDGET_MILLIS) {
                loadRussianCorpus(pinned.key.worship, session, {}, sourceTimeoutMillis = 8_000,
                    sources = pending, preserveExistingFor = additional.map { it.url }.toSet(),
                    onAttempt = { backgroundAttemptAt[it.url] = clock.millis() }, persistEachSource = true)
            }
            currentCoroutineContext().ensureActive()
            val refreshed = session.corpus ?: corpus
            // A total deadline may interrupt a later source; keep already verified pairs.
            if (refreshed.index.size > 0) withContext(Dispatchers.IO) {
                safely { cache.writeTranslationCorpus(refreshed.encode()) }
            }
            val updated = publishEnrichment(pinned, refreshed.index.plus(translations))?.result
            // A concurrent publisher may have advanced latest during the network wait.
            // Do not offer the earlier local candidate as a newer edition in that case.
            if (updated != null) updated
            else if (local != null && withContext(Dispatchers.IO) {
                    cache.read(pinned.key)?.contentHash == local.book.contentHash
                }) local else null
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private data class EnrichmentPublication(val result: BookLoadResult.Ready, val addedOwnTranslations: Boolean)

    private suspend fun publishEnrichment(pinned: ServiceBook, index: BookTranslationIndex): EnrichmentPublication? {
        // Retry one competing publication, always rebasing onto latest, never onto old pinned text.
        repeat(2) {
            currentCoroutineContext().ensureActive()
            val latest = withContext(Dispatchers.IO) {
                cache.read(pinned.key).also {
                    // An invalid latest is not a missing latest: never replace it using pinned.
                    if (it != null && (it.key != pinned.key || !ServiceBookIntegrity.valid(it)))
                        throw InvalidServiceBook("Некорректная сохраненная редакция")
                }
            }
            val (candidate, addedOwnTranslations) = withContext(Dispatchers.Default) {
                val before = latest ?: pinned
                val enriched = SavedBookEnrichment.apply(before, index)?.takeIf(ServiceBookIntegrity::valid)
                    ?: return@withContext null
                val previous = before.blocks.associateBy { it.id }
                val addedOwn = enriched.blocks.any { block ->
                    block.russian != null && block.translationSource == BookTranslationIndex.FEORIA_TRANSLATION_SOURCE &&
                        previous[block.id]?.let { it.russian == null } == true
                }
                enriched to addedOwn
            } ?: return null
            val verified = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                cache.writeIfLatest(latest?.contentHash, candidate) &&
                    cache.readVersion(candidate.key, candidate.contentHash) == candidate
            }
            if (verified) return EnrichmentPublication(BookLoadResult.Ready(candidate, fromCache = true,
                warning = BookWarnings.supplementWarning(candidate)), addedOwnTranslations)
        }
        return null
    }

    override suspend fun load(key: ServiceBookKey, force: Boolean, onProgress: (BookLoadProgress) -> Unit): BookLoadResult {
        if (!key.isValid()) return BookLoadResult.Failed(BookFailure.INVALID_KEY, "Не удалось выбрать дату или вид службы")
        foregroundLoads.incrementAndGet()
        backgroundJob.get()?.cancel()
        try { return mutex.withLock {
            if (force) backgroundAttemptAt.clear()
            val previous = withContext(Dispatchers.IO) { cache.read(key)?.takeIf {
                key.patronalId == null || it.key == key && ServiceBookIntegrity.valid(it)
            } }
            if (previous != null && !force) return@withLock BookLoadResult.Ready(previous, fromCache = true, warning = BookWarnings.supplementWarning(previous))
            try {
                val russianSession = RussianLoadSession(force)
                val patronalSelection = resolvePatronalSelection(key, previous)
                if (key.worship == WorshipKind.VIGIL) loadVigil(key, force, russianSession, onProgress, patronalSelection)
                else fetchBook(key, russianSession, onProgress, patronalSelection)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: UnconfirmedPatronalSelection) {
                BookLoadResult.Failed(BookFailure.INVALID_SOURCE,
                    "Источник не подтвердил выбранное посвящение храма. Проверьте выбор или попробуйте еще раз", previous)
            } catch (_: UnsupportedMatinsRank) {
                BookLoadResult.Failed(BookFailure.INVALID_SOURCE,
                    "Источник предлагает другой праздничный порядок на эту дату. Полиелейный вариант пока не подтвержден. Вернитесь к выбору службы", previous)
            } catch (_: InvalidServiceBook) {
                BookLoadResult.Failed(BookFailure.INVALID_SOURCE,
                    "Не удалось получить целую службу на эту дату. Сохраненный текст остался на телефоне", previous)
            } catch (_: Exception) {
                BookLoadResult.Failed(BookFailure.NETWORK,
                    "Не удалось загрузить службу. Проверьте связь и попробуйте еще раз", previous)
            }
        } } finally { foregroundLoads.decrementAndGet() }
    }

    private class RussianLoadSession(
        val refresh: Boolean,
        val attempted: MutableSet<String> = mutableSetOf(),
        val daily: MutableMap<String, Pair<BookTranslationIndex, BookSupplementStatus>> = mutableMapOf(),
        val calendars: MutableMap<String, CalendarSupplement?> = mutableMapOf(),
        val scriptureFallback: BookScriptureFallback.Session = BookScriptureFallback.Session(),
        var corpus: BookTranslationCorpus? = null
    )

    private data class RussianBookTexts(val main: BookTranslationIndex, val status: BookSupplementStatus,
        val patronal: BookTranslationIndex = BookTranslationIndex.empty())

    private suspend fun resolvePatronalSelection(key: ServiceBookKey, previous: ServiceBook?): PatronalEventProof? {
        val id = key.patronalId ?: return null
        return withContext(Dispatchers.IO) {
            val selected = safely { patronalProofs.find(id) }?.takeIf { it.matches(key) }
            selected ?: sequenceOf(previous).plus(if (key.worship == WorshipKind.VIGIL)
                listOf(WorshipKind.VESPERS, WorshipKind.MATINS, WorshipKind.HOUR1).asSequence().map { cache.read(key.copy(worship = it)) }
                else emptySequence()).filterNotNull().firstNotNullOfOrNull { saved ->
                    saved.takeIf { ServiceBookIntegrity.valid(it) && it.key.patronalId == id && it.key.churchType == key.churchType }
                        ?.patronalSelection?.takeIf { it.matches(key) }
                } ?: PatronalSourceSelection.reject()
        }
    }

    private suspend fun fetchBook(key: ServiceBookKey, russianSession: RussianLoadSession, progress: (BookLoadProgress) -> Unit,
        patronalSelection: PatronalEventProof?): BookLoadResult.Ready {
        progress(BookLoadProgress.SOURCE)
        val rankRequest = if (key.topLevel == null) null else {
            val preflight = fetchSourcePage(key.copy(topLevel = null).sourceUrl(), key)
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.Default) { MatinsRankSource.resolve(preflight, key, patronalSelection) }
        }
        currentCoroutineContext().ensureActive()
        val page = fetchSourcePage(rankRequest?.url ?: key.sourceUrl(), key)
        currentCoroutineContext().ensureActive()
        // Validate completeness before extra requests and before touching any cache.
        val plainBook = withContext(Dispatchers.Default) {
            parser.parse(page, key, clock.millis(), translations, rankRequest = rankRequest, patronalSelection = patronalSelection)
        }
        val hasScripture = plainBook.blocks.any { it.kind == BookBlockKind.SCRIPTURE && it.scriptureReferences.isNotEmpty() }
        val extras = coroutineScope {
            val verses = async {
                if (!hasScripture) null else {
                    progress(BookLoadProgress.READINGS)
                    val daily = safely { transport.fetch("https://azbyka.ru/biblia/days/${key.date}").html()?.let(BookScripture::fromPage) }
                        ?: BookScripture.empty()
                    val references = plainBook.blocks.filter { it.kind == BookBlockKind.SCRIPTURE && it.russian == null }
                        .mapNotNull { it.scriptureReferences.singleOrNull() }
                    BookScriptureFallback.load(daily, references, transport, session = russianSession.scriptureFallback)
                }
            }
            val russian = async {
                if (!downloadRussian) RussianBookTexts(BookTranslationIndex.empty(), BookSupplementStatus.NOT_REQUESTED)
                else {
                    val common = loadRussianCorpus(key.worship, russianSession, progress)
                    val daily = loadDailyRussian(key.date, russianSession, progress)
                    val patronal = patronalSelection?.takeIf { it.calendarDate != key.date }
                        ?.let { loadDailyRussian(it.calendarDate, russianSession, progress) }
                    // A shared cache may already include other orders. Keep dated HTML/API
                    // ahead of these optional pages, both on cold and subsequent liturgy loads.
                    val primary = if (key.worship == WorshipKind.LITURGY)
                        common.first.fromSources(BookTranslationIndex.PARALLEL_SOURCES) else common.first
                    RussianBookTexts(primary.plus(daily.first),
                        combineStatus(listOfNotNull(common.second, daily.second, patronal?.second)),
                        patronal?.first ?: BookTranslationIndex.empty())
                }
            }
            // The two primary sources own the bounded transport first. Optional context must
            // not consume a worker or a request deadline needed for the actual service text.
            val scripture = verses.await()
            val russianText = russian.await()
            if (!russianSession.calendars.containsKey(key.date))
                russianSession.calendars[key.date] = safely { calendar?.load(key.date) }
                    ?.takeIf { PublishedCalendarSupplements.valid(it.day, key.date) }
            Triple(scripture, russianText, russianSession.calendars[key.date])
        }
        val parsed = withContext(Dispatchers.Default) {
            // The service day's HTML/API pairs retain their priority. A different
            // dedication date only fills exact missing originals; it is not today's calendar.
            val exactPairs = extras.second.main.plus(extras.third?.translations ?: BookTranslationIndex.empty())
                .plus(extras.second.patronal).plus(translations)
            parser.parse(page, key, plainBook.fetchedAtMillis, exactPairs, extras.first ?: BookScripture.empty(), rankRequest,
                patronalSelection)
        }
        // An unsuccessful refresh of a supplement must not erase an exact, previously
        // verified translation. Original text and Scripture reference must still agree.
        val previousBook = withContext(Dispatchers.IO) { cache.read(key) }
        val previous = previousBook?.blocks?.associateBy { it.id }.orEmpty()
        val chapters = parsed.chapters.map { chapter -> chapter.copy(blocks = chapter.blocks.map { block ->
            val old = previous[block.id]
            val localFallback = block.translationSource == BookTranslationIndex.FEORIA_TRANSLATION_SOURCE
            if ((block.russian == null || localFallback) && old != null && old.kind == block.kind && old.original == block.original && !old.russian.isNullOrBlank() &&
                (block.kind != BookBlockKind.SCRIPTURE || old.scriptureReferences == block.scriptureReferences) &&
                old.translationSource != null && old.translationSource != BookTranslationIndex.EDITORIAL_SOURCE &&
                old.translationSource != BookTranslationIndex.FEORIA_TRANSLATION_SOURCE)
                block.copy(russian = old.russian, translationSource = old.translationSource, meaning = old.meaning ?: block.meaning)
            else block
        }) }
        val scriptureBlocks = chapters.flatMap { it.blocks }.filter { it.kind == BookBlockKind.SCRIPTURE }
        val scriptureStatus = when {
            scriptureBlocks.isEmpty() -> BookSupplementStatus.NOT_NEEDED
            scriptureBlocks.all { !it.russian.isNullOrBlank() } -> BookSupplementStatus.AVAILABLE
            else -> BookSupplementStatus.RETRY_NEEDED
        }
        val day = extras.third?.day ?: previousBook?.calendarDay
        val book = parsed.copy(chapters = chapters, calendarDay = day,
            sources = (parsed.sources + chapters.flatMap { it.blocks }.mapNotNull { it.translationSource } +
                listOfNotNull(day?.let { BookSource("Азбука веры · церковный день", it.sourceUrl) })).distinct(),
            enrichment = BookEnrichment(extras.second.status, scriptureStatus)).let {
            it.copy(contentHash = ServiceBookIntegrity.contentHash(it))
        }
        val withShared = if (key.worship == WorshipKind.LITURGY) withContext(Dispatchers.Default) {
            russianSession.corpus?.index?.let { SavedBookEnrichment.apply(book, it) } ?: book
        } else book
        return save(withShared, progress)
    }

    private suspend fun fetchSourcePage(url: String, key: ServiceBookKey): org.jsoup.nodes.Document {
        val response = transport.fetch(url)
        currentCoroutineContext().ensureActive()
        // A dedication's provenance must name the response actually verified, not
        // a request silently redirected to another source selection or origin.
        if (key.patronalId != null && response.url != url) PatronalSourceSelection.reject()
        return response.html() ?: throw InvalidServiceBook("Источник вернул не страницу службы")
    }

    private suspend fun loadRussianCorpus(worship: WorshipKind, session: RussianLoadSession,
        progress: (BookLoadProgress) -> Unit, sourceTimeoutMillis: Long? = null,
        sources: List<BookSource> = BookTranslationIndex.requiredSources(worship),
        preserveExistingFor: Set<String> = emptySet(), onAttempt: (BookSource) -> Unit = {},
        persistEachSource: Boolean = false): Pair<BookTranslationIndex, BookSupplementStatus> {
        val saved = session.corpus ?: withContext(Dispatchers.IO) {
            BookTranslationCorpus.decode(cache.readTranslationCorpus())
        }.also { session.corpus = it }
        if (saved.completeFor(sources) && !session.refresh) return saved.index to BookSupplementStatus.AVAILABLE
        val required = sources
        var merged = saved.index
        val completed = if (saved.current && saved.index.size > 0) saved.completedSources.toMutableSet() else mutableSetOf()
        // A forced refresh of one order cannot invalidate successful sources for another order.
        if (session.refresh) completed.removeAll(required.map { it.url }.filterNot { it in session.attempted }.toSet())
        session.corpus = BookTranslationCorpus(merged, completed.toSet())
        progress(BookLoadProgress.RUSSIAN)
        // Keep one Russian request in flight alongside Scripture. Map/schedule requests share the
        // bounded transport pool, so launching all three sources concurrently can reject a request.
        for (source in required.filterNot { it.url in completed || it.url in session.attempted }) {
            // Vespers, Matins and the first hour share sources. Attempt each once per user load,
            // including failures, rather than repeating a failed shared request for every part.
            session.attempted += source.url
            onAttempt(source)
            val loaded = safely {
                suspend fun fetch(): BookTranslationIndex? {
                    val response = transport.fetch(source.url)
                    if (source.url in preserveExistingFor && response.url != source.url) return null
                    return withContext(Dispatchers.Default) {
                        response.html()?.let { BookTranslationIndex.fromParallelPage(it, source.copy(url = response.url)) }
                    }
                }
                if (sourceTimeoutMillis == null) fetch() else withTimeoutOrNull(sourceTimeoutMillis) { fetch() }
            } ?: BookTranslationIndex.empty()
            if (loaded.size > 0) {
                merged = if (source.url in preserveExistingFor) merged.fillingMissing(loaded) else loaded.plus(merged)
                completed += source.url
            }
            session.corpus = BookTranslationCorpus(merged, completed.toSet())
            if (persistEachSource && loaded.size > 0) withContext(Dispatchers.IO) {
                safely { cache.writeTranslationCorpus(session.corpus!!.encode()) }
            }
        }
        val corpus = BookTranslationCorpus(merged, completed)
        // Shared sources are attempted once per load. Preserve their successful pairs in this
        // session even if the global corpus file cannot be saved (for example, its size limit).
        session.corpus = corpus
        // Even an unsuccessful forced refresh retains all previously verified exact pairs, while
        // incomplete source metadata makes the next network load retry what could not be refreshed.
        if (merged.size > 0) withContext(Dispatchers.IO) { safely { cache.writeTranslationCorpus(corpus.encode()) } }
        return merged to if (corpus.completeFor(sources)) BookSupplementStatus.AVAILABLE else BookSupplementStatus.RETRY_NEEDED
    }

    private suspend fun loadDailyRussian(date: String, session: RussianLoadSession,
        progress: (BookLoadProgress) -> Unit): Pair<BookTranslationIndex, BookSupplementStatus> {
        session.daily[date]?.let { return it }
        progress(BookLoadProgress.RUSSIAN)
        val index = safely {
            transport.fetch("https://azbyka.ru/days/$date").html()?.let { BookTranslationIndex.fromCalendarPage(it, date) }
        } ?: BookTranslationIndex.empty()
        // A whole vigil has one calendar date. Cache both success and failure for this load only;
        // exact pairs persist inside the completed books, not in an ever-growing global corpus.
        return (index to if (index.size > 0) BookSupplementStatus.AVAILABLE else BookSupplementStatus.RETRY_NEEDED)
            .also { session.daily[date] = it }
    }

    private suspend fun loadVigil(key: ServiceBookKey, force: Boolean, russianSession: RussianLoadSession,
        progress: (BookLoadProgress) -> Unit, patronalSelection: PatronalEventProof?): BookLoadResult.Ready {
        val parts = mutableListOf<ServiceBook>()
        for (kind in listOf(WorshipKind.VESPERS, WorshipKind.MATINS, WorshipKind.HOUR1)) {
            val partKey = key.copy(worship = kind)
            val saved = if (force) null else withContext(Dispatchers.IO) {
                cache.read(partKey)?.takeIf {
                    it.key == partKey && ServiceBookIntegrity.valid(it) && it.patronalSelection == patronalSelection
                }
            }
            parts += saved ?: fetchBook(partKey, russianSession, progress, patronalSelection).book
        }
        val chapters = parts.flatMap { part -> part.chapters.mapIndexed { index, chapter ->
            if (index == 0) chapter.copy(title = "${part.title} · ${chapter.title}") else chapter
        } }
        val preliminary = ServiceBook(key = key, title = "Всенощное бдение", sourceUrl = key.sourceUrl(),
            sourceTitle = "Азбука веры: вечерня, утреня и первый час", fetchedAtMillis = clock.millis(),
            selectedEventTitle = parts.first().selectedEventTitle, chapters = chapters, contentHash = "",
            notices = (listOf("Собраны вечерня, утреня и первый час на одну церковную дату. Переходы и сокращения в вашем храме могут отличаться") +
                parts.flatMap { it.notices }).distinct(), sources = parts.flatMap { it.sources }.distinct(),
            mainEventOptions = parts.first().mainEventOptions, secondEventOptions = parts.first().secondEventOptions,
            enrichment = BookEnrichment(combineStatus(parts.map { it.enrichment.russian }), combineStatus(parts.map { it.enrichment.scripture })),
            calendarDay = parts.firstNotNullOfOrNull { it.calendarDay }, patronalSelection = patronalSelection)
        return save(preliminary.copy(contentHash = ServiceBookIntegrity.contentHash(preliminary)), progress)
    }

    private suspend fun save(book: ServiceBook, progress: (BookLoadProgress) -> Unit): BookLoadResult.Ready {
        progress(BookLoadProgress.SAVING)
        val saved = withContext(Dispatchers.IO) { cache.write(book) && cache.readVersion(book.key, book.contentHash) == book }
        return BookLoadResult.Ready(book, fromCache = false, saved = saved,
            warning = listOfNotNull(BookWarnings.supplementWarning(book), if (saved) null else
                "Книга открыта, но сохранить ее на телефоне не удалось. Для повторного открытия понадобится сеть").joinToString(". ").ifEmpty { null })
    }

    private fun combineStatus(values: List<BookSupplementStatus>): BookSupplementStatus = when {
        BookSupplementStatus.RETRY_NEEDED in values -> BookSupplementStatus.RETRY_NEEDED
        BookSupplementStatus.NOT_REQUESTED in values -> BookSupplementStatus.NOT_REQUESTED
        BookSupplementStatus.AVAILABLE in values -> BookSupplementStatus.AVAILABLE
        else -> BookSupplementStatus.NOT_NEEDED
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try { block() } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }

    companion object {
        private const val BACKGROUND_BUDGET_MILLIS = 20_000L
        private const val BACKGROUND_RETRY_MILLIS = 5 * 60_000L
    }
}

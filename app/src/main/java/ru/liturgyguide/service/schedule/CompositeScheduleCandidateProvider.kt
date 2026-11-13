package ru.liturgyguide.service.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import ru.liturgyguide.model.Parish

/** Partial provider failure is visible, but cannot erase a fresh candidate from its neighbor. */
class CompositeScheduleCandidateProvider(
    private val providers: List<Pair<String, ScheduleCandidateProvider>>,
    private val timeoutMillis: Long = 30_000,
) : ScheduleCandidateProvider {
    init {
        require(providers.size in 1..2 && providers.map { it.first }.distinct().size == providers.size)
        require(providers.all { it.first.matches(Regex("[a-z][a-z0-9_]{1,24}")) })
        require(timeoutMillis in 1..30_000)
    }

    override suspend fun discover(parish: Parish, excludedUrls: Set<String>): ScheduleCandidateDiscovery = coroutineScope {
        val results = providers.map { (_, provider) -> async {
            try { withTimeoutOrNull(timeoutMillis) { provider.discover(parish, excludedUrls) }
                ?: ScheduleCandidateDiscovery(unavailable = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ScheduleCandidateDiscovery(unavailable = true) }
        } }.awaitAll()
        val candidates = mutableListOf<ScheduleSourceCandidate>()
        // Stable round-robin retains both provenances, even if one provider fills its own cap.
        for (index in 0 until 20) for (result in results) {
            result.candidates.getOrNull(index)?.let { candidate ->
                if (candidates.size < 20 && candidates.none { it.id == candidate.id }) candidates += candidate
            }
        }
        val observed = mutableListOf<String>()
        for (index in 0 until 200) for ((providerIndex, result) in results.withIndex()) {
            result.observedIds.getOrNull(index)?.let { id ->
                val prefix = providers[providerIndex].first
                val scoped = "$prefix:$id".takeIf { it.length <= 80 }
                    ?: "$prefix:sha:${ScheduleSourceCandidate.sha(id).take(50)}"
                if (observed.size < 200 && scoped !in observed) observed += scoped
            }
        }
        ScheduleCandidateDiscovery(candidates, observed, results.any { it.incomplete }, results.any { it.unavailable })
    }
}

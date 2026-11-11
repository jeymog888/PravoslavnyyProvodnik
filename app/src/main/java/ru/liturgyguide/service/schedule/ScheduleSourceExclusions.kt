package ru.liturgyguide.service.schedule

import java.net.URI
import java.util.Locale

/** Exclude a previously tried endpoint, not every page of its parish's website. */
internal class ScheduleSourceExclusions(urls: Set<String>) {
    private val endpoints = urls.mapNotNull(::endpoint).toSet()
    fun contains(url: String): Boolean = endpoint(url)?.let(endpoints::contains) ?: true

    companion object {
        private fun endpoint(value: String): String? = ScheduleUrl.publicSource(value)?.let { normalized ->
            val uri = URI(normalized)
            // Public transport already tries the permitted HTTPS / explicit HTTP alternatives.
            // www and trailing slash are aliases; distinct paths and query values remain distinct.
            val host = uri.host.lowercase(Locale.ROOT).removePrefix("www.")
            "$host${uri.rawPath.trimEnd('/')}" + uri.rawQuery?.let { "?$it" }.orEmpty()
        }
    }
}

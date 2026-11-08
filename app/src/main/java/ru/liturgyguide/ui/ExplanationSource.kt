package ru.liturgyguide.ui

import java.net.URI

/** Publication metadata is distinct from the parish selected for a service. */
internal data class ExplanationSource(val title: String, val siteLabel: String?, val url: String)

internal fun explanationSource(sourceUrl: String?, sourceTitle: String?): ExplanationSource? {
    val url = sourceUrl?.takeUnless(String::isBlank) ?: return null
    if (url == "https://ikoni.cerkov.ru/posledovanie-bozhestvennoj-liturgii/kratkij-ustav-sxema-liturgii-svyatitelya-ioanna-zlatousta/") {
        return ExplanationSource(
            "Краткий устав-схема Литургии святителя Иоанна Златоуста",
            "Сайт храма Пророка Илии", url
        )
    }
    val host = runCatching { URI(url).host }.getOrNull()?.takeUnless(String::isBlank)
    return ExplanationSource(sourceTitle?.trim()?.takeUnless(String::isBlank) ?: "Публикация",
        host?.let { "Сайт: $it" }, url)
}

package ru.liturgyguide.model

data class PrayerTrack(
    val id: String,
    val title: String,
    val subtitle: String,
    val lines: List<PlayerLine>,
    val sourceUrl: String?,
    val isExcerpt: Boolean,
    val explanation: String = "",
    val contextNote: String? = null,
    val isScripture: Boolean = false
)

/** A single reading position shared by both language presentations. */
data class PlayerLine(val id: String, val russian: String, val original: String?)

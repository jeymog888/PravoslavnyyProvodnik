package ru.liturgyguide.model

import com.google.gson.annotations.SerializedName

data class LiturgyStage(
    val id: String,
    val order: Int,
    val name: String,
    val description: String,
    @SerializedName(value = "durationMin", alternate = ["duration_min"]) val durationMin: Int,
    val texts: List<StageText>,
    val cue: String? = null,
    val section: String = "Литургия",
    val shortTitle: String = name,
    val meaning: String = description,
    val listenFor: List<String> = emptyList(),
    val whatToDo: String = "",
    val sourceUrl: String? = null,
    val sourceTitle: String? = null
)

data class StageText(
    val type: String,
    val original: String,
    val translation: String,
    val explanation: String,
    val sourceUrl: String? = null,
    val sourceTitle: String? = null,
    val isExcerpt: Boolean = true,
    val lines: List<PrayerLine> = emptyList(),
    val title: String? = null,
    val contextNote: String? = null
)

/** One editorially aligned pair; switching language must retain this position. */
data class PrayerLine(val original: String, val translation: String, val speaker: String? = null)

data class LiturgyService(
    val service: String,
    @SerializedName(value = "totalDurationMin", alternate = ["total_duration_min"]) val totalDurationMin: Int,
    val stages: List<LiturgyStage>,
    @SerializedName("source_url") val sourceUrl: String? = null,
    @SerializedName("content_note") val contentNote: String? = null
)

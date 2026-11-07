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
    val isExcerpt: Boolean = true
)

data class LiturgyService(
    val service: String,
    @SerializedName(value = "totalDurationMin", alternate = ["total_duration_min"]) val totalDurationMin: Int,
    val stages: List<LiturgyStage>,
    @SerializedName("source_url") val sourceUrl: String? = null,
    @SerializedName("content_note") val contentNote: String? = null
)

data class ChurchDay(
    val date: String,
    val dayOfWeek: String,
    val fasting: String? = null,
    val feast: String? = null,
    val typicalService: String? = null,
    val scheduleToday: String? = null
)

data class ServiceProgress(
    val currentStage: LiturgyStage?,
    val stageNumber: Int,
    val totalStages: Int,
    /** Position in the guide, not a prediction of how much of the service has elapsed. */
    val progressPercent: Int,
    val nextStage: LiturgyStage?,
    val previousStage: LiturgyStage?
)

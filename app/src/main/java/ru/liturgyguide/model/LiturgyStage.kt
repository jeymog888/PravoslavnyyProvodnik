package ru.liturgyguide.model

data class LiturgyStage(
    val id: String,
    val order: Int,
    val name: String,
    val description: String,
    val durationMin: Int,
    val texts: List<StageText>
)

data class StageText(
    val type: String,
    val original: String,
    val translation: String,
    val explanation: String
)

data class LiturgyService(
    val service: String,
    val totalDurationMin: Int,
    val stages: List<LiturgyStage>
)

data class Church(
    val id: String = "vsecaritsa",
    val name: String = "Храм иконы Божией Матери \"Всецарица\" (Елисаветинский)",
    val city: String = "г. Красногорск",
    val address: String = "Опалиха, Красногорск",
    val phone: String = "8(915)-346-47-52",
    val website: String = "www.елисаветинский-храм.рф",
    val priest: String = "иер. Павел",
    val timezone: String = "Europe/Moscow"
)

data class ChurchDay(
    val date: String,
    val dayOfWeek: String,
    val fasting: String? = null,
    val feast: String? = null,
    val typicalService: String = "Литургия Иоанна Златоуста",
    val scheduleToday: String? = null
)

data class ServiceProgress(
    val currentStage: LiturgyStage?,
    val stageNumber: Int,
    val totalStages: Int,
    val elapsedMinutes: Int,
    val remainingMinutes: Int,
    val progressPercent: Int,
    val nextStage: LiturgyStage?
)
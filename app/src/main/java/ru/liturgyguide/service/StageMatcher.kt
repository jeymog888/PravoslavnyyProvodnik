package ru.liturgyguide.service

import ru.liturgyguide.model.LiturgyService
import ru.liturgyguide.model.LiturgyStage
import com.google.gson.Gson

class StageMatcher(jsonString: String) {

    private val liturgyService: LiturgyService = Gson().fromJson(jsonString, LiturgyService::class.java)

    fun getAllStages(): List<LiturgyStage> = liturgyService.stages

    fun getStage(name: String): LiturgyStage? =
        liturgyService.stages.find { it.name == name }

    fun getStageByOrder(order: Int): LiturgyStage? =
        liturgyService.stages.find { it.order == order }

    fun getServiceName(): String = liturgyService.service

    fun getTotalDuration(): Int = liturgyService.totalDurationMin

    fun getStageNumber(stage: LiturgyStage): Int =
        liturgyService.stages.indexOf(stage) + 1

    fun getNextStage(currentName: String): LiturgyStage? {
        val currentIndex = liturgyService.stages.indexOfFirst { it.name == currentName }
        if (currentIndex >= 0 && currentIndex < liturgyService.stages.size - 1) {
            return liturgyService.stages[currentIndex + 1]
        }
        return null
    }
}
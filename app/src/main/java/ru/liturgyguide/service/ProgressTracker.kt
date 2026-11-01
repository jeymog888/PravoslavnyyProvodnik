package ru.liturgyguide.service

import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.ServiceProgress
import java.time.LocalTime
import java.time.Duration

class ProgressTracker(private val stageMatcher: StageMatcher) {

    fun getProgress(
        startTime: LocalTime,
        currentStageName: String?
    ): ServiceProgress {
        val allStages = stageMatcher.getAllStages()
        val totalDuration = stageMatcher.getTotalDuration()
        val now = LocalTime.now()

        val elapsed = Duration.between(startTime, now)
        val elapsedMin = elapsed.toMinutes().toInt()

        if (currentStageName == null) {
            var accumulated = 0
            for (stage in allStages) {
                accumulated += stage.durationMin
                if (elapsedMin <= accumulated) {
                    return buildProgress(stage, allStages, elapsedMin, totalDuration)
                }
            }
            return buildProgress(allStages.last(), allStages, elapsedMin, totalDuration)
        }

        val currentStage = stageMatcher.getStage(currentStageName)
        if (currentStage == null) {
            return ServiceProgress(
                currentStage = null,
                stageNumber = 0,
                totalStages = allStages.size,
                elapsedMinutes = elapsedMin,
                remainingMinutes = (totalDuration - elapsedMin).coerceAtLeast(0),
                progressPercent = ((elapsedMin * 100) / totalDuration).coerceAtMost(100),
                nextStage = allStages.firstOrNull()
            )
        }

        return buildProgress(currentStage, allStages, elapsedMin, totalDuration)
    }

    private fun buildProgress(
        stage: LiturgyStage,
        allStages: List<LiturgyStage>,
        elapsedMin: Int,
        totalDuration: Int
    ): ServiceProgress {
        val stageNumber = stageMatcher.getStageNumber(stage)
        val nextStage = stageMatcher.getNextStage(stage.name)
        val remaining = (totalDuration - elapsedMin).coerceAtLeast(0)
        val percent = ((elapsedMin * 100) / totalDuration).coerceAtMost(100)

        return ServiceProgress(
            currentStage = stage,
            stageNumber = stageNumber,
            totalStages = allStages.size,
            elapsedMinutes = elapsedMin,
            remainingMinutes = remaining,
            progressPercent = percent,
            nextStage = nextStage
        )
    }
}
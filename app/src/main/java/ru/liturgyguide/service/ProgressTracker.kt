package ru.liturgyguide.service

import ru.liturgyguide.model.ServiceProgress

/** Tracks the user's selected place in the guide. It does not listen to or time the service. */
class ProgressTracker(private val stageMatcher: StageMatcher) {
    fun getProgress(stageId: String?): ServiceProgress {
        val stages = stageMatcher.getAllStages()
        val currentStage = stageId?.let(stageMatcher::getStageById)
        val stageNumber = currentStage?.let(stageMatcher::getStageNumber) ?: 0

        return ServiceProgress(
            currentStage = currentStage,
            stageNumber = stageNumber,
            totalStages = stages.size,
            progressPercent = if (stages.isEmpty()) 0 else
                (stageNumber.toLong() * 100 / stages.size).toInt().coerceIn(0, 100),
            nextStage = if (currentStage == null) stages.firstOrNull() else
                stageMatcher.getNextStageById(currentStage.id),
            previousStage = currentStage?.let { stageMatcher.getPreviousStage(it.id) }
        )
    }
}

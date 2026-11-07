package ru.liturgyguide.service

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import ru.liturgyguide.model.LiturgyService
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.StageText

class StageMatcher(jsonString: String) {
    private val liturgyService = parseService(jsonString)

    fun getAllStages(): List<LiturgyStage> = liturgyService.stages

    fun getStage(name: String): LiturgyStage? = liturgyService.stages.find { it.name == name }

    fun getStageById(id: String): LiturgyStage? = liturgyService.stages.find { it.id == id }

    fun getStageByOrder(order: Int): LiturgyStage? = liturgyService.stages.find { it.order == order }

    fun getServiceName(): String = liturgyService.service

    /** Illustrative durations from the guide, never a forecast for a real service. */
    fun getTotalDuration(): Int = liturgyService.totalDurationMin

    fun getSourceUrl(): String? = liturgyService.sourceUrl

    fun getContentNote(): String? = liturgyService.contentNote

    fun getStageNumber(stage: LiturgyStage): Int =
        liturgyService.stages.indexOfFirst { it.id == stage.id } + 1

    fun getNextStage(currentName: String): LiturgyStage? {
        val index = liturgyService.stages.indexOfFirst { it.name == currentName }
        return if (index < 0) null else liturgyService.stages.getOrNull(index + 1)
    }

    fun getNextStageById(currentId: String): LiturgyStage? {
        val index = liturgyService.stages.indexOfFirst { it.id == currentId }
        return if (index < 0) null else liturgyService.stages.getOrNull(index + 1)
    }

    fun getPreviousStage(currentId: String): LiturgyStage? {
        val index = liturgyService.stages.indexOfFirst { it.id == currentId }
        return liturgyService.stages.getOrNull(index - 1)
    }

    private fun parseService(json: String): LiturgyService {
        val data = try {
            Gson().fromJson(json, ServiceData::class.java)
        } catch (error: JsonParseException) {
            throw IllegalArgumentException("Не удалось прочитать справочник службы", error)
        }
        requireNotNull(data) { "Справочник службы отсутствует" }
        val serviceName = requiredText(data.service, "Название службы")
        val stageData = requireNotNull(data.stages) { "В справочнике отсутствуют этапы" }
        require(stageData.isNotEmpty()) { "Справочник не содержит этапов" }

        val stages = stageData.mapIndexed { index, nullableStage ->
            val stage = requireNotNull(nullableStage) { "Этап ${index + 1} отсутствует" }
            val id = requiredText(stage.id, "Идентификатор этапа ${index + 1}")
            val order = requireNotNull(stage.order) { "У этапа $id отсутствует порядок" }
            require(order > 0) { "Порядок этапа $id должен быть положительным" }
            val duration = requireNotNull(stage.durationMin) { "У этапа $id отсутствует duration_min" }
            require(duration > 0) { "Длительность этапа $id должна быть положительной" }
            val texts = requireNotNull(stage.texts) { "У этапа $id отсутствует список текстов" }
                .mapIndexed { textIndex, nullableText ->
                    val text = requireNotNull(nullableText) { "Текст ${textIndex + 1} этапа $id отсутствует" }
                    StageText(
                        type = requiredText(text.type, "Тип текста этапа $id"),
                        original = requiredText(text.original, "Исходный текст этапа $id"),
                        translation = requiredText(text.translation, "Пересказ текста этапа $id"),
                        explanation = requiredText(text.explanation, "Пояснение текста этапа $id"),
                        sourceUrl = text.sourceUrl?.takeIf { it.startsWith("https://") },
                        sourceTitle = text.sourceTitle,
                        isExcerpt = text.isExcerpt ?: true
                    )
                }
            LiturgyStage(
                id = id,
                order = order,
                name = requiredText(stage.name, "Название этапа $id"),
                description = requiredText(stage.description, "Описание этапа $id"),
                durationMin = duration,
                texts = texts,
                cue = stage.cue?.trim()?.takeIf { it.isNotEmpty() },
                section = stage.section?.takeIf { it.isNotBlank() } ?: "Литургия",
                shortTitle = stage.shortTitle?.takeIf { it.isNotBlank() } ?: requireNotNull(stage.name),
                meaning = stage.meaning?.takeIf { it.isNotBlank() } ?: requireNotNull(stage.description),
                listenFor = stage.listenFor.orEmpty().filterNotNull().filter { it.isNotBlank() },
                whatToDo = stage.whatToDo.orEmpty(),
                sourceUrl = stage.sourceUrl?.takeIf { it.startsWith("https://") },
                sourceTitle = stage.sourceTitle
            )
        }.sortedBy { it.order }

        require(stages.distinctBy { it.id }.size == stages.size) { "Идентификаторы этапов повторяются" }
        require(stages.distinctBy { it.order }.size == stages.size) { "Порядок этапов повторяется" }
        val totalDuration = stages.sumOf { it.durationMin.toLong() }
        require(totalDuration <= Int.MAX_VALUE) { "Суммарная длительность этапов слишком велика" }

        // Ordered stages are the source of truth; a stale declared total cannot corrupt progress.
        return LiturgyService(
            service = serviceName,
            totalDurationMin = totalDuration.toInt(),
            stages = stages,
            sourceUrl = data.sourceUrl?.trim()?.takeIf { it.isNotEmpty() },
            contentNote = data.contentNote?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun requiredText(value: String?, field: String): String {
        require(!value.isNullOrBlank()) { "$field отсутствует" }
        return value.trim()
    }

    // Gson bypasses Kotlin constructors and nullability. Decode to nullable fields, then validate.
    private data class ServiceData(
        val service: String? = null,
        val stages: List<StageData?>? = null,
        @SerializedName("source_url") val sourceUrl: String? = null,
        @SerializedName("content_note") val contentNote: String? = null
    )

    private data class StageData(
        val id: String? = null,
        val order: Int? = null,
        val name: String? = null,
        val description: String? = null,
        @SerializedName(value = "durationMin", alternate = ["duration_min"]) val durationMin: Int? = null,
        val texts: List<TextData?>? = null,
        val cue: String? = null,
        val section: String? = null,
        val shortTitle: String? = null,
        val meaning: String? = null,
        val listenFor: List<String?>? = null,
        val whatToDo: String? = null,
        val sourceUrl: String? = null,
        val sourceTitle: String? = null
    )

    private data class TextData(
        val type: String? = null,
        val original: String? = null,
        val translation: String? = null,
        val explanation: String? = null,
        val sourceUrl: String? = null,
        val sourceTitle: String? = null,
        val isExcerpt: Boolean? = null
    )
}

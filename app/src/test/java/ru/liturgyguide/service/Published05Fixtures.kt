package ru.liturgyguide.service

import com.google.gson.Gson
import ru.liturgyguide.model.DailyReadings
import ru.liturgyguide.model.LiturgyStage
import ru.liturgyguide.model.ReadingDay
import ru.liturgyguide.model.StageText
import java.time.LocalDate

/** Expected plans come from the independently recorded release producer, never this builder. */
internal object Published05Fixtures {
    val dates = listOf(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 9))
    const val STAGES = "published_0_5/liturgy_stages.json"
    const val READINGS = "published_0_5/daily_readings.json"
    const val GOLDEN = "published_0_5/published-plans.json"

    fun bytes(name: String): ByteArray = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
        "Missing published fixture: $name"
    }.use { it.readBytes() }

    fun asset(name: String): String = bytes(name).toString(Charsets.UTF_8)
    fun oldPlan(date: LocalDate): ServicePlaybackPlan = requireNotNull(Historical05Content(::asset).loadPlan(date))
    fun stages(): List<LiturgyStage> = StageMatcher(asset(STAGES)).getAllStages()
    fun reading(date: LocalDate): ReadingDay? = Gson().fromJson(asset(READINGS), DailyReadings::class.java)
        .days.singleOrNull { it.date == date.toString() }

    fun extraText(id: String, original: String = "Дополненная соседняя фраза $id") =
        StageText("Тестовое дополнение $id", original, "Перевод дополнения $id", "Пояснение дополнения")

    /** Distinct neighbors before, between and after old texts preserve each whole old track. */
    fun expandedPlan(date: LocalDate): ServicePlaybackPlan = ServicePlaybackPlan.build(
        stages().map { stage -> stage.copy(texts = buildList {
            add(extraText("${stage.id}-before"))
            stage.texts.forEachIndexed { index, oldText ->
                add(oldText)
                add(extraText("${stage.id}-after-$index"))
            }
        }) }, date, reading(date)
    )

    /** Every old stage sequence stays intact while all its global indexes move. */
    fun prefixedPlan(date: LocalDate): ServicePlaybackPlan {
        val prefix = LiturgyStage("test_prefix", 1, "Проверочный этап", "Описание", 1, listOf(extraText("prefix")))
        return ServicePlaybackPlan.build(listOf(prefix) + stages().map { it.copy(order = it.order + 1) }, date, reading(date))
    }

    fun snapshot(plan: ServicePlaybackPlan, index: Int, offset: Long = 0, speed: PlaybackSpeed = PlaybackSpeed.NORMAL): ServicePlaybackSnapshot =
        ServicePlaybackSnapshot(plan.contentVersion, plan.fingerprint, index, offset, speed)
}

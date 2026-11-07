package ru.liturgyguide.di

import android.content.Context
import ru.liturgyguide.service.CalendarEngine
import ru.liturgyguide.service.ProgressTracker
import ru.liturgyguide.service.StageMatcher
import ru.liturgyguide.service.ParishRepository
import com.google.gson.Gson
import ru.liturgyguide.model.DailyReadings

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val calendar by lazy { CalendarEngine() }
    private var stageMatcherResult: Result<StageMatcher>? = null
    val parishes: Result<ParishRepository> by lazy {
        runCatching {
            ParishRepository(appContext.assets.open("churches.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
    }
    val readings: DailyReadings? by lazy {
        runCatching {
            appContext.assets.open("daily_readings.json").bufferedReader(Charsets.UTF_8).use {
                Gson().fromJson(it, DailyReadings::class.java)
            }
        }.getOrNull()
    }

    fun getCalendarEngine(): CalendarEngine = calendar

    fun getStageMatcher(): StageMatcher = getStageMatcherResult().getOrThrow()

    /** Cache failures as well as success; the UI can explicitly retry after showing an error. */
    @Synchronized
    fun getStageMatcherResult(forceReload: Boolean = false): Result<StageMatcher> {
        if (forceReload || stageMatcherResult == null) {
            stageMatcherResult = runCatching {
                val json = appContext.assets.open("liturgy_stages.json")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                StageMatcher(json)
            }
        }
        return requireNotNull(stageMatcherResult)
    }

    fun getProgressTracker(): ProgressTracker = ProgressTracker(getStageMatcher())
}

package ru.liturgyguide.di

import android.content.Context
import ru.liturgyguide.service.CalendarEngine
import ru.liturgyguide.service.StageMatcher
import ru.liturgyguide.service.ProgressTracker

class AppContainer(private val context: Context) {

    private var _calendarEngine: CalendarEngine? = null
    private var _stageMatcher: StageMatcher? = null
    private var _progressTracker: ProgressTracker? = null

    fun getCalendarEngine(): CalendarEngine {
        if (_calendarEngine == null) {
            _calendarEngine = CalendarEngine()
        }
        return _calendarEngine!!
    }

    fun getStageMatcher(): StageMatcher {
        if (_stageMatcher == null) {
            val jsonPath = context.assets.open("liturgy_stages.json").bufferedReader().use { it.readText() }
            _stageMatcher = StageMatcher(jsonPath)
        }
        return _stageMatcher!!
    }

    fun getProgressTracker(): ProgressTracker {
        if (_progressTracker == null) {
            _progressTracker = ProgressTracker(getStageMatcher())
        }
        return _progressTracker!!
    }
}
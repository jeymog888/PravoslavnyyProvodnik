package ru.liturgyguide.di

import android.content.Context
import ru.liturgyguide.service.StageMatcher
import ru.liturgyguide.service.ParishRepository
import ru.liturgyguide.service.Historical05Content
import com.google.gson.Gson
import ru.liturgyguide.model.DailyReadings
import ru.liturgyguide.service.discovery.DefaultPlaceDiscovery
import ru.liturgyguide.service.discovery.DiscoveryCacheStore
import ru.liturgyguide.service.discovery.PlaceDiscovery

class AppContainer(context: Context, private val discoveryOverride: PlaceDiscovery? = null,
    assetReader: ((String) -> String)? = null) {
    private val appContext = context.applicationContext
    private val readAsset: (String) -> String = assetReader ?: { name ->
        appContext.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    val historical05Content = Historical05Content(readAsset)
    val placeDiscovery: PlaceDiscovery by lazy {
        discoveryOverride ?: DefaultPlaceDiscovery(cache = object : DiscoveryCacheStore {
            private val cache = appContext.getSharedPreferences("place_discovery_cache", Context.MODE_PRIVATE)
            override fun read(slot: String): String? = if (slot in setOf("cities", "churches", "places"))
                runCatching { cache.getString(slot, null) }.getOrNull() else null
            override fun write(slot: String, value: String) {
                if (slot in setOf("cities", "churches", "places")) cache.edit().putString(slot, value).apply()
            }
        })
    }
    private var stageMatcherResult: Result<StageMatcher>? = null
    val parishes: Result<ParishRepository> by lazy {
        runCatching {
            ParishRepository(appContext.assets.open("churches.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
    }
    val readings: DailyReadings? by lazy {
        runCatching {
            Gson().fromJson(readAsset("daily_readings.json"), DailyReadings::class.java)
        }.getOrNull()
    }

    fun getStageMatcher(): StageMatcher = getStageMatcherResult().getOrThrow()

    /** Cache failures as well as success; the UI can explicitly retry after showing an error. */
    @Synchronized
    fun getStageMatcherResult(forceReload: Boolean = false): Result<StageMatcher> {
        if (forceReload || stageMatcherResult == null) {
            stageMatcherResult = runCatching {
                StageMatcher(readAsset("liturgy_stages.json"))
            }
        }
        return requireNotNull(stageMatcherResult)
    }
}

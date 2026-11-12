package ru.liturgyguide.di

import android.content.Context
import ru.liturgyguide.service.StageMatcher
import ru.liturgyguide.service.ParishRepository
import ru.liturgyguide.service.Historical05Content
import ru.liturgyguide.service.Historical06Content
import com.google.gson.Gson
import ru.liturgyguide.model.DailyReadings
import ru.liturgyguide.service.discovery.DefaultPlaceDiscovery
import ru.liturgyguide.service.discovery.DiscoveryCacheStore
import ru.liturgyguide.service.discovery.PlaceDiscovery
import ru.liturgyguide.service.discovery.BundledChurchCatalog
import ru.liturgyguide.service.discovery.BundledPlaceGazetteer
import ru.liturgyguide.service.discovery.CatalogPlaceDiscovery
import ru.liturgyguide.service.schedule.InternetSchedules
import ru.liturgyguide.service.schedule.InternetScheduleRepository
import ru.liturgyguide.service.schedule.ScheduleCacheStore
import ru.liturgyguide.service.schedule.UrlConnectionScheduleTransport
import ru.liturgyguide.service.schedule.AndroidScheduleDocumentExtractor
import ru.liturgyguide.service.schedule.FileOfficialDirectoryCacheStore
import ru.liturgyguide.service.book.FileBookCache
import ru.liturgyguide.service.book.ServiceBookRepository
import ru.liturgyguide.service.book.ServiceBooks
import ru.liturgyguide.service.book.PublishedCalendarSupplements
import java.io.File

class AppContainer(context: Context, private val discoveryOverride: PlaceDiscovery? = null,
    assetReader: ((String) -> String)? = null, scheduleOverride: InternetSchedules? = null,
    booksOverride: ServiceBooks? = null) {
    private val appContext = context.applicationContext
    private val readAsset: (String) -> String = assetReader ?: { name ->
        appContext.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    val historical05Content = Historical05Content(readAsset)
    val historical06Content = Historical06Content(readAsset)
    val internetSchedules: InternetSchedules by lazy {
        scheduleOverride ?: InternetScheduleRepository(UrlConnectionScheduleTransport(timeoutMillis = 15_000,
            limitBytes = AndroidScheduleDocumentExtractor.MAX_DOCUMENT_BYTES, allowExplicitHttp = true), object : ScheduleCacheStore {
            private val preferences = appContext.getSharedPreferences("internet_schedule_cache", Context.MODE_PRIVATE)
            override fun read(parishId: String): String? = runCatching { preferences.getString(parishId, null) }.getOrNull()
            override fun write(parishId: String, value: String) {
                if (value.length > 200_000) return
                val edit = preferences.edit().putString(parishId, value)
                if (!preferences.contains(parishId) && preferences.all.size >= 100) preferences.all.keys.firstOrNull()?.let(edit::remove)
                edit.apply()
            }
        }, AndroidScheduleDocumentExtractor(appContext),
            directoryCache = FileOfficialDirectoryCacheStore(File(appContext.cacheDir, "official-parish-directories")))
    }
    val serviceBooks: ServiceBooks by lazy {
        // The complete book uses published, exactly matched translations. Editorial paraphrases
        // from the older short guide are explanations, not a substitute for a sourced prayer.
        booksOverride ?: ServiceBookRepository(FileBookCache(File(appContext.filesDir, "service-books-v2")),
            calendar = PublishedCalendarSupplements())
    }
    val placeDiscovery: PlaceDiscovery by lazy {
        discoveryOverride ?: CatalogPlaceDiscovery(BundledChurchCatalog(open = {
            assetReader?.invoke(BundledChurchCatalog.ASSET_PATH)?.reader()
                ?: appContext.assets.open(BundledChurchCatalog.ASSET_PATH).bufferedReader(Charsets.UTF_8)
        }), DefaultPlaceDiscovery(cache = object : DiscoveryCacheStore {
            private val cache = appContext.getSharedPreferences("place_discovery_cache", Context.MODE_PRIVATE)
            override fun read(slot: String): String? = if (slot in setOf("cities", "churches", "places", "landmarks"))
                runCatching { cache.getString(slot, null) }.getOrNull() else null
            override fun write(slot: String, value: String) {
                if (slot in setOf("cities", "churches", "places", "landmarks")) cache.edit().putString(slot, value).apply()
            }
        }), places = BundledPlaceGazetteer(open = {
            assetReader?.invoke(BundledPlaceGazetteer.ASSET_PATH)?.reader()
                ?: appContext.assets.open(BundledPlaceGazetteer.ASSET_PATH).bufferedReader(Charsets.UTF_8)
        }))
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

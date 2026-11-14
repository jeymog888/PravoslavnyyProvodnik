package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.discovery.BundledChurchCatalog
import ru.liturgyguide.service.discovery.GeoPoint
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI

/** Suspended preparation only. The returned accepted seed pool performs no IO. */
interface OwnContactPoolFactories {
    suspend fun direct(parish: Parish): PublisherSeedPool
    suspend fun websites(parish: Parish): PublisherSeedPool
    suspend fun curatedOwners(): List<Parish>
}

/** Coordinate references remain metadata, outside the selected Parish and its fingerprint. */
internal data class CuratedPublisherMetadata(val parish: Parish, val coordinateSourceUrl: String?)
internal data class CuratedPublisherSnapshot(val records: List<CuratedPublisherMetadata>, val sourceSha256: String)

/** The small packaged directory is read once on IO; cancellation never commits a loaded snapshot. */
class BundledCuratedPublisherMetadata(private val open: () -> InputStream) {
    private val lock = Mutex()
    private var loaded = false
    private var snapshot: CuratedPublisherSnapshot? = null

    internal suspend fun get(): CuratedPublisherSnapshot? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!loaded) {
                val context = currentCoroutineContext()
                val parsed = try {
                    val bytes = open().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8_192)
                        while (true) {
                            context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(count <= MAX_BYTES - output.size())
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    context.ensureActive()
                    decode(bytes) { context.ensureActive() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                context.ensureActive()
                snapshot = parsed
                loaded = true
            }
            snapshot
        }
    }

    companion object {
        const val ASSET_PATH = "churches.json"
        internal const val MAX_BYTES = 1_000_000
        private val gson = Gson()

        private fun decode(bytes: ByteArray, checkCancelled: () -> Unit): CuratedPublisherSnapshot {
            require(bytes.isNotEmpty())
            val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            val rows = requireNotNull(root.getAsJsonArray("churches"))
            require(rows.size() in 1..100)
            val ids = mutableSetOf<String>()
            val records = rows.map { raw ->
                checkCancelled()
                val row = raw.asJsonObject
                val parish = gson.fromJson(row, Parish::class.java)
                require(row.string("id", 160).isNotBlank() && ids.add(parish.id))
                require(row.string("name", 500).isNotBlank())
                for (field in listOf("city", "address", "phone", "website", "timezone", "sourceUrl")) row.string(field, 2_048)
                val latitude = row.optionalNumber("latitude")
                val longitude = row.optionalNumber("longitude")
                require((latitude == null) == (longitude == null))
                if (latitude != null && longitude != null) require(GeoPoint(latitude, longitude).isValid)
                require(row.get("schedule")?.isJsonArray == true)
                val reference = row.get("coordinateSourceUrl")?.takeUnless { it.isJsonNull }?.let {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                    val url = it.asString
                    require(ScheduleUrl.publicSource(url) == url)
                    url
                }
                CuratedPublisherMetadata(parish, reference)
            }
            return CuratedPublisherSnapshot(records.toList(), ScheduleSourceCandidate.sha(bytes))
        }

        private fun JsonObject.string(key: String, maximum: Int): String {
            val value = requireNotNull(get(key))
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
            return value.asString.also { require(it.length <= maximum && it.none(Char::isISOControl)) }
        }
        private fun JsonObject.optionalNumber(key: String): Double? {
            val value = get(key)?.takeUnless { it.isJsonNull } ?: return null
            require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            return value.asDouble.also { require(it.isFinite()) }
        }
    }
}

class BundledPublisherPoolFactories(private val curated: BundledCuratedPublisherMetadata,
    private val catalog: BundledChurchCatalog) : OwnContactPoolFactories {
    override suspend fun direct(parish: Parish): PublisherSeedPool = withContext(Dispatchers.IO) {
        val center = pointOf(parish) ?: return@withContext LocalPublisherSeedPool(emptyList())
        val snapshot = curated.get() ?: return@withContext LocalPublisherSeedPool(emptyList())
        val publishers = snapshot.records.mapNotNull { record ->
            if (pointOf(record.parish) == null) null else ScheduleUrl.publicSource(record.parish.website)?.takeIf(PublisherContactScope::valid)
        }
        val seeds = snapshot.records.mapNotNull { record ->
            currentCoroutineContext().ensureActive()
            val reference = record.coordinateSourceUrl ?: return@mapNotNull null
            val point = pointOf(record.parish) ?: return@mapNotNull null
            if (center.distanceMeters(point) > RADIUS) return@mapNotNull null
            val owners = publishers.filter { CuratedPublisherScope.owns(it, reference) }
            // No unique known publisher: permit only this exact endpoint, without guessing a host root.
            val scope = owners.singleOrNull() ?: reference
            PublisherSeed(reference, PublisherSeedRole.CONTACT_REFERENCE, scope, provenance(record, snapshot.sourceSha256))
                .takeIf { it.valid() }
        }
        LocalPublisherSeedPool(seeds)
    }

    override suspend fun websites(parish: Parish): PublisherSeedPool = withContext(Dispatchers.IO) {
        val center = pointOf(parish) ?: return@withContext LocalPublisherSeedPool(emptyList())
        val snapshot = curated.get()
        val local = snapshot?.records.orEmpty().mapNotNull { record ->
            currentCoroutineContext().ensureActive()
            val point = pointOf(record.parish) ?: return@mapNotNull null
            if (center.distanceMeters(point) > RADIUS) return@mapNotNull null
            val website = ScheduleUrl.publicSource(record.parish.website)?.takeIf(PublisherContactScope::valid)
                ?: return@mapNotNull null
            PublisherSeed(website, PublisherSeedRole.WEBSITE, website, provenance(record, requireNotNull(snapshot).sourceSha256))
                .takeIf { it.valid() }
        }
        val osm = catalog.publisherSeeds(center, RADIUS, 60)
        // The accepted pool ranks all curated rows before OSM distance; do not take nearest two here.
        LocalPublisherSeedPool(local + osm)
    }

    override suspend fun curatedOwners(): List<Parish> = withContext(Dispatchers.IO) {
        curated.get()?.records?.map { it.parish }.orEmpty()
    }

    private fun provenance(record: CuratedPublisherMetadata, sha: String) = PublisherSeedProvenance(
        PublisherSeedOrigin.CURATED, record.parish.id, record.parish.name,
        requireNotNull(record.parish.latitude), requireNotNull(record.parish.longitude),
        "asset:${BundledCuratedPublisherMetadata.ASSET_PATH}", sha)
    private fun pointOf(parish: Parish) = parish.latitude?.let { lat -> parish.longitude?.let { lon -> GeoPoint(lat, lon) } }?.takeIf { it.isValid }
    companion object { private const val RADIUS = 10_000 }
}

/** Match a known publisher website; no host guessing, scheme upgrade or encoded tenant bypass. */
internal object CuratedPublisherScope {
    fun owns(publisher: String, target: String): Boolean = runCatching {
        val own = URI(requireNotNull(ScheduleUrl.publicSource(publisher)))
        val actual = URI(requireNotNull(ScheduleUrl.publicSource(target)))
        if (own.rawQuery != null || own.scheme != actual.scheme || own.host != actual.host || own.port != actual.port) return false
        fun path(uri: URI): String? = uri.rawPath.takeIf {
            '%' !in it && '\\' !in it && uri.normalize().rawPath == it
        }?.trimEnd('/')
        val scope = path(own) ?: return false
        val location = path(actual) ?: return false
        scope.isEmpty() || location == scope || location.startsWith("$scope/")
    }.getOrDefault(false)
}

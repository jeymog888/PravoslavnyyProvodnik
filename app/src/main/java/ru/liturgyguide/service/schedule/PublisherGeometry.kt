package ru.liturgyguide.service.schedule

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import ru.liturgyguide.model.Parish
import java.math.BigDecimal
import java.math.RoundingMode
import java.io.StringReader
import java.security.MessageDigest
import kotlin.math.*

data class PublisherPoint(val latitude: Double, val longitude: Double) {
    fun valid() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}
data class PublisherContour(val id: String, val version: Long, val name: String, val points: List<PublisherPoint>)
data class PublisherNeighbour(val type: String, val id: String, val points: List<PublisherPoint>)
data class PublisherArea(val west: Double, val south: Double, val east: Double, val north: Double) {
    fun contains(point: PublisherPoint) = point.valid() && point.longitude in west..east && point.latitude in south..north
    fun covers(other: PublisherArea) = west <= other.west && south <= other.south && east >= other.east && north >= other.north
    fun query() = "$west,$south,$east,$north"
}

/** Geometry identifies the selected map object; a nearby point alone never does. */
internal object PublisherGeometry {
    fun osmId(parish: Parish) = Regex("osm:way:([1-9][0-9]{0,18})").matchEntire(parish.id)?.groupValues?.get(1)
    fun fullUrl(parish: Parish) = osmId(parish)?.let { "https://api.openstreetmap.org/api/0.6/way/$it/full.json" }
    fun digest(value: Any): String = sha(Gson().toJson(value).toByteArray())
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun contour(response: ScheduleResponse, parish: Parish): PublisherContour? = runCatching {
        val id = requireNotNull(osmId(parish))
        require(response.url == fullUrl(parish) && response.bytes.size in 1..768_000)
        val root = JsonParser.parseString(response.bytes.toString(Charsets.UTF_8)).asJsonObject
        require(!root.has("error") && root.get("version").asString == "0.6")
        val elements = root.getAsJsonArray("elements")
        require(elements.size() in 5..600)
        val ways = elements.filter { it.asJsonObject.get("type")?.asString == "way" }
        val way = ways.single().asJsonObject
        require(way.get("id").asString == id)
        val tags = way.getAsJsonObject("tags")
        require(tags.get("amenity")?.asString == "place_of_worship" && tags.get("building")?.asString == "church")
        require(tags.get("religion")?.asString == "christian" && tags.get("denomination")?.asString == "orthodox")
        val name = tags.get("name").asString
        require(ScheduleEntityIdentity.normalize(name) == ScheduleEntityIdentity.normalize(parish.name))
        require(ScheduleEntityIdentity.scope(name) == ScheduleEntityScope.CHURCH)
        val nodes = linkedMapOf<String, PublisherPoint>()
        for (element in elements) {
            val node = element.asJsonObject
            if (node.get("type")?.asString != "node") continue
            require(nodes.put(node.get("id").asString, PublisherPoint(node.get("lat").asDouble, node.get("lon").asDouble)) == null)
        }
        val references = way.getAsJsonArray("nodes").map { it.asString }
        require(references.size in 4..500 && references.first() == references.last())
        require(references.dropLast(1).distinct().size == references.size - 1)
        val points = references.map { requireNotNull(nodes[it]) }
        val result = PublisherContour(id, way.get("version").asLong, name, points)
        require(valid(result))
        val selected = PublisherPoint(requireNotNull(parish.latitude), requireNotNull(parish.longitude))
        require(selected.valid() && distance(selected, points.first()) <= 150)
        result
    }.getOrNull()

    fun valid(contour: PublisherContour): Boolean = runCatching {
        require(Regex("[1-9][0-9]{0,18}").matches(contour.id) && contour.version > 0)
        val points = contour.points
        require(points.size in 4..500 && points.first() == points.last() && points.all(PublisherPoint::valid))
        require(points.dropLast(1).distinct().size == points.size - 1)
        val xy = projected(points)
        val area = abs(xy.zipWithNext().sumOf { (a, b) -> a.first * b.second - b.first * a.second }) / 2
        require(area in 1.0..40_000.0)
        require(points.all { distance(points.first(), it) <= 300 })
        for (i in 0 until xy.size - 1) for (j in i + 2 until xy.size - 1) {
            if (i == 0 && j == xy.size - 2) continue
            require(!intersects(xy[i], xy[i + 1], xy[j], xy[j + 1]))
        }
        true
    }.getOrDefault(false)

    /** 1e-6m is numerical equality, not an allowance for a map point outside the building. */
    fun strictlyInside(point: PublisherPoint, polygon: List<PublisherPoint>): Boolean {
        if (!point.valid() || polygon.size < 4 || polygon.first() != polygon.last()) return false
        val origin = polygon.first()
        val sx = 111_320 * cos(Math.toRadians(origin.latitude))
        fun xy(p: PublisherPoint) = (p.longitude - origin.longitude) * sx to (p.latitude - origin.latitude) * 111_320
        val p = xy(point); val values = polygon.map(::xy)
        for ((a, b) in values.zipWithNext()) if (edgeDistance(p, a, b) <= .000001) return false
        var inside = false
        for ((a, b) in values.zipWithNext()) if ((a.second > p.second) != (b.second > p.second) &&
            p.first < (b.first - a.first) * (p.second - a.second) / (b.second - a.second) + a.first) inside = !inside
        return inside
    }

    fun neighbourhoodArea(contour: PublisherContour): PublisherArea {
        val latitude = contour.points.map { it.latitude }.average()
        val dy = 150.0 / 111_320; val dx = 150.0 / (111_320 * cos(Math.toRadians(latitude)))
        // OSM reports bounds at seven decimal places. Canonicalize the requested
        // and expected area outwards at that precision instead of tolerating a
        // response which fails to cover the area that was actually requested.
        fun rounded(value: Double, mode: RoundingMode) = BigDecimal.valueOf(value).setScale(7, mode).toDouble()
        return PublisherArea(rounded(contour.points.minOf { it.longitude } - dx, RoundingMode.FLOOR),
            rounded(contour.points.minOf { it.latitude } - dy, RoundingMode.FLOOR),
            rounded(contour.points.maxOf { it.longitude } + dx, RoundingMode.CEILING),
            rounded(contour.points.maxOf { it.latitude } + dy, RoundingMode.CEILING))
    }
    fun neighbourhoodUrl(contour: PublisherContour) = "https://api.openstreetmap.org/api/0.6/map?bbox=${neighbourhoodArea(contour).query()}"

    fun neighbours(response: ScheduleResponse, contour: PublisherContour): List<PublisherNeighbour>? = runCatching {
        require(response.url == neighbourhoodUrl(contour) && response.bytes.size in 1..768_000)
        val text = response.bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
        // The production Accept header negotiates JSON for /map. It has the same
        // bounds and complete elements as XML; both formats use one final validator.
        if (text.startsWith('{')) return@runCatching checkedNeighbours(jsonNeighbours(text, contour), contour)
        require(!Regex("(?i)<!DOCTYPE|<!ENTITY").containsMatchIn(text) && Regex("(?is)</osm>\\s*$").containsMatchIn(text))
        val root = Jsoup.parse(text, response.url, Parser.xmlParser()).select("osm").single()
        require(root.attr("version") == "0.6")
        val bounds = root.select("bounds").single()
        require(PublisherArea(bounds.attr("minlon").toDouble(), bounds.attr("minlat").toDouble(),
            bounds.attr("maxlon").toDouble(), bounds.attr("maxlat").toDouble()).covers(neighbourhoodArea(contour)))
        val nodes = root.children().filter { it.tagName() == "node" }.associate { it.attr("id") to
            PublisherPoint(it.attr("lat").toDouble(), it.attr("lon").toDouble()) }
        require(nodes.size == root.children().count { it.tagName() == "node" } && nodes.values.all(PublisherPoint::valid))
        val result = mutableListOf<PublisherNeighbour>()
        for (element in root.children()) {
            if (element.tagName() !in setOf("way", "node", "relation")) continue
            val tags = element.children().filter { it.tagName() == "tag" }.associate { it.attr("k") to it.attr("v") }
            val religious = tags["amenity"] == "place_of_worship" || tags["building"] in setOf("church", "chapel", "cathedral", "monastery")
            if (!religious) continue
            require(element.tagName() != "relation") // No guessed multipolygon or incomplete religious relation.
            val id = element.attr("id")
            val points = if (element.tagName() == "node") listOf(requireNotNull(nodes[id])) else
                element.children().filter { it.tagName() == "nd" }.map { requireNotNull(nodes[it.attr("ref")]) }
            if (element.tagName() == "way") require(valid(PublisherContour(id, element.attr("version").toLong(), "", points)))
            result += PublisherNeighbour(element.tagName(), id, points)
        }
        checkedNeighbours(result, contour)
    }.getOrNull()

    private fun checkedNeighbours(values: List<PublisherNeighbour>, contour: PublisherContour): List<PublisherNeighbour> {
        require(values.size in 1..30 && values.map { it.type to it.id }.distinct().size == values.size)
        require(values.single { it.type == "way" && it.id == contour.id }.points == contour.points)
        return values
    }

    private fun jsonNeighbours(text: String, contour: PublisherContour): List<PublisherNeighbour> {
        val root = strictMapJson(text).asJsonObject
        require(!root.has("error") && root.get("version").asString == "0.6")
        fun number(value: JsonElement?): Double {
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            return value.asDouble.also { require(it.isFinite()) }
        }
        fun integer(value: JsonElement?): String {
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            val raw = value.asString
            require(Regex("[1-9][0-9]{0,18}").matches(raw) && raw.toLongOrNull()?.let { it > 0 } == true)
            return raw
        }
        fun string(value: JsonElement?): String {
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
            return value.asString
        }
        val bounds = root.getAsJsonObject("bounds")
        require(PublisherArea(number(bounds.get("minlon")), number(bounds.get("minlat")),
            number(bounds.get("maxlon")), number(bounds.get("maxlat"))).covers(neighbourhoodArea(contour)))
        val elements = root.getAsJsonArray("elements")
        require(elements.size() in 1..20_000)
        val objects = elements.map { it.asJsonObject }
        val identities = objects.map { element ->
            val type = string(element.get("type"))
            require(type in setOf("node", "way", "relation"))
            integer(element.get("version"))
            type to integer(element.get("id"))
        }
        require(identities.distinct().size == identities.size)
        val nodes = linkedMapOf<String, PublisherPoint>()
        objects.zip(identities).filter { it.second.first == "node" }.forEach { (element, identity) ->
            val point = PublisherPoint(number(element.get("lat")), number(element.get("lon")))
            require(point.valid())
            nodes[identity.second] = point
        }
        return objects.zip(identities).mapNotNull { (element, identity) ->
            val tags = element.get("tags")?.let { raw -> raw.asJsonObject.entrySet().associate { it.key to string(it.value) } }.orEmpty()
            val religious = tags["amenity"] == "place_of_worship" || tags["building"] in setOf("church", "chapel", "cathedral", "monastery")
            if (!religious) return@mapNotNull null
            require(identity.first != "relation")
            val points = if (identity.first == "node") listOf(requireNotNull(nodes[identity.second])) else
                element.getAsJsonArray("nodes").map { requireNotNull(nodes[integer(it)]) }
            if (identity.first == "way") require(valid(PublisherContour(identity.second,
                integer(element.get("version")).toLong(), "", points)))
            PublisherNeighbour(identity.first, identity.second, points)
        }
    }

    /** Public map responses exceed passport JSON limits; bound depth, values and duplicates separately. */
    private fun strictMapJson(text: String): JsonElement {
        require(text.length in 1..768_000)
        return JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            var count = 0
            fun read(depth: Int): JsonElement {
                require(depth < 12 && ++count <= 100_000)
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> JsonObject().also { obj ->
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            require(name.length <= 256 && !obj.has(name))
                            obj.add(name, read(depth + 1))
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> JsonArray().also { array ->
                        reader.beginArray(); while (reader.hasNext()) array.add(read(depth + 1)); reader.endArray()
                    }
                    JsonToken.STRING -> JsonPrimitive(reader.nextString().also { require(it.length <= 10_000) })
                    // Retain the original number lexeme: converting 1e0 to BigDecimal
                    // first would erase the distinction from an integer object id.
                    JsonToken.NUMBER -> JsonParser.parseString(reader.nextString().also {
                        require(it.length <= 40); it.toBigDecimal()
                    })
                    JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                    JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                    else -> error("Not literal JSON")
                }
            }
            read(0).also { require(reader.peek() == JsonToken.END_DOCUMENT) }
        }
    }

    fun unambiguous(contour: PublisherContour, neighbours: List<PublisherNeighbour>, anchors: List<PublisherPoint>): Boolean =
        neighbours.none { other -> !(other.type == "way" && other.id == contour.id) && if (other.type == "node")
            strictlyInside(other.points.single(), contour.points) || anchors.any { distance(it, other.points.single()) <= .001 }
        else anchors.any { strictlyInside(it, other.points) || boundary(it, other.points) } }

    private fun boundary(p: PublisherPoint, values: List<PublisherPoint>) = values.zipWithNext().any { (a, b) ->
        abs(distance(a, p) + distance(p, b) - distance(a, b)) < .000001 }
    fun distance(a: PublisherPoint, b: PublisherPoint) = OpenScheduleSiteResolver.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
    private fun projected(points: List<PublisherPoint>): List<Pair<Double, Double>> {
        val first = points.first(); val sx = 111_320 * cos(Math.toRadians(first.latitude))
        return points.map { (it.longitude - first.longitude) * sx to (it.latitude - first.latitude) * 111_320 }
    }
    private fun edgeDistance(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val x = b.first - a.first; val y = b.second - a.second
        val scale = x * x + y * y
        if (scale == 0.0) return hypot(p.first - a.first, p.second - a.second)
        val t = (((p.first - a.first) * x + (p.second - a.second) * y) / scale).coerceIn(0.0, 1.0)
        return hypot(p.first - a.first - t * x, p.second - a.second - t * y)
    }
    private fun intersects(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>, d: Pair<Double, Double>): Boolean {
        fun turn(p: Pair<Double, Double>, q: Pair<Double, Double>, r: Pair<Double, Double>) =
            (q.first - p.first) * (r.second - p.second) - (q.second - p.second) * (r.first - p.first)
        val x = turn(a, b, c); val y = turn(a, b, d); val z = turn(c, d, a); val w = turn(c, d, b)
        return x * y <= 0 && z * w <= 0 &&
            max(min(a.first, b.first), min(c.first, d.first)) <= min(max(a.first, b.first), max(c.first, d.first)) &&
            max(min(a.second, b.second), min(c.second, d.second)) <= min(max(a.second, b.second), max(c.second, d.second))
    }
}

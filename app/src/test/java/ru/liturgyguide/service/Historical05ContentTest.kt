package ru.liturgyguide.service

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate

class Historical05ContentTest {
    @Test fun `frozen assets and full plan tables match the independently produced release goldens`() {
        assertEquals("efe2788edc898d489721fda1665b9cd455c031cb2ee8a8994c361177500da101", sha(Published05Fixtures.bytes(Published05Fixtures.STAGES)))
        assertEquals("8086bdc710dc080d3a77b75eb90b031d8a586d446aad4d7a379b778229a022ae", sha(Published05Fixtures.bytes(Published05Fixtures.READINGS)))
        val goldenBytes = Published05Fixtures.bytes(Published05Fixtures.GOLDEN)
        assertEquals("304a414fd98232948de0d88000b4077c9316dc62b2a0d9df792632047637d369", sha(goldenBytes))
        val tables = JsonParser.parseString(goldenBytes.toString(Charsets.UTF_8)).asJsonArray
        assertEquals(2, tables.size())
        val fingerprints = listOf(
            "aeee10e94ab05b297121bd86b885600b113f941e7ffc115af8c6fb8a98880434",
            "3d69b0bf7c8e566560f8ab7651fa373f5c00304c2a609c73df4b8c115bf6497a"
        )
        val counts = listOf(listOf(169, 150, 18, 0, 1), listOf(101, 82, 16, 2, 1))
        tables.forEachIndexed { tableIndex, element ->
            val table = element.asJsonObject
            val date = Published05Fixtures.dates[tableIndex]
            val actual = Published05Fixtures.oldPlan(date)
            assertEquals(date.toString(), table["serviceDate"].asString)
            assertEquals(fingerprints[tableIndex], table["fingerprint"].asString)
            assertEquals(fingerprints[tableIndex], actual.fingerprint)
            assertEquals(1, actual.contentVersion)
            assertEquals(1, table["contentVersion"].asInt)
            assertEquals(counts[tableIndex], listOf(actual.segments.size,
                actual.segments.count { it is PlaybackSegment.Text }, actual.segments.count { it is PlaybackSegment.Wait },
                actual.segments.count { it is PlaybackSegment.MissingReading }, actual.segments.count { it is PlaybackSegment.End }))
            val expectedSegments = table["segments"].asJsonArray
            assertEquals(expectedSegments.size(), actual.segments.size)
            expectedSegments.forEachIndexed { index, segmentElement ->
                val expected = segmentElement.asJsonObject
                val segment = actual.segments[index]
                val label = "$date segment $index"
                assertEquals(label, index, expected["index"].asInt)
                assertEquals(label, expected["stageId"].asString, segment.stageId)
                assertEquals(label, expected["type"].asString, segment.javaClass.simpleName)
                when (segment) {
                    is PlaybackSegment.Text -> {
                        assertEquals(label, expected["trackId"].asString, segment.trackId)
                        assertEquals(label, expected["lineId"].asString, segment.lineId)
                        assertEquals(label, expected["durationBaseMillis"].asLong, segment.durationBaseMillis)
                        val line = actual.line(segment)
                        assertEquals(label, expected.get("original")?.takeUnless { it.isJsonNull }?.asString, line.original)
                        assertEquals(label, expected["russian"].asString, line.russian)
                    }
                    is PlaybackSegment.Wait -> assertEquals(label, expected["durationBaseMillis"].asLong, segment.durationBaseMillis)
                    // The original producer records the date once at table level, including barriers.
                    is PlaybackSegment.MissingReading -> assertEquals(label, table["serviceDate"].asString, segment.serviceDate.toString())
                    is PlaybackSegment.End -> Unit
                }
            }
        }
    }

    @Test fun `missing or unreadable historical assets fail closed independently`() {
        listOf(Published05Fixtures.STAGES, Published05Fixtures.READINGS).forEach { unavailable ->
            val loader = Historical05Content { name ->
                if (name == unavailable) throw IOException("Unavailable historical fixture")
                Published05Fixtures.asset(name)
            }
            Published05Fixtures.dates.forEach { assertNull("$unavailable on $it", loader.loadPlan(it)) }
        }
    }

    @Test fun `valid JSON with changed bytes is rejected before parsing or normalizing`() {
        val changes: List<(String) -> String> = listOf(
            { "$it\n" }, { "\uFEFF$it" }, { it.replace("\n", "\r\n") }, { it.replaceFirst("{", "{ ") }
        )
        listOf(Published05Fixtures.STAGES, Published05Fixtures.READINGS).forEach { altered ->
            changes.forEachIndexed { index, change ->
                val loader = Historical05Content { name ->
                    Published05Fixtures.asset(name).let { if (name == altered) change(it) else it }
                }
                assertNull("$altered byte mutation $index", loader.loadPlan(Published05Fixtures.dates.first()))
            }
        }
    }

    @Test fun `loader requests only closed historical resource names and keeps the requested saved date`() {
        val names = mutableListOf<String>()
        val loader = Historical05Content { name -> names += name; Published05Fixtures.asset(name) }
        val savedDate = LocalDate.of(2037, 2, 15)
        val plan = requireNotNull(loader.loadPlan(savedDate))
        assertEquals(setOf(Published05Fixtures.STAGES, Published05Fixtures.READINGS), names.toSet())
        assertEquals(savedDate, plan.serviceDate)
        assertEquals(listOf("epistle", "gospel"), plan.segments.filterIsInstance<PlaybackSegment.MissingReading>().map { it.stageId })
        assertTrue(plan.segments.filterIsInstance<PlaybackSegment.MissingReading>().all { it.serviceDate == savedDate })
        assertTrue(plan.tracksByStage.values.flatten().none { it.isScripture })
        assertNull(loader.loadPlan(LocalDate.of(0, 1, 1)))
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

package ru.liturgyguide.service

import com.google.gson.Gson
import ru.liturgyguide.model.DailyReadings
import java.security.MessageDigest
import java.time.LocalDate

/**
 * The two frozen assets from v0.6.0-guide, commit 2046ab2240833a5ac08d8ab58913f743a9f6b1ee.
 * The reader must preserve UTF-8 content exactly: validation precedes parsing or normalization.
 * A missing or changed resource makes historical recovery unavailable, never a current-data fallback.
 */
class Historical06Content(private val assetReader: (String) -> String) {
    fun loadPlan(serviceDate: LocalDate): ServicePlaybackPlan? = runCatching {
        // Shared builders may be reused only while their published semantics and golden tests hold.
        require(ServicePlaybackPlan.CONTENT_VERSION == PUBLISHED_CONTENT_VERSION)
        val stagesJson = verifiedAsset(STAGES_ASSET, STAGES_SHA256)
        val readingsJson = verifiedAsset(READINGS_ASSET, READINGS_SHA256)
        val stages = StageMatcher(stagesJson).getAllStages()
        val readings = Gson().fromJson(readingsJson, DailyReadings::class.java)
        val readingDay = readings.days.singleOrNull { it.date == serviceDate.toString() }
        ServicePlaybackPlan.build(stages, serviceDate, readingDay)
    }.getOrNull()

    private fun verifiedAsset(name: String, expectedSha256: String): String {
        val content = assetReader(name)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(sha256 == expectedSha256) { "Published 0.6 asset does not match its release bytes" }
        return content
    }

    private companion object {
        const val PUBLISHED_CONTENT_VERSION = 1
        const val STAGES_ASSET = "published_0_6/liturgy_stages.json"
        const val READINGS_ASSET = "published_0_6/daily_readings.json"
        const val STAGES_SHA256 = "ea0b891378c31dc7428643fe599862cf3bc06e58c88b8319c82ce2b8a04d7233"
        const val READINGS_SHA256 = "8086bdc710dc080d3a77b75eb90b031d8a586d446aad4d7a379b778229a022ae"
    }
}

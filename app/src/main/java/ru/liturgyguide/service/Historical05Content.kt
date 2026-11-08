package ru.liturgyguide.service

import com.google.gson.Gson
import ru.liturgyguide.model.DailyReadings
import java.security.MessageDigest
import java.time.LocalDate

/**
 * The two frozen assets from v0.5.0-guide, commit 98302858e341d8de621f7a1d87fce6d24b23db6b.
 * The reader must preserve UTF-8 content exactly: validation precedes parsing or normalization.
 * A missing or changed resource makes historical recovery unavailable, never a current-data fallback.
 */
class Historical05Content(private val assetReader: (String) -> String) {
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
        require(sha256 == expectedSha256) { "Published 0.5 asset does not match its release bytes" }
        return content
    }

    private companion object {
        const val PUBLISHED_CONTENT_VERSION = 1
        const val STAGES_ASSET = "published_0_5/liturgy_stages.json"
        const val READINGS_ASSET = "published_0_5/daily_readings.json"
        const val STAGES_SHA256 = "efe2788edc898d489721fda1665b9cd455c031cb2ee8a8994c361177500da101"
        const val READINGS_SHA256 = "8086bdc710dc080d3a77b75eb90b031d8a586d446aad4d7a379b778229a022ae"
    }
}

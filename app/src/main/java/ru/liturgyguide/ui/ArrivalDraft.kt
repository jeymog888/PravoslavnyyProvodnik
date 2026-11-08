package ru.liturgyguide.ui

import androidx.compose.runtime.saveable.listSaver
import ru.liturgyguide.model.Parish
import ru.liturgyguide.service.ReadingContext
import ru.liturgyguide.service.ReadingContextCodec
import ru.liturgyguide.service.ReadingContextSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class ArrivalMode { NEW_SERVICE, CORRECTION }
internal enum class ArrivalSource { SCHEDULE, MANUAL }

/** A choice awaiting confirmation. A scheduled occurrence is fixed without accepting a reading. */
internal data class ArrivalDraft(
    val churchId: String?,
    val churchName: String,
    val mode: ArrivalMode,
    val source: ArrivalSource,
    val serviceDate: String = "",
    val serviceTime: String = "",
    val upcoming: Boolean = false,
    val stageId: String? = null,
    val elapsedInStageMillis: Long = 0L,
    val query: String? = null,
    val automationStopped: Boolean = false,
    val pendingServiceContext: ReadingContext? = null
) {
    internal fun isWellFormed(): Boolean {
        if (churchId != null && !identifier(churchId)) return false
        if (churchName.isBlank() || churchName.length > 512 || serviceDate.length > 32 || serviceTime.length > 32 ||
            (stageId != null && !identifier(stageId)) || elapsedInStageMillis < 0 || (query?.length ?: 0) > 8_192) return false
        val pending = pendingServiceContext ?: return true
        return mode == ArrivalMode.NEW_SERVICE && source == ArrivalSource.MANUAL && !upcoming &&
            ReadingContextCodec.validated(pending) != null &&
            pending.source in setOf(ReadingContextSource.PUBLISHED, ReadingContextSource.USER) &&
            pending.startEpochMillis != null && pending.parishId == churchId
    }

    fun isValidFor(church: Parish?): Boolean {
        if (!isWellFormed() || churchId != church?.id) return false
        val pending = pendingServiceContext ?: return true
        val zone = runCatching { ZoneId.of(requireNotNull(church).timezone).id }.getOrNull() ?: return false
        return pending.timezone == zone
    }

    fun pendingServiceLabel(): String? = pendingServiceContext?.let { pending -> runCatching {
        val local = Instant.ofEpochMilli(requireNotNull(pending.startEpochMillis)).atZone(ZoneId.of(pending.timezone))
        val date = local.format(DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("ru")))
        val time = local.format(when {
            local.nano != 0 -> DateTimeFormatter.ISO_LOCAL_TIME
            local.second != 0 -> DateTimeFormatter.ofPattern("HH:mm:ss")
            else -> DateTimeFormatter.ofPattern("HH:mm")
        })
        "Литургия $date · начало в $time"
    }.getOrNull() }

    companion object {
        private fun identifier(value: String): Boolean = value.length in 1..160 && value == value.trim() &&
            value.none(Char::isISOControl)

        val Saver = listSaver<ArrivalDraft?, Any>(
            save = { ArrivalDraftCodec.encode(it) },
            restore = { ArrivalDraftCodec.decode(it) }
        )
    }
}

/** The former eleven-field state is supported; a corrupt twelfth field rejects the whole draft. */
internal object ArrivalDraftCodec {
    fun encode(draft: ArrivalDraft?): List<Any> = draft?.let {
        require(it.isWellFormed())
        listOf(it.churchId.orEmpty(), it.churchName, it.mode.name, it.source.name,
            it.serviceDate, it.serviceTime, it.upcoming, it.stageId.orEmpty(), it.elapsedInStageMillis,
            it.query.orEmpty(), it.automationStopped, it.pendingServiceContext?.let(ReadingContextCodec::encode).orEmpty())
    }.orEmpty()

    fun decode(values: List<Any?>): ArrivalDraft? = runCatching {
        if (values.isEmpty()) return null
        require(values.size == 11 || values.size == 12)
        fun text(index: Int, limit: Int): String = (values[index] as String).also { require(it.length <= limit) }
        val pending = if (values.size == 12) text(11, ReadingContextCodec.MAX_JSON_CHARS).let { raw ->
            if (raw.isEmpty()) null else requireNotNull(ReadingContextCodec.decode(raw))
        } else null
        ArrivalDraft(
            churchId = text(0, 160).ifEmpty { null }, churchName = text(1, 512),
            mode = ArrivalMode.valueOf(text(2, 32)), source = ArrivalSource.valueOf(text(3, 32)),
            serviceDate = text(4, 32), serviceTime = text(5, 32), upcoming = values[6] as Boolean,
            stageId = text(7, 160).ifEmpty { null }, elapsedInStageMillis = values[8] as Long,
            query = text(9, 8_192).ifEmpty { null }, automationStopped = values[10] as Boolean,
            pendingServiceContext = pending
        ).takeIf { it.isWellFormed() }
    }.getOrNull()
}

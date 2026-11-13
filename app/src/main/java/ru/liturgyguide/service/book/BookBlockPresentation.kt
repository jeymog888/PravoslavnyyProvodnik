package ru.liturgyguide.service.book

import ru.liturgyguide.model.BookBlock
import ru.liturgyguide.model.BookBlockKind
import ru.liturgyguide.model.ServiceBookKey
import ru.liturgyguide.model.WorshipKind

/** Read-only interpretation: an accepted edition keeps its complete text, IDs and hash. */
object BookBlockPresentation {
    private const val LEGACY_PUBLIC_EXCLAMATION = "И священник возглашает:"

    /** Only the whole public paragraphs verified in saved Matins and Vespers editions. */
    fun isLegacyPrayer(key: ServiceBookKey, block: BookBlock): Boolean = legacyPrayerBody(key, block) != null

    fun effectiveRussian(key: ServiceBookKey, block: BookBlock): String? =
        block.russian.takeUnless {
            it == block.original && block.translationSource == null && isLegacyPrayer(key, block)
        }

    fun isSpoken(key: ServiceBookKey, block: BookBlock): Boolean =
        block.kind == BookBlockKind.PRAYER || block.kind == BookBlockKind.SCRIPTURE || isLegacyPrayer(key, block)

    /** A timing input only; displayed text and search offsets always use the full original. */
    fun spokenText(key: ServiceBookKey, block: BookBlock, mode: BookReadingMode = BookReadingMode.ORIGINAL): String? {
        if (mode == BookReadingMode.MEANING || !isSpoken(key, block)) return null
        if (mode == BookReadingMode.RUSSIAN) effectiveRussian(key, block)?.let { return it }
        return legacyPrayerBody(key, block) ?: block.original
    }

    private fun legacyPrayerBody(key: ServiceBookKey, block: BookBlock): String? {
        if ((key.worship != WorshipKind.MATINS && key.worship != WorshipKind.VESPERS) ||
            block.kind != BookBlockKind.RUBRIC || block.isAltarText ||
            !block.original.startsWith(LEGACY_PUBLIC_EXCLAMATION)) return null
        // Do not use splitDatedPublicParagraph: its generic fallback admits other labels.
        return block.original.substring(LEGACY_PUBLIC_EXCLAMATION.length).trim().takeIf(String::isNotEmpty)
    }
}

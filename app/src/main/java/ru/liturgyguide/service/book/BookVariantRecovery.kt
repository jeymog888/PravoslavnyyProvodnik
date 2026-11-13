package ru.liturgyguide.service.book

import ru.liturgyguide.model.Parish
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey

/** A prior edition can be offered after failure, but remains a book under its original key. */
object BookVariantRecovery {
    fun legacyKey(requested: ServiceBookKey, parish: Parish?): ServiceBookKey? {
        if (!requested.isValid() || requested.patronalId != null || parish == null) return null
        val classification = ChurchTypeClassifier.classify(parish)
        if (requested.churchType != classification.requestType) return null
        val previousType = ServiceBookSelection.legacyChurchType(parish)
        return requested.copy(churchType = previousType).takeIf { it != requested }
    }

    fun candidate(requested: ServiceBookKey, parishId: String?, parish: Parish?,
        positionFor: (ServiceBookKey, String?) -> BookReadingPosition?,
        cachedVersion: (ServiceBookKey, String) -> ServiceBook?): ServiceBook? {
        if (parishId == null || parish?.id != parishId) return null
        val previousKey = legacyKey(requested, parish) ?: return null
        val position = positionFor(previousKey, parishId)?.takeIf {
            it.parishId == parishId && it.key == previousKey
        } ?: return null
        return cachedVersion(previousKey, position.contentHash)?.takeIf { book ->
            book.key == previousKey && book.contentHash == position.contentHash && ServiceBookIntegrity.valid(book) &&
                book.chapters.any { chapter -> chapter.id == position.chapterId && chapter.blocks.any { it.id == position.blockId } }
        }
    }
}

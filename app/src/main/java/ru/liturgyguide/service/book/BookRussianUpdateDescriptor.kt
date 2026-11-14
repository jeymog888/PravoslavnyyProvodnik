package ru.liturgyguide.service.book

import java.util.Collections
import ru.liturgyguide.model.BookBlock
import ru.liturgyguide.model.BookChapter
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey

/**
 * A transient explanation of a verified candidate, never permission to accept it.
 * The caller verifies durable candidate bytes and the live owner outside rendering.
 */
class BookRussianUpdateDescriptor private constructor(
    val ownerToken: Any,
    val ownerIdentity: String,
    val acceptedKey: ServiceBookKey,
    val acceptedHash: String,
    val candidateKey: ServiceBookKey,
    val candidateHash: String,
    newlyRussianIds: Set<String>
) {
    val newlyRussianBlockIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(newlyRussianIds))

    /** Stable through recreation, distinct for another occurrence or accepted/candidate pair. */
    val pairIdentity: String = listOf(ownerIdentity, acceptedKey.canonical(), acceptedHash,
        candidateKey.canonical(), candidateHash).joinToString("") { "${it.length}:$it" }

    /** Token identity also protects a new lifetime of the same visible book. */
    fun matches(acceptedKey: ServiceBookKey, acceptedHash: String, ownerToken: Any): Boolean =
        this.ownerToken === ownerToken && this.acceptedKey == acceptedKey && this.acceptedHash == acceptedHash

    companion object {
        private const val MAX_OWNER_IDENTITY = 8192
        private val hash = Regex("[a-f0-9]{64}")

        /**
         * Pure bounded diff. No disk, network, JSON serialization or full-book hashing.
         * verifiedCandidate means an exact saved version was read back by the caller;
         * this descriptor itself establishes no durability or source authority.
         */
        fun create(accepted: ServiceBook, candidate: BookLoadResult.Ready?, verifiedCandidate: Boolean,
            ownerToken: Any, ownerIdentity: String): BookRussianUpdateDescriptor? = runCatching {
            if (candidate == null || !candidate.saved || !verifiedCandidate ||
                ownerIdentity.length !in 1..MAX_OWNER_IDENTITY || ownerIdentity.any(Char::isISOControl) ||
                !accepted.key.isValid() || candidate.book.key != accepted.key ||
                !hash.matches(accepted.contentHash) || !hash.matches(candidate.book.contentHash) ||
                accepted.contentHash == candidate.book.contentHash) return@runCatching null
            val next = candidate.book
            val oldRows = indexed(accepted) ?: return@runCatching null
            val nextRows = indexed(next) ?: return@runCatching null
            val oldVisibility = BookVisibilityScope.of(accepted)
            val nextVisibility = BookVisibilityScope.of(next)
            val added = linkedSetOf<String>()
            for ((id, previous) in oldRows) {
                val current = nextRows[id] ?: continue
                val before = previous.block
                val after = current.block
                if (previous.chapter.id != current.chapter.id ||
                    before.original.isBlank() || before.original != after.original ||
                    before.kind != after.kind || before.speaker != after.speaker ||
                    before.isAltarText != after.isAltarText || before.scriptureReferences != after.scriptureReferences ||
                    !BookBlockPresentation.isSpoken(accepted.key, before) ||
                    !BookBlockPresentation.isSpoken(next.key, after) ||
                    oldVisibility.isPrivate(before) || nextVisibility.isPrivate(after) ||
                    BookBlockPresentation.effectiveRussian(accepted.key, before) != null ||
                    BookBlockPresentation.effectiveRussian(next.key, after).isNullOrBlank()) continue
                added += id
            }
            if (added.isEmpty()) null else BookRussianUpdateDescriptor(ownerToken, ownerIdentity,
                accepted.key, accepted.contentHash, next.key, next.contentHash, added)
        }.getOrNull()

        private data class Row(val chapter: BookChapter, val block: BookBlock)

        /** A repeated ID, even in an unrelated chapter, is not a unique target. */
        private fun indexed(book: ServiceBook): LinkedHashMap<String, Row>? {
            val chapters = book.chapters
            if (chapters.isEmpty() || chapters.any { !validId(it.id) } ||
                chapters.map { it.id }.distinct().size != chapters.size) return null
            val rows = linkedMapOf<String, Row>()
            for (chapter in chapters) for (block in chapter.blocks) {
                if (!validId(block.id) || rows.put(block.id, Row(chapter, block)) != null) return null
            }
            return rows.takeIf { it.isNotEmpty() }
        }

        private fun validId(id: String): Boolean = id.length in 1..180 && id.isNotBlank() && id.none(Char::isISOControl)
    }
}

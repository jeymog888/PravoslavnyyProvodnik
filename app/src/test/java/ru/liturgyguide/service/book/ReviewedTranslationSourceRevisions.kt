package ru.liturgyguide.service.book

import org.junit.Assert.assertEquals

/** Historical oracles remain sealed; only the reviewed four-pair index revision supersedes its old source seal. */
internal object ReviewedTranslationSourceRevisions {
    private const val INDEX_PATH = "app/src/main/java/ru/liturgyguide/service/book/BookTranslationIndex.kt"
    private const val BEFORE_PSALM19 = "ba4d0a5c2c2b504c429c93150471c4c8b5fe505f5f147a8457431574be5a9272"
    private const val WITH_FOUR_PUBLISHED_PSALMS = "313f91bf6b1b4fa334c1c9eb3d60aae68c96c6989cf99adaa03090bbd85e7ba5"

    fun assertMatches(path: String, historicalSha256: String) {
        val expected = if (path == INDEX_PATH && historicalSha256 == BEFORE_PSALM19) WITH_FOUR_PUBLISHED_PSALMS else historicalSha256
        assertEquals(path, expected, BookText.sha256(WeekdayAntiphonFixtures.sourceFile(path).readText()))
    }
}

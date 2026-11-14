package ru.liturgyguide.service.book

import org.junit.Assert.assertEquals

/** Historical oracles remain sealed; only the reviewed Psalm 19 index revision supersedes its old source seal. */
internal object ReviewedTranslationSourceRevisions {
    private const val INDEX_PATH = "app/src/main/java/ru/liturgyguide/service/book/BookTranslationIndex.kt"
    private const val BEFORE_PSALM19 = "ba4d0a5c2c2b504c429c93150471c4c8b5fe505f5f147a8457431574be5a9272"
    private const val WITH_PSALM19 = "0bfb96a8921698059670204f92051bbff5b055e703568e80115c8ae7bc8a58b3"

    fun assertMatches(path: String, historicalSha256: String) {
        val expected = if (path == INDEX_PATH && historicalSha256 == BEFORE_PSALM19) WITH_PSALM19 else historicalSha256
        assertEquals(path, expected, BookText.sha256(WeekdayAntiphonFixtures.sourceFile(path).readText()))
    }
}

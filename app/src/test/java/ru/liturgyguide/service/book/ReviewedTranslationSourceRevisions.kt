package ru.liturgyguide.service.book

import org.junit.Assert.assertEquals

/** Historical oracles stay sealed; only these exact reviewed extractor revisions supersede old source seals. */
internal object ReviewedTranslationSourceRevisions {
    private const val INDEX_PATH = "app/src/main/java/ru/liturgyguide/service/book/BookTranslationIndex.kt"
    private const val BEFORE_PSALM19 = "ba4d0a5c2c2b504c429c93150471c4c8b5fe505f5f147a8457431574be5a9272"
    private const val WITH_HOURS_PRAYER_BOUNDARY = "1570c1c0355e66345be639ff38126735d50d2c7f94b2ba35967a478613e8bad5"
    private const val CORPUS_PATH = "app/src/main/java/ru/liturgyguide/service/book/BookTranslationCorpus.kt"
    private const val BEFORE_HOURS_PRAYER = "94d917145ef331259ec1476f91d6185e91880fb4ad291b4e055ddb3428dabb86"
    private const val WITH_HOURS_PRAYER_CORPUS = "bd5de2b0a2b0f281e137c86fe11bfb89c1fbc881332bf802f6730e4b4e48cdba"

    // These two already-reviewed tests only changed their corpus version expectations.
    // Historical oracle files and every unrelated source path remain exact.
    private val versionTestRevisions = mapOf(
        "app/src/test/java/ru/liturgyguide/service/book/BookSundayOpeningTest.kt" to
            ("f3d4232d9b416f94bde32883995118ff348f2e5fd5b8a2103a1a3be1458f77d2" to
                "cfd94a0658f05644109cd89751a16054d73c9b2ea97f6d92ed525b15d12c6b6f"),
        "app/src/test/java/ru/liturgyguide/service/book/PublishedVerseTranslationTest.kt" to
            ("83768f8da17319184613b4a13d29c55207a35408513d6243ffb9a86bb66f48f9" to
                "daf252a1b6c7bdb4ab487041ddb4b4a3e7afefd31cfc4031be3da1a9f4038fa0")
    )

    fun assertMatches(path: String, historicalSha256: String) {
        val reviewedVersionTest = versionTestRevisions[path]?.takeIf { it.first == historicalSha256 }
        val expected = when {
            path == INDEX_PATH && historicalSha256 == BEFORE_PSALM19 -> WITH_HOURS_PRAYER_BOUNDARY
            path == CORPUS_PATH && historicalSha256 == BEFORE_HOURS_PRAYER -> WITH_HOURS_PRAYER_CORPUS
            reviewedVersionTest != null -> reviewedVersionTest.second
            else -> historicalSha256
        }
        assertEquals(path, expected, BookText.sha256(WeekdayAntiphonFixtures.sourceFile(path).readText()))
    }
}

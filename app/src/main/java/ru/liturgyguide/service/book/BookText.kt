package ru.liturgyguide.service.book

import java.security.MessageDigest
import java.text.Normalizer

object BookText {
    private val stressMarks = Regex("[\\u0300\\u0301\\u0340\\u0341]")
    private val nonLetters = Regex("[^\\p{L}\\p{N}]+")
    private val whitespace = Regex("[\\s\\u00a0]+")

    fun clean(value: String): String = whitespace.replace(value.replace("\u00ad", ""), " ").trim()

    /** Exact lexical matching: no edit distance, stem guessing or assigning a nearby translation. */
    fun normalized(value: String): String = nonLetters.replace(
        stressMarks.replace(Normalizer.normalize(value, Normalizer.Form.NFC), "")
            .lowercase().replace('ё', 'е').replace('ѣ', 'е').replace('і', 'и')
            .replace('i', 'и').replace('ѳ', 'ф').replace('ѵ', 'и'), " "
    ).trim()

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun withoutSpeaker(value: String): Pair<String?, String> {
        val pattern = Regex("^(?:(?:[12] )?(?:Лик|Хор)|Диакон|Священник|Иерей|Чтец|Народ|Предстоятель):\\s*", RegexOption.IGNORE_CASE)
        val found = pattern.find(clean(value)) ?: return null to clean(value)
        val speaker = found.value.substringBefore(':').trim().let {
            when (it.lowercase()) { "лик", "1 лик", "2 лик" -> "Хор"; "иерей" -> "Священник"; else -> it }
        }
        return speaker to clean(value).removeRange(found.range).trim()
    }
}

package ru.liturgyguide.service.book

import com.google.gson.Gson
import ru.liturgyguide.model.ServiceBook
import ru.liturgyguide.model.ServiceBookKey
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface BookCache {
    fun read(key: ServiceBookKey): ServiceBook?
    fun readVersion(key: ServiceBookKey, contentHash: String): ServiceBook? = read(key)?.takeIf { it.contentHash == contentHash }
    fun write(book: ServiceBook): Boolean
    fun readTranslationCorpus(): String? = null
    fun writeTranslationCorpus(json: String): Boolean = false
}

/** A content-addressed book is immutable; the small latest pointer is committed afterwards. */
class FileBookCache(private val directory: File) : BookCache {
    private val gson = Gson()

    @Synchronized override fun read(key: ServiceBookKey): ServiceBook? = runCatching {
        require(key.isValid())
        val pointer = File(directory, "${keyId(key)}.latest")
        if (!pointer.isFile || pointer.length() > 80) return null
        readVersion(key, pointer.readText(Charsets.UTF_8).trim())
    }.getOrNull()

    @Synchronized override fun readVersion(key: ServiceBookKey, contentHash: String): ServiceBook? = runCatching {
        require(key.isValid() && contentHash.matches(Regex("[a-f0-9]{64}")))
        val file = File(directory, "${keyId(key)}-$contentHash.json")
        if (!file.isFile || file.length() !in 1..MAX_BOOK_BYTES) return null
        gson.fromJson(file.readText(Charsets.UTF_8), ServiceBook::class.java).takeIf {
            it != null && it.key == key && it.contentHash == contentHash && ServiceBookIntegrity.valid(it)
        }
    }.getOrNull()

    @Synchronized override fun write(book: ServiceBook): Boolean = runCatching {
        require(ServiceBookIntegrity.valid(book))
        val json = gson.toJson(book)
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BOOK_BYTES)
        val stem = keyId(book.key)
        atomicWrite(File(directory, "$stem-${book.contentHash}.json"), json)
        atomicWrite(File(directory, "$stem.latest"), book.contentHash)
        true
    }.getOrDefault(false)

    @Synchronized override fun readTranslationCorpus(): String? = runCatching {
        File(directory, "russian-concordance.json").takeIf { it.isFile && it.length() in 1..BookTranslationCorpus.MAX_BYTES }
            ?.readText(Charsets.UTF_8)?.takeIf { BookTranslationCorpus.decode(it).index.size > 0 }
    }.getOrNull()

    @Synchronized override fun writeTranslationCorpus(json: String): Boolean = runCatching {
        require(json.toByteArray(Charsets.UTF_8).size <= BookTranslationCorpus.MAX_BYTES)
        require(BookTranslationCorpus.decode(json).index.size > 0)
        atomicWrite(File(directory, "russian-concordance.json"), json)
        true
    }.getOrDefault(false)

    private fun keyId(key: ServiceBookKey) = BookText.sha256(key.canonical()).take(32)

    private fun atomicWrite(destination: File, text: String) {
        if (!directory.isDirectory && !directory.mkdirs()) error("Could not create book cache")
        val temporary = File.createTempFile("book-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8)); stream.fd.sync()
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    companion object { private const val MAX_BOOK_BYTES = 12_000_000L }
}

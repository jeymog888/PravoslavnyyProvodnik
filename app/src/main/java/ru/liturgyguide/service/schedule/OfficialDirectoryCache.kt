package ru.liturgyguide.service.schedule

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Compact parsed public directory records; never mixed into an individual schedule cache. */
interface OfficialDirectoryCacheStore {
    fun read(profileId: String): String?
    fun write(profileId: String, value: String)
}

class MemoryOfficialDirectoryCacheStore : OfficialDirectoryCacheStore {
    private val entries = linkedMapOf<String, String>()
    @Synchronized override fun read(profileId: String): String? = entries[profileId]
    @Synchronized override fun write(profileId: String, value: String) {
        require(value.toByteArray(Charsets.UTF_8).size <= OrtOxDirectoryParser.MAX_BYTES)
        if (!entries.containsKey(profileId) && entries.size >= MAX_PROFILES) entries.remove(entries.keys.first())
        entries[profileId] = value
    }
    companion object { internal const val MAX_PROFILES = 8 }
}

class FileOfficialDirectoryCacheStore(private val directory: File) : OfficialDirectoryCacheStore {
    override fun read(profileId: String): String? = synchronized(lock) {
        val file = file(profileId)
        if (!file.isFile || file.length() !in 1..OrtOxDirectoryParser.MAX_BYTES.toLong()) null
        else file.readText(Charsets.UTF_8)
    }

    override fun write(profileId: String, value: String) = synchronized(lock) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..OrtOxDirectoryParser.MAX_BYTES)
        check(directory.mkdirs() || directory.isDirectory)
        val target = file(profileId)
        val temporary = File.createTempFile("directory-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            check(temporary.renameTo(target))
            directory.listFiles().orEmpty().filter { it != target && Regex("[a-f0-9]{64}\\.json").matches(it.name) }
                .sortedByDescending(File::lastModified).drop(MemoryOfficialDirectoryCacheStore.MAX_PROFILES - 1)
                .forEach(File::delete)
        } finally { temporary.delete() }
    }

    private fun file(profileId: String): File {
        require(Regex("[a-z0-9-]{1,80}").matches(profileId))
        val name = MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$name.json")
    }

    companion object { private val lock = Any() }
}

internal object OfficialDirectoryCodec {
    private const val VERSION = 1
    fun encode(snapshot: OfficialDirectorySnapshot): String = Gson().toJsonTree(snapshot).asJsonObject
        .apply { addProperty("version", VERSION) }.toString()

    fun decode(value: String, profile: OfficialDirectoryProfile): OfficialDirectorySnapshot? = runCatching {
        require(value.toByteArray(Charsets.UTF_8).size in 1..OrtOxDirectoryParser.MAX_BYTES)
        val root = JsonParser.parseString(value).asJsonObject
        require(root["version"].asInt == VERSION && root["profileId"].asString == profile.id && root["directoryUrl"].asString == profile.url)
        val fetchedAt = root["fetchedAt"].asString
        require(fetchedAt.length <= 40)
        java.time.Instant.parse(fetchedAt)
        val hash = root["sourceSha256"].asString
        require(Regex("[a-f0-9]{64}").matches(hash))
        val rows = root["entries"].asJsonArray
        require(rows.size() in profile.minimumEntries..OrtOxDirectoryParser.MAX_ENTRIES)
        val entries = rows.map { row ->
            val item = row.asJsonObject
            val id = item["id"].asString
            val title = item["title"].asString
            require(Regex("t[0-9]{1,12}").matches(id) && title.length <= 300 && title.none(Char::isISOControl))
            fun optional(name: String, limit: Int): String? = item[name]?.takeUnless { it.isJsonNull }?.asString?.also {
                require(it.length <= limit && it.none(Char::isISOControl))
            }
            val website = optional("website", 2048)
            require(website == null || ScheduleUrl.publicSource(website) == website)
            OfficialDirectoryEntry(id, title, optional("address", 500), website, optional("coordinates", 100))
        }
        require(entries.map { it.id }.distinct().size == entries.size)
        OfficialDirectorySnapshot(profile.id, profile.url, fetchedAt, hash, entries)
    }.getOrNull()
}

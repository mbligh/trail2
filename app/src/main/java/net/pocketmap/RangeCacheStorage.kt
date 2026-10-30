package net.pocketmap

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Disk format shared by the native SDK interceptor and the Compose resource provider. */
class RangeCacheStorage(
    private val directory: File,
    private val highWater: Long = 500L * 1024 * 1024,
    private val lowWater: Long = 400L * 1024 * 1024
) {
    data class Entry(val contentRange: String, val mime: String, val data: ByteArray)
    private var writes = 0
    init { directory.mkdirs() }

    @Synchronized fun get(url: String, range: String, namespace: String): Entry? {
        val file = file(url, range, namespace)
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val contentRange = input.readUTF()
                val mime = input.readUTF()
                val length = input.readInt()
                require(length in 1..32 * 1024 * 1024)
                val data = ByteArray(length)
                input.readFully(data)
                Entry(contentRange, mime, data)
            }
        }.getOrElse { file.delete(); null }
    }

    @Synchronized fun put(url: String, range: String, namespace: String, entry: Entry) {
        if (entry.data.isEmpty() || entry.data.size > 32 * 1024 * 1024 || entry.contentRange.isBlank()) return
        val file = file(url, range, namespace)
        val temporary = File(file.path + ".tmp")
        try {
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeUTF(entry.contentRange)
                output.writeUTF(entry.mime)
                output.writeInt(entry.data.size)
                output.write(entry.data)
            }
            check(temporary.renameTo(file))
        } finally { temporary.delete() }
        if (++writes % 32 == 0) prune()
    }

    private fun prune() {
        val files = directory.listFiles()?.filter { it.extension == "range" }
            ?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        if (size <= highWater) return
        for (file in files) {
            val length = file.length()
            if (file.delete()) size -= length
            if (size <= lowWater) break
        }
    }

    private fun file(url: String, range: String, namespace: String): File {
        // Preserve the original TileRepository.hash and RangeCache key verbatim.
        val hash = MessageDigest.getInstance("SHA-256").digest("$url|$range|$namespace".toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.range")
    }
}

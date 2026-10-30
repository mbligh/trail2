package net.pocketmap.composeprobe

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistent PMTiles range cache with a bounded 500/400 MiB pruning policy.
 * It requires exact 206 responses, never downloads a whole archive and
 * returns cached data without network connectivity.
 */
internal class PmtilesRangeSource(
    private val directory: File,
    private val url: String,
    private val namespace: String,
    private val allowNetwork: () -> Boolean = { true },
    private val fetch: (Long, Int) -> ByteArray? = { offset, length ->
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 7_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Range", "bytes=$offset-" + (offset + length - 1))
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (connection.responseCode != 206) throw IOException("Expected HTTP 206")
            val expected = "bytes $offset-" + (offset + length - 1) + "/"
            if (connection.getHeaderField("Content-Range")?.lowercase()?.startsWith(expected) != true)
                throw IOException("Invalid Content-Range")
            // Do not allocate/read an unbounded response from a broken range server.
            connection.inputStream.use { stream ->
                val bytes = ByteArray(length)
                DataInputStream(stream).readFully(bytes)
                if (stream.read() != -1) throw IOException("Oversized range response")
                bytes
            }
        } finally { connection.disconnect() }
    }
) {
    val cacheHits = AtomicInteger()
    val networkRequests = AtomicInteger()
    val networkBytes = AtomicLong()
    val blockedRequests = AtomicInteger()

    init {
        require(url.startsWith("https://") || url.startsWith("http://127.0.0.1:"))
        require(namespace.isNotBlank())
        directory.mkdirs()
    }

    @Synchronized fun read(offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length !in 1..(16 * 1024 * 1024) || offset > Long.MAX_VALUE - length)
            return null
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$url|$namespace|$offset|$length".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(directory, "$hash.range")
        if (file.exists()) {
            if (file.length() == length.toLong()) {
                cacheHits.incrementAndGet()
                return file.readBytes()
            }
            file.delete()
        }
        if (!allowNetwork()) {
            blockedRequests.incrementAndGet()
            return null
        }
        networkRequests.incrementAndGet()
        val bytes = try { fetch(offset, length) } catch (error: IOException) {
            android.util.Log.w("ComposeProbeRange", "Range $offset+$length failed for $url", error)
            null
        }
        if (bytes?.size != length) return null
        networkBytes.addAndGet(bytes.size.toLong())
        val tmp = File(file.path + ".tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file) && !file.exists()) throw IOException("Cannot persist range")
        } finally { tmp.delete() }
        prune()
        return bytes
    }

    private var writes = 0
    private fun prune() {
        if (++writes % 32 != 0) return
        val files = directory.listFiles()?.filter { it.extension == "range" }?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        if (size <= 500L * 1024 * 1024) return
        for (file in files) {
            val length = file.length()
            if (file.delete()) size -= length
            if (size <= 400L * 1024 * 1024) break
        }
    }
}

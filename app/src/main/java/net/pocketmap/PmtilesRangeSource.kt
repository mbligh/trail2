package net.pocketmap

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import net.pocketmap.RangeCacheStorage
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
    private val fetch: (Long, Int) -> RangeCacheStorage.Entry? = { offset, length ->
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 7_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Range", "bytes=$offset-" + (offset + length - 1))
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (connection.responseCode != 206) throw IOException("Expected HTTP 206")
            val expected = "bytes $offset-" + (offset + length - 1) + "/"
            val contentRange = connection.getHeaderField("Content-Range") ?: ""
            if (!contentRange.lowercase().startsWith(expected))
                throw IOException("Invalid Content-Range")
            // Do not allocate/read an unbounded response from a broken range server.
            connection.inputStream.use { stream ->
                val bytes = ByteArray(length)
                DataInputStream(stream).readFully(bytes)
                if (stream.read() != -1) throw IOException("Oversized range response")
                RangeCacheStorage.Entry(contentRange,
                    connection.getHeaderField("Content-Type") ?: "application/octet-stream", bytes)
            }
        } finally { connection.disconnect() }
    }
) {
    private val storage = RangeCacheStorage(directory)
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
        val requestedRange = "bytes=$offset-" + (offset + length - 1)
        val expected = "bytes $offset-" + (offset + length - 1) + "/"
        storage.get(url, requestedRange, namespace)?.let { entry ->
            if (entry.data.size == length && entry.contentRange.lowercase().startsWith(expected)) {
                cacheHits.incrementAndGet()
                return entry.data
            }
        }
        if (!allowNetwork()) {
            blockedRequests.incrementAndGet()
            return null
        }
        networkRequests.incrementAndGet()
        val entry = try { fetch(offset, length) } catch (error: IOException) {
            android.util.Log.w("TrailTiles", "Range $offset+$length failed for $url", error)
            null
        }
        if (entry == null || entry.data.size != length || !entry.contentRange.lowercase().startsWith(expected)) return null
        networkBytes.addAndGet(entry.data.size.toLong())
        runCatching { storage.put(url, requestedRange, namespace, entry) }.onFailure {
            android.util.Log.w("TrailTiles", "Cannot persist $requestedRange", it)
        }
        return entry.data
    }
}

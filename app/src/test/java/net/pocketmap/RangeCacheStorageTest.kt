package net.pocketmap

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

class RangeCacheStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val url = "https://example.test/contours.pmtiles"
    private fun legacyFile(directory: File, range: String, version: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest("$url|$range|$version".toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.range")
    }

    @Test fun readsExistingNativeCacheAndWritesTheSameBinaryFormat() {
        val directory = temporary.newFolder()
        val file = legacyFile(directory, "bytes=0-3", "1")
        // Original RangeCache layout, written without using the new storage class.
        DataOutputStream(file.outputStream()).use {
            it.writeUTF("bytes 0-3/1234"); it.writeUTF("application/octet-stream")
            it.writeInt(4); it.write(byteArrayOf(1, 2, 3, 4))
        }
        val storage = RangeCacheStorage(directory)
        val old = storage.get(url, "bytes=0-3", "1")!!
        assertEquals("bytes 0-3/1234", old.contentRange)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), old.data)
        assertNull(storage.get(url, "bytes=0-3", "2"))
        storage.put(url, "bytes=4-5", "1", RangeCacheStorage.Entry("bytes 4-5/1234", "image/png", byteArrayOf(5, 6)))
        DataInputStream(legacyFile(directory, "bytes=4-5", "1").inputStream()).use {
            assertEquals("bytes 4-5/1234", it.readUTF()); assertEquals("image/png", it.readUTF())
            assertEquals(2, it.readInt()); assertEquals(5, it.read()); assertEquals(6, it.read()); assertEquals(-1, it.read())
        }
        assertArrayEquals(byteArrayOf(5, 6), RangeCacheStorage(directory).get(url, "bytes=4-5", "1")!!.data)
    }

    @Test fun corruptRangesAreDiscardedWithoutLosingOtherCachedData() {
        val directory = temporary.newFolder()
        val storage = RangeCacheStorage(directory)
        storage.put(url, "bytes=0-3", "1", RangeCacheStorage.Entry("bytes 0-3/1234", "application/octet-stream", byteArrayOf(1, 2, 3, 4)))
        val corrupt = legacyFile(directory, "bytes=4-8", "1")
        DataOutputStream(corrupt.outputStream()).use {
            it.writeUTF("bytes 4-8/1234"); it.writeUTF("application/octet-stream")
            it.writeInt(5); it.write(1) // truncated after the declared byte count
        }
        assertNull(storage.get(url, "bytes=4-8", "1")); assertFalse(corrupt.exists())
        assertNotNull(storage.get(url, "bytes=0-3", "1"))
    }
}

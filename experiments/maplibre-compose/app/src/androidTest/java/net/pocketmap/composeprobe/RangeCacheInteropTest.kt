package net.pocketmap.composeprobe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.pocketmap.RangeCacheStorage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RangeCacheInteropTest {
    @Test fun nativeFormatRangesAndResponseHeadersRoundTripThroughCompose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "range-interop-${System.nanoTime()}")
        val url = "https://example.test/contours.pmtiles"
        try {
            val storage = RangeCacheStorage(directory)
            storage.put(url, "bytes=0-3", "1",
                RangeCacheStorage.Entry("bytes 0-3/9876", "application/octet-stream", byteArrayOf(1, 2, 3, 4)))
            val offline = PmtilesRangeSource(directory, url, "1", allowNetwork = { false })
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), offline.read(0, 4))
            assertEquals(1, offline.cacheHits.get()); assertEquals(0, offline.networkRequests.get())
            val online = PmtilesRangeSource(directory, url, "1") { offset, length ->
                assertEquals(4L, offset); assertEquals(2, length)
                RangeCacheStorage.Entry("bytes 4-5/9876", "application/octet-stream", byteArrayOf(5, 6))
            }
            assertArrayEquals(byteArrayOf(5, 6), online.read(4, 2))
            val nativeReplay = RangeCacheStorage(directory).get(url, "bytes=4-5", "1")!!
            assertEquals("bytes 4-5/9876", nativeReplay.contentRange)
            assertArrayEquals(byteArrayOf(5, 6), nativeReplay.data)
            // A response for the wrong requested offset must never poison the cache.
            val invalid = PmtilesRangeSource(directory, url, "1") { _, _ ->
                RangeCacheStorage.Entry("bytes 100-101/9876", "application/octet-stream", byteArrayOf(7, 8))
            }
            assertNull(invalid.read(6, 2)); assertNull(storage.get(url, "bytes=6-7", "1"))
        } finally { directory.deleteRecursively() }
    }
}

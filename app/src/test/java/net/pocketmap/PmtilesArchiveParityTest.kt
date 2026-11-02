package net.pocketmap

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class PmtilesArchiveParityTest {
    private fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
    }.toByteArray()
    private fun directory(ids: List<Long>, runs: List<Long>, sizes: List<Int>, offsets: List<Long>): ByteArray {
        val out = ByteArrayOutputStream()
        fun number(value: Long) {
            var n = value
            while (n >= 128) { out.write((n.toInt() and 127) or 128); n = n ushr 7 }
            out.write(n.toInt())
        }
        number(ids.size.toLong())
        ids.forEachIndexed { i, id -> number(id - if (i == 0) 0 else ids[i - 1]) }
        runs.forEach(::number); sizes.forEach { number(it.toLong()) }; offsets.forEach(::number)
        return out.toByteArray()
    }
    @Test fun rootLeavesRunsAndGzipMatchFrozenReaderAndRequestedRanges() {
        for (leaf in listOf(false, true)) for (internalGzip in listOf(false, true)) for (tileGzip in listOf(false, true)) {
            val expectedA = "tile-A".toByteArray()
            val expectedB = ByteArray(300) { (it % 251).toByte() }
            val a = if (tileGzip) gzip(expectedA) else expectedA
            val b = if (tileGzip) gzip(expectedB) else expectedB
            fun pack(bytes: ByteArray) = if (internalGzip) gzip(bytes) else bytes
            val entries = pack(directory(listOf(1, 3), listOf(2, 2), listOf(a.size, b.size), listOf(1, 0)))
            val root = if (leaf) pack(directory(listOf(1), listOf(0), listOf(entries.size), listOf(1))) else entries
            val leaves = if (leaf) entries else byteArrayOf()
            val tileOffset = 127 + root.size + leaves.size
            val header = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("PMTiles".toByteArray()); put(3.toByte())
                putLong(8, 127); putLong(16, root.size.toLong())
                putLong(40, (127 + root.size).toLong()); putLong(48, leaves.size.toLong())
                putLong(56, tileOffset.toLong()); putLong(64, (a.size + b.size).toLong())
                put(97, (if (internalGzip) 2 else 1).toByte()); put(98, (if (tileGzip) 2 else 1).toByte())
                put(99, 1.toByte()); put(100, 1.toByte()); put(101, 1.toByte())
            }.array()
            val bytes = header + root + leaves + a + b
            val oldRanges = mutableListOf<Pair<Long, Int>>()
            val newRanges = mutableListOf<Pair<Long, Int>>()
            fun range(trace: MutableList<Pair<Long, Int>>, offset: Long, length: Int): ByteArray? {
                trace.add(offset to length)
                if (offset < 0 || offset > bytes.size.toLong() - length) return null
                return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
            }
            val old = LegacyPmtilesArchive { offset, length -> range(oldRanges, offset, length) }
            val shared = PmtilesArchive { offset, length -> range(newRanges, offset, length) }
            assertEquals(old.minZoom, shared.minZoom); assertEquals(old.maxZoom, shared.maxZoom)
            assertEquals(old.tileType, shared.tileType)
            for (x in 0..1) for (y in 0..1) {
                assertArrayEquals(old.get(1, x, y), shared.get(1, x, y))
            }
            assertArrayEquals(expectedA, shared.get(1, 0, 0))
            assertArrayEquals(expectedB, shared.get(1, 1, 0))
            old.get(1, 0, 0); old.get(1, 1, 0)
            for ((z, x, y) in listOf(Triple(0,0,0), Triple(2,0,0), Triple(1,-1,0), Triple(1,2,0), Triple(1,0,2))) {
                assertNull(old.get(z, x, y)); assertNull(shared.get(z, x, y))
            }
            assertEquals("leaf=$leaf internalGzip=$internalGzip tileGzip=$tileGzip", oldRanges, newRanges)
        }
    }
    @Test fun hilbertIdsMatchFrozenAlgorithmAtEveryLowZoomCoordinateAndHighZoomEdges() {
        for (z in 0..6) for (x in 0 until (1 shl z)) for (y in 0 until (1 shl z))
            assertEquals(LegacyPmtilesArchive.tileId(z, x, y), PmtilesArchive.tileId(z, x, y))
        for (z in 7..26) for (x in listOf(0, (1 shl z) / 2, (1 shl z) - 1)) for (y in listOf(0, (1 shl z) - 1))
            assertEquals(LegacyPmtilesArchive.tileId(z, x, y), PmtilesArchive.tileId(z, x, y))
    }
    @Test fun gzipLimitIsEnforcedWhileReading() {
        val bytes = gzip(ByteArray(1025))
        assertThrows(IOException::class.java) { PmtilesArchive.inflate(bytes, 1024) }
        assertArrayEquals(ByteArray(1025), PmtilesArchive.inflate(bytes, 1025))
    }
}

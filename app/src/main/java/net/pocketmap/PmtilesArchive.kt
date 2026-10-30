package net.pocketmap

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** PMTiles v3 reader over local files or persistently cached HTTP byte ranges. */
internal class PmtilesArchive(private val range: (Long, Int) -> ByteArray?) {
    internal data class Header(val rootOffset: Long, val rootLength: Int, val leafOffset: Long,
        val leafLength: Long, val tileOffset: Long, val tileLength: Long,
        val internalCompression: Int, val tileCompression: Int,
        val tileType: Int, val minZoom: Int, val maxZoom: Int)
    private data class Entry(val id: Long, val offset: Long, val length: Int, val run: Long)

    private val header: Header by lazy {
        val bytes = range(0, 127) ?: throw IOException("PMTiles header unavailable")
        require(bytes.size == 127 && bytes.copyOfRange(0, 7).decodeToString() == "PMTiles" &&
            bytes[7].toInt() == 3) { "Not a PMTiles v3 archive" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun uint64(index: Int): Long = b.getLong(index).also { require(it >= 0) }
        fun size(index: Int): Int = uint64(index)
            .also { require(it in 1..(4L * 1024 * 1024)) }.toInt()
        val internal = bytes[97].toInt() and 255
        val tile = bytes[98].toInt() and 255
        require(internal in 1..2 && tile in 1..2) { "Unsupported PMTiles compression" }
        Header(uint64(8), size(16), uint64(40), uint64(48), uint64(56), uint64(64),
            internal, tile, bytes[99].toInt() and 255,
            bytes[100].toInt() and 255, bytes[101].toInt() and 255).also {
            require(it.rootOffset + it.rootLength <= 16384)
            require(it.maxZoom <= 26 && it.minZoom <= it.maxZoom)
        }
    }

    val minZoom: Int get() = header.minZoom
    val maxZoom: Int get() = header.maxZoom
    val tileType: Int get() = header.tileType

    fun get(z: Int, x: Int, y: Int): ByteArray? {
        val h = header
        if (z !in h.minZoom..h.maxZoom || x < 0 || y < 0 ||
            x.toLong() >= (1L shl z) || y.toLong() >= (1L shl z)) return null
        val id = tileId(z, x, y)
        var offset = h.rootOffset
        var length = h.rootLength
        repeat(4) {
            val data = range(offset, length) ?: return null
            val entries = decodeDirectory(unpack(data, h.internalCompression))
            val candidate = entries.lastOrNull { it.id <= id } ?: return null
            if (candidate.run == 0L) {
                if (candidate.offset < 0 || candidate.offset > h.leafLength ||
                    candidate.length > h.leafLength - candidate.offset) return null
                offset = h.leafOffset + candidate.offset
                length = candidate.length
            } else {
                if (id - candidate.id >= candidate.run) return null
                if (candidate.offset < 0 || candidate.offset > h.tileLength ||
                    candidate.length > h.tileLength - candidate.offset) return null
                val bytes = range(h.tileOffset + candidate.offset, candidate.length) ?: return null
                return unpack(bytes, h.tileCompression)
            }
        }
        throw IOException("PMTiles directory depth exceeded")
    }

    private fun decodeDirectory(bytes: ByteArray): List<Entry> {
        var p = 0
        fun varint(): Long {
            var n = 0L
            var shift = 0
            while (true) {
                if (p >= bytes.size || shift > 63) throw IOException("Invalid PMTiles varint")
                val b = bytes[p++].toInt() and 255
                n = n or ((b and 127).toLong() shl shift)
                if ((b and 128) == 0) return n
                shift += 7
            }
        }
        val count = varint()
        if (count !in 1..100000) throw IOException("Invalid PMTiles directory count")
        val ids = LongArray(count.toInt())
        val runs = LongArray(count.toInt())
        val sizes = IntArray(count.toInt())
        val offsets = LongArray(count.toInt())
        for (i in ids.indices) ids[i] = (if (i == 0) 0 else ids[i - 1]) + varint()
        for (i in runs.indices) runs[i] = varint()
        for (i in sizes.indices) {
            val v = varint()
            if (v !in 1..(16L * 1024 * 1024)) throw IOException("Invalid PMTiles blob length")
            sizes[i] = v.toInt()
        }
        for (i in offsets.indices) {
            val v = varint()
            offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + sizes[i - 1] else v - 1
            if (offsets[i] < 0) throw IOException("Invalid PMTiles blob offset")
        }
        return ids.indices.map { Entry(ids[it], offsets[it], sizes[it], runs[it]) }
    }

    private fun unpack(data: ByteArray, compression: Int): ByteArray = when (compression) {
        1 -> data
        2 -> GZIPInputStream(ByteArrayInputStream(data)).use { stream ->
            stream.readBytes().also { require(it.size <= 16 * 1024 * 1024) }
        }
        else -> throw IOException("Unsupported PMTiles compression")
    }

    companion object {
        /** Zoom prefix plus Hilbert tile index (PMTiles specification). */
        fun tileId(z: Int, x: Int, y: Int): Long {
            require(z in 0..26 && x >= 0 && y >= 0 &&
                x.toLong() < (1L shl z) && y.toLong() < (1L shl z))
            var a = x.toLong()
            var b = y.toLong()
            var id = ((1L shl (2 * z)) - 1L) / 3L
            for (bit in z - 1 downTo 0) {
                val s = 1L shl bit
                val rx = if ((a and s) != 0L) 1L else 0L
                val ry = if ((b and s) != 0L) 1L else 0L
                id += (((3L * rx) xor ry) shl (2 * bit))
                if (ry == 0L) {
                    if (rx == 1L) {
                        a = s - 1 - a
                        b = s - 1 - b
                    }
                    val tmp = a; a = b; b = tmp
                }
            }
            return id
        }
    }
}

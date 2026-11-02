package net.pocketmap.iosprobe

import net.pocketmap.shared.pmtiles.SharedPmtilesArchive
import net.pocketmap.shared.pmtiles.PmtilesException

interface IosPmtilesRangeReader {
    fun read(offset: Long, length: Int): ByteArray?
    fun inflate(bytes: ByteArray, maxBytes: Int): ByteArray?
}

/** Native range/file and gzip I/O remain outside the shared archive traversal. */
class IosPmtilesArchive(reader: IosPmtilesRangeReader) {
    private val archive = SharedPmtilesArchive(reader::read) { bytes, max ->
        reader.inflate(bytes, max) ?: throw PmtilesException("Cannot inflate PMTiles gzip")
    }
    @Throws(Exception::class)
    fun metadata() = IosPmtilesMetadata(archive.minZoom, archive.maxZoom, archive.tileType)
    @Throws(Exception::class)
    fun tile(z: Int, x: Int, y: Int): ByteArray? = archive.get(z, x, y)
}
data class IosPmtilesMetadata(val minZoom: Int, val maxZoom: Int, val tileType: Int)

package net.pocketmap

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import net.pocketmap.shared.pmtiles.SharedPmtilesArchive
import net.pocketmap.shared.pmtiles.PmtilesException

/** Platform range/cache and bounded gzip adapters around the common v3 reader. */
internal class PmtilesArchive(range: (Long, Int) -> ByteArray?) {
    private val shared = SharedPmtilesArchive(range, ::inflate)
    val minZoom: Int get() = adapt { shared.minZoom }
    val maxZoom: Int get() = adapt { shared.maxZoom }
    val tileType: Int get() = adapt { shared.tileType }
    fun get(z: Int, x: Int, y: Int): ByteArray? = adapt { shared.get(z, x, y) }

    private fun <T> adapt(block: () -> T): T = try { block() }
        catch (error: PmtilesException) { throw IOException(error.message, error) }

    companion object {
        fun tileId(z: Int, x: Int, y: Int) = SharedPmtilesArchive.tileId(z, x, y)
        internal fun inflate(data: ByteArray, maxBytes: Int): ByteArray =
            GZIPInputStream(ByteArrayInputStream(data)).use { stream ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count > maxBytes - result.size()) throw IOException("PMTiles inflated blob too large")
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
    }
}

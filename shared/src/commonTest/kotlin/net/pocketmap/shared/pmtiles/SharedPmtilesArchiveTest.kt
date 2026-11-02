package net.pocketmap.shared.pmtiles

import kotlin.test.*

class SharedPmtilesArchiveTest {
    private fun archive(directory: ByteArray = byteArrayOf(1, 0, 1, 1, 1), tile: ByteArray = byteArrayOf(42),
                        edit: (ByteArray) -> Unit = {}): ByteArray {
        val header = ByteArray(127)
        "PMTiles".encodeToByteArray().copyInto(header); header[7] = 3
        fun number(offset: Int, value: Long) { repeat(8) { header[offset + it] = (value ushr (8 * it)).toByte() } }
        number(8,127); number(16,directory.size.toLong()); number(56,(127 + directory.size).toLong()); number(64,tile.size.toLong())
        header[97] = 1; header[98] = 1; header[99] = 1
        edit(header)
        return header + directory + tile
    }
    private fun reader(bytes: ByteArray, inflate: (ByteArray, Int) -> ByteArray = { _, _ -> error("Unexpected gzip") }) =
        SharedPmtilesArchive({ offset, length ->
            if (offset < 0 || offset > bytes.size.toLong() - length) null
            else bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }, inflate)
    @Test fun knownHilbertOrderAndSparseRuns() {
        assertEquals(0L, SharedPmtilesArchive.tileId(0,0,0))
        assertEquals(listOf(1L,2L,3L,4L), listOf(0 to 0, 0 to 1, 1 to 1, 1 to 0).map { (x,y) -> SharedPmtilesArchive.tileId(1,x,y) })
        val bytes = archive(byteArrayOf(2, 1, 3, 1, 1, 1, 1, 1, 0), byteArrayOf(10,20)) { it[100]=1; it[101]=1 }
        val r = reader(bytes)
        assertContentEquals(byteArrayOf(10), r.get(1,0,0))
        assertNull(r.get(1,0,1)); assertNull(r.get(1,1,1))
        assertContentEquals(byteArrayOf(20), r.get(1,1,0))
        assertNull(r.get(0,0,0)); assertNull(r.get(1,-1,0)); assertNull(r.get(1,2,0))
        assertFailsWith<IllegalArgumentException> { SharedPmtilesArchive.tileId(27,0,0) }
    }
    @Test fun malformedAndOverflowingInputsAreRejected() {
        for (dir in listOf(byteArrayOf(), byteArrayOf(0), ByteArray(10) { 0xff.toByte() },
            byteArrayOf(1,0,1,0,1), byteArrayOf(1,0,1,1,0), byteArrayOf(1,0,1))) {
            val bytes = if (dir.isEmpty()) archive().copyOf(12) else archive(dir)
            assertFails { reader(bytes).get(0,0,0) }
        }
        assertFails { reader(archive { it[7]=2 }).get(0,0,0) }
        assertFails { reader(archive { it[97]=3 }).get(0,0,0) }
        assertFails { reader(archive { it[101]=27 }).get(0,0,0) }
        assertFails { reader(archive { for (i in 56..63) it[i] = if (i==63) 0x7f.toByte() else 0xff.toByte() }).get(0,0,0) }
        val truncated = SharedPmtilesArchive({ offset, length -> if (offset == 0L) archive().copyOf(127) else ByteArray(length + 1) }, { _, _ -> byteArrayOf() })
        assertFailsWith<PmtilesException> { truncated.get(0,0,0) }
    }
    @Test fun platformInflaterGetsLimitAndOversizedOutputIsRejected() {
        var limit = 0
        val r = reader(archive { it[98]=2 }) { _, max -> limit = max; byteArrayOf(7) }
        assertContentEquals(byteArrayOf(7), r.get(0,0,0)); assertEquals(16*1024*1024, limit)
        assertFailsWith<PmtilesException> { reader(archive { it[98]=2 }) { _, _ -> ByteArray(16*1024*1024 + 1) }.get(0,0,0) }
    }
}

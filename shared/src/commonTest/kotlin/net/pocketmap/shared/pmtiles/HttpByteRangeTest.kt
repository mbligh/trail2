package net.pocketmap.shared.pmtiles
import kotlin.test.*

class HttpByteRangeTest {
    @Test fun exactRequestResponseAndLengthMustAgree() {
        assertEquals(1000L, HttpByteRange(127, 5).total("bytes 127-131/1000"))
        assertTrue(HttpByteRange.matches("bytes=127-131", "bytes 127-131/132", 5))
        for (header in listOf("bytes 126-130/1000", "bytes 127-132/1000", "bytes 127-131/131", "bytes 127-131/*", "bytes 127-131/-1", "bytes 127-131/9223372036854775808"))
            assertNull(HttpByteRange(127,5).total(header))
        assertFalse(HttpByteRange.matches("bytes=127-131", "bytes 127-131/1000", 4))
        assertFalse(HttpByteRange.matches("bytes=127-131,200-204", "bytes 127-131/1000", 5))
        assertNull(HttpByteRange(Long.MAX_VALUE, 1).total(""))
        assertNull(HttpByteRange(-1, 1).total(""))
    }
}

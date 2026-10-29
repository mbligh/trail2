package net.pocketmap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedHeadingMathTest {
    @Test fun headingsWrapCorrectly() {
        assertEquals(0f, HeadingMath.smooth(359f, 1f, 0.5f), 0.001f)
        assertEquals(0f, HeadingMath.smooth(1f, 359f, 0.5f), 0.001f)
        assertEquals(90f, HeadingMath.azimuth(1f, 0f)!!, 0.001f)
        assertNull(HeadingMath.azimuth(0.01f, 0.01f))
    }
}

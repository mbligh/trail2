package net.pocketmap.shared

import kotlin.test.*

class LocationReportTextTest {
    @Test fun fixedPrecisionCoordinatesAreUsedInBothTextAndSatelliteLink() {
        val calls = mutableListOf<Pair<Double, Int>>()
        val text = LocationReportText.message(12.0, -34.0) { value, digits ->
            calls.add(value to digits)
            if (value > 0) "12.000000" else "-34.000000"
        }
        assertEquals(listOf(12.0 to 6, -34.0 to 6), calls)
        assertEquals("12.000000, -34.000000\nhttps://www.google.com/maps/@?api=1&map_action=map&center=12.000000%2C-34.000000&zoom=18&basemap=satellite\n\n", text)
    }
}

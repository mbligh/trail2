package net.pocketmap.shared

import kotlin.test.*
import net.pocketmap.shared.recording.TrackSummaryText

class TrackSummaryTextTest {
    @Test fun durationRolloversAndExportLabelsStayConsistent() {
        val s = TrackStatistics.Summary(1000.0, 14400, 3659, 30.0, 20.0)
        val text = TrackSummaryText.description(s) { _, digits -> if (digits == 2) "1.00" else "height" }
        assertEquals("Distance: 1.00 km; Elapsed: 4h 00m 00s; Moving: 1h 00m 59s; Ascent: height m; Descent: height m", text)
        val zero = TrackSummaryText.description(s.copy(elapsedSeconds = 59, movingSeconds = 60)) { _, _ -> "0" }
        assertTrue(zero.contains("Elapsed: 0m 59s; Moving: 1m 00s"))
    }
    @Test fun unitConversionAndPrecisionArePassedToThePlatformFormatter() {
        val s = TrackStatistics.Summary(1609.344, 180, 60, 30.0, 20.0)
        val calls = mutableListOf<Pair<Double, Int>>()
        val imperial = TrackSummaryText.display(s, true) { value, digits -> calls.add(value to digits); "n" }
        assertEquals(listOf(2, 0, 0), calls.map { it.second })
        assertEquals(1.0, calls[0].first, 0.0000001)
        assertEquals(30 * 3.280839895, calls[1].first, 0.0000001)
        assertTrue(imperial.startsWith("Distance: n mi\nElapsed time: 3m 00s"))
        assertTrue(imperial.endsWith("Ascent: n ft\nDescent: n ft"))
        calls.clear()
        val metric = TrackSummaryText.display(s, false) { value, digits -> calls.add(value to digits); "n" }
        assertEquals(1.609344, calls[0].first, 0.0000001)
        assertEquals(30.0, calls[1].first)
        assertTrue(metric.contains("Distance: n km"))
        assertTrue(metric.endsWith("Descent: n m"))
    }
}

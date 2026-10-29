package net.pocketmap.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackStatisticsTest {
    private data class Fix(
        override val latitude: Double, override val longitude: Double,
        override val time: Long, override val altitude: Double?
    ) : TrackStatistics.Sample

    @Test fun sumsSeparateClimbsRatherThanStartToHighest() {
        val altitudes = listOf(0.0, 15.24, 30.48, 15.24, 0.0, 30.48, 60.96, 30.48, 0.0)
        val stats = TrackStatistics.summarize(altitudes.mapIndexed { i, h ->
            Fix(40.0 + i * 0.0002, -74.0, i * 30_000L, h)
        })
        assertEquals(91.44, stats.ascentMeters, 0.001)
        assertEquals(91.44, stats.descentMeters, 0.001)
        assertEquals(240, stats.elapsedSeconds)
        assertTrue(stats.distanceMeters > 0.0)
    }

    @Test fun ignoresGpsOutagesButPreservesTotalElapsedTime() {
        val stats = TrackStatistics.summarize(listOf(
            Fix(40.0, -74.0, 0L, 0.0),
            Fix(40.001, -74.0, 30_000L, 15.0),
            Fix(41.0, -74.0, 180_000L, 300.0),
            Fix(41.001, -74.0, 210_000L, 310.0)
        ))
        assertEquals(25.0, stats.ascentMeters, 0.001)
        assertEquals(0.0, stats.descentMeters, 0.001)
        assertEquals(210, stats.elapsedSeconds)
        assertTrue(stats.distanceMeters < 300.0)
    }

    @Test fun rejectsStationaryAltitudeSpike() {
        val heights = listOf(100.0, 103.0, 99.0, 102.0, 100.0, 145.0, 100.0, 101.0)
        val stats = TrackStatistics.summarize(heights.mapIndexed { i, h -> Fix(40.0, -74.0, i * 30_000L, h) })
        assertEquals(0.0, stats.ascentMeters, 0.01)
        assertEquals(0.0, stats.descentMeters, 0.01)
        assertEquals(0.0, stats.distanceMeters, 0.01)
        assertEquals(0L, stats.movingSeconds)
    }
}

package net.pocketmap.shared

import kotlin.math.PI

/** Platform-independent track analysis; the platform supplies location fixes and storage. */
object TrackStatistics {
    /** Read-only fields required from a recorded fix; Android and iOS can implement this. */
    interface Sample {
        val latitude: Double
        val longitude: Double
        val time: Long // Unix epoch milliseconds
        val altitude: Double? // metres; null if unavailable
    }

    /** Calculated from saved GPS fixes; elapsed is first-to-last fix, not service uptime. */
    data class Summary(
        val distanceMeters: Double, val elapsedSeconds: Long, val movingSeconds: Long,
        val ascentMeters: Double, val descentMeters: Double
    )
    private const val GAP_MS = 90_000L
    private const val MIN_MOVING_SPEED_MPS = 0.5
    // Horizontal GPS accuracy is not a measure of vertical accuracy.
    private const val ELEVATION_HYSTERESIS_METERS = 8.0

    private fun distanceMeters(a: Sample, b: Sample): Double {
        val lat1 = (a.latitude * (PI / 180.0))
        val lat2 = (b.latitude * (PI / 180.0))
        val deltaLat = lat2 - lat1
        val deltaLon = ((b.longitude - a.longitude) * (PI / 180.0))
        val h = kotlin.math.sin(deltaLat / 2).let { it * it } +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
            kotlin.math.sin(deltaLon / 2).let { it * it }
        return 2 * 6_371_000.0 * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Ignore GPS outages and out-of-order fixes when measuring distance/movement. */
    fun summarize(points: List<Sample>): Summary {
        var distance = 0.0
        var movingMs = 0L
        for ((a, b) in points.zipWithNext()) {
            val ms = b.time - a.time
            if (ms !in 1L..GAP_MS) continue
            if (!a.latitude.isFinite() || !a.longitude.isFinite() ||
                !b.latitude.isFinite() || !b.longitude.isFinite()) continue
            val meters = distanceMeters(a, b)
            distance += meters
            if (meters / (ms / 1000.0) >= MIN_MOVING_SPEED_MPS) movingMs += ms
        }
        var ascent = 0.0
        var descent = 0.0
        val run = mutableListOf<Sample>()
        fun flush() {
            if (run.size > 1) {
                val (up, down) = ascentDescent(run)
                ascent += up
                descent += down
            }
            run.clear()
        }
        var previous: Sample? = null
        for (point in points) {
            if (previous != null && point.time - previous.time !in 1L..GAP_MS) flush()
            if (point.altitude?.isFinite() == true) run.add(point) else flush()
            previous = point
        }
        flush()
        val elapsedMs = if (points.size >= 2) (points.last().time - points.first().time).coerceAtLeast(0L) else 0L
        return Summary(distance, elapsedMs / 1000, movingMs / 1000, ascent, descent)
    }

    /** Hysteresis ignores small altitude fluctuations; isolated stationary GPS spikes
     *  are suppressed before measuring distinct climbs and descents. */
    private fun ascentDescent(run: List<Sample>): Pair<Double, Double> {
        val heights = run.map { it.altitude!! }.toMutableList()
        for (i in 1 until run.lastIndex) {
            val previous = run[i - 1]
            val next = run[i + 1]
            val height = heights[i]
            val prevAltitude = previous.altitude!!
            val nextAltitude = next.altitude!!
            if (kotlin.math.abs(prevAltitude - nextAltitude) < 5.0 &&
                kotlin.math.abs(height - prevAltitude) > 12.0 &&
                kotlin.math.abs(height - nextAltitude) > 12.0 &&
                distanceMeters(previous, next) < 20.0) {
                heights[i] = (prevAltitude + nextAltitude) / 2.0
            }
        }
        var minimum = heights.first()
        var maximum = minimum
        var baseline = minimum
        var extreme = minimum
        var direction = 0 // 0 unknown, 1 ascending, -1 descending
        var up = 0.0
        var down = 0.0
        for (height in heights.drop(1)) {
            when (direction) {
                0 -> {
                    minimum = minOf(minimum, height)
                    maximum = maxOf(maximum, height)
                    if (maximum - minimum >= ELEVATION_HYSTERESIS_METERS) {
                        direction = if (height == maximum) 1 else -1
                        baseline = if (direction == 1) minimum else maximum
                        extreme = height
                    }
                }
                1 -> {
                    if (height > extreme) extreme = height
                    else if (extreme - height >= ELEVATION_HYSTERESIS_METERS) {
                        up += extreme - baseline
                        baseline = extreme
                        extreme = height
                        direction = -1
                    }
                }
                else -> {
                    if (height < extreme) extreme = height
                    else if (height - extreme >= ELEVATION_HYSTERESIS_METERS) {
                        down += baseline - extreme
                        baseline = extreme
                        extreme = height
                        direction = 1
                    }
                }
            }
        }
        if (direction == 1) up += extreme - baseline
        if (direction == -1) down += baseline - extreme
        return up.coerceAtLeast(0.0) to down.coerceAtLeast(0.0)
    }

}

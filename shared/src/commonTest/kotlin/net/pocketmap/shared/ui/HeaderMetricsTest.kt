package net.pocketmap.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class HeaderMetricsTest {
    private val metrics = HeaderMetrics("1.2mi", "3.4mph", "5min", "±6m", "17.0")

    @Test fun inactiveRecordingHidesOnlyTrackMetrics() {
        assertEquals(listOf("", "", "", "±6m", "17.0"), metrics.displayValues(recording = false))
    }

    @Test fun activeRecordingShowsAllMetrics() {
        assertEquals(listOf("1.2mi", "3.4mph", "5min", "±6m", "17.0"), metrics.displayValues(recording = true))
    }
}

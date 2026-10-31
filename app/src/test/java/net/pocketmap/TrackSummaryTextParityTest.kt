package net.pocketmap

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackSummaryTextParityTest {
    @Test fun commonSummaryMatchesExistingFormattingInBothUnitSystems() {
        for (distance in listOf(0.0, 12.345, 1609.344, 123456.789))
            for (seconds in listOf(0L, 59, 60, 3599, 3600, 14400)) {
                val current = RecordedTrack.Summary(distance, seconds, seconds / 2, 30.48, 91.44)
                val previous = LegacyRecordedTrack.Summary(distance, seconds, seconds / 2, 30.48, 91.44)
                assertEquals(LegacyRecordedTrack.description(previous), RecordedTrack.description(current))
                for (imperial in listOf(false, true)) assertEquals(
                    LegacyRecordedTrack.displaySummary(previous, imperial), RecordedTrack.displaySummary(current, imperial))
            }
    }
}

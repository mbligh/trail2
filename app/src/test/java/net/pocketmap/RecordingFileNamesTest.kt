package net.pocketmap

import java.io.File
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingFileNamesTest {
    private val newYork = ZoneId.of("America/New_York")
    private val timestamp = Instant.parse("2026-11-07T21:30:05.987Z")
    @get:Rule val temp = TemporaryFolder()

    @Test fun usesLocalTimeWithSecondPrecision() {
        assertEquals("Trail_2026-11-07_16-30-05", RecordingFileNames.format(timestamp, newYork))
        assertEquals("Trail_2026-11-08_06-30-05",
            RecordingFileNames.format(timestamp, ZoneId.of("Asia/Tokyo")))
    }

    @Test fun oldUtcNamesConvertForMenusAndExportsWithoutRenamingTheFile() {
        val old = File("Trail-2026-11-07T21-30-05.987Z.csv")
        assertEquals("Trail_2026-11-07_16-30-05", RecordingFileNames.displayName(old, newYork))
        assertEquals("Trail_2026-11-07_16-30-05",
            RecordingFileNames.displayName(File("Trail_2026-11-07_16-30-05.csv"), newYork))
    }

    @Test fun sessionsStartedInSameSecondNeverOverwriteEachOther() {
        val first = RecordingFileNames.createRecording(temp.root, timestamp, newYork)
        first.writeText("saved data")
        val second = RecordingFileNames.createRecording(temp.root, timestamp, newYork)
        assertEquals("Trail_2026-11-07_16-30-05.csv", first.name)
        assertEquals("Trail_2026-11-07_16-30-06.csv", second.name)
        assertEquals("saved data", first.readText())
    }
}

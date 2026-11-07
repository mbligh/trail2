package net.pocketmap

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** User-visible track names; saved GPS fix timestamps remain UTC-based epoch milliseconds. */
internal object RecordingFileNames {
    private val formatter = DateTimeFormatter.ofPattern("'Trail_'yyyy-MM-dd_HH-mm-ss", Locale.ROOT)
    // The former filenames used Instant.toString() with ':' replaced by '-'.
    private val legacyUtcName = Regex("""Trail-(\d{4}-\d{2}-\d{2})T(\d{2})-(\d{2})-(\d{2})(?:\.\d+)?Z""")

    fun format(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        formatter.format(at.atZone(zone))

    fun displayName(file: File, zone: ZoneId = ZoneId.systemDefault()): String {
        val name = file.nameWithoutExtension
        val match = legacyUtcName.matchEntire(name) ?: return name
        val (day, hour, minute, second) = match.destructured
        return try {
            format(Instant.parse("${day}T${hour}:${minute}:${second}Z"), zone)
        } catch (_: DateTimeParseException) {
            name
        }
    }

    /** Reserve each filename atomically so rapid restart cannot overwrite an earlier session. */
    fun createRecording(
        directory: File,
        at: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): File {
        var candidate = at
        repeat(86_400) {
            val file = File(directory, "${format(candidate, zone)}.csv")
            if (file.createNewFile()) return file
            candidate = candidate.plusSeconds(1)
        }
        error("Could not find a unique recording filename")
    }
}

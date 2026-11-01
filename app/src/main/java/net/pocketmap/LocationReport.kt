package net.pocketmap

import java.util.Locale
import net.pocketmap.shared.LocationReportText

object LocationReport {
    fun message(latitude: Double, longitude: Double): String =
        LocationReportText.message(latitude, longitude) { value, digits ->
            String.format(Locale.US, "%.$digits" + "f", value)
        }
}

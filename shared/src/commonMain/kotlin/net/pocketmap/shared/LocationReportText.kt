package net.pocketmap.shared

/** Platforms supply locale-independent fixed decimal formatting. */
object LocationReportText {
    fun message(latitude: Double, longitude: Double, format: (Double, Int) -> String): String {
        val lat = format(latitude, 6)
        val lon = format(longitude, 6)
        val url = "https://www.google.com/maps/@?api=1&map_action=map&center=$lat%2C$lon&zoom=18&basemap=satellite"
        return "$lat, $lon\n$url\n\n"
    }
}

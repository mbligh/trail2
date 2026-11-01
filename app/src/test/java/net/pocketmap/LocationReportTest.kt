package net.pocketmap

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

class LocationReportTest {
    @Test fun sharedReportMatchesFrozenAndroidPayload() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            for ((latitude, longitude) in listOf(40.6727254 to -74.7490004, -33.1234567 to 151.7654321,
                0.0 to -0.0, 90.0 to 180.0, -90.0 to -180.0, 0.0000004 to -0.0000006)) {
                val lat = String.format(Locale.US, "%.6f", latitude)
                val lon = String.format(Locale.US, "%.6f", longitude)
                val expected = "$lat, $lon\nhttps://www.google.com/maps/@?api=1&map_action=map&center=$lat%2C$lon&zoom=18&basemap=satellite\n\n"
                assertEquals(expected, LocationReport.message(latitude, longitude))
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun coordinatesAndSatelliteLinkMatchRegardlessOfLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val lines = LocationReport.message(40.6727254, -74.7490004).lines()
            assertEquals("40.672725, -74.749000", lines[0])
            val uri = URI(lines[1])
            assertEquals("https", uri.scheme)
            assertEquals("www.google.com", uri.host)
            val params = uri.rawQuery.split('&').associate {
                val pair = it.split('=', limit = 2)
                pair[0] to URLDecoder.decode(pair[1], "UTF-8")
            }
            assertEquals("40.672725,-74.749000", params["center"])
            assertEquals("satellite", params["basemap"])
            assertEquals("map", params["map_action"])
            assertEquals("1", params["api"])
        } finally { Locale.setDefault(previous) }
    }
}

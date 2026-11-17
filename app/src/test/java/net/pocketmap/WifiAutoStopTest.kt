package net.pocketmap

import org.junit.Assert.*
import org.junit.Test

class WifiAutoStopTest {
    @Test fun ssidMatchingHandlesAndroidQuotesButNotCaseDifferences() {
        assertEquals("Home Wi-Fi", WifiAutoStop.normalize("\"Home Wi-Fi\""))
        assertEquals("Home Wi-Fi", WifiAutoStop.normalize("Home Wi-Fi"))
        assertNull(WifiAutoStop.normalize("<unknown ssid>"))
        assertNull(WifiAutoStop.normalize("\"\""))
        assertNull(WifiAutoStop.normalize(null))
        assertFalse(WifiAutoStop.matches(null, "\"Home Wi-Fi\""))
        assertFalse(WifiAutoStop.matches("Home Wi-Fi", "\"home wi-fi\""))
        assertFalse(WifiAutoStop.matches("Home Wi-Fi", "\"Visitor\""))
        assertTrue(WifiAutoStop.matches("Home Wi-Fi", "\"Home Wi-Fi\""))
        assertTrue(WifiAutoStop.matches("Home Wi-Fi", "Home Wi-Fi"))
    }

    @Test fun callbackReplayIsNotAConnectionEvent() {
        val gate = WifiConnectionGate(listOf(1))
        assertFalse(gate.shouldStop(1, "Home", "\"Home\""))
        assertFalse(gate.shouldStop(2, "Home", "\"Visitor\""))
        assertFalse(gate.shouldStop(2, "Home", "<unknown ssid>"))
        assertTrue(gate.shouldStop(2, "Home", "\"Home\""))
        gate.onLost(1)
        assertTrue(gate.shouldStop(1, "Home", "\"Home\""))
    }
}

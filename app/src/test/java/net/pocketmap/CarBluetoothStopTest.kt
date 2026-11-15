package net.pocketmap

import org.junit.Assert.*
import org.junit.Test

class CarBluetoothStopTest {
    @Test fun selectionIsExplicitAndCaseInsensitive() {
        assertFalse(CarBluetoothStop.matches(null, "02:11:22:33:44:55"))
        assertFalse(CarBluetoothStop.matches("", "02:11:22:33:44:55"))
        assertFalse(CarBluetoothStop.matches("02:11:22:33:44:55", null))
        assertFalse(CarBluetoothStop.matches("02:11:22:33:44:55", "AA:11:22:33:44:55"))
        assertTrue(CarBluetoothStop.matches("02:11:22:33:44:55", "02:11:22:33:44:55"))
        assertTrue(CarBluetoothStop.matches("aa:bb:cc:dd:ee:ff", "AA:BB:CC:DD:EE:FF"))
    }
}

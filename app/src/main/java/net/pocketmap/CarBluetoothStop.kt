package net.pocketmap

/** Match only a deliberately selected paired car, never arbitrary Bluetooth devices. */
internal object CarBluetoothStop {
    const val PREF_KEY = "auto_stop_bluetooth_device"

    fun matches(selectedAddress: String?, connectedAddress: String?): Boolean =
        !selectedAddress.isNullOrBlank() &&
        !connectedAddress.isNullOrBlank() &&
        selectedAddress.equals(connectedAddress, ignoreCase = true)
}

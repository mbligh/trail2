package net.pocketmap

import android.net.wifi.WifiManager

/** Wi-Fi network names (SSIDs) are case-sensitive. Ignore redacted/unknown identities. */
internal object WifiAutoStop {
    const val PREF_KEY = "auto_stop_wifi_ssid"

    fun normalize(raw: String?): String? {
        if (raw == null || raw == WifiManager.UNKNOWN_SSID) return null
        val name = if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"')
            raw.substring(1, raw.length - 1) else raw
        return name.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }
    }

    fun matches(selected: String?, observed: String?): Boolean =
        !selected.isNullOrBlank() && selected == normalize(observed)
}

/** Discard the initial replay of already-connected Wi-Fi networks. */
internal class WifiConnectionGate<T>(alreadyConnected: Collection<T>) {
    private val initial = alreadyConnected.toMutableSet()

    fun shouldStop(network: T, selected: String?, observed: String?): Boolean =
        network !in initial && WifiAutoStop.matches(selected, observed)

    fun onLost(network: T) { initial.remove(network) }
}

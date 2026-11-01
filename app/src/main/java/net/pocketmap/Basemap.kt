package net.pocketmap

import android.content.Context
import org.json.JSONObject
import net.pocketmap.shared.config.SharedMapConfiguration

data class LatLng(val latitude: Double, val longitude: Double)
typealias Basemap = net.pocketmap.shared.config.ConfiguredBasemap
data class MapOverlay(val id: String, val name: String, val defaultVisible: Boolean, val definition: JSONObject)

/** Android adapts bundled assets and the existing tile/cache routing to common config. */
class MapConfiguration(readAsset: (String) -> String) {
    constructor(context: Context) : this({ path -> context.assets.open(path).bufferedReader().use { it.readText() } })
    private val shared = SharedMapConfiguration(readAsset)
    val sources = JSONObject(shared.sources.toString())
    val basemaps: List<Basemap> = shared.basemaps
    val overlays = shared.overlays.map { MapOverlay(it.id, it.name, it.defaultVisible, JSONObject(it.definition.toString())) }

    internal fun styleJson(basemap: Basemap, enabled: Set<String>, route: (String, JSONObject) -> JSONObject): JSONObject =
        JSONObject(shared.style(basemap, enabled) { id, source -> route(id, JSONObject(source)).toString() })

    fun style(basemap: Basemap, enabled: Set<String>, tiles: TileRepository): JSONObject =
        styleJson(basemap, enabled, tiles::styleSource)
}

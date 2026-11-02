package net.pocketmap.iosprobe

import net.pocketmap.shared.config.SharedMapConfiguration

interface IosMapAssetReader { fun read(path: String): String? }

/** Bundled configuration is shared; native code retains resource/cache routing. */
class IosMapConfiguration(reader: IosMapAssetReader) {
    private val config = SharedMapConfiguration { path -> requireNotNull(reader.read(path)) { "Missing map asset: $path" } }
    val basemaps = config.basemaps.map { IosBasemapOption(it.name, it.label, it.attribution, it.attributionUrl ?: "") }
    val layers = config.overlays.map { IosLayerOption(it.id, it.name, it.defaultVisible) }
    val basemapNames = config.basemaps.map { it.name }
    @Throws(Exception::class)
    fun styleWithLayers(name: String, enabled: List<String>): String = config.style(config.basemaps.first { it.name == name }, enabled.toSet()) { _, source -> source }
    @Throws(Exception::class)
    fun style(name: String): String = config.style(config.basemaps.first { it.name == name }, emptySet()) { _, source -> source }
}

data class IosBasemapOption(val id: String, val label: String, val attribution: String, val attributionUrl: String)

data class IosLayerOption(val id: String, val label: String, val defaultVisible: Boolean)

package net.pocketmap.iosprobe

import net.pocketmap.shared.config.SharedMapConfiguration

interface IosMapAssetReader { fun read(path: String): String? }

/** Bundled configuration is shared; native code retains resource/cache routing. */
class IosMapConfiguration(reader: IosMapAssetReader) {
    private val config = SharedMapConfiguration { path -> requireNotNull(reader.read(path)) { "Missing map asset: $path" } }
    val basemapNames = config.basemaps.map { it.name }
    @Throws(Exception::class)
    fun style(name: String): String = config.style(config.basemaps.first { it.name == name }, emptySet()) { _, source -> source }
}

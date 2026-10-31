package net.pocketmap.iosprobe

import net.pocketmap.shared.trails.SharedTrailKml

/** Keeps Swift I/O separate from the exact common importer and style model. */
class IosTrailBridge {
    @Throws(Exception::class)
    fun parse(bytes: ByteArray): IosTrailDocument = IosTrailDocument(SharedTrailKml.parse(bytes))
}

class IosTrailDocument internal constructor(internal val document: SharedTrailKml.Document) {
    val name = document.name
    val featureCount = document.features.size
    val geoJson = net.pocketmap.shared.trails.SharedTrailGeoJson.encode(document)
}

internal fun iconKey(f: SharedTrailKml.Feature) = net.pocketmap.shared.trails.SharedTrailGeoJson.iconKey(f)

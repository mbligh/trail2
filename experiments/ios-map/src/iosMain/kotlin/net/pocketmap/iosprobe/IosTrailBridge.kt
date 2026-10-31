package net.pocketmap.iosprobe

import kotlinx.serialization.json.*
import net.pocketmap.shared.trails.SharedTrailKml

/** Keeps Swift I/O separate from the exact common importer and style model. */
class IosTrailBridge {
    @Throws(Exception::class)
    fun parse(bytes: ByteArray): IosTrailDocument = IosTrailDocument(SharedTrailKml.parse(bytes))
}

class IosTrailDocument internal constructor(internal val document: SharedTrailKml.Document) {
    val name = document.name
    val featureCount = document.features.size
    val geoJson = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            document.features.forEach { f -> add(buildJsonObject {
                put("type", "Feature")
                putJsonObject("properties") {
                    put("name", f.name); put("lineColor", rgb(f.style.lineColor))
                    put("lineOpacity", if (f.kind == "Polygon" && !f.style.outline) 0.0 else alpha(f.style.lineColor))
                    put("fillColor", rgb(f.style.fillColor)); put("fillOpacity", if (f.style.fill) alpha(f.style.fillColor) else 0.0)
                    put("width", f.style.width.toInt().coerceAtLeast(2)); put("icon", iconKey(f))
                    put("scale", f.style.iconScale); put("anchor", if (f.style.iconId == "1763") "center" else "bottom")
                }
                putJsonObject("geometry") {
                    put("type", f.kind)
                    put("coordinates", when (f.kind) {
                        "Point" -> coordinate(f.points.first())
                        "Polygon" -> JsonArray((listOf(f.points) + f.holes).map { ring ->
                            JsonArray((if (ring.first() == ring.last()) ring else ring + ring.first()).map(::coordinate))
                        })
                        else -> JsonArray(f.points.map(::coordinate))
                    })
                }
            }) }
        }
    }.toString()
}

internal fun iconKey(f: SharedTrailKml.Feature) = "trail-icon-${f.style.iconId}-${f.style.iconColor}"
private fun coordinate(p: SharedTrailKml.Coordinate) = JsonArray(listOf(JsonPrimitive(p.longitude), JsonPrimitive(p.latitude)))
private fun rgb(value: Int) = "#" + (value and 0xffffff).toString(16).padStart(6, '0')
private fun alpha(value: Int) = (value ushr 24) / 255.0

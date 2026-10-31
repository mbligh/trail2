package net.pocketmap


/** Parses the geometry and local styles in a My Maps KML export; never loads external resources. */
object TrailKml {
    const val MAX_BYTES = 5 * 1024 * 1024
    data class Coordinate(val latitude: Double, val longitude: Double)
    data class Style(val lineColor: Int = 0xff009d57.toInt(), val width: Float = 4f,
                     val iconId: String = "", val iconColor: Int = -1, val iconScale: Float = 1f,
                     val fillColor: Int = 0x33009d57, val fill: Boolean = true, val outline: Boolean = true)
    data class Feature(val name: String, val kind: String, val points: List<Coordinate>,
                       val holes: List<List<Coordinate>>, val style: Style)
    data class Document(val name: String, val features: List<Feature>)

    fun parse(bytes: ByteArray): Document {
        val parsed = net.pocketmap.shared.trails.SharedTrailKml.parse(bytes)
        fun point(p: net.pocketmap.shared.trails.SharedTrailKml.Coordinate) = Coordinate(p.latitude, p.longitude)
        return Document(parsed.name, parsed.features.map { feature ->
            val s = feature.style
            Feature(feature.name, feature.kind, feature.points.map(::point),
                feature.holes.map { ring -> ring.map(::point) },
                Style(s.lineColor, s.width, s.iconId, s.iconColor, s.iconScale, s.fillColor, s.fill, s.outline))
        })
    }
    internal fun shared(document: Document): net.pocketmap.shared.trails.SharedTrailKml.Document {
        fun point(p: Coordinate) = net.pocketmap.shared.trails.SharedTrailKml.Coordinate(p.latitude, p.longitude)
        return net.pocketmap.shared.trails.SharedTrailKml.Document(document.name, document.features.map { f ->
            val s = f.style
            net.pocketmap.shared.trails.SharedTrailKml.Feature(f.name, f.kind, f.points.map(::point),
                f.holes.map { it.map(::point) }, net.pocketmap.shared.trails.SharedTrailKml.Style(
                    s.lineColor, s.width, s.iconId, s.iconColor, s.iconScale, s.fillColor, s.fill, s.outline))
        })
    }
    internal fun color(text: String?, fallback: Int) = net.pocketmap.shared.trails.SharedTrailKml.color(text, fallback)
}

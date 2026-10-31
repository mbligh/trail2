package net.pocketmap.shared.trails

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.ExperimentalXmlUtilApi
import nl.adaptivity.xmlutil.core.KtXmlReader

/** Parses the geometry and local styles in a My Maps KML export; never loads external resources. */
object SharedTrailKml {
    const val MAX_BYTES = 5 * 1024 * 1024
    data class Coordinate(val latitude: Double, val longitude: Double)
    data class Style(val lineColor: Int = 0xff009d57.toInt(), val width: Float = 4f,
                     val iconId: String = "", val iconColor: Int = -1, val iconScale: Float = 1f,
                     val fillColor: Int = 0x33009d57, val fill: Boolean = true, val outline: Boolean = true)
    data class Feature(val name: String, val kind: String, val points: List<Coordinate>,
                       val holes: List<List<Coordinate>>, val style: Style)
    data class Document(val name: String, val features: List<Feature>)

    @Throws(Exception::class)
    fun parse(bytes: ByteArray): Document {
        require(bytes.size <= MAX_BYTES) { "Map file is too large" }
        val xml = bytes.decodeToString()
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) { "External XML entities are not supported" }
        val root = readXml(xml)
        require(root.name == "kml") { "Expected a KML map" }
        require(root.descendants("NetworkLink").isEmpty()) { "Use an export containing map data, not a network link" }
        val styles = root.descendants("Style").filter { it.getAttribute("id").isNotEmpty() }.associateBy { it.getAttribute("id") }
        val maps = root.descendants("StyleMap").associate { m ->
            m.getAttribute("id") to (m.children("Pair").firstOrNull { it.text("key") == "normal" }?.text("styleUrl") ?: "")
        }
        fun resolve(url: String): XmlNode? {
            var id = url.removePrefix("#")
            repeat(8) { if (maps.containsKey(id)) id = maps.getValue(id).removePrefix("#") }
            return styles[id]
        }
        val features = mutableListOf<Feature>()
        var totalPoints = 0
        val folders = root.descendants("Folder").filter { it.text("name") == "Trails" }
        require(folders.isNotEmpty()) { "Trails layer is missing" }
        for (placemark in folders.flatMap { it.descendants("Placemark") }.distinct()) {
            val style = readStyle(placemark.children("Style").firstOrNull() ?: resolve(placemark.text("styleUrl")))
            val name = placemark.text("name").ifBlank { "Trail" }
            for (kind in listOf("LineString", "Point", "Polygon")) {
                for (geometry in placemark.descendants(kind)) {
                    val points = if (kind == "Polygon") coordinates(geometry.children("outerBoundaryIs").firstOrNull()?.descendants("coordinates")?.firstOrNull()?.textContent ?: "")
                                 else coordinates(geometry.descendants("coordinates").firstOrNull()?.textContent ?: "")
                    require(points.size >= when(kind) { "Point" -> 1; "Polygon" -> 3; else -> 2 }) { "Incomplete map geometry" }
                    val holes = geometry.children("innerBoundaryIs").map { coordinates(it.descendants("coordinates").firstOrNull()?.textContent ?: "") }
                    require(holes.all { it.size >= 3 }) { "Incomplete polygon hole" }
                    totalPoints += points.size + holes.sumOf { it.size }
                    require(totalPoints <= 200_000) { "Too many map coordinates" }
                    features.add(Feature(name, kind, points, holes, style))
                }
            }
        }
        require(features.isNotEmpty()) { "No trails or places in map" }
        return Document(root.children("Document").firstOrNull()?.text("name") ?: "Trails", features)
    }
    private fun coordinates(text: String): List<Coordinate> = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.map {
        val parts = it.split(',')
        require(parts.size >= 2) { "Invalid coordinate" }
        val lon = parts[0].toDouble(); val lat = parts[1].toDouble()
        require(lon.isFinite() && lat.isFinite() && lon in -180.0..180.0 && lat in -90.0..90.0) { "Coordinate out of range" }
        Coordinate(lat, lon)
    }
    private fun readStyle(element: XmlNode?): Style {
        if (element == null) return Style()
        val line = element.children("LineStyle").firstOrNull()
        val poly = element.children("PolyStyle").firstOrNull()
        val icon = element.children("IconStyle").firstOrNull()
        val iconId = Regex("icon-(\\d+)").find(element.getAttribute("id"))?.groupValues?.get(1) ?: ""
        return Style(color(line?.text("color"), 0xff009d57.toInt()),
            (line?.text("width")?.toFloatOrNull()?.takeIf { it.isFinite() } ?: 4f).coerceIn(1f, 12f),
            iconId, color(icon?.text("color"), -1), (icon?.text("scale")?.toFloatOrNull()?.takeIf { it.isFinite() } ?: 1f).coerceIn(0.5f, 2f),
            color(poly?.text("color"), 0x33009d57), poly?.text("fill") != "0", poly?.text("outline") != "0")
    }
    fun color(text: String?, fallback: Int): Int {
        if (text == null || !text.matches(Regex("[0-9a-fA-F]{8}"))) return fallback
        // KML uses AABBGGRR; Android uses AARRGGBB.
        return (text.substring(0, 2) + text.substring(6, 8) + text.substring(4, 6) + text.substring(2, 4)).toLong(16).toInt()
    }
    // A small bounded tree keeps the established DOM-style selection rules,
    // while xmlutil's same strict reader runs on Android, JVM and iOS.
    private class XmlNode(val name: String, val id: String) {
        val content = mutableListOf<Any>()
        fun getAttribute(name: String) = if (name == "id") id else ""
        val textContent: String get() = buildString {
            for (part in content) append(if (part is XmlNode) part.textContent else part as String)
        }
    }
    private fun XmlNode.children(name: String) = content.filterIsInstance<XmlNode>().filter { it.name == name }
    private fun XmlNode.text(name: String) = children(name).firstOrNull()?.textContent?.trim() ?: ""
    private fun XmlNode.descendants(name: String): List<XmlNode> = buildList {
        for (child in content.filterIsInstance<XmlNode>()) {
            if (child.name == name) add(child)
            addAll(child.descendants(name))
        }
    }

    @OptIn(ExperimentalXmlUtilApi::class)
    private fun readXml(xml: String): XmlNode {
        val reader = KtXmlReader(xml, expandEntities = true, relaxed = false)
        val stack = mutableListOf<XmlNode>()
        var root: XmlNode? = null
        var nodes = 0
        try {
            while (reader.hasNext()) when (reader.next()) {
                EventType.START_ELEMENT -> {
                    require(stack.size < 128 && ++nodes <= 250_000) { "Map XML is too complex" }
                    val node = XmlNode(reader.localName, reader.getAttributeValue("", "id") ?: "")
                    if (stack.isEmpty()) {
                        require(root == null) { "Multiple map roots" }
                        root = node
                    } else stack.last().content.add(node)
                    stack.add(node)
                }
                EventType.END_ELEMENT -> {
                    require(stack.isNotEmpty() && stack.last().name == reader.localName) { "Mismatched map element" }
                    stack.removeAt(stack.lastIndex)
                }
                EventType.TEXT, EventType.CDSECT, EventType.IGNORABLE_WHITESPACE, EventType.ENTITY_REF -> {
                    if (stack.isNotEmpty()) stack.last().content.add(reader.text)
                    else require(reader.text.isBlank()) { "Text outside map root" }
                }
                EventType.DOCDECL -> error("External XML entities are not supported")
                else -> Unit
            }
            require(stack.isEmpty()) { "Incomplete map XML" }
            return requireNotNull(root) { "Expected a KML map" }
        } finally { reader.close() }
    }
}

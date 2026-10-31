import Foundation

/// Runs inside the simulator app against the actual linked Kotlin writer.
/// Foundation's independent XML parser checks structure and decoded field values.
enum RecordingExportValidation {
    private final class Node {
        let name: String
        let namespace: String?
        let attributes: [String: String]
        var text = ""
        var children = [Node]()
        init(_ name: String, _ namespace: String?, _ attributes: [String: String]) { self.name = name; self.namespace = namespace; self.attributes = attributes }
        func all(_ name: String) -> [Node] {
            (self.name == name ? [self] : []) + children.flatMap { $0.all(name) }
        }
        func value(_ name: String) -> String { children.first { $0.name == name }?.text ?? "" }
    }
    private final class Reader: NSObject, XMLParserDelegate {
        var root: Node?
        var stack = [Node]()
        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                    qualifiedName qName: String?, attributes attributeDict: [String: String]) {
            let node = Node(elementName, namespaceURI, attributeDict)
            if let parent = stack.last { parent.children.append(node) } else { root = node }
            stack.append(node)
        }
        func parser(_ parser: XMLParser, foundCharacters string: String) { stack.last?.text += string }
        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
            _ = stack.popLast()
        }
    }
    private enum Failure: Error { case invalid(String) }
    private static func require(_ value: Bool, _ message: String) throws {
        if !value { throw Failure.invalid(message) }
    }
    private static func read(_ xml: String) throws -> Node {
        let reader = Reader()
        let parser = XMLParser(data: Data(xml.utf8))
        parser.shouldProcessNamespaces = true
        parser.delegate = reader
        guard parser.parse(), let root = reader.root else { throw Failure.invalid("Cannot parse export") }
        return root
    }
    private static func milliseconds(_ text: String) -> Int64? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var date = formatter.date(from: text)
        if date == nil { formatter.formatOptions = [.withInternetDateTime]; date = formatter.date(from: text) }
        return date.map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) }
    }

    static func verify(gpx: String, kml: String, points: [RecordedFix], pins: [RecordedPin], summary: TrackExportStatistics) throws {
        let g = try read(gpx), k = try read(kml)
        try require(g.name == "gpx" && g.namespace == "http://www.topografix.com/GPX/1/1", "GPX namespace")
        try require(k.name == "kml" && k.namespace == "http://www.opengis.net/kml/2.2", "KML namespace")
        try require(g.all("trk").first?.value("name") == "Fixture & <woods>", "GPX escaped name")
        try require(k.all("Document").first?.value("name") == "Fixture & <woods>", "KML escaped name")
        let fixes = g.all("trkpt"), waypoints = g.all("wpt")
        try require(fixes.count == points.count && waypoints.count == pins.count, "Export fix counts")
        try require(g.children.first?.name == "wpt", "Waypoints precede tracks")
        try require(g.all("trkseg").count == 2 && k.all("LineString").count == 2, "GPS gap boundaries")
        for (node, fix) in zip(fixes, points) {
            try require(Double(node.attributes["lat"] ?? "") == fix.latitude && Double(node.attributes["lon"] ?? "") == fix.longitude, "GPX coordinates")
            try require(milliseconds(node.value("time")) == fix.time, "Millisecond timestamps")
            try require(Double(node.value("ele")) == fix.altitude, "GPX elevation")
        }
        for (node, pin) in zip(waypoints, pins) {
            try require(node.value("desc") == pin.text && milliseconds(node.value("time")) == pin.fix.time, "GPX pin note/time")
        }
        let marker = k.all("Placemark").first { $0.value("name") == "Pin" }
        try require(marker?.value("description") == pins.first?.text, "KML pin note")
        try require(marker?.value("styleUrl") == "#recorded-pin", "KML blue pin style")
        try require(marker?.all("when").first.flatMap { milliseconds($0.text) } == pins.first?.fix.time, "KML pin timestamp")
        let good = k.all("Style").first { $0.attributes["id"] == "good" }
        try require(good?.all("color").first?.text == "ff00aa00", "Accuracy line color")
        try require(k.all("styleUrl").filter { $0.text == "#good" }.count == 2, "Accuracy segments")
        let values: [(String, Double)] = [("distanceMeters", summary.distanceMeters), ("elapsedSeconds", Double(summary.elapsedSeconds)),
            ("movingSeconds", Double(summary.movingSeconds)), ("ascentMeters", summary.ascentMeters), ("descentMeters", summary.descentMeters)]
        for (name, expected) in values {
            try require(g.all(name).first { $0.namespace == "https://mbligh.org/trail" }.flatMap { Double($0.text) } == expected, "GPX summary " + name)
            let data = k.all("Data").first { $0.attributes["name"] == name }
            try require(data.flatMap { Double($0.value("value")) } == expected, "KML summary " + name)
        }
    }
}

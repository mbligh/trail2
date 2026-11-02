// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "TrailPlatform",
    platforms: [.iOS("18.5"), .macOS(.v14)],
    products: [.library(name: "TrailPlatform", targets: ["TrailPlatform"])],
    targets: [
        .target(name: "TrailPlatform", linkerSettings: [.linkedLibrary("sqlite3"), .linkedLibrary("z")]),
        .testTarget(name: "TrailPlatformTests", dependencies: ["TrailPlatform"])
    ]
)

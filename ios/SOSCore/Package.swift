// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "SOSCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "SOSCore", targets: ["SOSCore"])],
    targets: [
        .target(name: "SOSCore"),
        .testTarget(name: "SOSCoreTests", dependencies: ["SOSCore"]),
    ]
)

// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorPlugingpsWahana",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CapacitorPlugingpsWahana",
            targets: ["WahanaPluginPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "WahanaPluginPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/WahanaPluginPlugin"),
        .testTarget(
            name: "WahanaPluginPluginTests",
            dependencies: ["WahanaPluginPlugin"],
            path: "ios/Tests/WahanaPluginPluginTests")
    ]
)
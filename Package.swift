// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "TimeWhisper",
    platforms: [.macOS("15.0")],
    products: [.library(name: "TimeWhisper", targets: ["TimeWhisper"])],
    dependencies: [.package(url: "https://github.com/huggingface/swift-transformers", from: "1.1.8")],
    targets: [
        .binaryTarget(name: "TimeWhisperCore", url: "https://github.com/dayedge/time-whisper/releases/download/0.7.0/TimeWhisperCore-0.7.0.xcframework.zip", checksum: "81c6a8d6833bdb4fd8855a87a1b72e6986b87ee2436d7d534d596747390faf9f"),
        .target(name: "TimeWhisper", dependencies: ["TimeWhisperCore",
            .product(name: "Tokenizers", package: "swift-transformers"),
            .product(name: "Hub", package: "swift-transformers")], path: "swift/Sources/TimeWhisper"),
        .executableTarget(name: "TimeWhisperExample", dependencies: ["TimeWhisper"], path: "examples/swift"),
        .executableTarget(name: "TimeWhisperEvaluate", dependencies: ["TimeWhisper"], path: "examples/swift-evaluate"),
        .testTarget(name: "TimeWhisperTests", dependencies: ["TimeWhisper"], path: "swift/Tests/TimeWhisperTests")
    ]
)

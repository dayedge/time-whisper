// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "TimeWhisper",
    platforms: [.macOS("15.0")],
    products: [.library(name: "TimeWhisper", targets: ["TimeWhisper"])],
    dependencies: [.package(url: "https://github.com/huggingface/swift-transformers", from: "1.1.8")],
    targets: [
        .binaryTarget(name: "TimeWhisperCore", url: "https://github.com/dayedge/time-whisper/releases/download/0.7.1/TimeWhisperCore-0.7.1.xcframework.zip", checksum: "b32a1a7cb368ee6cf1a4d9c47055b9c625eefb8f7d6a257431c3611fc5e8a576"),
        .target(name: "TimeWhisper", dependencies: ["TimeWhisperCore",
            .product(name: "Tokenizers", package: "swift-transformers"),
            .product(name: "Hub", package: "swift-transformers")], path: "swift/Sources/TimeWhisper"),
        .executableTarget(name: "TimeWhisperExample", dependencies: ["TimeWhisper"], path: "examples/swift"),
        .executableTarget(name: "TimeWhisperEvaluate", dependencies: ["TimeWhisper"], path: "examples/swift-evaluate"),
        .testTarget(name: "TimeWhisperTests", dependencies: ["TimeWhisper"], path: "swift/Tests/TimeWhisperTests")
    ]
)

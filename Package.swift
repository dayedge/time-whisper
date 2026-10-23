// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "TimeWhisper",
    platforms: [.macOS("15.0")],
    products: [
        .library(name: "TimeWhisper", targets: ["TimeWhisper"]),
    ],
    dependencies: [
        // 1.1.8 or later: earlier versions split text into the wrong pieces with the vocabulary export.py trims.
        .package(url: "https://github.com/huggingface/swift-transformers", from: "1.1.8"),
    ],
    targets: [
        .binaryTarget(name: "TimeWhisperCore", path: "build/XCFrameworks/release/TimeWhisperCore.xcframework"),
        // The tagger is a Core ML model (training/export.py writes it); swift-transformers splits text into its word pieces.
        .target(name: "TimeWhisper", dependencies: ["TimeWhisperCore", .product(name: "Tokenizers", package: "swift-transformers"),
                                                    .product(name: "Hub", package: "swift-transformers")],
                path: "swift/Sources/TimeWhisper"),
        .executableTarget(name: "TimeWhisperExample", dependencies: ["TimeWhisper"], path: "examples/swift"),
        .executableTarget(name: "TimeWhisperEvaluate", dependencies: ["TimeWhisper"], path: "examples/swift-evaluate"),
        .testTarget(name: "TimeWhisperTests", dependencies: ["TimeWhisper"], path: "swift/Tests/TimeWhisperTests"),
    ]
)

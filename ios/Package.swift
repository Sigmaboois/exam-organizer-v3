// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "ExamOrganizer",
    platforms: [
        .iOS(.v17),
    ],
    products: [
        .library(
            name: "ExamOrganizer",
            targets: ["ExamOrganizer"]
        ),
    ],
    targets: [
        .target(
            name: "ExamOrganizer"
        ),
    ],
    // Swift 5 mode: the app does its slow work on background tasks and hands results
    // back to the main actor itself, so Swift 6's strict checking isn't needed
    swiftLanguageModes: [.v5]
)

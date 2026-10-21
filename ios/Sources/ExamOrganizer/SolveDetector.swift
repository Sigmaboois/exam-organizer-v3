import Foundation
import PDFKit

/// Has a question paper been written on, and on which pages?
struct SolveResult: Codable, Equatable {
    var solved: Bool
    var pages: [Int]       // 1-based pages with writing
    var method: String     // "compared" (reliable) or "estimated" (no clean original to compare with)
    var pageCount: Int
}

/// The student's PDF is drawn page by page and compared with Cambridge's clean original:
/// anything darker than the original (pen strokes, typed answers) is ink. This works with any
/// note-taking app. Without the original, a weaker check looks for coloured ink and annotations.
enum SolveDetector {
    static let renderScale: CGFloat = 1.0     // 72 dpi is plenty to spot handwriting
    static let inkDarkerBy = 70               // grey levels a pixel must darken by to count as ink
    static let pageInkRatio = 0.0015          // 0.15% of a page darkened = something was written
    static let colourSaturation = 90          // fallback: how colourful a pixel must be to count as ink
    static let annotationTypes: Set<String> = ["Ink", "FreeText", "Square", "Circle", "Line", "Polygon",
                                               "PolyLine", "Stamp", "Text"]

    /// Compare against the clean original if there is one, otherwise estimate.
    static func detect(student: URL, clean: URL?) throws -> SolveResult {
        if let clean, let result = try? compare(student: student, clean: clean) {
            return result
        }
        return try estimate(student: student)
    }

    static func compare(student studentURL: URL, clean cleanURL: URL) throws -> SolveResult {
        let student = PDFTools.pages(of: try PDFTools.open(studentURL))
        let clean = PDFTools.pages(of: try PDFTools.open(cleanURL))
        var pages: [Int] = []
        for (index, page) in student.enumerated() {
            let number = index + 1
            if index >= clean.count {
                pages.append(number)   // extra pages added by the student (e.g. working paper)
                continue
            }
            // Both pages are drawn at the clean page's size, so a re-saved copy lines up
            let size = clean[index].bounds(for: .mediaBox).size
            let width = max(1, Int(size.width * renderScale))
            let height = max(1, Int(size.height * renderScale))
            let studentPixels = PDFTools.greyPixels(of: page, width: width, height: height)
            let cleanPixels = PDFTools.greyPixels(of: clean[index], width: width, height: height)
            if inkRatio(student: studentPixels, clean: cleanPixels, width: width, height: height) >= pageInkRatio {
                pages.append(number)
            }
        }
        // Writing only a name on the cover doesn't count as solving it
        return SolveResult(solved: pages.contains { $0 > 1 }, pages: pages, method: "compared",
                           pageCount: student.count)
    }

    /// Fraction of the page that is darker in the student's copy than in the original.
    static func inkRatio(student: [UInt8], clean: [UInt8], width: Int, height: Int) -> Double {
        // Let the original's printing spread by a couple of pixels, so a slightly
        // shifted re-save of the same paper doesn't look like writing
        let spread = minFilter(clean, width: width, height: height, radius: 2)
        let count = min(student.count, spread.count)
        var ink = 0
        student.withUnsafeBufferPointer { studentBuffer in
            spread.withUnsafeBufferPointer { spreadBuffer in
                for index in 0..<count where Int(spreadBuffer[index]) - Int(studentBuffer[index]) >= inkDarkerBy {
                    ink += 1
                }
            }
        }
        return Double(ink) / Double(max(1, width * height))
    }

    /// Darkest value in a (2·radius+1)² square around each pixel: two quick passes.
    static func minFilter(_ pixels: [UInt8], width: Int, height: Int, radius: Int) -> [UInt8] {
        guard width > 0, height > 0, pixels.count >= width * height else { return pixels }
        var across = pixels
        var result = pixels
        pixels.withUnsafeBufferPointer { source in
            across.withUnsafeMutableBufferPointer { across in
                for y in 0..<height {
                    let row = y * width
                    for x in 0..<width {
                        var darkest: UInt8 = 255
                        for dx in max(0, x - radius)...min(width - 1, x + radius) where source[row + dx] < darkest {
                            darkest = source[row + dx]
                        }
                        across[row + x] = darkest
                    }
                }
            }
        }
        across.withUnsafeBufferPointer { across in
            result.withUnsafeMutableBufferPointer { result in
                for y in 0..<height {
                    for x in 0..<width {
                        var darkest: UInt8 = 255
                        for dy in max(0, y - radius)...min(height - 1, y + radius) where across[dy * width + x] < darkest {
                            darkest = across[dy * width + x]
                        }
                        result[y * width + x] = darkest
                    }
                }
            }
        }
        return result
    }

    /// No original to compare with: look for coloured ink and PDF annotations.
    static func estimate(student url: URL) throws -> SolveResult {
        let pages = PDFTools.pages(of: try PDFTools.open(url))
        var found = Set<Int>()
        for (index, page) in pages.enumerated() {
            let number = index + 1
            let drawn = page.annotations.contains { annotation in
                let type = (annotation.type ?? "").replacingOccurrences(of: "/", with: "")
                return annotationTypes.contains(type)
            }
            if drawn {
                found.insert(number)
                continue
            }
            let size = page.bounds(for: .mediaBox).size
            let width = max(1, Int(size.width * renderScale))
            let height = max(1, Int(size.height * renderScale))
            let pixels = PDFTools.rgbPixels(of: page, width: width, height: height)
            var coloured = 0
            var offset = 0
            while offset + 2 < pixels.count {
                let r = Int(pixels[offset]), g = Int(pixels[offset + 1]), b = Int(pixels[offset + 2])
                let high = max(r, g, b)
                if high > 0 && (high - min(r, g, b)) * 255 / high >= colourSaturation {
                    coloured += 1
                }
                offset += 4
            }
            if Double(coloured) / Double(width * height) >= pageInkRatio {
                found.insert(number)
            }
        }
        let sorted = found.sorted()
        return SolveResult(solved: sorted.contains { $0 > 1 }, pages: sorted, method: "estimated",
                           pageCount: pages.count)
    }
}

import Foundation
import PDFKit

/// The official files that go with an exam: the clean question paper (for solve detection),
/// the mark scheme (for checking) and the grade thresholds (for grades).
/// Looked for in the sorted library first, then the download cache, and only then downloaded.
enum CambridgeFiles {
    static let downloadURL = "https://pastpapers.papacambridge.com/directories/CAIE/CAIE-pastpapers/upload/"
    static let sessionLetters = ["February-March": "m", "May-June": "s", "October-November": "w"]

    enum Kind: String {
        case questionPaper = "qp", markScheme = "ms", thresholds = "gt"

        var label: String {
            switch self {
            case .questionPaper: return "question paper"
            case .markScheme: return "mark scheme"
            case .thresholds: return "grade thresholds"
            }
        }

        var paperType: String { rawValue.uppercased() }
    }

    /// Cambridge's own file name, e.g. 9709_w23_ms_12.pdf or 9709_w23_gt.pdf
    static func officialName(_ metadata: PaperMetadata, _ kind: Kind) throws -> String {
        guard let letter = sessionLetters[metadata.session ?? ""], let year = metadata.year, year.count == 4,
              let code = metadata.subjectCode else {
            throw UserError("This paper is missing the details needed to find its official files.")
        }
        let series = "\(code)_\(letter)\(year.suffix(2))"
        if kind == .thresholds { return "\(series)_gt.pdf" }
        return "\(series)_\(kind.rawValue)_\(metadata.paper ?? "")\(metadata.variant ?? "").pdf"
    }

    static func isPDF(_ url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        let start = (try? handle.read(upToCount: 5)) ?? Data()
        return start == Data("%PDF-".utf8)
    }

    /// A local copy: in the app's sorted library, or downloaded before. Question papers only ever
    /// come from Cambridge: a sorted copy in the library might have been written on.
    static func findLocal(_ metadata: PaperMetadata, _ kind: Kind) -> URL? {
        if kind == .markScheme, let sorted = Library.sibling(of: metadata, type: kind.paperType, root: AppPaths.library),
           isPDF(sorted) {
            return sorted
        }
        guard let name = try? officialName(metadata, kind) else { return nil }
        let cached = AppPaths.cache.appendingPathComponent(name)
        return isPDF(cached) ? cached : nil
    }

    static func download(_ metadata: PaperMetadata, _ kind: Kind) async throws -> URL {
        let name = try officialName(metadata, kind)
        guard let url = URL(string: downloadURL + name) else {
            throw UserError("This paper's details don't make a valid download address.")
        }
        var request = URLRequest(url: url, timeoutInterval: 60)
        request.setValue("Mozilla/5.0 (Exam Organizer for iPad)", forHTTPHeaderField: "User-Agent")
        let reply: (Data, URLResponse)
        do {
            reply = try await URLSession.shared.data(for: request)
        } catch {
            throw UserError("Couldn't connect to download the official file. Check the internet connection.")
        }
        let (data, response) = reply
        guard (response as? HTTPURLResponse)?.statusCode == 200, data.starts(with: Data("%PDF-".utf8)) else {
            throw UserError("Cambridge's \(kind.label) for this paper isn't available online yet.")
        }
        let target = AppPaths.cache.appendingPathComponent(name)
        try data.write(to: target, options: .atomic)
        return target
    }

    /// Local copy if there is one, otherwise download it.
    static func get(_ metadata: PaperMetadata, _ kind: Kind) async throws -> URL {
        if let local = findLocal(metadata, kind) { return local }
        return try await download(metadata, kind)
    }
}

// MARK: - Grade thresholds

/// One grade's minimum mark, in the order Cambridge prints them (best first).
struct GradeBoundary: Codable, Equatable {
    var grade: String
    var minimum: Int
}

struct Thresholds: Equatable {
    var maxMark: Int
    var boundaries: [GradeBoundary]   // empty when the columns couldn't be read for certain

    /// Scale the score to the threshold paper's max mark and return the grade.
    func grade(score: Int, outOf maxScore: Int) -> String? {
        guard !boundaries.isEmpty, maxScore > 0 else { return nil }
        let scaled = Double(score) * Double(maxMark) / Double(maxScore)
        for boundary in boundaries where scaled >= Double(boundary.minimum) {
            return boundary.grade
        }
        return "U"
    }
}

/// Reads Cambridge's grade threshold PDF: the row for this exact paper ("Component 12  75 60 53 …")
/// under the table header ("A B C D E", "A C E F", "AA BB CC", "9 8 7 … 1").
enum ThresholdReader {
    static let gradeToken = "(?:A\\*A\\*|A\\*|[A-G]{1,2}|[a-g]|[1-9])"
    static var headerPattern: String {
        // Other header words can share the line ("mark A B C D E F G"): the run of grades at the end counts
        "(?m)(?:^|[ \\t:])[ \\t]*(\(gradeToken)(?:\\s+\(gradeToken)){2,9})(?=\\s*(?:$|Component\\b))"
    }
    static let missingCharacters = Set("-–—−‐‑~\u{FFFD}")
    static let usualGrades: [String: [String]] = [
        "IGCSE": ["A", "B", "C", "D", "E", "F", "G"],
        "O Level": ["A", "B", "C", "D", "E"],
        "AS": ["a", "b", "c", "d", "e"],
        "AS & A Level": ["A", "B", "C", "D", "E"],
    ]

    static func thresholds(for metadata: PaperMetadata) async throws -> Thresholds {
        let url = try await CambridgeFiles.get(metadata, .thresholds)
        return try read(url, metadata: metadata)
    }

    static func read(_ url: URL, metadata: PaperMetadata) throws -> Thresholds {
        let document = try PDFTools.open(url)
        let component = "\(metadata.paper ?? "")\(metadata.variant ?? "")"
        // The cells after "Component 12": numbers, or dashes for grades that aren't awarded.
        // PDFKit may put them on one line or several, so any spacing between them is fine.
        let cell = "[\\d\\-–—−‐‑~\\x{FFFD}]+"
        let rowPattern = "Component\\s+\(NSRegularExpression.escapedPattern(for: component))(?!\\d)\\s+((?:\(cell)\\s+)*\(cell))"

        for page in PDFTools.pages(of: document) {
            guard let text = page.string,
                  let row = RE.allMatches(rowPattern, in: text).first else { continue }
            let ns = text as NSString
            let cells = values(in: ns.substring(with: row.range(at: 1)))
            guard cells.count >= 2, let maxMark = cells[0] else { break }
            let gradeCells = Array(cells.dropFirst())

            let headers = RE.allMatches(headerPattern, in: text).filter { $0.range.location < row.range.location }
            var pairs: [(String, Int?)]?
            if let header = headers.last {
                let letters = ns.substring(with: header.range(at: 1)).split(whereSeparator: { $0.isWhitespace }).map(String.init)
                pairs = match(letters: letters, cells: gradeCells)
                    ?? matchByPosition(page: page, text: ns, header: header.range(at: 1), row: row.range(at: 1))
            } else if let usual = usualGrades[metadata.qualification ?? ""], usual.count == gradeCells.count {
                pairs = Array(zip(usual, gradeCells))
            }
            var boundaries = (pairs ?? []).compactMap { grade, minimum in minimum.map { GradeBoundary(grade: grade, minimum: $0) } }
            let minimums = boundaries.map(\.minimum)
            if minimums != minimums.sorted(by: >) || minimums.contains(where: { $0 > maxMark }) {
                boundaries = []   // no grade is better than a wrong one
            }
            return Thresholds(maxMark: maxMark, boundaries: boundaries)
        }
        throw UserError("This paper isn't listed in Cambridge's grade thresholds.")
    }

    /// The max mark, then one cell per grade (nil = that grade isn't awarded on this paper).
    static func values(in line: String) -> [Int?] {
        var cells: [Int?] = []
        for token in line.split(whereSeparator: { $0.isWhitespace }) {
            if let number = Int(token) {
                cells.append(number)
            } else if token.allSatisfy({ missingCharacters.contains($0) }) || ["n/a", "na"].contains(token.lowercased()) {
                cells.append(nil)
            } else {
                break
            }
        }
        return cells
    }

    static func match(letters: [String], cells: [Int?]) -> [(String, Int?)]? {
        cells.count == letters.count ? Array(zip(letters, cells)) : nil
    }

    /// Older tables leave a blank cell for grades that aren't awarded, so the numbers don't line
    /// up with the header by count. Line them up by where they sit on the page instead.
    static func matchByPosition(page: PDFPage, text: NSString, header: NSRange, row: NSRange) -> [(String, Int?)]? {
        func tokens(_ range: NSRange, _ pattern: String) -> [(String, CGFloat)] {
            RE.compile(pattern).matches(in: text as String, range: range).map { match in
                let first = page.characterBounds(at: match.range.location)
                let last = page.characterBounds(at: match.range.location + match.range.length - 1)
                return (text.substring(with: match.range), (first.minX + last.maxX) / 2)
            }
        }
        let letters = tokens(header, gradeToken)
        let numbers = tokens(row, "\\d+")
        guard letters.count >= 2, numbers.count >= 2,
              letters.allSatisfy({ $0.1 > 0 }), numbers.allSatisfy({ $0.1 > 0 }),
              zip(letters, letters.dropFirst()).allSatisfy({ $0.1 < $1.1 }) else { return nil }
        let spacing = zip(letters, letters.dropFirst()).map { $1.1 - $0.1 }.min() ?? 0
        guard spacing > 0 else { return nil }

        var found: [String: Int] = [:]
        for (value, x) in numbers.dropFirst() {   // the first number is the max mark
            guard let nearest = letters.min(by: { abs($0.1 - x) < abs($1.1 - x) }),
                  abs(nearest.1 - x) < spacing / 2, found[nearest.0] == nil,
                  let number = Int(value) else { return nil }
            found[nearest.0] = number
        }
        return letters.map { ($0.0, found[$0.0]) }
    }
}

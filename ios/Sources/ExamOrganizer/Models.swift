import Foundation

// MARK: - Paper details

/// What the parser found out about one Cambridge PDF. Same fields as the desktop app.
struct PaperMetadata: Codable, Equatable, Hashable {
    var qualification: String?
    var subjectName: String?
    var subjectCode: String?
    var year: String?
    var session: String?
    var paper: String?
    var variant: String?
    var paperType: String?

    /// "Physics (0625)"
    var title: String {
        guard let code = subjectCode else { return "" }
        return "\(subjectName ?? "Unknown subject") (\(code))"
    }

    /// "IGCSE · May–June 2024 · Paper 4, variant 2"
    func describe(withType: Bool = false) -> String {
        guard subjectCode != nil else { return "" }
        var parts: [String] = []
        if let qualification { parts.append(qualification) }
        if let session, let year { parts.append("\(session.replacingOccurrences(of: "-", with: "–")) \(year)") }
        if let paper { parts.append("Paper \(paper)" + (variant.map { ", variant \($0)" } ?? "")) }
        if withType, let paperType { parts.append(Catalog.paperTypes[paperType] ?? paperType) }
        return parts.joined(separator: " · ")
    }

    /// "AS & A Level Mathematics (9709) Paper 1 v2, October/November 2023"
    var heading: String {
        "\(qualification ?? "") \(subjectName ?? "") (\(subjectCode ?? "")) Paper \(paper ?? "") v\(variant ?? ""), "
            + "\((session ?? "").replacingOccurrences(of: "-", with: "/")) \(year ?? "")"
    }

    var isQuestionPaper: Bool { paperType == "QP" && subjectCode != nil }
}

enum Catalog {
    static let paperTypes: [String: String] = [
        "QP": "Question paper",
        "MS": "Mark scheme",
        "IN": "Insert",
        "ER": "Examiner report",
        "GT": "Grade thresholds",
        "CI": "Confidential instructions",
    ]
    static let paperTypeOrder = ["QP", "MS", "IN", "ER", "GT", "CI"]
    static let qualifications = ["IGCSE", "O Level", "AS", "AS & A Level"]
    static let sessions = ["February-March", "May-June", "October-November"]
    static let gradeOrder = ["9", "8", "7", "6", "5", "4", "3", "2", "1", "A*", "A", "B", "C", "D", "E", "F", "G",
                             "a", "b", "c", "d", "e", "U"]

    static func sessionLabel(_ session: String) -> String {
        session.replacingOccurrences(of: "-", with: "–")
    }
}

// MARK: - Marking

struct MarkedQuestion: Codable, Identifiable, Equatable {
    var id = UUID()
    var question: String
    var page: Int
    var maxMarks: Int
    var marksAwarded: Int
    var studentAnswer: String
    var marking: String
    var feedback: String
    var confidence: String
    var topic: String
}

struct Marking: Codable, Equatable {
    var questions: [MarkedQuestion]
    var total: Int
    var maxTotal: Int
    var paperTotalMarks: Int
    var complete: Bool
    var needsReview: [String]
    var summary: String
    var model: String
    var teacherAdjusted: Bool

    var percent: Int { maxTotal > 0 ? Int((100.0 * Double(total) / Double(maxTotal)).rounded()) : 0 }

    mutating func recount() {
        total = questions.reduce(0) { $0 + $1.marksAwarded }
        maxTotal = questions.reduce(0) { $0 + $1.maxMarks }
    }
}

// MARK: - Errors that are safe to show to a teacher

struct UserError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

func friendlyMessage(_ error: Error) -> String {
    if let error = error as? LocalizedError, let description = error.errorDescription {
        return description
    }
    let nsError = error as NSError
    if nsError.domain == NSURLErrorDomain {
        return "Couldn't connect. Check the internet connection and try again."
    }
    if nsError.domain == NSCocoaErrorDomain {
        return "This file couldn't be read or saved. It may have been moved or deleted."
    }
    return "Something went wrong: \(error.localizedDescription)"
}

// MARK: - Small regular expression helpers

enum RE {
    private static var cache: [String: NSRegularExpression] = [:]
    private static let lock = NSLock()

    static func compile(_ pattern: String) -> NSRegularExpression {
        lock.lock()
        defer { lock.unlock() }
        if let cached = cache[pattern] { return cached }
        // Patterns are written in the app, so a bad one is a programming mistake
        let compiled = try! NSRegularExpression(pattern: pattern)
        cache[pattern] = compiled
        return compiled
    }

    /// Groups of the first match (index 0 = whole match), or nil.
    static func first(_ pattern: String, in text: String) -> [String?]? {
        let ns = text as NSString
        guard let match = compile(pattern).firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) else {
            return nil
        }
        return groups(of: match, in: ns)
    }

    static func all(_ pattern: String, in text: String) -> [[String?]] {
        let ns = text as NSString
        return compile(pattern).matches(in: text, range: NSRange(location: 0, length: ns.length))
            .map { groups(of: $0, in: ns) }
    }

    static func allMatches(_ pattern: String, in text: String) -> [NSTextCheckingResult] {
        let ns = text as NSString
        return compile(pattern).matches(in: text, range: NSRange(location: 0, length: ns.length))
    }

    static func contains(_ pattern: String, in text: String) -> Bool {
        let ns = text as NSString
        return compile(pattern).firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) != nil
    }

    static func replace(_ pattern: String, in text: String, with template: String) -> String {
        let ns = text as NSString
        return compile(pattern).stringByReplacingMatches(
            in: text, range: NSRange(location: 0, length: ns.length), withTemplate: template)
    }

    private static func groups(of match: NSTextCheckingResult, in ns: NSString) -> [String?] {
        (0..<match.numberOfRanges).map { index in
            let range = match.range(at: index)
            return range.location == NSNotFound ? nil : ns.substring(with: range)
        }
    }
}

extension String {
    /// The first `count` characters (Python's text[:count]).
    func head(_ count: Int) -> String { String(prefix(count)) }

    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }

    var nilIfEmpty: String? {
        let value = trimmed
        return value.isEmpty ? nil : value
    }
}

import Foundation

/// Where the app keeps things. Documents is visible in the Files app
/// (On My iPad › Exam Organizer), so sorted exams and marked papers go there.
enum AppPaths {
    static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    /// The built-in exam library: Documents/Exams
    static var library: URL { folder(documents.appendingPathComponent("Exams", isDirectory: true)) }

    /// Marked copies of checked papers: Documents/Marked papers/<class>/<student>/
    static var marked: URL { folder(documents.appendingPathComponent("Marked papers", isDirectory: true)) }

    /// Private working copies of the PDFs teachers add (originals stay where they were).
    static var imports: URL { folder(support.appendingPathComponent("Imports", isDirectory: true)) }

    /// Official files downloaded from the internet (mark schemes, clean papers, thresholds).
    static var cache: URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return folder(caches.appendingPathComponent("Cambridge", isDirectory: true))
    }

    static var support: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return folder(base)
    }

    @discardableResult
    static func folder(_ url: URL) -> URL {
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// The app's container moves when it's updated, so the score book stores paths relative to Documents.
    static func relativePath(_ url: URL) -> String? {
        let base = documents.standardizedFileURL.path
        let path = url.standardizedFileURL.path
        guard path.hasPrefix(base + "/") else { return nil }
        return String(path.dropFirst(base.count + 1))
    }

    static func resolve(_ relative: String?) -> URL? {
        guard let relative, !relative.isEmpty else { return nil }
        let url = documents.appendingPathComponent(relative)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// Make text safe for a file or folder name.
    static func safeName(_ text: String) -> String {
        let cleaned = String(text.map { "<>:\"/\\|?*".contains($0) ? "-" : $0 })
            .trimmingCharacters(in: CharacterSet(charactersIn: " ."))
        return cleaned.isEmpty ? "Untitled" : cleaned
    }

    /// url, or "name (2).pdf", "name (3).pdf"… if it already exists.
    static func unique(_ url: URL) -> URL {
        let manager = FileManager.default
        guard manager.fileExists(atPath: url.path) else { return url }
        let folder = url.deletingLastPathComponent()
        let stem = url.deletingPathExtension().lastPathComponent
        let ext = url.pathExtension
        var number = 2
        while true {
            let candidate = folder.appendingPathComponent("\(stem) (\(number))").appendingPathExtension(ext)
            if !manager.fileExists(atPath: candidate.path) { return candidate }
            number += 1
        }
    }

    /// Copy a PDF the teacher picked into the app, keeping its name (the parser reads Cambridge file names).
    static func importCopy(of source: URL, name: String? = nil) throws -> URL {
        let folder = self.folder(imports.appendingPathComponent(UUID().uuidString, isDirectory: true))
        let target = folder.appendingPathComponent(safeName(name ?? source.lastPathComponent))
        try FileManager.default.copyItem(at: source, to: target)
        return target
    }

    /// Every PDF inside a folder (and its sub-folders).
    static func pdfs(in folder: URL) -> [URL] {
        guard let walker = FileManager.default.enumerator(
            at: folder, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles]) else {
            return []
        }
        var found: [URL] = []
        for case let url as URL in walker where url.pathExtension.lowercased() == "pdf" {
            found.append(url)
        }
        return found.sorted { $0.path.localizedStandardCompare($1.path) == .orderedAscending }
    }

    static func removeImport(_ url: URL) {
        let folder = url.deletingLastPathComponent()
        if folder.deletingLastPathComponent().standardizedFileURL == imports.standardizedFileURL {
            try? FileManager.default.removeItem(at: folder)
        }
    }
}

/// The sorting rules (the desktop app's renamer.py): one folder per paper, named the same way.
enum Library {
    static let seriesFolders = ["GT": "Grade thresholds", "ER": "Examiner report"]
    static let seriesNames = ["GT": "Grade thresholds", "ER": "Examiner report", "IN": "Insert",
                              "CI": "Confidential instructions"]

    /// A paper folder, unless this is a whole-series file (grade thresholds, or an examiner report).
    static func hasComponent(_ metadata: PaperMetadata) -> Bool {
        metadata.paper != nil && metadata.variant != nil && metadata.paperType != "GT"
    }

    /// Exams/Physics/IGCSE/0625/2024/May-June/4/2
    static func folder(for metadata: PaperMetadata, root: URL) -> URL {
        var parts = [metadata.subjectName ?? "Unknown subject", metadata.qualification ?? "Unknown",
                     metadata.subjectCode ?? "0000", metadata.year ?? "Unknown year", metadata.session ?? "Unknown session"]
        if hasComponent(metadata) {
            parts += [metadata.paper ?? "", metadata.variant ?? ""]
        } else {
            parts.append(seriesFolders[metadata.paperType ?? ""] ?? "Series")
        }
        return parts.reduce(root) { $0.appendingPathComponent(AppPaths.safeName($1), isDirectory: true) }
    }

    /// "Physics - Paper 4 v2 QP.pdf", or "Physics - Grade thresholds.pdf" for a series file.
    static func fileName(for metadata: PaperMetadata) -> String {
        let subject = metadata.subjectName ?? "Unknown subject"
        if hasComponent(metadata) {
            return AppPaths.safeName("\(subject) - Paper \(metadata.paper ?? "") v\(metadata.variant ?? "") \(metadata.paperType ?? "")") + ".pdf"
        }
        let label = seriesNames[metadata.paperType ?? ""] ?? metadata.paperType ?? "Paper"
        return AppPaths.safeName("\(subject) - \(label)") + ".pdf"
    }

    /// Copy one exam into its folder. Never overwrites an exam that is already sorted.
    static func place(_ file: URL, metadata: PaperMetadata, root: URL) throws -> URL {
        let folder = self.folder(for: metadata, root: root)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let target = folder.appendingPathComponent(fileName(for: metadata))
        if FileManager.default.fileExists(atPath: target.path) {
            throw UserError("Already sorted. An exam with the same name is already in that folder.")
        }
        try FileManager.default.copyItem(at: file, to: target)
        return target
    }

    /// A sorted copy of this paper's mark scheme or clean question paper, if the library has one.
    static func sibling(of metadata: PaperMetadata, type: String, root: URL) -> URL? {
        var wanted = metadata
        wanted.paperType = type
        let url = folder(for: wanted, root: root).appendingPathComponent(fileName(for: wanted))
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }
}

/// Where sorted exams go: the app's own library, or a folder the teacher picked (iCloud Drive, a USB drive…).
struct Destination: Codable, Equatable {
    var bookmark: Data?
    var displayName: String?

    static let library = Destination(bookmark: nil, displayName: nil)

    var label: String {
        bookmark == nil ? "On My iPad › Exam Organizer › Exams" : (displayName ?? "Chosen folder")
    }

    /// Run `work` with access to the folder. The built-in library needs no permission.
    func withAccess<T>(_ work: (URL) throws -> T) throws -> T {
        guard let bookmark else { return try work(AppPaths.library) }
        var stale = false
        let url: URL
        do {
            url = try URL(resolvingBookmarkData: bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)
        } catch {
            throw UserError("I can't reach the folder you chose any more. Choose it again.")
        }
        let allowed = url.startAccessingSecurityScopedResource()
        defer { if allowed { url.stopAccessingSecurityScopedResource() } }
        return try work(url)
    }

    static func from(folder url: URL) throws -> Destination {
        let allowed = url.startAccessingSecurityScopedResource()
        defer { if allowed { url.stopAccessingSecurityScopedResource() } }
        let data = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        return Destination(bookmark: data, displayName: url.lastPathComponent)
    }
}

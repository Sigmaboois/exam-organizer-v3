import Foundation
import UniformTypeIdentifiers

/// Brings PDFs into the app: from the Files picker, a picked folder, drag and drop, or "Open in".
/// Everything is copied into the app first, so the originals are never changed.
enum Importer {
    struct Outcome {
        var copied: [URL] = []
        var skipped = 0   // files that weren't PDFs
        var failed = 0    // PDFs that couldn't be copied
    }

    /// Files picked in the Files picker (security-scoped URLs).
    static func copyPicked(_ urls: [URL]) -> Outcome {
        var outcome = Outcome()
        for url in urls {
            let allowed = url.startAccessingSecurityScopedResource()
            defer { if allowed { url.stopAccessingSecurityScopedResource() } }
            var isFolder: ObjCBool = false
            if FileManager.default.fileExists(atPath: url.path, isDirectory: &isFolder), isFolder.boolValue {
                let inner = copyFolderContents(url)
                outcome.copied += inner.copied
                outcome.skipped += inner.skipped
                outcome.failed += inner.failed
            } else if url.pathExtension.lowercased() == "pdf" {
                if let copy = try? AppPaths.importCopy(of: url) {
                    outcome.copied.append(copy)
                } else {
                    outcome.failed += 1
                }
            } else {
                outcome.skipped += 1
            }
        }
        return outcome
    }

    /// Every PDF in a folder (and its sub-folders). The caller has access to the folder.
    static func copyFolderContents(_ folder: URL) -> Outcome {
        var outcome = Outcome()
        guard let walker = FileManager.default.enumerator(
            at: folder, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles]) else {
            return outcome
        }
        for case let url as URL in walker {
            let values = try? url.resourceValues(forKeys: [.isRegularFileKey])
            guard values?.isRegularFile == true else { continue }
            if url.pathExtension.lowercased() == "pdf" {
                if let copy = try? AppPaths.importCopy(of: url) {
                    outcome.copied.append(copy)
                } else {
                    outcome.failed += 1
                }
            } else {
                outcome.skipped += 1
            }
        }
        return outcome
    }

    /// PDFs and folders dragged in from Files, Mail, Notability, GoodNotes…
    static func copyDropped(_ providers: [NSItemProvider]) async -> Outcome {
        var outcome = Outcome()
        for provider in providers {
            if provider.hasItemConformingToTypeIdentifier(UTType.pdf.identifier) {
                if let copy = await loadCopy(provider) {
                    outcome.copied.append(copy)
                } else {
                    outcome.failed += 1
                }
            } else if provider.hasItemConformingToTypeIdentifier(UTType.folder.identifier) {
                let inner = await loadFolder(provider)
                outcome.copied += inner.copied
                outcome.skipped += inner.skipped
                outcome.failed += inner.failed
            } else {
                outcome.skipped += 1
            }
        }
        return outcome
    }

    private static func loadCopy(_ provider: NSItemProvider) async -> URL? {
        await withCheckedContinuation { continuation in
            _ = provider.loadFileRepresentation(forTypeIdentifier: UTType.pdf.identifier) { url, _ in
                guard let url else {
                    continuation.resume(returning: nil)
                    return
                }
                // The temporary file is deleted as soon as this returns, so copy it now
                var name = url.lastPathComponent
                if url.pathExtension.lowercased() != "pdf" {
                    name = (provider.suggestedName ?? "Dropped paper") + ".pdf"
                }
                continuation.resume(returning: try? AppPaths.importCopy(of: url, name: name))
            }
        }
    }

    private static func loadFolder(_ provider: NSItemProvider) async -> Outcome {
        await withCheckedContinuation { continuation in
            _ = provider.loadFileRepresentation(forTypeIdentifier: UTType.folder.identifier) { url, _ in
                guard let url else {
                    continuation.resume(returning: Outcome(failed: 1))
                    return
                }
                continuation.resume(returning: copyFolderContents(url))
            }
        }
    }

    /// A PDF sent to the app with Share / Open in.
    static func copyIncoming(_ url: URL) -> URL? {
        let allowed = url.startAccessingSecurityScopedResource()
        defer { if allowed { url.stopAccessingSecurityScopedResource() } }
        guard url.pathExtension.lowercased() == "pdf", let copy = try? AppPaths.importCopy(of: url) else { return nil }
        // Files from Mail, AirDrop… arrive as iOS's own copy in Documents/Inbox, which would pile up in Files
        let inbox = AppPaths.documents.appendingPathComponent("Inbox", isDirectory: true).resolvingSymlinksInPath().path
        if url.resolvingSymlinksInPath().path.hasPrefix(inbox + "/") {
            try? FileManager.default.removeItem(at: url)
        }
        return copy
    }

    static func summary(_ outcome: Outcome, addedTo place: String) -> String {
        let count = outcome.copied.count
        var text = count == 0 ? "No PDFs found." : "Added \(count) PDF\(count == 1 ? "" : "s") to \(place)."
        if outcome.skipped > 0 {
            text += " Skipped \(outcome.skipped) file\(outcome.skipped == 1 ? "" : "s") that weren't PDFs."
        }
        if outcome.failed > 0 {
            text += " \(outcome.failed) couldn't be copied."
        }
        return text
    }
}

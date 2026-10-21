import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// A rounded panel on the grouped background, like the desktop app's cards.
struct Card<Content: View>: View {
    var title: String?
    var number: Int?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if let title {
                HStack(spacing: 10) {
                    if let number {
                        Text("\(number)")
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(.white)
                            .frame(width: 26, height: 26)
                            .background(Circle().fill(Color.accentColor))
                    }
                    Text(title).font(.title3.weight(.semibold))
                }
            }
            content
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous)
            .fill(Color(uiColor: .secondarySystemGroupedBackground)))
    }
}

struct Pill: View {
    var text: String
    var systemImage: String?
    var tint: Color = .accentColor

    var body: some View {
        HStack(spacing: 4) {
            if let systemImage { Image(systemName: systemImage) }
            Text(text)
        }
        .font(.caption.weight(.semibold))
        .padding(.horizontal, 9)
        .padding(.vertical, 4)
        .foregroundStyle(tint)
        .background(Capsule().fill(tint.opacity(0.14)))
    }
}

struct EmptyState: View {
    var systemImage: String
    var title: String
    var message: String

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text(title).font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }
}

/// Drop PDFs or folders here, or pick them with the buttons.
struct DropZone: View {
    var title: String
    var subtitle: String
    var onChooseFiles: () -> Void
    var onChooseFolder: () -> Void
    var onDrop: ([NSItemProvider]) -> Void

    @State private var targeted = false

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "square.and.arrow.down.on.square")
                .font(.system(size: 40))
                .foregroundStyle(Color.accentColor)
            Text(title)
                .font(.headline)
                .multilineTextAlignment(.center)
            Text(subtitle)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            HStack(spacing: 10) {
                Button(action: onChooseFiles) {
                    Label("Choose files", systemImage: "doc.badge.plus")
                }
                Button(action: onChooseFolder) {
                    Label("Choose a folder", systemImage: "folder.badge.plus")
                }
            }
            .buttonStyle(.bordered)
        }
        .padding(20)
        .frame(maxWidth: .infinity, minHeight: 210)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(targeted ? Color.accentColor.opacity(0.10) : Color.clear))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(targeted ? Color.accentColor : Color.secondary.opacity(0.35),
                              style: StrokeStyle(lineWidth: 2, dash: [8, 6])))
        .onDrop(of: [UTType.pdf, UTType.folder], isTargeted: $targeted) { providers in
            onDrop(providers)
            return true
        }
    }
}

/// Status icon at the start of a row.
struct StatusIcon: View {
    enum Kind { case working, file, done, warning }
    var kind: Kind

    var body: some View {
        Group {
            switch kind {
            case .working: ProgressView()
            case .file: Image(systemName: "doc.text").foregroundStyle(Color.accentColor)
            case .done: Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
            case .warning: Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            }
        }
        .font(.title3)
        .frame(width: 30)
    }
}

struct ToastView: View {
    var toast: Toast

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: toast.isError ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                .foregroundStyle(toast.isError ? Color.red : Color.green)
            Text(toast.message)
                .font(.subheadline)
                .multilineTextAlignment(.leading)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
        .background(.regularMaterial, in: Capsule())
        .shadow(color: .black.opacity(0.15), radius: 12, y: 4)
        .padding(.horizontal, 24)
    }
}

/// Which files the shared Files picker is choosing (one picker per screen works best on iPad).
enum PickerMode {
    case files, folder, destination

    var types: [UTType] {
        switch self {
        case .files: return [.pdf]
        case .folder, .destination: return [.folder]
        }
    }

    var multiple: Bool { self == .files }
}

/// Open a folder in the Files app.
func filesAppURL(for folder: URL) -> URL? {
    guard let path = folder.path.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) else { return nil }
    return URL(string: "shareddocuments://" + path)
}

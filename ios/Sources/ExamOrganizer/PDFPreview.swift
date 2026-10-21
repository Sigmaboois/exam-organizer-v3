import PDFKit
import SwiftUI

/// Shows a PDF full screen with Share and Done (QuickLook's SwiftUI preview isn't in xtool's SDK).
struct PreviewFile: Identifiable {
    let url: URL
    var id: String { url.path }
}

struct PDFKitView: UIViewRepresentable {
    var url: URL

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.document = PDFDocument(url: url)
        return view
    }

    func updateUIView(_ view: PDFView, context: Context) {
        if view.document?.documentURL != url {
            view.document = PDFDocument(url: url)
        }
    }
}

@MainActor
struct PDFPreviewSheet: View {
    var file: PreviewFile
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            PDFKitView(url: file.url)
                .ignoresSafeArea(edges: .bottom)
                .navigationTitle(file.url.deletingPathExtension().lastPathComponent)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Done") { dismiss() }
                    }
                    ToolbarItem(placement: .primaryAction) {
                        ShareLink(item: file.url) {
                            Label("Share", systemImage: "square.and.arrow.up")
                        }
                    }
                }
        }
    }
}

extension View {
    /// Show the PDF at `url` while it isn't nil.
    func pdfPreview(_ url: Binding<URL?>) -> some View {
        sheet(item: Binding(get: { url.wrappedValue.map(PreviewFile.init) },
                            set: { url.wrappedValue = $0?.url })) { file in
            PDFPreviewSheet(file: file)
        }
    }
}

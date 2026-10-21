import SwiftUI
import UIKit
import Vision
import VisionKit

/// Apple's document camera: several pages, automatic edge detection and straightening.
struct DocumentScanner: UIViewControllerRepresentable {
    var onFinish: ([UIImage]) -> Void
    var onCancel: () -> Void

    static var isAvailable: Bool { VNDocumentCameraViewController.isSupported }

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let controller = VNDocumentCameraViewController()
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: VNDocumentCameraViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        let parent: DocumentScanner

        init(_ parent: DocumentScanner) { self.parent = parent }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController,
                                          didFinishWith scan: VNDocumentCameraScan) {
            parent.onFinish((0..<scan.pageCount).map { scan.imageOfPage(at: $0) })
        }

        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
            parent.onCancel()
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) {
            parent.onCancel()
        }
    }
}

/// One scan, waiting for the teacher to say whose paper it is.
struct ScanBatch: Identifiable {
    let id = UUID()
    let images: [UIImage]
}

enum ScanTools {
    /// The photos as one PDF (A4 width, each page as tall as its photo) in the app's working folder.
    static func makePDF(_ images: [UIImage], name: String) throws -> URL {
        let folder = AppPaths.folder(AppPaths.imports.appendingPathComponent(UUID().uuidString, isDirectory: true))
        let url = folder.appendingPathComponent(AppPaths.safeName(name) + ".pdf")
        let width: CGFloat = 595
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: width, height: 842))
        try renderer.writePDF(to: url) { context in
            for image in images {
                let size = image.size
                let height = size.width > 0 ? (width * size.height / size.width).rounded() : 842
                let bounds = CGRect(x: 0, y: 0, width: width, height: height)
                context.beginPage(withBounds: bounds, pageInfo: [:])
                // JPEG keeps the file small enough to send for checking
                let photo = image.jpegData(compressionQuality: 0.75).flatMap { UIImage(data: $0) } ?? image
                photo.draw(in: bounds)
            }
        }
        return url
    }

    /// The printed text on the first pages, read on the phone. The cover page names the paper.
    static func recognizeText(_ images: [UIImage], pages: Int = 2) -> String {
        var text: [String] = []
        for image in images.prefix(pages) {
            guard let cgImage = image.cgImage else { continue }
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = false
            let handler = VNImageRequestHandler(cgImage: cgImage, orientation: orientation(image.imageOrientation), options: [:])
            try? handler.perform([request])
            let observations = request.results as? [VNRecognizedTextObservation] ?? []
            text.append(observations.compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n"))
        }
        return text.joined(separator: "\n")
    }

    private static func orientation(_ value: UIImage.Orientation) -> CGImagePropertyOrientation {
        switch value {
        case .up: return .up
        case .down: return .down
        case .left: return .left
        case .right: return .right
        case .upMirrored: return .upMirrored
        case .downMirrored: return .downMirrored
        case .leftMirrored: return .leftMirrored
        case .rightMirrored: return .rightMirrored
        @unknown default: return .up
        }
    }
}

/// After scanning: whose paper is it, which class, and which exam (filled in from the scan).
@MainActor
struct ScanSheet: View {
    var batch: ScanBatch
    var onCheck: (_ file: URL, _ metadata: PaperMetadata, _ studentID: UUID, _ pages: Int) -> Void

    @EnvironmentObject private var scoreBook: ScoreBook
    @Environment(\.dismiss) private var dismiss

    @State private var reading = true
    @State private var pdf: URL?
    @State private var fields = PaperParser.ManualFields()
    @State private var detected: String?
    @State private var classChoice: UUID?
    @State private var newClassName = ""
    @State private var studentName = ""
    @State private var error: String?

    private var suggestions: [Student] {
        guard let classChoice else { return [] }
        let typed = studentName.trimmed.lowercased()
        return scoreBook.members(of: classChoice).filter { typed.isEmpty || $0.name.lowercased().contains(typed) }
    }

    var body: some View {
        NavigationStack {
            Group {
                if reading {
                    VStack(spacing: 14) {
                        ProgressView()
                        Text("Reading the paper…").font(.headline)
                        Text("Making a PDF of \(batch.images.count) page\(batch.images.count == 1 ? "" : "s") and finding which exam it is.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(30)
                } else {
                    form
                }
            }
            .navigationTitle("Scanned paper")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        if let pdf { AppPaths.removeImport(pdf) }
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Check paper", action: check)
                        .disabled(reading)
                }
            }
            .task { await prepare() }
        }
        .interactiveDismissDisabled()
    }

    private var form: some View {
        Form {
            Section {
                Picker("Class", selection: $classChoice) {
                    ForEach(scoreBook.classes.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }) {
                        Text($0.name).tag(UUID?.some($0.id))
                    }
                    Text("New class…").tag(UUID?.none)
                }
                .pickerStyle(.menu)
                if classChoice == nil {
                    TextField("New class name, e.g. Year 11 Physics", text: $newClassName)
                }
                TextField("Student's name", text: $studentName)
                    .textContentType(.name)
                    .autocorrectionDisabled()
                if !suggestions.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(suggestions.prefix(12)) { student in
                                Button(student.name) { studentName = student.name }
                                    .buttonStyle(.bordered)
                                    .controlSize(.small)
                            }
                        }
                    }
                }
            } header: {
                Text("Whose paper is this?")
            } footer: {
                Text("A new name is added to the class. Their marked paper and a list of their mistakes are saved in "
                     + "Files › On My iPhone › Exam Organizer › Classes.")
            }

            Section {
                TextField("Subject code, e.g. 9709", text: $fields.subjectCode)
                    .keyboardType(.numberPad)
                Picker("Qualification", selection: $fields.qualification) {
                    Text("Work it out from the code").tag(String?.none)
                    ForEach(Catalog.qualifications, id: \.self) { Text($0).tag(String?.some($0)) }
                }
                TextField("Year, e.g. 2024", text: $fields.year)
                    .keyboardType(.numberPad)
                Picker("Session", selection: $fields.session) {
                    Text("Choose").tag(String?.none)
                    ForEach(Catalog.sessions, id: \.self) { Text(Catalog.sessionLabel($0)).tag(String?.some($0)) }
                }
                HStack {
                    TextField("Paper", text: $fields.paper).keyboardType(.numberPad)
                    TextField("Variant", text: $fields.variant).keyboardType(.numberPad)
                }
            } header: {
                Text("Which exam?")
            } footer: {
                Text(detected ?? "")
            }

            if let error {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                }
            }
        }
    }

    private func prepare() async {
        guard reading else { return }
        let images = batch.images
        let name = "Scan \(Date().formatted(.dateTime.year().month().day().hour().minute()))"
        let result: (URL?, PaperMetadata, String?) = await Task.detached(priority: .userInitiated) {
            let pdf = try? ScanTools.makePDF(images, name: name)
            let text = ScanTools.recognizeText(images)
            let found = PaperParser.analyse(text: text, filename: "scan.pdf")
            return (pdf, found.metadata, found.problem)
        }.value
        pdf = result.0
        var metadata = result.1
        metadata.paperType = "QP"
        fields = PaperParser.ManualFields(metadata)
        if result.2 == nil {
            detected = "Found: \(metadata.title) · \(metadata.describe()). Change anything that's wrong."
        } else {
            detected = "I couldn't read every detail from the photos, so please fill in what's missing. "
                + "Tip: include the cover page in the scan."
        }
        if classChoice == nil {
            classChoice = scoreBook.classes.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }.first?.id
        }
        reading = false
        if pdf == nil { error = "Couldn't make a PDF from the photos. Try scanning again." }
    }

    private func check() {
        guard let pdf else {
            error = "Couldn't make a PDF from the photos. Try scanning again."
            return
        }
        var exam = fields
        exam.paperType = "QP"
        let resolved = PaperParser.completeManual(exam)
        if let message = resolved.error {
            error = message
            return
        }
        let name = studentName.trimmed
        guard !name.isEmpty else {
            error = "Type the student's name."
            return
        }
        do {
            let classID: UUID
            if let classChoice {
                classID = classChoice
            } else {
                let className = newClassName.trimmed
                guard !className.isEmpty else {
                    error = "Choose a class, or type a name for a new one."
                    return
                }
                classID = try scoreBook.classes.first { $0.name.caseInsensitiveCompare(className) == .orderedSame }?.id
                    ?? scoreBook.addClass(className).id
            }
            let studentID = try scoreBook.members(of: classID)
                .first { $0.name.caseInsensitiveCompare(name) == .orderedSame }?.id
                ?? scoreBook.addStudent(name, to: classID).id
            onCheck(pdf, resolved.metadata, studentID, batch.images.count)
            dismiss()
        } catch {
            self.error = friendlyMessage(error)
        }
    }
}

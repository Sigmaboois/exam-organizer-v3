import SwiftUI

@MainActor
struct CheckView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var model: CheckModel
    @EnvironmentObject private var scoreBook: ScoreBook
    @Environment(\.horizontalSizeClass) private var sizeClass

    @State private var pickerMode = PickerMode.files
    @State private var pickerShown = false
    @State private var editing: SheetID?
    @State private var showingResults: SheetID?
    @State private var assignTo: UUID?
    @State private var scanning = false
    @State private var scannedImages: [UIImage]?
    @State private var scanBatch: ScanBatch?
    @AppStorage("check_info_open") private var infoOpen = true

    var body: some View {
        ScrollView {
            VStack(spacing: sizeClass == .regular ? 20 : 16) {
                if sizeClass == .regular {
                    HStack(alignment: .top, spacing: 20) {
                        addCard.frame(width: 380)
                        infoCard
                    }
                } else {
                    infoCard
                    addCard
                }
                listCard
            }
            .padding(sizeClass == .regular ? 24 : 16)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("Check papers")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    model.checkAll()
                } label: {
                    Label(model.readyCount > 0 ? "Check \(model.readyCount) paper\(model.readyCount == 1 ? "" : "s")" : "Check all",
                          systemImage: "checkmark.seal")
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.readyCount == 0)
            }
        }
        .fileImporter(isPresented: $pickerShown, allowedContentTypes: pickerMode.types,
                      allowsMultipleSelection: pickerMode.multiple) { result in
            guard case .success(let urls) = result, !urls.isEmpty else { return }
            Task { @MainActor in
                let outcome = await Task.detached { Importer.copyPicked(urls) }.value
                model.add(outcome.copied)
                app.showToast(Importer.summary(outcome, addedTo: "the list"), error: outcome.copied.isEmpty)
            }
        }
        .sheet(item: $editing) { sheet in
            if let paper = model.find(sheet.id) {
                DetailsEditor(fileName: paper.name, initial: paper.metadata) { metadata in
                    model.apply(metadata, to: sheet.id)
                }
            }
        }
        .fullScreenCover(isPresented: $scanning, onDismiss: {
            // Ask whose paper it is once the camera has closed
            if let images = scannedImages, !images.isEmpty {
                scanBatch = ScanBatch(images: images)
            }
            scannedImages = nil
        }) {
            DocumentScanner(onFinish: { images in
                scannedImages = images
                scanning = false
            }, onCancel: {
                scanning = false
            })
            .ignoresSafeArea()
        }
        .sheet(item: $scanBatch) { batch in
            ScanSheet(batch: batch) { file, metadata, studentID, pages in
                model.addScanned(file, metadata: metadata, studentID: studentID, pages: pages)
                app.showToast("Checking " + (scoreBook.student(studentID).map { "\($0.name)'s" } ?? "the") + " paper…")
            }
            .environmentObject(scoreBook)
        }
        .sheet(item: $showingResults) { sheet in
            ResultsView(paperID: sheet.id)
                .environmentObject(app)
                .environmentObject(model)
                .environmentObject(scoreBook)
        }
    }

    // MARK: Cards

    private var infoCard: some View {
        Card {
            DisclosureGroup(isExpanded: $infoOpen) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("I find the official mark scheme for each paper (from your library, or I download it), "
                         + "then an AI examiner marks the answers question by question. Handwriting from "
                         + "Notability, GoodNotes or any other app is fine.")
                    Text("You get the score, the Cambridge grade, feedback for each question and a marked PDF copy.")
                    Text("The AI is good but not perfect: anything it's unsure about is flagged for you to double-check, "
                         + "and you can change any mark.")
                        .foregroundStyle(.secondary)
                }
                .font(.subheadline)
                .padding(.top, 8)
            } label: {
                Label("How checking works", systemImage: "info.circle")
                    .font(.headline)
            }
        }
    }

    private var addCard: some View {
        Card(title: "Add solved papers") {
            Button {
                if DocumentScanner.isAvailable {
                    scanning = true
                } else {
                    app.showToast("This device can't scan documents with its camera.", error: true)
                }
            } label: {
                Label("Scan a paper with the camera", systemImage: "doc.viewfinder")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            Text("Take a photo of every page. I make a PDF, read which exam it is, ask whose it is, then check it.")
                .font(.footnote)
                .foregroundStyle(.secondary)
            DropZone(
                title: "Drag students' papers here",
                subtitle: "Export them from your notes app as PDFs, or share them straight to Exam Organizer.",
                onChooseFiles: { pick(.files) },
                onChooseFolder: { pick(.folder) },
                onDrop: { providers in
                    Task { @MainActor in
                        let outcome = await Importer.copyDropped(providers)
                        model.add(outcome.copied)
                        app.showToast(Importer.summary(outcome, addedTo: "the list"), error: outcome.copied.isEmpty)
                    }
                })
        }
    }

    private var listCard: some View {
        Card(title: "Papers") {
            if model.papers.isEmpty {
                EmptyState(systemImage: "pencil.and.list.clipboard", title: "No papers yet",
                           message: "Add a student's solved question paper to check it against the mark scheme.")
            } else {
                ViewThatFits(in: .horizontal) {
                    HStack { toolsRow }
                    VStack(alignment: .leading, spacing: 10) { toolsRow }
                }
                if let last = model.lastResult {
                    Label(last, systemImage: "checkmark.circle.fill")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.green)
                }
                LazyVStack(spacing: 10) {
                    ForEach(model.papers) { paper in
                        CheckRow(paper: paper,
                                 busy: model.busyWith != nil,
                                 onFix: { editing = SheetID(id: paper.id) },
                                 onResults: { showingResults = SheetID(id: paper.id) })
                    }
                }
            }
        }
    }

    @ViewBuilder private var toolsRow: some View {
        Text(model.summary)
            .font(.subheadline)
            .foregroundStyle(.secondary)
        Spacer(minLength: 0)
        HStack(spacing: 8) {
            StudentMenu(title: assignTo.flatMap { scoreBook.student($0)?.name } ?? "Assign every paper to…",
                        allowDontSave: false) { student, _ in
                assignTo = student
            }
            Button("Assign") {
                if let assignTo { model.assignAll(to: assignTo) }
            }
            .disabled(assignTo == nil)
            Button(role: .destructive) {
                model.clearAll()
            } label: {
                Label("Clear", systemImage: "xmark.circle")
            }
        }
        .buttonStyle(.bordered)
    }

    private func pick(_ mode: PickerMode) {
        pickerMode = mode
        pickerShown = true
    }
}

/// Pick a student, grouped by class. "Don't save" checks a paper without keeping the score.
@MainActor
struct StudentMenu: View {
    @EnvironmentObject private var scoreBook: ScoreBook
    var title: String
    var allowDontSave = true
    var onChoose: (UUID?, Bool) -> Void

    var body: some View {
        Menu {
            if allowDontSave {
                Button { onChoose(nil, true) } label: { Label("Don't save to a student", systemImage: "person.slash") }
            }
            ForEach(scoreBook.classesByQualification, id: \.0) { _, classes in
                ForEach(classes) { schoolClass in
                    Section(schoolClass.name) {
                        ForEach(scoreBook.members(of: schoolClass.id)) { student in
                            Button(student.name) { onChoose(student.id, false) }
                        }
                    }
                }
            }
            if scoreBook.students.isEmpty {
                Text("Add classes and students in Classes first")
            }
        } label: {
            Label(title, systemImage: "person.crop.circle")
                .lineLimit(1)
        }
    }
}

@MainActor
private struct CheckRow: View {
    @EnvironmentObject private var model: CheckModel
    @EnvironmentObject private var scoreBook: ScoreBook
    var paper: CheckPaper
    var busy: Bool
    var onFix: () -> Void
    var onResults: () -> Void

    private var icon: StatusIcon.Kind {
        switch paper.status {
        case .waiting, .reading, .queued, .checking: return .working
        case .ready: return .file
        case .done: return .done
        case .problem: return .warning
        }
    }

    private var studentTitle: String {
        if paper.dontSave { return "Not saved to a student" }
        return scoreBook.student(paper.studentID)?.name ?? "Whose paper is this?"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                StatusIcon(kind: icon)
                VStack(alignment: .leading, spacing: 5) {
                    Text(paper.metadata?.title.nilIfEmpty ?? paper.name)
                        .font(.body.weight(.semibold))
                        .lineLimit(1)
                    if let details = paper.metadata?.describe().nilIfEmpty {
                        Text(details).font(.footnote).foregroundStyle(.secondary)
                    }
                    pills
                    note
                }
                Spacer(minLength: 8)
                Menu {
                    if paper.status == .ready || paper.status == .problem {
                        Button(action: onFix) { Label("Fix details", systemImage: "pencil") }
                    }
                    if paper.status != .checking {
                        Button(role: .destructive) { model.remove(paper.id) } label: {
                            Label("Remove from the list", systemImage: "trash")
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle").font(.title3)
                }
            }
            HStack(spacing: 8) {
                StudentMenu(title: studentTitle) { student, dontSave in
                    model.choose(student: student, dontSave: dontSave, for: paper.id)
                }
                .buttonStyle(.bordered)
                .disabled(paper.status == .checking)
                Spacer(minLength: 0)
                if paper.status == .done {
                    Button(action: onResults) { Label("Results", systemImage: "list.clipboard") }
                        .buttonStyle(.borderedProminent)
                }
                if paper.checkable && [.ready, .done, .problem].contains(paper.status) {
                    Button(paper.status == .done ? "Check again" : "Check") { model.queue(paper.id) }
                        .buttonStyle(.bordered)
                        .disabled(busy && paper.status != .done)
                }
            }
            .padding(.leading, 42)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous)
            .fill(Color(uiColor: .tertiarySystemGroupedBackground)))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
            .strokeBorder(paper.status == .problem ? Color.orange.opacity(0.5) : Color.clear))
    }

    @ViewBuilder private var pills: some View {
        let items = pillItems
        if !items.isEmpty {
            HStack(spacing: 6) {
                ForEach(items.indices, id: \.self) { index in items[index] }
            }
        }
    }

    private var pillItems: [Pill] {
        var items: [Pill] = []
        if let outcome = paper.outcome, paper.status == .done {
            items.append(Pill(text: "\(outcome.marking.total) / \(outcome.marking.maxTotal)  ·  \(outcome.marking.percent)%",
                              systemImage: "checkmark.seal", tint: .green))
            if let grade = outcome.grade { items.append(Pill(text: "Grade \(grade)")) }
            if !outcome.marking.needsReview.isEmpty {
                items.append(Pill(text: "Double-check \(outcome.marking.needsReview.count)", systemImage: "eye", tint: .orange))
            }
        }
        if paper.metadata?.paperType == "QP", let answers = paper.answers {
            if answers.method == "scanned" {
                items.append(Pill(text: "Scanned · \(answers.pageCount) page\(answers.pageCount == 1 ? "" : "s")",
                                  systemImage: "camera", tint: .purple))
            } else if answers.solved {
                items.append(Pill(text: "Answers on \(answers.pages.count) page\(answers.pages.count == 1 ? "" : "s")",
                                  systemImage: "pencil.and.scribble", tint: .purple))
            } else {
                items.append(Pill(text: "Looks unanswered", systemImage: "info.circle", tint: .orange))
            }
        }
        return items
    }

    @ViewBuilder private var note: some View {
        switch paper.status {
        case .waiting:
            Text("Waiting to be read…").font(.footnote).foregroundStyle(.secondary)
        case .reading:
            Text("Reading the paper and looking for answers…").font(.footnote).foregroundStyle(.secondary)
        case .queued:
            Text("Waiting for its turn…").font(.footnote).foregroundStyle(.secondary)
        case .checking:
            Text(paper.progress).font(.footnote).foregroundStyle(.secondary)
        case .ready:
            if paper.studentID == nil && !paper.dontSave {
                Text("Choose whose paper this is, then tap Check.").font(.footnote).foregroundStyle(.secondary)
            }
        case .done:
            Text(paper.saved ? "Saved to the score book ✓" : "Not saved to a student yet.")
                .font(.footnote)
                .foregroundStyle(paper.saved ? Color.green : Color.secondary)
        case .problem:
            Text(paper.message).font(.footnote).foregroundStyle(.orange)
        }
    }
}

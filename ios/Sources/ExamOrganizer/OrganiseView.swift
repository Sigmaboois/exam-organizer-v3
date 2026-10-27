import SwiftUI

struct SheetID: Identifiable {
    let id: UUID
}

@MainActor
struct OrganiseView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var model: OrganiseModel
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.openURL) private var openURL

    @State private var pickerMode = PickerMode.files
    @State private var pickerShown = false
    @State private var editing: SheetID?
    @State private var preview: URL?
    @State private var previewAccess: URL?    // the picked folder, kept open while its sorted copy is shown

    var body: some View {
        ScrollView {
            if sizeClass == .regular {
                HStack(alignment: .top, spacing: 20) {
                    VStack(spacing: 20) {
                        addCard
                        destinationCard
                    }
                    .frame(width: 380)
                    listCard
                }
                .padding(24)
            } else {
                VStack(spacing: 16) {
                    addCard
                    destinationCard
                    listCard
                }
                .padding(16)
            }
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("Organise")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    Task { await model.sortAll() }
                } label: {
                    Label(model.readyCount > 0 ? "Sort \(model.readyCount) exam\(model.readyCount == 1 ? "" : "s")" : "Sort exams",
                          systemImage: "sparkles")
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.readyCount == 0 || model.isSorting)
            }
        }
        .fileImporter(isPresented: $pickerShown, allowedContentTypes: pickerMode.types,
                      allowsMultipleSelection: pickerMode.multiple) { result in
            handlePicked(result)
        }
        .sheet(item: $editing) { sheet in
            if let item = model.items.first(where: { $0.id == sheet.id }) {
                DetailsEditor(fileName: item.name, initial: item.metadata) { metadata in
                    model.apply(metadata, to: sheet.id)
                }
            }
        }
        .pdfPreview($preview)
        .onChange(of: preview) {
            if preview == nil {
                previewAccess?.stopAccessingSecurityScopedResource()
                previewAccess = nil
            }
        }
    }

    // MARK: Cards

    private var addCard: some View {
        Card(title: "Add exams", number: 1) {
            DropZone(
                title: "Drag exam PDFs here",
                subtitle: "From Files, Mail or your notes app. One file, lots of files, or whole folders.",
                onChooseFiles: { pick(.files) },
                onChooseFolder: { pick(.folder) },
                onDrop: { providers in
                    Task {
                        let outcome = await Importer.copyDropped(providers)
                        model.add(outcome.copied)
                        app.showToast(Importer.summary(outcome, addedTo: "the list"), error: outcome.copied.isEmpty)
                    }
                })
        }
    }

    private var destinationCard: some View {
        Card(title: "Where they go", number: 2) {
            HStack(spacing: 12) {
                Image(systemName: model.destination.bookmark == nil ? "ipad" : "folder.fill")
                    .font(.title2)
                    .foregroundStyle(Color.accentColor)
                Text(model.destination.label)
                    .font(.subheadline.weight(.medium))
                Spacer()
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(uiColor: .tertiarySystemGroupedBackground)))
            HStack {
                Button {
                    pick(.destination)
                } label: {
                    Label("Choose a folder", systemImage: "folder")
                }
                if model.destination.bookmark != nil {
                    Button("Use the app's library") { model.destination = .library }
                } else if let url = filesAppURL(for: AppPaths.library) {
                    Button {
                        openURL(url)
                    } label: {
                        Label("Show in Files", systemImage: "arrow.up.forward.app")
                    }
                }
            }
            .buttonStyle(.bordered)
            Text("Your files are copied and sorted: the originals stay where they are. Pick an iCloud Drive "
                 + "folder to see the sorted exams on your other devices too.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    private var listCard: some View {
        Card(title: "Check and sort", number: 3) {
            if model.items.isEmpty {
                EmptyState(systemImage: "doc.on.doc", title: "No exams yet",
                           message: "Exams you add show up here so you can check them before sorting.")
            } else {
                HStack {
                    Text(model.summary)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Menu {
                        Button("Clear sorted exams") { model.clearFinished() }
                        Button("Clear the whole list", role: .destructive) { model.clearAll() }
                    } label: {
                        Label("Clear", systemImage: "xmark.circle")
                    }
                }
                LazyVStack(spacing: 10) {
                    ForEach(model.items) { item in
                        ExamRow(item: item,
                                onFix: { editing = SheetID(id: item.id) },
                                onCheck: { model.sendToCheck(item.id) },
                                onPreview: { showPreview(item) },
                                onShow: {
                                    if let folder = item.sortedTo?.deletingLastPathComponent(),
                                       let url = filesAppURL(for: folder) {
                                        openURL(url)
                                    }
                                },
                                onRemove: { model.remove(item.id) })
                    }
                }
            }
        }
    }

    /// A copy sorted into a picked folder can only be read while that folder is open.
    private func showPreview(_ item: ExamItem) {
        previewAccess?.stopAccessingSecurityScopedResource()
        previewAccess = item.sortedTo == nil ? nil : item.sortedInto?.startAccess()
        preview = item.sortedTo ?? item.file
    }

    // MARK: Files picker

    private func pick(_ mode: PickerMode) {
        pickerMode = mode
        pickerShown = true
    }

    private func handlePicked(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, !urls.isEmpty else { return }
        if pickerMode == .destination {
            do {
                model.destination = try Destination.from(folder: urls[0])
                app.showToast("Sorted exams will go to \(urls[0].lastPathComponent).")
            } catch {
                app.showToast("Couldn't use that folder. Try another one.", error: true)
            }
            return
        }
        Task {
            let outcome = await Task.detached { Importer.copyPicked(urls) }.value
            model.add(outcome.copied)
            app.showToast(Importer.summary(outcome, addedTo: "the list"), error: outcome.copied.isEmpty)
        }
    }
}

@MainActor
private struct ExamRow: View {
    var item: ExamItem
    var onFix: () -> Void
    var onCheck: () -> Void
    var onPreview: () -> Void
    var onShow: () -> Void
    var onRemove: () -> Void

    private var icon: StatusIcon.Kind {
        switch item.status {
        case .waiting, .reading, .sorting: return .working
        case .ready: return .file
        case .sorted: return .done
        case .problem: return .warning
        }
    }

    private var solvedPaper: Bool {
        item.metadata?.paperType == "QP" && item.answers?.solved == true
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            StatusIcon(kind: icon)
            VStack(alignment: .leading, spacing: 5) {
                Text(item.metadata?.title.nilIfEmpty ?? item.name)
                    .font(.body.weight(.semibold))
                    .lineLimit(1)
                if let details = item.metadata?.describe(withType: true).nilIfEmpty {
                    Text(details)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                pills
                note
            }
            Spacer(minLength: 8)
            if solvedPaper && item.status != .problem {
                Button("Check", action: onCheck)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
            }
            Menu {
                if item.status == .ready || item.status == .problem {
                    Button(action: onFix) { Label("Fix details", systemImage: "pencil") }
                }
                if item.metadata?.paperType == "QP" {
                    Button(action: onCheck) { Label("Check this paper", systemImage: "checkmark.seal") }
                }
                Button(action: onPreview) { Label("Preview", systemImage: "eye") }
                if item.sortedTo != nil {
                    Button(action: onShow) { Label("Show in Files", systemImage: "folder") }
                }
                if item.status != .sorting {
                    Button(role: .destructive, action: onRemove) { Label("Remove from the list", systemImage: "trash") }
                }
            } label: {
                Image(systemName: "ellipsis.circle")
                    .font(.title3)
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous)
            .fill(Color(uiColor: .tertiarySystemGroupedBackground)))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous)
            .strokeBorder(item.status == .problem ? Color.orange.opacity(0.5) : Color.clear))
    }

    @ViewBuilder private var pills: some View {
        if item.metadata?.paperType == "QP" {
            if item.checkingAnswers {
                Pill(text: "Looking for answers…", systemImage: "magnifyingglass", tint: .secondary)
            } else if let answers = item.answers {
                if answers.solved {
                    Pill(text: "Answers on \(answers.pages.count) page\(answers.pages.count == 1 ? "" : "s")",
                         systemImage: "pencil.and.scribble", tint: .purple)
                } else {
                    Pill(text: "Looks unanswered", systemImage: "info.circle", tint: .orange)
                }
            }
        }
    }

    @ViewBuilder private var note: some View {
        switch item.status {
        case .waiting:
            Text("Waiting to be read…").font(.footnote).foregroundStyle(.secondary)
        case .reading:
            Text("Reading the paper…").font(.footnote).foregroundStyle(.secondary)
        case .sorting:
            Text("Sorting…").font(.footnote).foregroundStyle(.secondary)
        case .sorted:
            Text("Sorted ✓  \(item.sortedTo?.lastPathComponent ?? "")").font(.footnote).foregroundStyle(.green)
        case .problem:
            Text(item.problem ?? "Something went wrong.").font(.footnote).foregroundStyle(.orange)
        case .ready:
            EmptyView()
        }
    }
}

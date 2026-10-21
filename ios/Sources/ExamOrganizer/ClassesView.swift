import SwiftUI

/// Classes and students, with every checked paper's score. On iPad the class list sits on the
/// left and the chosen class on the right; on a phone they're two pages.
@MainActor
struct ClassesView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var scoreBook: ScoreBook
    @Environment(\.horizontalSizeClass) private var sizeClass

    @State private var selected: UUID?
    @State private var newClassName = ""
    @State private var exportURL: URL?

    var body: some View {
        Group {
            if sizeClass == .regular {
                HStack(spacing: 0) {
                    classList
                        .frame(width: 320)
                    Divider()
                    if let id = selected, scoreBook.schoolClass(id) != nil {
                        ClassDetail(classID: id)
                    } else {
                        EmptyState(systemImage: "person.3", title: "Choose a class",
                                   message: "Pick a class on the left, or add your first one.")
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .background(Color(uiColor: .systemGroupedBackground))
                    }
                }
            } else {
                classList
            }
        }
        .navigationTitle("Classes")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    do {
                        exportURL = try scoreBook.exportCSV(scoreBook.results, name: "All results")
                    } catch {
                        app.showToast("Couldn't make the spreadsheet.", error: true)
                    }
                } label: {
                    Label("Export all results", systemImage: "tablecells")
                }
                .disabled(scoreBook.results.isEmpty)
            }
        }
        .sheet(item: Binding(get: { exportURL.map(ExportFile.init) }, set: { exportURL = $0?.url })) { file in
            ExportSheet(file: file)
        }
    }

    /// iPad: tapping a class selects it and shows it on the right. iPhone: tapping opens it.
    /// (A list in selection mode can't open links, which is why the two are separate.)
    @ViewBuilder private var classList: some View {
        if sizeClass == .regular {
            List(selection: $selected) { classRows }
                .listStyle(.insetGrouped)
        } else {
            List { classRows }
                .listStyle(.insetGrouped)
        }
    }

    @ViewBuilder private var classRows: some View {
        Section {
            HStack {
                TextField("New class, e.g. Year 11 Physics", text: $newClassName)
                    .onSubmit(addClass)
                Button("Add", action: addClass)
                    .buttonStyle(.borderless)
                    .disabled(newClassName.trimmed.isEmpty)
            }
        }
        if scoreBook.classes.isEmpty {
            Text("No classes yet. Checked papers are saved to students, so start by adding a class.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        ForEach(scoreBook.classesByQualification, id: \.0) { divider, classes in
            Section(divider) {
                ForEach(classes) { schoolClass in
                    if sizeClass == .regular {
                        ClassRow(schoolClass: schoolClass).tag(schoolClass.id)
                    } else {
                        NavigationLink {
                            ClassDetail(classID: schoolClass.id)
                        } label: {
                            ClassRow(schoolClass: schoolClass)
                        }
                    }
                }
            }
        }
    }

    private func addClass() {
        do {
            let added = try scoreBook.addClass(newClassName)
            newClassName = ""
            selected = added.id
        } catch {
            app.showToast(friendlyMessage(error), error: true)
        }
    }
}

@MainActor
private struct ClassRow: View {
    @EnvironmentObject private var scoreBook: ScoreBook
    var schoolClass: SchoolClass

    var body: some View {
        let count = scoreBook.members(of: schoolClass.id).count
        VStack(alignment: .leading, spacing: 2) {
            Text(schoolClass.name).font(.body.weight(.medium))
            Text("\(count) student\(count == 1 ? "" : "s")")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

struct ExportFile: Identifiable {
    let url: URL
    var id: String { url.path }
}

/// Share the spreadsheet: save to Files, AirDrop, email…
@MainActor
struct ExportSheet: View {
    var file: ExportFile
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 18) {
                Image(systemName: "tablecells")
                    .font(.system(size: 48))
                    .foregroundStyle(Color.accentColor)
                Text(file.url.lastPathComponent).font(.headline)
                Text("A spreadsheet of the results. It opens in Excel, Numbers and Google Sheets.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                ShareLink(item: file.url) {
                    Label("Save or send", systemImage: "square.and.arrow.up")
                        .frame(maxWidth: 260)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }
            .padding(30)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

@MainActor
struct ClassDetail: View {
    var classID: UUID

    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var scoreBook: ScoreBook
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    @State private var newStudent = ""
    @State private var renaming: Renaming?
    @State private var pasting = false
    @State private var pasted = ""
    @State private var confirmDelete = false
    @State private var exportURL: URL?
    @State private var preview: URL?

    private struct Renaming: Identifiable {
        let id: UUID
        let isClass: Bool
        var name: String
    }

    private var schoolClass: SchoolClass? { scoreBook.schoolClass(classID) }

    var body: some View {
        let records = scoreBook.records(forClass: classID)
        let members = scoreBook.members(of: classID)
        List {
            Section {
                overview(records)
            } header: {
                Text(schoolClass?.qualification ?? "Overview")
            }

            Section {
                HStack {
                    TextField("Add a student", text: $newStudent)
                        .onSubmit(addStudent)
                    Button("Add", action: addStudent)
                        .buttonStyle(.borderless)
                        .disabled(newStudent.trimmed.isEmpty)
                }
                Button {
                    pasted = ""
                    pasting = true
                } label: {
                    Label("Paste a class list", systemImage: "list.bullet.clipboard")
                }
            }

            Section("Students") {
                if members.isEmpty {
                    Text("No students yet.").foregroundStyle(.secondary)
                }
                ForEach(members) { student in
                    StudentRow(student: student, records: scoreBook.records(forStudent: student.id),
                               onRename: { renaming = Renaming(id: student.id, isClass: false, name: student.name) },
                               onPreview: { preview = $0 })
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(schoolClass?.name ?? "Class")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button {
                        renaming = Renaming(id: classID, isClass: true, name: schoolClass?.name ?? "")
                    } label: {
                        Label("Rename class", systemImage: "pencil")
                    }
                    if let name = schoolClass?.name, let url = filesAppURL(for: ClassFolders.create(className: name)) {
                        Button {
                            openURL(url)
                        } label: {
                            Label("Show class folder in Files", systemImage: "folder")
                        }
                    }
                    Button {
                        do {
                            exportURL = try scoreBook.exportCSV(records, name: schoolClass?.name ?? "Class results")
                        } catch {
                            app.showToast("Couldn't make the spreadsheet.", error: true)
                        }
                    } label: {
                        Label("Export this class", systemImage: "tablecells")
                    }
                    .disabled(records.isEmpty)
                    Button(role: .destructive) {
                        confirmDelete = true
                    } label: {
                        Label("Delete class", systemImage: "trash")
                    }
                } label: {
                    Label("Class options", systemImage: "ellipsis.circle")
                }
            }
        }
        .confirmationDialog("Delete \(schoolClass?.name ?? "this class")?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete the class, its students and their scores", role: .destructive) {
                scoreBook.deleteClass(classID)
                dismiss()
            }
        }
        .alert(renaming?.isClass == true ? "Rename class" : "Rename student", isPresented: Binding(
            get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: Binding(get: { renaming?.name ?? "" }, set: { renaming?.name = $0 }))
            Button("Cancel", role: .cancel) { renaming = nil }
            Button("Save") { saveRename() }
        }
        .sheet(isPresented: $pasting) {
            PasteListSheet(text: $pasted) {
                let result = scoreBook.addStudents(fromList: pasted, to: classID)
                var message = "Added \(result.added) student\(result.added == 1 ? "" : "s")."
                if result.skipped > 0 { message += " \(result.skipped) were already in the class." }
                app.showToast(message)
            }
        }
        .sheet(item: Binding(get: { exportURL.map(ExportFile.init) }, set: { exportURL = $0?.url })) { file in
            ExportSheet(file: file)
        }
        .pdfPreview($preview)
    }

    @ViewBuilder
    private func overview(_ records: [CheckRecord]) -> some View {
        if records.isEmpty {
            Text("No papers checked yet.").foregroundStyle(.secondary)
        } else {
            let average = Int((ScoreBook.average(records) ?? 0).rounded())
            Text("Average \(average)% across \(records.count) paper\(records.count == 1 ? "" : "s")")
                .font(.headline)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(ScoreBook.gradeCounts(records), id: \.0) { grade, count in
                        Pill(text: "\(grade)  \(count)")
                    }
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("Topics to revise").font(.subheadline.weight(.semibold))
                let topics = ScoreBook.topicLosses(records)
                if !topics.isEmpty {
                    ForEach(topics.prefix(6)) { item in
                        Text("\(item.topic)  ·  \(item.lost) mark\(item.lost == 1 ? "" : "s") lost across "
                             + "\(item.questions) part\(item.questions == 1 ? "" : "s")")
                            .font(.footnote)
                    }
                } else if ScoreBook.topicsRecorded(records) {
                    Text("No marks lost on a topic so far.").font(.footnote).foregroundStyle(.secondary)
                } else {
                    Text("Topic insights show up after the next paper you check.").font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
    }

    private func addStudent() {
        do {
            try scoreBook.addStudent(newStudent, to: classID)
            newStudent = ""
        } catch {
            app.showToast(friendlyMessage(error), error: true)
        }
    }

    private func saveRename() {
        guard let renaming else { return }
        do {
            if renaming.isClass {
                try scoreBook.renameClass(renaming.id, to: renaming.name)
            } else {
                try scoreBook.renameStudent(renaming.id, to: renaming.name)
            }
            self.renaming = nil
        } catch {
            app.showToast(friendlyMessage(error), error: true)
        }
    }
}

@MainActor
private struct StudentRow: View {
    @EnvironmentObject private var scoreBook: ScoreBook
    var student: Student
    var records: [CheckRecord]
    var onRename: () -> Void
    var onPreview: (URL) -> Void

    @State private var expanded = false
    @State private var confirmDelete = false

    private var subtitle: String {
        guard let latest = records.first else { return "No papers checked yet" }
        let average = Int((ScoreBook.average(records) ?? 0).rounded())
        return "\(records.count) paper\(records.count == 1 ? "" : "s") checked · average \(average)% · latest "
            + "\(latest.score)/\(latest.maxScore)" + (latest.grade.map { " (\($0))" } ?? "")
    }

    var body: some View {
        DisclosureGroup(isExpanded: $expanded) {
            if records.isEmpty {
                Text("Check a paper and choose \(student.name) to see scores here.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            ForEach(records) { record in
                ResultRow(record: record, onPreview: onPreview)
                    .swipeActions {
                        Button(role: .destructive) {
                            scoreBook.deleteResult(record.id)
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
            }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(student.name).font(.body.weight(.medium))
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Menu {
                    Button(action: onRename) { Label("Rename", systemImage: "pencil") }
                    Button(role: .destructive) { confirmDelete = true } label: {
                        Label("Delete student", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .buttonStyle(.borderless)
            }
        }
        .confirmationDialog("Delete \(student.name)?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete \(student.name) and their scores", role: .destructive) {
                scoreBook.deleteStudent(student.id)
            }
        }
    }
}

@MainActor
private struct ResultRow: View {
    var record: CheckRecord
    var onPreview: (URL) -> Void
    @State private var showingReport = false

    var body: some View {
        let m = record.metadata
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("\(m.subjectName ?? "Paper") · Paper \(m.paper ?? "")\(m.variant ?? "")")
                    .font(.subheadline.weight(.medium))
                Text("\(Catalog.sessionLabel(m.session ?? "")) \(m.year ?? "") · checked "
                     + record.checkedAt.formatted(date: .abbreviated, time: .omitted))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Text("\(record.score)/\(record.maxScore)")
                .font(.subheadline.weight(.semibold))
            if let grade = record.grade {
                Pill(text: grade)
            }
            Button {
                showingReport = true
            } label: {
                Image(systemName: "text.badge.xmark")
            }
            .buttonStyle(.borderless)
            if let url = AppPaths.resolve(record.markedPDF) {
                Button {
                    onPreview(url)
                } label: {
                    Image(systemName: "doc.richtext")
                }
                .buttonStyle(.borderless)
            }
        }
        .sheet(isPresented: $showingReport) {
            ReportSheet(record: record)
        }
    }
}

/// The mistakes report for one checked paper (made on the spot for papers checked before reports existed).
@MainActor
private struct ReportSheet: View {
    var record: CheckRecord
    @EnvironmentObject private var scoreBook: ScoreBook
    @Environment(\.dismiss) private var dismiss

    private var file: URL? {
        if let saved = AppPaths.resolve(record.reportFile) { return saved }
        let student = scoreBook.student(record.studentID)
        let text = MistakesReport.text(marking: record.marking, metadata: record.metadata, student: student?.name,
                                       className: scoreBook.schoolClass(student?.classID)?.name, grade: record.grade,
                                       checkedAt: record.checkedAt)
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(ClassFolders.paperName(record.metadata) + " - mistakes.txt")
        return (try? Data(text.utf8).write(to: url, options: .atomic)) != nil ? url : nil
    }

    var body: some View {
        let url = file
        NavigationStack {
            ScrollView {
                Text(url.flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? "The report couldn't be opened.")
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
            }
            .navigationTitle("Mistakes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                if let url {
                    ToolbarItem(placement: .primaryAction) {
                        ShareLink(item: url) { Label("Share", systemImage: "square.and.arrow.up") }
                    }
                }
            }
        }
    }
}

/// Paste names from a spreadsheet or a message: one per line (commas work too).
@MainActor
private struct PasteListSheet: View {
    @Binding var text: String
    var onAdd: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 10) {
                Text("One name per line. Names already in the class are skipped.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                TextEditor(text: $text)
                    .padding(6)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Color(uiColor: .secondarySystemBackground)))
            }
            .padding()
            .navigationTitle("Paste a class list")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add students") {
                        onAdd()
                        dismiss()
                    }
                    .disabled(text.trimmed.isEmpty)
                }
            }
        }
    }
}

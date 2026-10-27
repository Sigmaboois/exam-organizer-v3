import Foundation
import SwiftUI

struct Toast: Identifiable, Equatable {
    let id = UUID()
    let message: String
    let isError: Bool
}

/// Shared state: which screen is open, messages, settings, and the three screens' models.
@MainActor
final class AppModel: ObservableObject {
    enum Screen: String, CaseIterable, Identifiable, Hashable {
        case organise, check, classes

        var id: String { rawValue }

        var title: String {
            switch self {
            case .organise: return "Organise"
            case .check: return "Check papers"
            case .classes: return "Classes"
            }
        }

        var systemImage: String {
            switch self {
            case .organise: return "folder"
            case .check: return "checkmark.seal"
            case .classes: return "person.3"
            }
        }
    }

    static let keyName = "gemini_key"

    @Published var screen: Screen? = .organise
    @Published var toast: Toast?
    @Published var geminiKey: String {
        didSet { UserDefaults.standard.set(geminiKey, forKey: Self.keyName) }
    }

    let scoreBook: ScoreBook
    let organise: OrganiseModel
    let check: CheckModel

    init() {
        let book = ScoreBook()
        scoreBook = book
        organise = OrganiseModel()
        check = CheckModel(scoreBook: book)
        geminiKey = UserDefaults.standard.string(forKey: Self.keyName) ?? ""
        organise.app = self
        check.app = self
        // Working copies from last time aren't in any list any more
        try? FileManager.default.removeItem(at: AppPaths.imports)
    }

    func showToast(_ message: String, error: Bool = false) {
        toast = Toast(message: message, isError: error)
    }

    /// A PDF sent to the app with Share / Open in from another app.
    func openIncoming(_ url: URL) {
        guard let copy = Importer.copyIncoming(url) else {
            showToast("Only PDFs can be opened in Exam Organizer.", error: true)
            return
        }
        organise.add([copy])
        screen = .organise
        showToast("Added \(url.lastPathComponent).")
    }
}

// MARK: - Organise

struct ExamItem: Identifiable {
    enum Status: Equatable { case waiting, reading, ready, problem, sorting, sorted }

    let id = UUID()
    var file: URL
    var name: String
    var status: Status = .waiting
    var metadata: PaperMetadata?
    var problem: String?
    var answers: SolveResult?
    var checkingAnswers = false
    var sortedTo: URL?
    var sortedInto: Destination?    // a picked folder must be opened again to read sortedTo
}

@MainActor
final class OrganiseModel: ObservableObject {
    weak var app: AppModel?

    @Published var items: [ExamItem] = []
    @Published private(set) var isSorting = false
    @Published var destination: Destination {
        didSet {
            if let data = try? JSONEncoder().encode(destination) {
                UserDefaults.standard.set(data, forKey: "destination")
            }
        }
    }

    private var reading = false

    init() {
        if let data = UserDefaults.standard.data(forKey: "destination"),
           let saved = try? JSONDecoder().decode(Destination.self, from: data) {
            destination = saved
        } else {
            destination = .library
        }
    }

    var readyCount: Int { items.filter { $0.status == .ready }.count }

    var summary: String {
        guard !items.isEmpty else { return "" }
        var parts = ["\(items.count) file\(items.count == 1 ? "" : "s")"]
        let counts: [(String, [ExamItem.Status])] = [("being read", [.waiting, .reading]), ("ready", [.ready]),
                                                     ("sorted", [.sorted]), ("need attention", [.problem])]
        for (label, statuses) in counts {
            let count = items.filter { statuses.contains($0.status) }.count
            if count > 0 { parts.append("\(count) \(label)") }
        }
        return parts.joined(separator: " · ")
    }

    private func update(_ id: UUID, _ change: (inout ExamItem) -> Void) {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        change(&items[index])
    }

    func add(_ files: [URL]) {
        for file in files {
            items.append(ExamItem(file: file, name: file.lastPathComponent))
        }
        readWaiting()
    }

    func remove(_ id: UUID) {
        guard let item = items.first(where: { $0.id == id }), item.status != .sorting else { return }
        items.removeAll { $0.id == id }
        AppPaths.removeImport(item.file)
    }

    func clearFinished() {
        let finished = items.filter { $0.status == .sorted }
        items.removeAll { $0.status == .sorted }
        finished.forEach { AppPaths.removeImport($0.file) }
    }

    func clearAll() {
        guard !isSorting else { return }
        items.forEach { AppPaths.removeImport($0.file) }
        items.removeAll()
    }

    /// The teacher fixed the details by hand.
    func apply(_ metadata: PaperMetadata, to id: UUID) {
        update(id) { item in
            item.metadata = metadata
            item.problem = nil
            item.status = .ready
            item.answers = nil
        }
        if metadata.paperType == "QP" {
            Task { await detectAnswers(id) }
        }
    }

    /// Send a solved question paper to the Check screen.
    func sendToCheck(_ id: UUID) {
        guard let item = items.first(where: { $0.id == id }), let app else { return }
        let source = item.sortedTo ?? item.file
        let copy = try? (item.sortedInto ?? .library).withAccess { _ in
            try AppPaths.importCopy(of: source, name: item.name)
        }
        guard let copy else {
            app.showToast("Couldn't copy this paper.", error: true)
            return
        }
        app.check.add([copy], metadata: item.metadata, answers: item.answers)
        app.screen = .check
    }

    // MARK: Reading

    private func readWaiting() {
        guard !reading else { return }
        reading = true
        Task {
            await readAll()
            reading = false
        }
    }

    private func readAll() async {
        while let index = items.firstIndex(where: { $0.status == .waiting }) {
            let id = items[index].id
            let file = items[index].file
            let name = items[index].name
            items[index].status = .reading
            let found: (PaperMetadata, String?) = await Task.detached(priority: .userInitiated) {
                do {
                    let text = try PDFTools.text(of: file)
                    let result = PaperParser.analyse(text: text, filename: name)
                    return (result.metadata, result.problem)
                } catch {
                    return (PaperMetadata(), friendlyMessage(error))
                }
            }.value
            update(id) { item in
                item.metadata = found.0
                item.problem = found.1
                item.status = found.1 == nil ? .ready : .problem
            }
            if found.1 == nil && found.0.paperType == "QP" {
                await detectAnswers(id)
            }
        }
    }

    private func detectAnswers(_ id: UUID) async {
        guard let item = items.first(where: { $0.id == id }), let metadata = item.metadata else { return }
        update(id) { $0.checkingAnswers = true }
        let clean = try? await CambridgeFiles.get(metadata, .questionPaper)
        let file = item.file
        let result = await Task.detached(priority: .utility) {
            try? SolveDetector.detect(student: file, clean: clean)
        }.value
        update(id) { item in
            item.answers = result
            item.checkingAnswers = false
        }
    }

    // MARK: Sorting

    func sortAll() async {
        let ready = items.filter { $0.status == .ready }
        guard !ready.isEmpty, !isSorting else { return }
        isSorting = true
        let destination = self.destination
        var sorted = 0
        var failed = 0
        for item in ready {
            guard let metadata = item.metadata else { continue }
            update(item.id) { $0.status = .sorting }
            let file = item.file
            let placed: Result<URL, Error> = await Task.detached(priority: .userInitiated) {
                do {
                    let url = try destination.withAccess { root in try Library.place(file, metadata: metadata, root: root) }
                    return .success(url)
                } catch {
                    return .failure(error)
                }
            }.value
            switch placed {
            case .success(let url):
                update(item.id) { item in
                    item.status = .sorted
                    item.sortedTo = url
                    item.sortedInto = destination
                }
                sorted += 1
            case .failure(let error):
                update(item.id) { item in
                    item.status = .problem
                    item.problem = friendlyMessage(error)
                }
                failed += 1
            }
        }
        isSorting = false
        var message = "Sorted \(sorted) exam\(sorted == 1 ? "" : "s") into \(destination.bookmark == nil ? "your library" : destination.label)."
        if failed > 0 { message += " \(failed) couldn't be sorted." }
        app?.showToast(message, error: sorted == 0 && failed > 0)
    }
}

// MARK: - Check papers

struct CheckOutcome {
    var marking: Marking
    var grade: String?
    var markedPDF: URL
    var report: URL?    // the mistakes report next to the marked PDF
}

struct CheckPaper: Identifiable {
    enum Status: Equatable { case waiting, reading, ready, queued, checking, done, problem }

    let id = UUID()
    var file: URL
    var name: String
    var status: Status = .waiting
    var metadata: PaperMetadata?
    var answers: SolveResult?
    var message = ""
    var progress = ""
    var studentID: UUID?
    var dontSave = false
    var outcome: CheckOutcome?
    var resultID: UUID?    // its score book entry, replaced if the paper is checked again

    var checkable: Bool { metadata?.isQuestionPaper == true }
    var saved: Bool { resultID != nil }
}

@MainActor
final class CheckModel: ObservableObject {
    weak var app: AppModel?
    let scoreBook: ScoreBook

    @Published var papers: [CheckPaper] = []
    @Published private(set) var busyWith: UUID?
    @Published var lastResult: String?

    private var reading = false
    private var checking = false

    init(scoreBook: ScoreBook) {
        self.scoreBook = scoreBook
    }

    var readyCount: Int { papers.filter { $0.status == .ready && $0.checkable }.count }

    var summary: String {
        guard !papers.isEmpty else { return "" }
        var parts = ["\(papers.count) paper\(papers.count == 1 ? "" : "s")"]
        let counts: [(String, [CheckPaper.Status])] = [("being read", [.waiting, .reading]), ("ready", [.ready]),
                                                       ("checking", [.queued, .checking]), ("checked", [.done]),
                                                       ("need attention", [.problem])]
        for (label, statuses) in counts {
            let count = papers.filter { statuses.contains($0.status) }.count
            if count > 0 { parts.append("\(count) \(label)") }
        }
        return parts.joined(separator: " · ")
    }

    func find(_ id: UUID) -> CheckPaper? {
        papers.first { $0.id == id }
    }

    private func update(_ id: UUID, _ change: (inout CheckPaper) -> Void) {
        guard let index = papers.firstIndex(where: { $0.id == id }) else { return }
        change(&papers[index])
    }

    func add(_ files: [URL], metadata: PaperMetadata? = nil, answers: SolveResult? = nil) {
        for file in files {
            var paper = CheckPaper(file: file, name: file.lastPathComponent)
            paper.metadata = metadata
            paper.answers = answers
            papers.append(paper)
        }
        readWaiting()
    }

    /// A paper scanned with the camera: the teacher has already said whose it is and which exam,
    /// so it goes straight into the checking queue.
    func addScanned(_ file: URL, metadata: PaperMetadata, studentID: UUID, pages: Int) {
        var paper = CheckPaper(file: file, name: file.lastPathComponent)
        paper.metadata = metadata
        paper.answers = SolveResult(solved: true, pages: Array(1...max(1, pages)), method: "scanned", pageCount: pages)
        paper.studentID = studentID
        paper.status = .ready
        papers.insert(paper, at: 0)
        queue(paper.id)
    }

    func remove(_ id: UUID) {
        guard let paper = find(id), paper.status != .checking else { return }
        papers.removeAll { $0.id == id }
        AppPaths.removeImport(paper.file)
    }

    func clearAll() {
        let kept = papers.filter { $0.status == .checking }
        papers.filter { $0.status != .checking }.forEach { AppPaths.removeImport($0.file) }
        papers = kept
    }

    func apply(_ metadata: PaperMetadata, to id: UUID) {
        update(id) { paper in
            paper.metadata = metadata
            paper.answers = nil
            paper.message = ""
            paper.status = .waiting
        }
        readWaiting()
    }

    // MARK: Students

    /// Choose whose paper this is (nil = not chosen yet). Saves straight away if it's already checked.
    func choose(student: UUID?, dontSave: Bool = false, for id: UUID) {
        update(id) { paper in
            paper.studentID = student
            paper.dontSave = dontSave
        }
        if let paper = find(id), paper.status == .done, !paper.saved, student != nil {
            save(id)
        }
    }

    func assignAll(to studentID: UUID) {
        var assigned = 0
        var skipped = 0
        for paper in papers {
            if paper.status == .checking {
                skipped += 1
                continue
            }
            update(paper.id) { item in
                item.studentID = studentID
                item.dontSave = false
            }
            assigned += 1
            if paper.status == .done && !paper.saved {
                save(paper.id, quiet: true)
            }
        }
        let name = scoreBook.student(studentID)?.name ?? "that student"
        let note = skipped > 0 ? " Left \(skipped) that's still being checked." : ""
        app?.showToast("Assigned \(assigned) paper\(assigned == 1 ? "" : "s") to \(name).\(note)")
    }

    // MARK: Reading

    private func readWaiting() {
        guard !reading else { return }
        reading = true
        Task {
            await readAll()
            reading = false
        }
    }

    private func readAll() async {
        while let index = papers.firstIndex(where: { $0.status == .waiting }) {
            let paper = papers[index]
            papers[index].status = .reading
            var metadata = paper.metadata
            var problem: String?
            if metadata == nil {
                let file = paper.file
                let name = paper.name
                let found: (PaperMetadata, String?) = await Task.detached(priority: .userInitiated) {
                    do {
                        let result = PaperParser.analyse(text: try PDFTools.text(of: file), filename: name)
                        return (result.metadata, result.problem)
                    } catch {
                        return (PaperMetadata(), friendlyMessage(error))
                    }
                }.value
                metadata = found.0
                problem = found.1
            }
            if problem == nil, let type = metadata?.paperType, type != "QP" {
                let label = (Catalog.paperTypes[type] ?? "different file").lowercased()
                problem = "This is a \(label). Add the student's question paper instead."
            }
            var answers = paper.answers
            if problem == nil, answers == nil, let metadata {
                let clean = try? await CambridgeFiles.get(metadata, .questionPaper)
                let file = paper.file
                answers = await Task.detached(priority: .utility) {
                    try? SolveDetector.detect(student: file, clean: clean)
                }.value
            }
            let finalMetadata = metadata
            let finalAnswers = answers
            let finalProblem = problem
            update(paper.id) { item in
                item.metadata = finalMetadata
                item.answers = finalAnswers
                item.message = finalProblem ?? ""
                item.status = finalProblem == nil ? .ready : .problem
            }
        }
    }

    // MARK: Checking

    func queue(_ id: UUID) {
        guard let paper = find(id), paper.checkable, paper.status != .queued, paper.status != .checking else { return }
        update(id) { $0.status = .queued }
        runQueue()
    }

    func checkAll() {
        for paper in papers where paper.status == .ready && paper.checkable {
            update(paper.id) { $0.status = .queued }
        }
        runQueue()
    }

    /// Checks run one at a time, so the shared free AI quota isn't used up in bursts.
    private func runQueue() {
        guard !checking else { return }
        checking = true
        Task {
            while let next = papers.first(where: { $0.status == .queued }) {
                await check(next.id)
            }
            checking = false
        }
    }

    /// The student's own folder (Classes/<class>/<student>), or "Not saved to a student".
    private func markedURL(_ metadata: PaperMetadata, studentID: UUID?) -> URL {
        let folder = studentID.flatMap { scoreBook.folder(forStudent: $0) } ?? ClassFolders.unassigned
        return ClassFolders.freeMarkedURL(in: folder, base: ClassFolders.paperName(metadata))
    }

    /// Move a checked paper's files into its student's folder (the student was chosen after checking).
    private func moveIntoStudentFolder(_ id: UUID) {
        guard let paper = find(id), var outcome = paper.outcome, let studentID = paper.studentID,
              let folder = scoreBook.folder(forStudent: studentID) else { return }
        guard outcome.markedPDF.deletingLastPathComponent().standardizedFileURL != folder.standardizedFileURL else { return }
        let manager = FileManager.default
        let oldStem = outcome.markedPDF.deletingPathExtension().lastPathComponent
        let base = oldStem.hasSuffix(" - marked") ? String(oldStem.dropLast(" - marked".count)) : oldStem
        let newPDF = ClassFolders.freeMarkedURL(in: folder, base: base)
        guard (try? manager.moveItem(at: outcome.markedPDF, to: newPDF)) != nil else { return }
        if let report = outcome.report {
            let newReport = ClassFolders.reportURL(for: newPDF)
            outcome.report = (try? manager.moveItem(at: report, to: newReport)) != nil ? newReport : nil
        }
        outcome.markedPDF = newPDF
        let updated = outcome
        update(id) { $0.outcome = updated }
        rewriteReport(id)   // it names the student
    }

    private func rewriteReport(_ id: UUID) {
        guard let paper = find(id), var outcome = paper.outcome, let metadata = paper.metadata else { return }
        let student = scoreBook.student(paper.studentID)
        outcome.report = try? MistakesReport.write(
            marking: outcome.marking, metadata: metadata, student: student?.name,
            className: scoreBook.schoolClass(student?.classID)?.name, grade: outcome.grade, next: outcome.markedPDF)
        let updated = outcome
        update(id) { $0.outcome = updated }
    }

    private func check(_ id: UUID) async {
        guard let paper = find(id), let metadata = paper.metadata else { return }
        busyWith = id
        update(id) { item in
            item.status = .checking
            item.progress = "Finding the mark scheme…"
        }
        let student = scoreBook.student(paper.studentID)
        let className = scoreBook.schoolClass(student?.classID)?.name
        let key = app?.geminiKey
        let file = paper.file

        do {
            let scheme = try await CambridgeFiles.get(metadata, .markScheme)
            update(id) { $0.progress = "Marking the answers… (this can take a minute)" }
            var marking = try await Task.detached(priority: .userInitiated) {
                try await Marker.mark(student: file, markScheme: scheme, metadata: metadata, keyOverride: key)
            }.value

            update(id) { $0.progress = "Working out the grade…" }
            var grade: String?
            // The AI's own "paper total" isn't reliable, so only warn about missing parts
            // when Cambridge's official maximum mark says something is missing
            marking.complete = true
            if let thresholds = try? await ThresholdReader.thresholds(for: metadata) {
                grade = thresholds.grade(score: marking.total, outOf: marking.maxTotal)
                marking.paperTotalMarks = thresholds.maxMark
                marking.complete = marking.maxTotal == thresholds.maxMark
            }

            update(id) { $0.progress = "Making the marked copy…" }
            let output = markedURL(metadata, studentID: paper.dontSave ? nil : paper.studentID)
            let finalMarking = marking
            let finalGrade = grade
            let studentName = student?.name
            let report: URL? = try await Task.detached(priority: .userInitiated) {
                try MarkedPDF.make(student: file, marking: finalMarking, output: output, title: metadata.heading,
                                   studentName: studentName, grade: finalGrade)
                return try? MistakesReport.write(marking: finalMarking, metadata: metadata, student: studentName,
                                                 className: className, grade: finalGrade, next: output)
            }.value

            update(id) { item in
                item.outcome = CheckOutcome(marking: finalMarking, grade: finalGrade, markedPDF: output, report: report)
                item.status = .done
                item.progress = ""
            }
            if let current = self.find(id), current.studentID != nil, !current.dontSave {
                save(id, quiet: true)
            }
            lastResult = "\(metadata.title): \(finalMarking.total)/\(finalMarking.maxTotal)"
                + (finalGrade.map { ", grade \($0)" } ?? "")
        } catch {
            update(id) { item in
                item.status = .problem
                item.message = friendlyMessage(error)
                item.progress = ""
            }
        }
        busyWith = nil
    }

    /// Save a checked paper to its student's scores (replacing an earlier save of the same paper).
    func save(_ id: UUID, quiet: Bool = false) {
        // The student (or their class) was deleted after the paper was assigned: ask again, don't save under nobody
        if let paper = find(id), let studentID = paper.studentID, scoreBook.student(studentID) == nil {
            update(id) { item in
                item.studentID = nil
                item.resultID = nil   // deleted along with the student
            }
            app?.showToast("That student was deleted. Choose whose paper \(paper.name) is.", error: true)
            return
        }
        moveIntoStudentFolder(id)
        guard let paper = find(id), let outcome = paper.outcome, let studentID = paper.studentID,
              let metadata = paper.metadata else { return }
        if let old = paper.resultID {
            scoreBook.deleteResult(old)
        }
        let resultID = scoreBook.saveResult(studentID: studentID, metadata: metadata, marking: outcome.marking,
                                            grade: outcome.grade, markedPDF: outcome.markedPDF, report: outcome.report)
        update(id) { $0.resultID = resultID }
        if !quiet {
            app?.showToast("Saved to \(scoreBook.student(studentID)?.name ?? "the student")'s scores.")
        }
    }

    /// Apply a teacher's mark changes: regrade, redraw the marked PDF and update the score book.
    func revise(_ id: UUID, awards: [Int]) async throws {
        guard let paper = find(id), var outcome = paper.outcome, let metadata = paper.metadata else { return }
        var marking = outcome.marking
        guard awards.count == marking.questions.count else {
            throw UserError("The mark list doesn't match the questions.")
        }
        for (index, award) in awards.enumerated() {
            let maxMarks = marking.questions[index].maxMarks
            guard award >= 0, award <= maxMarks else {
                throw UserError("\(marking.questions[index].question) is out of \(maxMarks).")
            }
            marking.questions[index].marksAwarded = award
        }
        marking.recount()
        marking.teacherAdjusted = true
        marking.needsReview = []
        var grade = outcome.grade
        if let thresholds = try? await ThresholdReader.thresholds(for: metadata) {
            grade = thresholds.grade(score: marking.total, outOf: marking.maxTotal)
            marking.paperTotalMarks = thresholds.maxMark
            marking.complete = marking.maxTotal == thresholds.maxMark
        }
        let studentName = scoreBook.student(paper.studentID)?.name
        // Renaming a class or student moves its folders, so redraw the saved copy where it is now
        if let record = scoreBook.results.first(where: { $0.id == paper.resultID }), let path = record.markedPDF {
            outcome.markedPDF = AppPaths.documents.appendingPathComponent(path)
        }
        let output = outcome.markedPDF
        let file = paper.file
        let finalMarking = marking
        let finalGrade = grade
        try await Task.detached(priority: .userInitiated) {
            try MarkedPDF.make(student: file, marking: finalMarking, output: output, title: metadata.heading,
                               studentName: studentName, grade: finalGrade)
        }.value
        outcome.marking = finalMarking
        outcome.grade = finalGrade
        let updated = outcome
        update(id) { $0.outcome = updated }
        rewriteReport(id)
        if let resultID = paper.resultID {
            scoreBook.updateResult(resultID, marking: finalMarking, grade: finalGrade, markedPDF: output,
                                   report: find(id)?.outcome?.report)
        }
    }
}

import Foundation

struct SchoolClass: Codable, Identifiable, Hashable {
    var id = UUID()
    var name: String
    var qualification: String?
}

struct Student: Codable, Identifiable, Hashable {
    var id = UUID()
    var classID: UUID
    var name: String
}

struct CheckRecord: Codable, Identifiable, Equatable {
    var id = UUID()
    var studentID: UUID
    var checkedAt: Date
    var metadata: PaperMetadata
    var score: Int
    var maxScore: Int
    var grade: String?
    var marking: Marking
    var markedPDF: String?    // relative to Documents (the app's container moves on updates)
    var reportFile: String?   // the mistakes report next to it (missing in records from older versions)

    var percent: Double? { maxScore > 0 ? 100.0 * Double(score) / Double(maxScore) : nil }
}

struct TopicLoss: Identifiable {
    var topic: String
    var lost: Int
    var max: Int
    var questions: Int
    var id: String { topic }
}

/// The teacher's score book: classes, students and checked papers. Saved as one JSON file.
/// Classes are grouped under qualification dividers based on the papers their students sit.
@MainActor
final class ScoreBook: ObservableObject {
    static let qualificationOrder = ["IGCSE", "O Level", "AS", "AS & A Level"]

    private struct Contents: Codable {
        var classes: [SchoolClass] = []
        var students: [Student] = []
        var results: [CheckRecord] = []
    }

    @Published private(set) var classes: [SchoolClass] = []
    @Published private(set) var students: [Student] = []
    @Published private(set) var results: [CheckRecord] = []

    private let file: URL

    init(file: URL = AppPaths.support.appendingPathComponent("scorebook.json")) {
        self.file = file
        if let data = try? Data(contentsOf: file) {
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            if let contents = try? decoder.decode(Contents.self, from: data) {
                classes = contents.classes
                students = contents.students
                results = contents.results
            }
        }
        ensureFolders()
    }

    private func save() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        if let data = try? encoder.encode(Contents(classes: classes, students: students, results: results)) {
            try? data.write(to: file, options: .atomic)
        }
    }

    private static func same(_ a: String, _ b: String) -> Bool {
        a.compare(b, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame
    }

    // MARK: Classes

    @discardableResult
    func addClass(_ rawName: String) throws -> SchoolClass {
        let name = rawName.trimmed
        guard !name.isEmpty else { throw UserError("Please give the class a name.") }
        guard !classes.contains(where: { Self.same($0.name, name) }) else {
            throw UserError("There is already a class called \"\(name)\".")
        }
        let added = SchoolClass(name: name)
        classes.append(added)
        ClassFolders.create(className: name)
        save()
        return added
    }

    func renameClass(_ id: UUID, to rawName: String) throws {
        let name = rawName.trimmed
        guard !name.isEmpty else { throw UserError("Please give the class a name.") }
        guard !classes.contains(where: { $0.id != id && Self.same($0.name, name) }) else {
            throw UserError("There is already a class called \"\(name)\".")
        }
        guard let index = classes.firstIndex(where: { $0.id == id }) else { return }
        let old = classes[index].name
        classes[index].name = name
        ClassFolders.renameClass(from: old, to: name)
        relocateFiles(from: ClassFolders.folder(className: old), to: ClassFolders.folder(className: name))
        save()
    }

    /// Folders moved: point the saved results at the files' new place.
    private func relocateFiles(from oldFolder: URL, to newFolder: URL) {
        guard let oldPrefix = AppPaths.relativePath(oldFolder.appendingPathComponent("x")).map({ String($0.dropLast()) }),
              let newPrefix = AppPaths.relativePath(newFolder.appendingPathComponent("x")).map({ String($0.dropLast()) }),
              oldPrefix != newPrefix else { return }
        func moved(_ path: String?) -> String? {
            guard let path, path.hasPrefix(oldPrefix) else { return path }
            return newPrefix + path.dropFirst(oldPrefix.count)
        }
        for index in results.indices {
            results[index].markedPDF = moved(results[index].markedPDF)
            results[index].reportFile = moved(results[index].reportFile)
        }
    }

    /// Make sure every class and student has a folder (for score books from before folders existed).
    func ensureFolders() {
        for schoolClass in classes {
            ClassFolders.create(className: schoolClass.name)
            for student in members(of: schoolClass.id) {
                ClassFolders.create(className: schoolClass.name, student: student.name)
            }
        }
    }

    func deleteClass(_ id: UUID) {
        let gone = Set(students.filter { $0.classID == id }.map(\.id))
        results.removeAll { gone.contains($0.studentID) }
        students.removeAll { $0.classID == id }
        classes.removeAll { $0.id == id }
        save()
    }

    func schoolClass(_ id: UUID?) -> SchoolClass? {
        classes.first { $0.id == id }
    }

    /// [(divider, classes)] in a sensible order. Unknown qualification goes under "Other".
    var classesByQualification: [(String, [SchoolClass])] {
        let groups = Dictionary(grouping: classes) { $0.qualification ?? "Other" }
        let order = Self.qualificationOrder + groups.keys.filter { !Self.qualificationOrder.contains($0) }.sorted()
        return order.compactMap { key in
            groups[key].map { (key, $0.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }) }
        }
    }

    // MARK: Students

    @discardableResult
    func addStudent(_ rawName: String, to classID: UUID) throws -> Student {
        let name = rawName.trimmed
        guard !name.isEmpty else { throw UserError("Please type the student's name.") }
        guard !students.contains(where: { $0.classID == classID && Self.same($0.name, name) }) else {
            throw UserError("\(name) is already in this class.")
        }
        let added = Student(classID: classID, name: name)
        students.append(added)
        if let className = schoolClass(classID)?.name {
            ClassFolders.create(className: className, student: name)
        }
        save()
        return added
    }

    /// Add one student per line (or comma). Returns (added, skipped as already there).
    func addStudents(fromList text: String, to classID: UUID) -> (added: Int, skipped: Int) {
        var added = 0, skipped = 0
        let names = text.components(separatedBy: CharacterSet(charactersIn: "\n,;\t"))
            .map { $0.trimmed }.filter { !$0.isEmpty }
        for name in names {
            if students.contains(where: { $0.classID == classID && Self.same($0.name, name) }) {
                skipped += 1
            } else {
                students.append(Student(classID: classID, name: name))
                if let className = schoolClass(classID)?.name {
                    ClassFolders.create(className: className, student: name)
                }
                added += 1
            }
        }
        if added > 0 { save() }
        return (added, skipped)
    }

    func renameStudent(_ id: UUID, to rawName: String) throws {
        let name = rawName.trimmed
        guard !name.isEmpty else { throw UserError("Please type the student's name.") }
        guard let index = students.firstIndex(where: { $0.id == id }) else { return }
        let classID = students[index].classID
        guard !students.contains(where: { $0.id != id && $0.classID == classID && Self.same($0.name, name) }) else {
            throw UserError("\(name) is already in this class.")
        }
        let old = students[index].name
        students[index].name = name
        if let className = schoolClass(classID)?.name {
            ClassFolders.renameStudent(className: className, from: old, to: name)
            relocateFiles(from: ClassFolders.folder(className: className, student: old),
                          to: ClassFolders.folder(className: className, student: name))
        }
        save()
    }

    func deleteStudent(_ id: UUID) {
        results.removeAll { $0.studentID == id }
        students.removeAll { $0.id == id }
        save()
    }

    func student(_ id: UUID?) -> Student? {
        students.first { $0.id == id }
    }

    func members(of classID: UUID) -> [Student] {
        students.filter { $0.classID == classID }
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    /// "Sara Khan (Year 11 Physics)" for menus
    func label(for studentID: UUID?) -> String? {
        guard let student = student(studentID) else { return nil }
        return student.name
    }

    // MARK: Results

    @discardableResult
    func saveResult(studentID: UUID, metadata: PaperMetadata, marking: Marking, grade: String?, markedPDF: URL?,
                    report: URL? = nil) -> UUID {
        // A class takes the qualification of the papers its students sit (first one wins)
        if let student = student(studentID), let index = classes.firstIndex(where: { $0.id == student.classID }),
           classes[index].qualification == nil, let qualification = metadata.qualification {
            classes[index].qualification = qualification
        }
        let record = CheckRecord(studentID: studentID, checkedAt: Date(), metadata: metadata, score: marking.total,
                                 maxScore: marking.maxTotal, grade: grade, marking: marking,
                                 markedPDF: markedPDF.flatMap(AppPaths.relativePath),
                                 reportFile: report.flatMap(AppPaths.relativePath))
        results.append(record)
        save()
        return record.id
    }

    /// Replace the score after a teacher corrects the AI's marks.
    func updateResult(_ id: UUID, marking: Marking, grade: String?, markedPDF: URL?, report: URL? = nil) {
        guard let index = results.firstIndex(where: { $0.id == id }) else { return }
        results[index].marking = marking
        results[index].score = marking.total
        results[index].maxScore = marking.maxTotal
        results[index].grade = grade
        if let markedPDF { results[index].markedPDF = AppPaths.relativePath(markedPDF) }
        if let report { results[index].reportFile = AppPaths.relativePath(report) }
        save()
    }

    /// Where a student's checked papers are kept.
    func folder(forStudent id: UUID) -> URL? {
        guard let student = student(id), let className = schoolClass(student.classID)?.name else { return nil }
        return ClassFolders.create(className: className, student: student.name)
    }

    func deleteResult(_ id: UUID) {
        results.removeAll { $0.id == id }
        save()
    }

    func records(forStudent id: UUID) -> [CheckRecord] {
        results.filter { $0.studentID == id }.sorted { $0.checkedAt > $1.checkedAt }
    }

    func records(forClass id: UUID) -> [CheckRecord] {
        let members = Set(students.filter { $0.classID == id }.map(\.id))
        return results.filter { members.contains($0.studentID) }.sorted { $0.checkedAt > $1.checkedAt }
    }

    nonisolated static func average(_ records: [CheckRecord]) -> Double? {
        let percents = records.compactMap(\.percent)
        return percents.isEmpty ? nil : percents.reduce(0, +) / Double(percents.count)
    }

    /// True once a checked paper includes a topic on any question.
    nonisolated static func topicsRecorded(_ records: [CheckRecord]) -> Bool {
        records.contains { record in record.marking.questions.contains { !$0.topic.trimmed.isEmpty } }
    }

    /// Topics where marks were lost, worst first.
    nonisolated static func topicLosses(_ records: [CheckRecord]) -> [TopicLoss] {
        var buckets: [String: TopicLoss] = [:]
        for record in records {
            for question in record.marking.questions {
                let topic = question.topic.trimmed
                guard !topic.isEmpty else { continue }
                var bucket = buckets[topic] ?? TopicLoss(topic: topic, lost: 0, max: 0, questions: 0)
                bucket.lost += max(0, question.maxMarks - question.marksAwarded)
                bucket.max += question.maxMarks
                bucket.questions += 1
                buckets[topic] = bucket
            }
        }
        return buckets.values.filter { $0.lost > 0 }.sorted {
            $0.lost != $1.lost ? $0.lost > $1.lost : $0.topic.lowercased() < $1.topic.lowercased()
        }
    }

    /// Grade counts in Cambridge order, for the class overview.
    nonisolated static func gradeCounts(_ records: [CheckRecord]) -> [(String, Int)] {
        var counts: [String: Int] = [:]
        for record in records { counts[record.grade ?? "Ungraded", default: 0] += 1 }
        let ordered = Catalog.gradeOrder.filter { counts[$0] != nil }
        let rest = counts.keys.filter { !ordered.contains($0) }.sorted()
        return (ordered + rest).map { ($0, counts[$0] ?? 0) }
    }

    // MARK: Spreadsheet

    /// A CSV file (opens in Excel, Numbers and Google Sheets) of these results.
    func exportCSV(_ records: [CheckRecord], name: String) throws -> URL {
        func field(_ value: String) -> String {
            value.contains(where: { ",\"\n".contains($0) }) ? "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : value
        }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        var lines = ["Student,Class,Qualification,Subject,Code,Paper,Series,Score,Out of,%,Grade,Checked on"]
        let sorted = records.sorted {
            let a = student($0.studentID)?.name ?? "", b = student($1.studentID)?.name ?? ""
            return a == b ? $0.checkedAt > $1.checkedAt : a.localizedStandardCompare(b) == .orderedAscending
        }
        for record in sorted {
            let student = self.student(record.studentID)
            let className = schoolClass(student?.classID)?.name ?? ""
            let m = record.metadata
            let series = "\((m.session ?? "").replacingOccurrences(of: "-", with: "/")) \(m.year ?? "")".trimmed
            let percent = record.percent.map { String(format: "%.1f", $0) } ?? ""
            lines.append([student?.name ?? "", className, m.qualification ?? "", m.subjectName ?? "", m.subjectCode ?? "",
                          "\(m.paper ?? "")\(m.variant ?? "")", series, String(record.score), String(record.maxScore),
                          percent, record.grade ?? "", formatter.string(from: record.checkedAt)].map(field).joined(separator: ","))
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(AppPaths.safeName(name) + ".csv")
        // A byte-order mark so Excel reads names with accents correctly
        try Data(("\u{FEFF}" + lines.joined(separator: "\r\n")).utf8).write(to: url, options: .atomic)
        return url
    }
}

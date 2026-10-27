import Foundation

/// Every class has a folder, with one sub-folder per student holding their checked exams:
/// Documents/Classes/<class>/<student>/<paper> - marked.pdf and <paper> - mistakes.txt.
/// The folders show in the Files app under On My iPhone › Exam Organizer › Classes.
enum ClassFolders {
    static var root: URL { AppPaths.folder(AppPaths.documents.appendingPathComponent("Classes", isDirectory: true)) }

    /// Papers checked without choosing a student
    static var unassigned: URL { AppPaths.folder(AppPaths.marked.appendingPathComponent("Not saved to a student", isDirectory: true)) }

    static func folder(className: String) -> URL {
        root.appendingPathComponent(AppPaths.safeName(className), isDirectory: true)
    }

    static func folder(className: String, student: String) -> URL {
        folder(className: className).appendingPathComponent(AppPaths.safeName(student), isDirectory: true)
    }

    @discardableResult
    static func create(className: String, student: String? = nil) -> URL {
        let url = student.map { folder(className: className, student: $0) } ?? folder(className: className)
        return AppPaths.folder(url)
    }

    /// Rename a folder, merging into an existing one if two names now match.
    static func move(_ from: URL, to: URL) {
        let manager = FileManager.default
        guard from.standardizedFileURL != to.standardizedFileURL, manager.fileExists(atPath: from.path) else {
            AppPaths.folder(to)
            return
        }
        if !manager.fileExists(atPath: to.path) {
            try? manager.createDirectory(at: to.deletingLastPathComponent(), withIntermediateDirectories: true)
            if (try? manager.moveItem(at: from, to: to)) != nil { return }
        }
        AppPaths.folder(to)
        for item in (try? manager.contentsOfDirectory(at: from, includingPropertiesForKeys: nil)) ?? [] {
            try? manager.moveItem(at: item, to: AppPaths.unique(to.appendingPathComponent(item.lastPathComponent)))
        }
        // Only once it's empty, so files that couldn't be moved aren't deleted
        if (try? manager.contentsOfDirectory(atPath: from.path))?.isEmpty == true {
            try? manager.removeItem(at: from)
        }
    }

    static func renameClass(from old: String, to new: String) {
        move(folder(className: old), to: folder(className: new))
    }

    static func renameStudent(className: String, from old: String, to new: String) {
        move(folder(className: className, student: old), to: folder(className: className, student: new))
    }

    /// "Physics P42 May-June 2024"
    static func paperName(_ metadata: PaperMetadata) -> String {
        AppPaths.safeName("\(metadata.subjectName ?? "Paper") P\(metadata.paper ?? "")\(metadata.variant ?? "") "
                          + "\(metadata.session ?? "") \(metadata.year ?? "")")
    }

    /// "<base> - marked.pdf" in that folder, or "<base> (2) - marked.pdf"… if it's taken, so the
    /// mistakes report next to it gets the same number.
    static func freeMarkedURL(in folder: URL, base: String) -> URL {
        var candidate = base
        var number = 2
        while FileManager.default.fileExists(atPath: folder.appendingPathComponent("\(candidate) - marked.pdf").path) {
            candidate = "\(base) (\(number))"
            number += 1
        }
        return folder.appendingPathComponent("\(candidate) - marked.pdf")
    }

    /// The report that goes next to a marked PDF.
    static func reportURL(for markedPDF: URL) -> URL {
        let stem = markedPDF.deletingPathExtension().lastPathComponent
        let base = stem.hasSuffix(" - marked") ? String(stem.dropLast(" - marked".count)) : stem
        return markedPDF.deletingLastPathComponent().appendingPathComponent("\(base) - mistakes.txt")
    }
}

/// A plain-text explanation of a checked paper: the score, every mark that was lost and why,
/// the feedback, and the topics to revise. Opens anywhere (Files, Notes, email, a PC).
enum MistakesReport {
    static func text(marking: Marking, metadata: PaperMetadata, student: String?, className: String?,
                     grade: String?, checkedAt: Date = Date()) -> String {
        var lines: [String] = []
        let rule = String(repeating: "=", count: 36)
        lines.append("EXAM ORGANIZER - MARKED PAPER")
        lines.append(rule)
        if let student {
            lines.append("Student: \(student)" + (className.map { " (\($0))" } ?? ""))
        }
        lines.append("Paper:   \(metadata.heading)")
        lines.append("Checked: \(checkedAt.formatted(date: .long, time: .shortened))")
        lines.append("")
        lines.append("Score:   \(marking.total) / \(marking.maxTotal) (\(marking.percent)%)")
        lines.append("Grade:   " + (grade.map { "\($0) (Cambridge grade thresholds)" } ?? "not available for this paper"))
        if !marking.complete {
            lines.append("Note:    the question marks add up to \(marking.maxTotal), but the paper is out of "
                         + "\(marking.paperTotalMarks). Some parts may be missing.")
        }
        if marking.teacherAdjusted { lines.append("Note:    marks were adjusted by the teacher.") }
        if !marking.needsReview.isEmpty {
            lines.append("Please double-check: \(marking.needsReview.joined(separator: ", "))")
        }
        if !marking.summary.isEmpty {
            lines.append("")
            lines.append(marking.summary)
        }

        let lost = marking.questions.filter { $0.marksAwarded < $0.maxMarks }
        lines.append("")
        lines.append("WHERE MARKS WERE LOST (\(lost.count) of \(marking.questions.count) parts)")
        lines.append(rule)
        if lost.isEmpty {
            lines.append("None - full marks on every part.")
        }
        for question in lost {
            lines.append("")
            lines.append("\(question.question)   \(question.marksAwarded)/\(question.maxMarks)   "
                         + "lost \(question.maxMarks - question.marksAwarded)"
                         + (question.topic.isEmpty ? "" : "   [\(question.topic)]"))
            if !question.studentAnswer.isEmpty { lines.append("  Student wrote: \(question.studentAnswer)") }
            if !question.marking.isEmpty { lines.append("  Why:           \(question.marking)") }
            if !question.feedback.isEmpty { lines.append("  Advice:        \(question.feedback)") }
        }

        let topics = ScoreBook.topicLosses([CheckRecord(studentID: UUID(), checkedAt: checkedAt, metadata: metadata,
                                                        score: marking.total, maxScore: marking.maxTotal,
                                                        grade: grade, marking: marking, markedPDF: nil)])
        if !topics.isEmpty {
            lines.append("")
            lines.append("TOPICS TO REVISE")
            lines.append(rule)
            for topic in topics {
                lines.append("- \(topic.topic): lost \(topic.lost) of \(topic.max) mark\(topic.max == 1 ? "" : "s")")
            }
        }

        lines.append("")
        lines.append("EVERY QUESTION")
        lines.append(rule)
        for question in marking.questions {
            let mark = question.marksAwarded == question.maxMarks ? "full marks" : "lost \(question.maxMarks - question.marksAwarded)"
            lines.append("\(question.question)   \(question.marksAwarded)/\(question.maxMarks)   \(mark)"
                         + (question.confidence == "low" ? "   (please double-check)" : ""))
        }
        lines.append("")
        lines.append("Marked by an AI examiner against Cambridge's official mark scheme. It can make mistakes,")
        lines.append("so check anything that looks wrong.")
        return lines.joined(separator: "\n") + "\n"
    }

    static func write(marking: Marking, metadata: PaperMetadata, student: String?, className: String?,
                      grade: String?, next markedPDF: URL) throws -> URL {
        let url = ClassFolders.reportURL(for: markedPDF)
        let text = text(marking: marking, metadata: metadata, student: student, className: className, grade: grade)
        try Data(text.utf8).write(to: url, options: .atomic)
        return url
    }
}

import Foundation

/// Cambridge's subject list ({"IGCSE": {"0625": "Physics", …}, …}), bundled with the app.
enum Subjects {
    static let table: [String: [String: String]] = {
        guard let url = Bundle.main.url(forResource: "subjects", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode([String: [String: String]].self, from: data) else {
            return [:]
        }
        return decoded
    }()

    /// (qualification, name) when the code belongs to exactly one qualification.
    static func lookup(_ code: String?) -> (qualification: String, name: String)? {
        guard let code else { return nil }
        let found = table.compactMap { qualification, subjects in
            subjects[code].map { (qualification: qualification, name: $0) }
        }
        return found.count == 1 ? found[0] : nil
    }

    static func all(for code: String) -> [(qualification: String, name: String)] {
        table.compactMap { qualification, subjects in subjects[code].map { (qualification: qualification, name: $0) } }
            .sorted { $0.qualification < $1.qualification }
    }

    static func name(_ code: String?, in qualification: String?) -> String? {
        guard let code, let qualification else { return nil }
        return table[qualification]?[code]
    }
}

/// Works out which exam a PDF is. A line-by-line port of the desktop app's parser.py.
enum PaperParser {
    static let kindFromFilename = ["qp": "QP", "ms": "MS", "in": "IN", "er": "ER", "gt": "GT", "ci": "CI"]
    static let letterSession = ["m": "February-March", "s": "May-June", "w": "October-November"]

    /// The same clean-up the desktop reader does: drop full stops and dot leaders.
    static func clean(_ text: String) -> String {
        RE.replace("[. ]{3,}", in: text.replacingOccurrences(of: ".", with: ""), with: "")
    }

    // MARK: Type of file

    /// MS, GT, ER, IN, CI, or nil when it still looks like a question paper (or isn't one).
    static func detectPaperType(_ text: String) -> String? {
        if text.isEmpty { return nil }
        let head = text.head(3000)
        // Only the UPPERCASE phrase counts: grade thresholds and examiner reports say
        // "mark scheme" in normal sentences
        if text.contains("MARK SCHEME") { return "MS" }
        if RE.contains("(?i)grade thresholds", in: head) { return "GT" }
        if RE.contains("(?i)principal examiner report|examiner report", in: head) { return "ER" }
        if RE.contains("(?i)confidential instructions", in: head) { return "CI" }
        // Inserts: an "INSERT" heading, "This (Reading Booklet) Insert contains…", or a barcode ending in -I
        // (*3736339926-I*). Some PDF readers split the barcode into one character per line.
        if RE.contains("(?im)^\\s*(?:READING BOOKLET\\s+)?INSERT\\s*$", in: head)
            || RE.contains("(?i)this (?:reading booklet )?insert contains", in: head)
            || RE.contains("\\*\\s*(?:\\d\\s*){10}-\\s*I\\s*\\*", in: head) {
            return "IN"
        }
        return nil
    }

    // MARK: Text

    static func qualification(in text: String) -> String? {
        if RE.contains("IGCSE|General Certificate of Secondary Education", in: text) { return "IGCSE" }
        if RE.contains("O Level|Ordinary Level", in: text) { return "O Level" }
        if RE.contains("Cambridge International AS Level|Advanced Subsidiary Level(?! and)", in: text) { return "AS" }
        if RE.contains("AS & A Level|Advanced Subsidiary and Advanced Level|Advanced Subsidiary Level and Advanced Level|International Advanced Level", in: text) {
            return "AS & A Level"
        }
        return nil
    }

    static func qualification(fromHeading heading: String) -> String? {
        let lower = heading.lowercased()
        if lower.contains("igcse") { return "IGCSE" }
        if lower.contains("o level") { return "O Level" }
        if lower.contains("as level") { return "AS" }
        if lower.contains("a level") { return "AS & A Level" }
        return nil
    }

    static func session(fromWords words: String) -> String? {
        switch words.lowercased() {
        case "june", "may/june", "may": return "May-June"
        case "february/march", "march", "february": return "February-March"
        case "october/november", "november", "october": return "October-November"
        default: return nil
        }
    }

    static func metaExtract(_ text: String) -> PaperMetadata {
        var metadata = PaperMetadata()
        metadata.paperType = detectPaperType(text)

        // Inserts use the same footer code as a question paper. Series files do not.
        if !["MS", "GT", "ER"].contains(metadata.paperType ?? "") {
            metadata.qualification = qualification(in: text)
            if let match = RE.first("\\d{4}/\\d{2}/\\w/\\w/\\d{2}", in: text), let code = match[0] {
                let pieces = code.split(separator: "/").map(String.init)
                metadata.subjectCode = pieces[0]
                metadata.paper = String(pieces[1].prefix(1))
                metadata.variant = String(pieces[1].suffix(1))
                // A re-saved PDF sometimes turns O (October) into 0
                switch pieces[2].uppercased().replacingOccurrences(of: "0", with: "O") {
                case "O": metadata.session = "October-November"
                case "M": metadata.session = "May-June"
                case "F": metadata.session = "February-March"
                default: break
                }
                metadata.year = "20" + pieces[4]
            } else {
                if let found = RE.first("(\\d{4})/(\\d)(\\d)", in: text) {
                    metadata.subjectCode = found[1]
                    metadata.paper = found[2]
                    metadata.variant = found[3]
                }
                if let found = RE.first("(February/March|May/June|October/November|March|June)\\s*(\\d{4})", in: text) {
                    metadata.year = found[2]
                    metadata.session = found[1].flatMap(session(fromWords:))
                }
            }
        }

        if metadata.paperType == "MS" {
            if let found = RE.first("(\\d{4})/(\\d{2})\\s+(.+?)\\s*[–-]\\s*Mark Scheme", in: text) {
                metadata.qualification = found[3].flatMap(qualification(fromHeading:))
                metadata.subjectCode = found[1]?.trimmed
                if let pv = found[2] {
                    metadata.paper = String(pv.prefix(1))
                    metadata.variant = String(pv.suffix(1))
                }
                if let series = RE.first("mark schemes for the\\s+(May/June|October/November|February/March|March|June)\\s+(\\d{4})", in: text) {
                    metadata.session = series[1].flatMap(session(fromWords:))
                    metadata.year = series[2]?.trimmed
                }
            } else if let found = RE.first("(.+?)\\s*[–-]\\s*(October/November|May/June|February/March|March|June)\\s+(\\d{4})\\s+(\\d{4})\\s+(\\d{2})", in: text) {
                metadata.qualification = found[1].flatMap(qualification(fromHeading:))
                metadata.session = found[2].flatMap(session(fromWords:))
                metadata.year = found[3]?.trimmed
                metadata.subjectCode = found[4]?.trimmed
                if let pv = found[5] {
                    metadata.paper = String(pv.prefix(1))
                    metadata.variant = String(pv.suffix(1))
                }
            }
        }

        fillSeriesGaps(&metadata, text)
        metadata.subjectName = Subjects.name(metadata.subjectCode, in: metadata.qualification)
        return metadata
    }

    /// Grade thresholds and examiner reports name the syllabus and the series, not one paper.
    static func fillSeriesGaps(_ metadata: inout PaperMetadata, _ text: String) {
        if text.isEmpty { return }
        if metadata.qualification == nil {
            metadata.qualification = qualification(in: text)
        }
        if metadata.subjectCode == nil {
            // "Syllabus 0606", "Paper 0606/11", "(0606)" or a heading like "Education 0606 Additional
            // Mathematics". Only numbers that are real subject codes count, so years are never picked.
            let head = text.head(1500)
            for pattern in ["Syllabus\\s+(\\d{4})", "(?<!\\d)(\\d{4})/\\d{1,2}(?!\\d)", "\\((\\d{4})\\)", "(?<!\\d)(\\d{4})(?!\\d)"] {
                if let code = RE.all(pattern, in: head).compactMap({ $0[1] }).first(where: { Subjects.lookup($0) != nil }) {
                    metadata.subjectCode = code
                    break
                }
            }
        }
        if metadata.year == nil || metadata.session == nil,
           let found = RE.first("(February/March|May/June|October/November|November|March|June)\\s*(\\d{4})", in: text) {
            if metadata.year == nil { metadata.year = found[2] }
            if metadata.session == nil { metadata.session = found[1].flatMap(session(fromWords:)) }
        }
    }

    // MARK: File name

    /// Cambridge's own file name, e.g. 9709_w23_qp_12.pdf or 0625_s24_gt.pdf, or this app's own names.
    static func fromFilename(_ filename: String) -> PaperMetadata {
        var metadata = PaperMetadata()
        let name = (filename as NSString).lastPathComponent
        if let found = RE.first("(?i)(?<!\\d)(\\d{4})[_\\-\\s]+([msw])(\\d{2})[_\\-\\s]+(qp|ms|in|er|gt|ci)(?:[_\\-\\s]+(\\d)(\\d))?(?!\\d)", in: name) {
            metadata.subjectCode = found[1]
            metadata.session = found[2].flatMap { letterSession[$0.lowercased()] }
            if let yy = found[3].flatMap({ Int($0) }) {
                metadata.year = String(yy >= 90 ? 1900 + yy : 2000 + yy)
            }
            metadata.paperType = found[4].flatMap { kindFromFilename[$0.lowercased()] }
            if let paper = found[5], let variant = found[6] {
                metadata.paper = paper
                metadata.variant = variant
            }
            return metadata
        }
        if let own = RE.first("(?i)Paper\\s+(\\d)\\s+v(\\d+)\\s+(QP|MS|IN|ER|GT|CI)\\b", in: name) {
            metadata.paper = own[1]
            metadata.variant = own[2]
            metadata.paperType = own[3]?.uppercased()
        }
        return metadata
    }

    // MARK: Putting it together

    static func fillSubject(_ metadata: inout PaperMetadata) {
        guard let code = metadata.subjectCode else { return }
        let found = Subjects.lookup(code)
        let chosen = metadata.qualification
        // A code that exists in exactly one list beats a loose text guess (9709 is not IGCSE)
        if let found, chosen == nil || Subjects.name(code, in: chosen) == nil {
            metadata.qualification = found.qualification
            metadata.subjectName = found.name
        } else if let name = Subjects.name(code, in: chosen) {
            metadata.subjectName = name
        } else if metadata.subjectName == nil {
            metadata.subjectName = found?.name ?? "Subject \(code)"
        }
    }

    /// Text first, then the file name fills anything the text didn't say.
    static func identify(text: String, filename: String?) -> PaperMetadata {
        var metadata = metaExtract(text)
        let explicit = detectPaperType(text) != nil
        let fromName = fromFilename(filename ?? "")
        metadata.qualification = metadata.qualification ?? fromName.qualification
        metadata.subjectName = metadata.subjectName ?? fromName.subjectName
        metadata.subjectCode = metadata.subjectCode ?? fromName.subjectCode
        metadata.year = metadata.year ?? fromName.year
        metadata.session = metadata.session ?? fromName.session
        metadata.paper = metadata.paper ?? fromName.paper
        metadata.variant = metadata.variant ?? fromName.variant
        if fromName.paperType != nil && !explicit {
            metadata.paperType = fromName.paperType
        }
        if metadata.paperType == nil && metadata.subjectCode != nil && metadata.paper != nil {
            metadata.paperType = "QP"
        }
        fillSubject(&metadata)
        return metadata
    }

    /// Question papers, mark schemes and inserts belong to one paper. A series file does not.
    static func needsComponent(_ metadata: PaperMetadata) -> Bool {
        switch metadata.paperType {
        case "GT": return false
        case "ER": return metadata.paper != nil || metadata.variant != nil
        default: return true
        }
    }

    static func missingFields(_ metadata: PaperMetadata) -> [String] {
        var missing: [String] = []
        if metadata.qualification == nil { missing.append("qualification") }
        if metadata.subjectCode == nil { missing.append("subject code") }
        if metadata.year == nil { missing.append("year") }
        if metadata.session == nil { missing.append("session") }
        if metadata.paperType == nil { missing.append("file type") }
        if needsComponent(metadata) {
            if metadata.paper == nil { missing.append("paper") }
            if metadata.variant == nil { missing.append("variant") }
        }
        return missing
    }

    /// Read one PDF's text and say what it is. problem is nil when it can be sorted.
    static func analyse(text rawText: String, filename: String) -> (metadata: PaperMetadata, problem: String?) {
        let text = clean(rawText)
        var metadata = identify(text: text, filename: filename)
        if text.trimmed.isEmpty && metadata.subjectCode == nil {
            return (metadata, "I couldn't find any text in this PDF. It might be a scanned image.")
        }
        if metadata.subjectCode == nil {
            return (metadata, "This doesn't look like a Cambridge paper.")
        }
        if metadata.year == nil || metadata.session == nil {
            return (metadata, "I couldn't tell which exam series this is from. If it was re-saved by a "
                    + "note-taking app, try the original PDF, or tap Fix details.")
        }
        if metadata.qualification == nil {
            return (metadata, "I couldn't tell whether this is IGCSE, O Level or AS & A Level.")
        }
        let missing = missingFields(metadata)
        if !missing.isEmpty {
            return (metadata, "Some details are missing: \(missing.joined(separator: ", ")). Tap Fix details.")
        }
        // Subjects Cambridge no longer offers aren't on their website, so give them a fallback name
        if metadata.subjectName == nil, let code = metadata.subjectCode {
            metadata.subjectName = "Subject \(code)"
        }
        return (metadata, nil)
    }

    // MARK: Fix details form

    struct ManualFields {
        var subjectCode = ""
        var qualification: String?
        var year = ""
        var session: String?
        var paper = ""
        var variant = ""
        var paperType: String?

        init() {}

        init(_ metadata: PaperMetadata?) {
            subjectCode = metadata?.subjectCode ?? ""
            qualification = metadata?.qualification
            year = metadata?.year ?? ""
            session = metadata?.session
            paper = metadata?.paper ?? ""
            variant = metadata?.variant ?? ""
            paperType = metadata?.paperType
        }
    }

    /// Turn the Fix details form into metadata, or an error message to show.
    static func completeManual(_ fields: ManualFields) -> (metadata: PaperMetadata, error: String?) {
        var metadata = PaperMetadata()
        metadata.subjectCode = fields.subjectCode.nilIfEmpty
        metadata.qualification = fields.qualification
        metadata.year = fields.year.nilIfEmpty
        metadata.session = fields.session
        metadata.paper = fields.paper.nilIfEmpty
        metadata.variant = fields.variant.nilIfEmpty
        metadata.paperType = fields.paperType

        if let year = metadata.year, !RE.contains("^\\d{4}$", in: year) {
            return (metadata, "Year should be 4 digits, like 2024.")
        }
        if let paper = metadata.paper, !RE.contains("^\\d$", in: paper) {
            return (metadata, "Paper should be a single digit, like 4.")
        }
        if let variant = metadata.variant, !RE.contains("^\\d$", in: variant) {
            return (metadata, "Variant should be a single digit, like 2. Use 0 for a paper with no variant.")
        }
        if let code = metadata.subjectCode, let chosen = metadata.qualification {
            let found = Subjects.all(for: code)
            if !found.isEmpty && !found.contains(where: { $0.qualification == chosen }) {
                if found.count == 1 {
                    return (metadata, "\(code) is \(found[0].qualification) (\(found[0].name)), not \(chosen).")
                }
                return (metadata, "\(code) isn't listed under \(chosen).")
            }
        }
        fillSubject(&metadata)
        let missing = missingFields(metadata)
        if !missing.isEmpty {
            return (metadata, "Still needed: \(missing.joined(separator: ", ")).")
        }
        return (metadata, nil)
    }
}

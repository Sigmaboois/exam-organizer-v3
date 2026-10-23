package com.gasserelshafei.examorganizer

import android.content.Context
import org.json.JSONObject

/** Cambridge's subject list ({"IGCSE": {"0625": "Physics", …}, …}), bundled with the app. */
object Subjects {
    var table: Map<String, Map<String, String>> = emptyMap()
        private set

    fun load(context: Context) {
        load(context.assets.open("subjects.json").bufferedReader().use { it.readText() })
    }

    fun load(json: String) {
        val root = JSONObject(json)
        table = root.keys().asSequence().associateWith { qualification ->
            val subjects = root.getJSONObject(qualification)
            subjects.keys().asSequence().associateWith { subjects.getString(it) }
        }
    }

    /** (qualification, name) when the code belongs to exactly one qualification. */
    fun lookup(code: String?): Pair<String, String>? {
        if (code == null) return null
        val found = table.mapNotNull { (qualification, subjects) -> subjects[code]?.let { qualification to it } }
        return found.singleOrNull()
    }

    fun all(code: String): List<Pair<String, String>> =
        table.mapNotNull { (qualification, subjects) -> subjects[code]?.let { qualification to it } }.sortedBy { it.first }

    fun name(code: String?, qualification: String?): String? =
        if (code == null || qualification == null) null else table[qualification]?.get(code)

    val count: Int get() = table.values.sumOf { it.size }
}

/** Works out which exam a PDF is. A line-by-line port of the desktop app's parser.py. */
object PaperParser {
    private val kindFromFilename = mapOf("qp" to "QP", "ms" to "MS", "in" to "IN", "er" to "ER", "gt" to "GT", "ci" to "CI")
    private val letterSession = mapOf("m" to "February-March", "s" to "May-June", "w" to "October-November")

    private fun find(pattern: String, text: String) = Regex(pattern).find(text)
    private fun has(pattern: String, text: String) = Regex(pattern).containsMatchIn(text)
    private fun MatchResult.group(index: Int): String? = groups[index]?.value

    /** The same clean-up the desktop reader does: drop full stops and dot leaders. */
    fun clean(text: String): String = text.replace(".", "").replace(Regex("[. ]{3,}"), "")

    /** MS, GT, ER, IN, CI, or null when it still looks like a question paper (or isn't one). */
    fun detectPaperType(text: String): String? {
        if (text.isEmpty()) return null
        val head = text.take(3000)
        // Only the UPPERCASE phrase counts: grade thresholds and examiner reports say "mark scheme" in sentences
        if ("MARK SCHEME" in text) return "MS"
        if (has("(?i)grade thresholds", head)) return "GT"
        if (has("(?i)principal examiner report|examiner report", head)) return "ER"
        if (has("(?i)confidential instructions", head)) return "CI"
        // Inserts: an "INSERT" heading, "This (Reading Booklet) Insert contains…", or a barcode ending in -I
        // (*3736339926-I*). Some PDF readers split the barcode into one character per line.
        if (has("(?im)^\\s*(?:READING BOOKLET\\s+)?INSERT\\s*$", head) ||
            has("(?i)this (?:reading booklet )?insert contains", head) ||
            has("\\*\\s*(?:\\d\\s*){10}-\\s*I\\s*\\*", head)) {
            return "IN"
        }
        return null
    }

    private fun qualificationIn(text: String): String? = when {
        has("IGCSE|General Certificate of Secondary Education", text) -> "IGCSE"
        has("O Level|Ordinary Level", text) -> "O Level"
        has("Cambridge International AS Level|Advanced Subsidiary Level(?! and)", text) -> "AS"
        has("AS & A Level|Advanced Subsidiary and Advanced Level|Advanced Subsidiary Level and Advanced Level|International Advanced Level", text) -> "AS & A Level"
        else -> null
    }

    private fun qualificationFromHeading(heading: String): String? {
        val lower = heading.lowercase()
        return when {
            "igcse" in lower -> "IGCSE"
            "o level" in lower -> "O Level"
            "as level" in lower -> "AS"
            "a level" in lower -> "AS & A Level"
            else -> null
        }
    }

    private fun sessionFromWords(words: String?): String? = when (words?.lowercase()) {
        "june", "may/june", "may" -> "May-June"
        "february/march", "march", "february" -> "February-March"
        "october/november", "november", "october" -> "October-November"
        else -> null
    }

    fun metaExtract(text: String): PaperMetadata {
        var m = PaperMetadata(paperType = detectPaperType(text))

        // Inserts use the same footer code as a question paper. Series files do not.
        if (m.paperType !in setOf("MS", "GT", "ER")) {
            m = m.copy(qualification = qualificationIn(text))
            val code = find("\\d{4}/\\d{2}/\\w/\\w/\\d{2}", text)?.value
            if (code != null) {
                val pieces = code.split("/")
                // A re-saved PDF sometimes turns O (October) into 0
                val session = when (pieces[2].uppercase().replace("0", "O")) {
                    "O" -> "October-November"
                    "M" -> "May-June"
                    "F" -> "February-March"
                    else -> null
                }
                m = m.copy(subjectCode = pieces[0], paper = pieces[1].take(1), variant = pieces[1].takeLast(1),
                    session = session, year = "20" + pieces[4])
            } else {
                find("(\\d{4})/(\\d)(\\d)", text)?.let {
                    m = m.copy(subjectCode = it.group(1), paper = it.group(2), variant = it.group(3))
                }
                find("(February/March|May/June|October/November|March|June)\\s*(\\d{4})", text)?.let {
                    m = m.copy(year = it.group(2), session = sessionFromWords(it.group(1)))
                }
            }
        }

        if (m.paperType == "MS") {
            val new = find("(\\d{4})/(\\d{2})\\s+(.+?)\\s*[–-]\\s*Mark Scheme", text)
            if (new != null) {
                val pv = new.group(2) ?: ""
                m = m.copy(qualification = qualificationFromHeading(new.group(3) ?: ""), subjectCode = new.group(1)?.trim(),
                    paper = pv.take(1), variant = pv.takeLast(1))
                find("mark schemes for the\\s+(May/June|October/November|February/March|March|June)\\s+(\\d{4})", text)?.let {
                    m = m.copy(session = sessionFromWords(it.group(1)), year = it.group(2)?.trim())
                }
            } else {
                find("(.+?)\\s*[–-]\\s*(October/November|May/June|February/March|March|June)\\s+(\\d{4})\\s+(\\d{4})\\s+(\\d{2})", text)?.let {
                    val pv = it.group(5) ?: ""
                    m = m.copy(qualification = qualificationFromHeading(it.group(1) ?: ""), session = sessionFromWords(it.group(2)),
                        year = it.group(3)?.trim(), subjectCode = it.group(4)?.trim(), paper = pv.take(1), variant = pv.takeLast(1))
                }
            }
        }

        m = fillSeriesGaps(m, text)
        return m.copy(subjectName = Subjects.name(m.subjectCode, m.qualification))
    }

    /** Grade thresholds and examiner reports name the syllabus and the series, not one paper. */
    fun fillSeriesGaps(metadata: PaperMetadata, text: String): PaperMetadata {
        if (text.isEmpty()) return metadata
        var m = metadata
        if (m.qualification == null) m = m.copy(qualification = qualificationIn(text))
        if (m.subjectCode == null) {
            // "Syllabus 0606", "Paper 0606/11", "(0606)" or a heading like "Education 0606 Additional Mathematics".
            // Only numbers that are real subject codes count, so years are never picked.
            val head = text.take(1500)
            for (pattern in listOf("Syllabus\\s+(\\d{4})", "(?<!\\d)(\\d{4})/\\d{1,2}(?!\\d)", "\\((\\d{4})\\)", "(?<!\\d)(\\d{4})(?!\\d)")) {
                val code = Regex(pattern).findAll(head).mapNotNull { it.group(1) }.firstOrNull { Subjects.lookup(it) != null }
                if (code != null) {
                    m = m.copy(subjectCode = code)
                    break
                }
            }
        }
        if (m.year == null || m.session == null) {
            find("(February/March|May/June|October/November|November|March|June)\\s*(\\d{4})", text)?.let {
                m = m.copy(year = m.year ?: it.group(2), session = m.session ?: sessionFromWords(it.group(1)))
            }
        }
        return m
    }

    /** Cambridge's own file name, e.g. 9709_w23_qp_12.pdf or 0625_s24_gt.pdf, or this app's own names. */
    fun fromFilename(filename: String): PaperMetadata {
        val name = filename.substringAfterLast('/').substringAfterLast('\\')
        val found = find("(?i)(?<!\\d)(\\d{4})[_\\-\\s]+([msw])(\\d{2})[_\\-\\s]+(qp|ms|in|er|gt|ci)(?:[_\\-\\s]+(\\d)(\\d))?(?!\\d)", name)
        if (found != null) {
            val yy = found.group(3)?.toIntOrNull()
            return PaperMetadata(
                subjectCode = found.group(1),
                session = letterSession[found.group(2)?.lowercase()],
                year = yy?.let { (if (it >= 90) 1900 + it else 2000 + it).toString() },
                paperType = kindFromFilename[found.group(4)?.lowercase()],
                paper = found.group(5),
                variant = found.group(6),
            )
        }
        val own = find("(?i)Paper\\s+(\\d)\\s+v(\\d+)\\s+(QP|MS|IN|ER|GT|CI)\\b", name)
        return if (own != null) PaperMetadata(paper = own.group(1), variant = own.group(2), paperType = own.group(3)?.uppercase())
        else PaperMetadata()
    }

    fun fillSubject(metadata: PaperMetadata): PaperMetadata {
        val code = metadata.subjectCode ?: return metadata
        val found = Subjects.lookup(code)
        val chosen = metadata.qualification
        // A code that exists in exactly one list beats a loose text guess (9709 is not IGCSE)
        return when {
            found != null && (chosen == null || Subjects.name(code, chosen) == null) ->
                metadata.copy(qualification = found.first, subjectName = found.second)
            Subjects.name(code, chosen) != null -> metadata.copy(subjectName = Subjects.name(code, chosen))
            metadata.subjectName == null -> metadata.copy(subjectName = found?.second ?: "Subject $code")
            else -> metadata
        }
    }

    /** Text first, then the file name fills anything the text didn't say. */
    fun identify(text: String, filename: String?): PaperMetadata {
        var m = metaExtract(text)
        val explicit = detectPaperType(text) != null
        val f = fromFilename(filename ?: "")
        m = m.copy(
            qualification = m.qualification ?: f.qualification,
            subjectName = m.subjectName ?: f.subjectName,
            subjectCode = m.subjectCode ?: f.subjectCode,
            year = m.year ?: f.year,
            session = m.session ?: f.session,
            paper = m.paper ?: f.paper,
            variant = m.variant ?: f.variant,
        )
        if (f.paperType != null && !explicit) m = m.copy(paperType = f.paperType)
        if (m.paperType == null && m.subjectCode != null && m.paper != null) m = m.copy(paperType = "QP")
        return fillSubject(m)
    }

    /** Question papers, mark schemes and inserts belong to one paper. A series file does not. */
    fun needsComponent(m: PaperMetadata): Boolean = when (m.paperType) {
        "GT" -> false
        "ER" -> m.paper != null || m.variant != null
        else -> true
    }

    fun missingFields(m: PaperMetadata): List<String> {
        val missing = mutableListOf<String>()
        if (m.qualification == null) missing += "qualification"
        if (m.subjectCode == null) missing += "subject code"
        if (m.year == null) missing += "year"
        if (m.session == null) missing += "session"
        if (m.paperType == null) missing += "file type"
        if (needsComponent(m)) {
            if (m.paper == null) missing += "paper"
            if (m.variant == null) missing += "variant"
        }
        return missing
    }

    /** Read one PDF's text and say what it is. problem is null when it can be sorted. */
    fun analyse(rawText: String, filename: String): Pair<PaperMetadata, String?> {
        val text = clean(rawText)
        var m = identify(text, filename)
        if (text.isBlank() && m.subjectCode == null) return m to "I couldn't find any text in this PDF. It might be a scanned image."
        if (m.subjectCode == null) return m to "This doesn't look like a Cambridge paper."
        if (m.year == null || m.session == null) return m to ("I couldn't tell which exam series this is from. If it was " +
            "re-saved by a note-taking app, try the original PDF, or tap Fix details.")
        if (m.qualification == null) return m to "I couldn't tell whether this is IGCSE, O Level or AS & A Level."
        val missing = missingFields(m)
        if (missing.isNotEmpty()) return m to "Some details are missing: ${missing.joinToString(", ")}. Tap Fix details."
        // Subjects Cambridge no longer offers aren't on their website, so give them a fallback name
        if (m.subjectName == null) m = m.copy(subjectName = "Subject ${m.subjectCode}")
        return m to null
    }

    // ---------- Fix details form ----------

    data class ManualFields(
        val subjectCode: String = "",
        val qualification: String? = null,
        val year: String = "",
        val session: String? = null,
        val paper: String = "",
        val variant: String = "",
        val paperType: String? = null,
    ) {
        companion object {
            fun from(m: PaperMetadata?) = ManualFields(
                m?.subjectCode ?: "", m?.qualification, m?.year ?: "", m?.session, m?.paper ?: "", m?.variant ?: "", m?.paperType,
            )
        }
    }

    /** Turn the Fix details form into metadata, or an error message to show. */
    fun completeManual(fields: ManualFields): Pair<PaperMetadata, String?> {
        var m = PaperMetadata(
            subjectCode = fields.subjectCode.nullIfBlank(), qualification = fields.qualification,
            year = fields.year.nullIfBlank(), session = fields.session, paper = fields.paper.nullIfBlank(),
            variant = fields.variant.nullIfBlank(), paperType = fields.paperType,
        )
        if (m.year != null && !Regex("\\d{4}").matches(m.year!!)) return m to "Year should be 4 digits, like 2024."
        if (m.paper != null && !Regex("\\d").matches(m.paper!!)) return m to "Paper should be a single digit, like 4."
        if (m.variant != null && !Regex("\\d").matches(m.variant!!)) {
            return m to "Variant should be a single digit, like 2. Use 0 for a paper with no variant."
        }
        val code = m.subjectCode
        val chosen = m.qualification
        if (code != null && chosen != null) {
            val found = Subjects.all(code)
            if (found.isNotEmpty() && found.none { it.first == chosen }) {
                return m to if (found.size == 1) "$code is ${found[0].first} (${found[0].second}), not $chosen."
                else "$code isn't listed under $chosen."
            }
        }
        m = fillSubject(m)
        val missing = missingFields(m)
        if (missing.isNotEmpty()) return m to "Still needed: ${missing.joinToString(", ")}."
        return m to null
    }
}

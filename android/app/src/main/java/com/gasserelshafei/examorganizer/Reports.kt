package com.gasserelshafei.examorganizer

import java.text.DateFormat
import java.util.Date

/** Folder names for classes and students: Classes/<class>/<student>. */
object ClassFolders {
    const val ROOT = "Classes"
    const val UNASSIGNED = "Not saved to a student"

    fun path(className: String, student: String? = null): List<String> =
        listOfNotNull(ROOT, className, student)

    /** "Physics P42 May-June 2024" */
    fun paperName(m: PaperMetadata): String =
        Store.safeName("${m.subjectName ?: "Paper"} P${m.paper ?: ""}${m.variant ?: ""} ${m.session ?: ""} ${m.year ?: ""}")
}

/**
 * A plain-text explanation of a checked paper: the score, every mark that was lost and why,
 * the feedback, and the topics to revise. Opens anywhere (Files, Docs, email, a PC).
 */
object MistakesReport {
    fun text(marking: Marking, m: PaperMetadata, student: String?, className: String?, grade: String?,
             checkedAt: Date = Date()): String {
        val rule = "=".repeat(36)
        val lines = mutableListOf("EXAM ORGANIZER - MARKED PAPER", rule)
        if (student != null) lines += "Student: $student" + (className?.let { " ($it)" } ?: "")
        lines += "Paper:   ${m.heading}"
        lines += "Checked: ${DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(checkedAt)}"
        lines += ""
        lines += "Score:   ${marking.total} / ${marking.maxTotal} (${marking.percent}%)"
        lines += "Grade:   " + (grade?.let { "$it (Cambridge grade thresholds)" } ?: "not available for this paper")
        if (!marking.complete) lines += "Note:    the question marks add up to ${marking.maxTotal}, but the paper is out of " +
            "${marking.paperTotalMarks}. Some parts may be missing."
        if (marking.teacherAdjusted) lines += "Note:    marks were adjusted by the teacher."
        if (marking.needsReview.isNotEmpty()) lines += "Please double-check: ${marking.needsReview.joinToString(", ")}"
        if (marking.summary.isNotEmpty()) { lines += ""; lines += marking.summary }

        val lost = marking.questions.filter { it.marksAwarded < it.maxMarks }
        lines += ""
        lines += "WHERE MARKS WERE LOST (${lost.size} of ${marking.questions.size} parts)"
        lines += rule
        if (lost.isEmpty()) lines += "None - full marks on every part."
        for (q in lost) {
            lines += ""
            lines += "${q.question}   ${q.marksAwarded}/${q.maxMarks}   lost ${q.maxMarks - q.marksAwarded}" +
                (if (q.topic.isEmpty()) "" else "   [${q.topic}]")
            if (q.studentAnswer.isNotEmpty()) lines += "  Student wrote: ${q.studentAnswer}"
            if (q.marking.isNotEmpty()) lines += "  Why:           ${q.marking}"
            if (q.feedback.isNotEmpty()) lines += "  Advice:        ${q.feedback}"
        }

        val topics = ScoreBook.topicLosses(listOf(marking))
        if (topics.isNotEmpty()) {
            lines += ""
            lines += "TOPICS TO REVISE"
            lines += rule
            topics.forEach { lines += "- ${it.topic}: lost ${it.lost} of ${it.max} mark${if (it.max == 1) "" else "s"}" }
        }

        lines += ""
        lines += "EVERY QUESTION"
        lines += rule
        for (q in marking.questions) {
            val mark = if (q.marksAwarded == q.maxMarks) "full marks" else "lost ${q.maxMarks - q.marksAwarded}"
            lines += "${q.question}   ${q.marksAwarded}/${q.maxMarks}   $mark" + (if (q.confidence == "low") "   (please double-check)" else "")
        }
        lines += ""
        lines += "Marked by an AI examiner against Cambridge's official mark scheme. It can make mistakes,"
        lines += "so check anything that looks wrong."
        return lines.joinToString("\n") + "\n"
    }
}

package com.gasserelshafei.examorganizer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File

/**
 * A marked copy of the student's PDF: cover page(s) with the score, grade and every question's
 * marks and feedback, then the student's own pages with a red marks box on each page that has answers.
 * Drawn as real PDF text with the standard Helvetica font, so it looks sharp in any viewer.
 */
object MarkedPdf {
    private val red = floatArrayOf(0.80f, 0.10f, 0.10f)
    private val dark = floatArrayOf(0.15f, 0.15f, 0.20f)
    private val grey = floatArrayOf(0.40f, 0.40f, 0.45f)
    private val green = floatArrayOf(0.10f, 0.50f, 0.20f)
    private val amber = floatArrayOf(0.75f, 0.45f, 0.00f)
    private val regular: PDFont get() = PDType1Font.HELVETICA
    private val bold: PDFont get() = PDType1Font.HELVETICA_BOLD

    /** Helvetica only has Western (WinAnsi) characters: swap or drop the rest so drawing never fails. */
    private fun safe(text: String): String = buildString {
        for (c in text.replace('\n', ' ')) {
            when (c) {
                '‘', '’' -> append('\'')
                '“', '”' -> append('"')
                '–', '—', '−' -> append('-')
                '×' -> append('x')
                '\t', '\u00A0' -> append(' ')   // PDFBox's WinAnsi has no non-breaking space
                '\u00AD' -> {}                  // nor a soft hyphen (invisible anyway)
                else -> append(if (c.code in 32..126 || c.code in 161..255) c else '?')
            }
        }
    }

    private fun wrap(text: String, font: PDFont, size: Float, width: Float): List<String> {
        val words = safe(text).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (font.getStringWidth(candidate) / 1000 * size <= width || line.isEmpty()) line = candidate
            else { lines += line; line = word }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    private fun PDPageContentStream.text(x: Float, y: Float, value: String, size: Float, font: PDFont, colour: FloatArray) {
        beginText()
        setNonStrokingColor(colour[0], colour[1], colour[2])
        setFont(font, size)
        newLineAtOffset(x, y)
        showText(safe(value))
        endText()
    }

    private fun PDPageContentStream.box(x: Float, y: Float, w: Float, h: Float, fill: FloatArray, stroke: FloatArray) {
        setNonStrokingColor(fill[0], fill[1], fill[2])
        setStrokingColor(stroke[0], stroke[1], stroke[2])
        setLineWidth(1f)
        addRect(x, y, w, h)
        fillAndStroke()
    }

    /** Write the marked copy to output. Call from a background thread. */
    fun make(student: File, marking: Marking, output: File, title: String, studentName: String?, grade: String?) {
        PDDocument.load(student).use { document ->
            if (document.numberOfPages == 0) throw UserError("This PDF has no pages.")
            // Marks boxes on the student's own pages
            val byPage = marking.questions.groupBy { it.page }
            document.pages.forEachIndexed { index, page ->
                val questions = byPage[index + 1].orEmpty()
                if (questions.isNotEmpty()) marksBox(document, page, questions)
            }
            // Cover page(s) first
            val size = document.getPage(0).mediaBox.let { PDRectangle(it.width, it.height) }
            val covers = coverPages(document, size, marking, title, studentName, grade)
            val first = document.getPage(0)
            covers.forEach { document.pages.insertBefore(it, first) }
            output.parentFile?.mkdirs()
            document.save(output)
        }
    }

    private fun marksBox(document: PDDocument, page: PDPage, questions: List<MarkedQuestion>) {
        val box = page.mediaBox
        val width = 130f
        val height = 20f + 14f * questions.size
        val x = box.upperRightX - width - 14f
        val y = box.upperRightY - height - 14f
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { s ->
            s.box(x, y, width, height, floatArrayOf(1f, 0.97f, 0.97f), red)
            s.text(x + 8, y + height - 13, "Marks", 8f, bold, red)
            questions.forEachIndexed { i, q ->
                s.text(x + 8, y + height - 27 - 14f * i, "${q.question}:  ${q.marksAwarded}/${q.maxMarks}", 9f, bold, red)
            }
        }
    }

    private fun coverPages(document: PDDocument, size: PDRectangle, marking: Marking, title: String,
                           studentName: String?, grade: String?): List<PDPage> {
        val pages = mutableListOf<PDPage>()
        val margin = 50f
        val width = size.width - 2 * margin
        var page = PDPage(size)
        var stream = PDPageContentStream(document, page)
        var y = size.height - margin

        fun newPage() {
            stream.close()
            pages += page
            page = PDPage(size)
            stream = PDPageContentStream(document, page)
            y = size.height - margin
        }

        stream.text(margin, y - 10, "Marked by Exam Organizer", 20f, bold, dark)
        y -= 34
        stream.text(margin, y, title, 11f, regular, grey)
        y -= 16
        if (studentName != null) {
            stream.text(margin, y, "Student: $studentName", 11f, regular, grey)
            y -= 16
        }
        stream.box(margin, y - 62, width, 52f, floatArrayOf(0.95f, 0.96f, 1f), floatArrayOf(0.80f, 0.84f, 0.95f))
        stream.text(margin + 16, y - 42, "${marking.total} / ${marking.maxTotal}", 26f, bold, red)
        stream.text(margin + 190, y - 33, "${marking.percent}%", 14f, bold, dark)
        if (grade != null) stream.text(margin + 190, y - 50, "Grade $grade (Cambridge thresholds)", 10f, regular, grey)
        y -= 84
        for (line in wrap(marking.summary, regular, 10f, width)) {
            stream.text(margin, y, line, 10f, regular, dark)
            y -= 14
        }
        if (marking.needsReview.isNotEmpty()) {
            y -= 4
            for (line in wrap("Please double-check: " + marking.needsReview.joinToString(", "), bold, 10f, width)) {
                stream.text(margin, y, line, 10f, bold, amber)
                y -= 14
            }
        }
        if (marking.teacherAdjusted) {
            stream.text(margin, y, "Marks adjusted by the teacher.", 10f, bold, dark)
            y -= 14
        }
        y -= 12

        val textX = margin + 125
        val textWidth = size.width - margin - textX
        fun header() {
            stream.text(margin, y, "Question", 9f, bold, grey)
            stream.text(margin + 70, y, "Marks", 9f, bold, grey)
            stream.text(textX, y, "Marking and feedback", 9f, bold, grey)
            y -= 14
        }
        header()
        for (q in marking.questions) {
            val markingLines = wrap(q.marking, regular, 9f, textWidth)
            val feedbackLines = wrap(q.feedback, regular, 9f, textWidth)
            val needed = 14f * (markingLines.size + feedbackLines.size).coerceAtLeast(1) + 10
            if (y - needed < margin) {
                newPage()
                header()
            }
            val colour = if (q.marksAwarded == q.maxMarks) green else if (q.marksAwarded == 0) red else amber
            stream.text(margin, y, q.question, 10f, bold, dark)
            stream.text(margin + 70, y, "${q.marksAwarded} / ${q.maxMarks}", 10f, bold, colour)
            (markingLines.map { it to dark } + feedbackLines.map { it to grey }).forEachIndexed { i, (line, c) ->
                stream.text(textX, y - 14f * i, line, 9f, regular, c)
            }
            y -= needed
        }
        stream.close()
        pages += page
        return pages
    }
}

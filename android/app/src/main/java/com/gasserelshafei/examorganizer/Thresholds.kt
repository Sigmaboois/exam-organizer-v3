package com.gasserelshafei.examorganizer

/** A word on a PDF page and where its middle sits across the page. */
data class Word(val text: String, val x: Float)

/** One line of text, left to right. */
data class Line(val words: List<Word>)

/** One grade's minimum mark, in the order Cambridge prints them (best first). */
data class GradeBoundary(val grade: String, val minimum: Int)

data class Thresholds(val maxMark: Int, val boundaries: List<GradeBoundary>) {
    /** Scale the score to the threshold paper's max mark and return the grade. */
    fun grade(score: Int, outOf: Int): String? {
        if (boundaries.isEmpty() || outOf <= 0) return null
        val scaled = score.toDouble() * maxMark / outOf
        return boundaries.firstOrNull { scaled >= it.minimum }?.grade ?: "U"
    }
}

/**
 * Reads the row for one paper ("Component 12  75 60 53 …") from Cambridge's grade threshold
 * document, under its table header ("A B C D E", "A C E F", "AA BB CC", "9 8 7 … 1").
 * Older tables leave blank cells for grades a paper doesn't award, so when the numbers don't
 * line up with the header by count they are matched by where they sit on the page.
 * If the columns can't be matched for certain the grades are left out: no grade is better
 * than a wrong one.
 */
object ThresholdReader {
    private val gradeToken = Regex("A\\*A\\*|A\\*|[A-G]{1,2}|[a-g]|[1-9]")
    private val missingCharacters = setOf('-', '–', '—', '−', '‐', '‑', '~', '�')
    private val usualGrades = mapOf(
        "IGCSE" to listOf("A", "B", "C", "D", "E", "F", "G"),
        "O Level" to listOf("A", "B", "C", "D", "E"),
        "AS" to listOf("a", "b", "c", "d", "e"),
        "AS & A Level" to listOf("A", "B", "C", "D", "E"),
    )

    private fun isGrade(text: String) = gradeToken.matches(text)
    private fun isCell(text: String) =
        text.toIntOrNull() != null || (text.isNotEmpty() && text.all { it in missingCharacters }) ||
            text.lowercase() in setOf("n/a", "na")

    fun parse(lines: List<Line>, component: String, qualification: String?): Thresholds {
        for ((index, line) in lines.withIndex()) {
            val words = line.words
            val at = (0 until words.size - 1).firstOrNull { words[it].text == "Component" && words[it + 1].text == component }
                ?: continue
            val cells = words.drop(at + 2).takeWhile { isCell(it.text) }
            val maxMark = cells.firstOrNull()?.text?.toIntOrNull() ?: continue
            val gradeCells = cells.drop(1)

            val header = header(lines, index)
            val pairs: List<Pair<String, Int?>>? = when {
                header != null && header.size == gradeCells.size ->
                    header.map { it.text }.zip(gradeCells.map { it.text.toIntOrNull() })
                header != null -> byPosition(header, gradeCells)
                else -> usualGrades[qualification]?.takeIf { it.size == gradeCells.size }
                    ?.zip(gradeCells.map { it.text.toIntOrNull() })
            }
            var boundaries = pairs.orEmpty().mapNotNull { (grade, minimum) -> minimum?.let { GradeBoundary(grade, it) } }
            val minimums = boundaries.map { it.minimum }
            if (minimums != minimums.sortedDescending() || minimums.any { it > maxMark }) boundaries = emptyList()
            return Thresholds(maxMark, boundaries)
        }
        throw UserError("This paper isn't listed in Cambridge's grade thresholds.")
    }

    /** The nearest header row of grades above this line. Other header words can share the line
     *  ("mark A B C D E F G"), so it's the run of grades at the end of a line that counts. */
    private fun header(lines: List<Line>, rowIndex: Int): List<Word>? {
        for (index in rowIndex - 1 downTo 0) {
            val words = lines[index].words
            if (words.firstOrNull()?.text == "Component") continue
            val run = words.takeLastWhile { isGrade(it.text) }
            if (run.size >= 3) return run
        }
        return null
    }

    /** Match each number to the grade column it sits under. */
    private fun byPosition(header: List<Word>, cells: List<Word>): List<Pair<String, Int?>>? {
        if (header.size < 2 || header.zipWithNext().any { (a, b) -> a.x >= b.x }) return null
        val spacing = header.zipWithNext { a, b -> b.x - a.x }.minOrNull() ?: return null
        val found = mutableMapOf<String, Int?>()
        for (cell in cells) {
            val nearest = header.minByOrNull { kotlin.math.abs(it.x - cell.x) } ?: return null
            if (kotlin.math.abs(nearest.x - cell.x) >= spacing / 2 || nearest.text in found) return null
            found[nearest.text] = cell.text.toIntOrNull()
        }
        return header.map { it.text to found[it.text] }
    }
}

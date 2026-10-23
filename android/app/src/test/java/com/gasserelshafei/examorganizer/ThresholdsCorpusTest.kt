package com.gasserelshafei.examorganizer

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Reads every component of every real grade threshold PDF and compares the result with the
 * desktop app's (corpus/GT_expected.json, exported from src/cambridge_files.py).
 * Uses desktop PDFBox, which works the same as the Android port the app uses.
 */
class ThresholdsCorpusTest {
    /** Same as the app's PdfLines, for desktop PDFBox. */
    private class Stripper : PDFTextStripper() {
        val lines = mutableListOf(mutableListOf<Word>())

        init { sortByPosition = true }

        override fun writeString(text: String, positions: MutableList<TextPosition>) {
            var chars = mutableListOf<TextPosition>()
            fun flush() {
                if (chars.isEmpty()) return
                val word = chars.joinToString("") { it.unicode }
                val x = (chars.first().xDirAdj + chars.last().xDirAdj + chars.last().widthDirAdj) / 2
                lines.last().add(Word(word, x))
                chars = mutableListOf()
            }
            for (position in positions) {
                if (position.unicode.isBlank()) flush() else chars.add(position)
            }
            flush()
        }

        override fun writeLineSeparator() { lines.add(mutableListOf()) }
        override fun writeWordSeparator() {}
    }

    @Test
    fun corpus() {
        val root = File(System.getProperty("corpus") ?: "")
        val expectedFile = File(root, "GT_expected.json")
        assumeTrue("no corpus given", expectedFile.isFile)
        val expected = JSONObject(expectedFile.readText())
        var checked = 0
        var same = 0
        val problems = mutableListOf<String>()
        for (name in expected.keys().asSequence().sorted()) {
            val lines = PDDocument.load(File(root, "GT/$name")).use { document ->
                Stripper().also { it.getText(document) }.lines.map { Line(it) }
            }
            val components = expected.getJSONObject(name)
            for (component in components.keys().asSequence().sorted()) {
                val want = components.getJSONObject(component)
                checked++
                val got = try { ThresholdReader.parse(lines, component, null) } catch (e: UserError) { null }
                val wantMax = want.optInt("max", -1)
                val wantGrades = (0 until (want.optJSONArray("grades")?.length() ?: 0)).map {
                    val pair = want.getJSONArray("grades").getJSONArray(it)
                    GradeBoundary(pair.getString(0), pair.getInt(1))
                }
                if (got != null && got.maxMark == wantMax && got.boundaries == wantGrades) same++
                else problems += "$name $component: got ${got?.maxMark} ${got?.boundaries?.joinToString { "${it.grade}=${it.minimum}" }}" +
                    " | desktop $wantMax ${wantGrades.joinToString { "${it.grade}=${it.minimum}" }}"
            }
        }
        System.getenv("GT_DEBUG")?.let { debug ->
            val lines = PDDocument.load(File(root, "GT/$debug")).use { document ->
                Stripper().also { it.getText(document) }.lines
            }
            File("build/thresholds-debug.txt").writeText(lines.take(40).joinToString("\n") { line ->
                line.joinToString("  ") { "${it.text}@${it.x.toInt()}" }
            })
        }
        val report = "THRESHOLDS: $same/$checked components identical to the desktop app\n" +
            problems.take(12).joinToString("\n") { "     $it" } + "\n"
        File("build/thresholds-report.txt").apply { parentFile.mkdirs() }.writeText(report)
        println(report)
    }
}

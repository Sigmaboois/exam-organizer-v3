package com.gasserelshafei.examorganizer

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the parser over a folder of real Cambridge PDFs ({QP,MS,IN,ER,GT}/<official name>.pdf) and
 * checks every field against what the official file name says, with the real name and text-only.
 * Text comes from PDFBox, the same library the app reads PDFs with.
 * Run with:  gradle testReleaseUnitTest -Dcorpus=/path/to/corpus
 */
class ParserCorpusTest {
    private fun expected(name: String): PaperMetadata {
        var m = PaperParser.fillSubject(PaperParser.fromFilename(name))
        if (m.paperType == "GT") m = m.copy(paper = null, variant = null)
        return m
    }

    @Test
    fun corpus() {
        val root = File(System.getProperty("corpus") ?: "")
        assumeTrue("no corpus given", root.isDirectory)
        Subjects.load(File("src/main/assets/subjects.json").readText())

        val texts = mutableMapOf<String, MutableList<Pair<String, String>>>()
        for (kind in listOf("QP", "MS", "IN", "ER", "GT")) {
            val folder = File(root, kind)
            val files = folder.listFiles { f -> f.name.endsWith(".pdf") }?.sortedBy { it.name } ?: continue
            texts[kind] = files.map { file ->
                file.name to try { PDDocument.load(file).use { PDFTextStripper().getText(it) } } catch (e: Exception) { "" }
            }.toMutableList()
        }

        val report = StringBuilder()
        for (mode in listOf("name", "text")) {
            report.appendLine("===== mode: $mode =====")
            for ((kind, items) in texts) {
                var ok = 0
                val problems = mutableListOf<String>()
                for ((name, text) in items) {
                    val want = expected(name)
                    val got = PaperParser.analyse(text, if (mode == "name") name else "upload.pdf").first
                    val wrong = mutableListOf<String>()
                    if (got.subjectCode != want.subjectCode) wrong += "code ${got.subjectCode}"
                    if (got.year != want.year) wrong += "year ${got.year}"
                    if (got.session != want.session) wrong += "session ${got.session}"
                    if (got.paperType != want.paperType) wrong += "type ${got.paperType}"
                    if (got.qualification != want.qualification) wrong += "qual ${got.qualification}"
                    if (want.paperType != "GT" && want.paperType != "ER" &&
                        (got.paper != want.paper || got.variant != want.variant)) wrong += "pv ${got.paper}${got.variant}"
                    if (wrong.isEmpty()) ok++ else problems += "$name: ${wrong.joinToString(", ")}"
                }
                report.appendLine("  $kind: $ok/${items.size} fully correct")
                problems.take(6).forEach { report.appendLine("     $it") }
            }
        }
        File("build/parser-corpus-report.txt").apply { parentFile.mkdirs() }.writeText(report.toString())
        println(report)
    }
}

package com.gasserelshafei.examorganizer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/** Has a question paper been written on, and on which pages? */
data class SolveResult(val solved: Boolean, val pages: List<Int>, val method: String, val pageCount: Int)

/**
 * The student's PDF is drawn page by page and compared with Cambridge's clean original:
 * anything darker than the original (pen strokes, typed answers) is ink. Works with any notes app.
 * Without the original, a weaker check looks for coloured ink and drawn annotations.
 */
object SolveDetector {
    private const val INK_DARKER_BY = 70        // grey levels a pixel must darken by to count as ink
    private const val PAGE_INK_RATIO = 0.0015   // 0.15% of a page darkened = something was written
    private const val COLOUR_SATURATION = 90    // fallback: how colourful a pixel must be to count as ink
    private val annotationTypes = setOf("Ink", "FreeText", "Square", "Circle", "Line", "Polygon", "PolyLine", "Stamp", "Text")

    fun detect(student: File, clean: File?): SolveResult {
        if (clean != null) runCatching { return compare(student, clean) }
        return estimate(student)
    }

    private fun compare(studentFile: File, cleanFile: File): SolveResult =
        PdfTools.renderer(studentFile) { student ->
            PdfTools.renderer(cleanFile) { clean ->
                val pages = mutableListOf<Int>()
                for (index in 0 until student.pageCount) {
                    if (index >= clean.pageCount) {
                        pages += index + 1   // extra pages added by the student (e.g. working paper)
                        continue
                    }
                    // Both pages are drawn at the clean page's size (72 dpi), so a re-saved copy lines up
                    val (width, height) = clean.openPage(index).use { it.width to it.height }
                    val cleanGrey = clean.openPage(index).use { page ->
                        PdfTools.render(page, width, height).let { bitmap -> PdfTools.grey(bitmap).also { bitmap.recycle() } }
                    }
                    val studentGrey = student.openPage(index).use { page ->
                        PdfTools.render(page, width, height).let { bitmap -> PdfTools.grey(bitmap).also { bitmap.recycle() } }
                    }
                    if (inkRatio(studentGrey, cleanGrey, width, height) >= PAGE_INK_RATIO) pages += index + 1
                }
                // Writing only a name on the cover doesn't count as solving it
                SolveResult(pages.any { it > 1 }, pages, "compared", student.pageCount)
            }
        }

    /** Fraction of the page that is darker in the student's copy than in the original. */
    fun inkRatio(student: IntArray, clean: IntArray, width: Int, height: Int): Double {
        // Let the original's printing spread by a couple of pixels, so a shifted re-save doesn't look like writing
        val spread = minFilter(clean, width, height, 2)
        var ink = 0
        for (i in 0 until minOf(student.size, spread.size)) if (spread[i] - student[i] >= INK_DARKER_BY) ink++
        return ink.toDouble() / (width * height).coerceAtLeast(1)
    }

    /** Darkest value in a (2·radius+1)² square around each pixel: two quick passes. */
    fun minFilter(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return pixels
        val across = IntArray(pixels.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var darkest = 255
                for (dx in maxOf(0, x - radius)..minOf(width - 1, x + radius)) if (pixels[row + dx] < darkest) darkest = pixels[row + dx]
                across[row + x] = darkest
            }
        }
        val result = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var darkest = 255
                for (dy in maxOf(0, y - radius)..minOf(height - 1, y + radius)) {
                    val value = across[dy * width + x]
                    if (value < darkest) darkest = value
                }
                result[y * width + x] = darkest
            }
        }
        return result
    }

    /** No original to compare with: look for coloured ink and drawn annotations. */
    private fun estimate(file: File): SolveResult {
        val found = sortedSetOf<Int>()
        runCatching {
            PDDocument.load(file).use { document ->
                document.pages.forEachIndexed { index, page ->
                    if (page.annotations.any { it.subtype in annotationTypes }) found += index + 1
                }
            }
        }
        val count = PdfTools.renderer(file) { renderer ->
            for (index in 0 until renderer.pageCount) {
                if (index + 1 in found) continue
                renderer.openPage(index).use { page ->
                    val bitmap = PdfTools.render(page, page.width, page.height)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    var coloured = 0
                    for (p in pixels) {
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        val high = maxOf(r, g, b)
                        if (high > 0 && (high - minOf(r, g, b)) * 255 / high >= COLOUR_SATURATION) coloured++
                    }
                    if (coloured.toDouble() / pixels.size >= PAGE_INK_RATIO) found += index + 1
                    bitmap.recycle()
                }
            }
            renderer.pageCount
        }
        return SolveResult(found.any { it > 1 }, found.toList(), "estimated", count)
    }
}

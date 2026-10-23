package com.gasserelshafei.examorganizer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/** Reading and drawing PDFs. Everything here is slow-ish: call it from a background thread. */
object PdfTools {
    private fun open(file: File): PDDocument = try {
        PDDocument.load(file)
    } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
        throw UserError("This PDF is password-protected, so I can't read it.")
    } catch (e: Exception) {
        throw UserError("This PDF couldn't be opened. It may be damaged.")
    }

    /** All the text, page after page. */
    fun text(file: File): String = open(file).use { PDFTextStripper().getText(it) }

    /** The text as positioned words, line by line (for grade threshold tables). */
    fun lines(file: File): List<Line> = open(file).use { document ->
        PositionStripper().also { it.getText(document) }.lines.map { Line(it) }
    }

    private class PositionStripper : PDFTextStripper() {
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

    fun pageCount(file: File): Int = renderer(file) { it.pageCount }

    fun <T> renderer(file: File, use: (PdfRenderer) -> T): T {
        val descriptor = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            throw UserError("This PDF couldn't be opened.")
        }
        return descriptor.use { fd ->
            val renderer = try { PdfRenderer(fd) } catch (e: Exception) {
                throw UserError("This PDF couldn't be opened. It may be damaged or password-protected.")
            }
            renderer.use(use)
        }
    }

    /** One page drawn on white at width × height pixels. */
    fun render(page: PdfRenderer.Page, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    /** Grey levels (0 black … 255 white) of a bitmap. */
    fun grey(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        return pixels
    }
}

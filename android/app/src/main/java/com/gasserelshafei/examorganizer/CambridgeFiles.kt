package com.gasserelshafei.examorganizer

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The official files that go with an exam: the clean question paper (for spotting answers),
 * the mark scheme (for checking) and the grade thresholds (for grades). Mark schemes are looked
 * for in the sorted library first; everything else comes from the download cache or the internet.
 */
class CambridgeFiles(private val context: Context, private val store: Store) {
    enum class Kind(val code: String, val label: String) {
        QUESTION_PAPER("qp", "question paper"), MARK_SCHEME("ms", "mark scheme"), THRESHOLDS("gt", "grade thresholds")
    }

    private val cache = File(context.cacheDir, "cambridge").apply { mkdirs() }

    fun officialName(m: PaperMetadata, kind: Kind): String {
        val letter = mapOf("February-March" to "m", "May-June" to "s", "October-November" to "w")[m.session]
        val year = m.year
        if (letter == null || year == null || year.length != 4 || m.subjectCode == null) {
            throw UserError("This paper is missing the details needed to find its official files.")
        }
        val series = "${m.subjectCode}_$letter${year.takeLast(2)}"
        return if (kind == Kind.THRESHOLDS) "${series}_gt.pdf" else "${series}_${kind.code}_${m.paper ?: ""}${m.variant ?: ""}.pdf"
    }

    private fun isPdf(file: File) = file.isFile && file.inputStream().use { input ->
        ByteArray(5).let { input.read(it) == 5 && String(it) == "%PDF-" }
    }

    /** Local copy if there is one, otherwise download it. Call from a background thread. */
    fun get(m: PaperMetadata, kind: Kind): File {
        if (kind == Kind.MARK_SCHEME) {
            // A mark scheme the teacher already sorted (a sorted question paper might be written on, so never those)
            val wanted = m.copy(paperType = "MS")
            store.localCopy((Library.folder(wanted) + Library.fileName(wanted)).joinToString("/") { Store.safeName(it) })
                ?.takeIf(::isPdf)?.let { return it }
        }
        val name = officialName(m, kind)
        val cached = File(cache, name)
        if (isPdf(cached)) return cached
        return download(name, kind, cached)
    }

    private fun download(name: String, kind: Kind, target: File): File {
        val connection = try {
            (URL(DOWNLOAD_URL + name).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Exam Organizer for Android)")
            }
        } catch (e: Exception) {
            throw UserError("Couldn't connect to download the official file. Check the internet connection.")
        }
        try {
            val status = try { connection.responseCode } catch (e: Exception) {
                throw UserError("Couldn't connect to download the official file. Check the internet connection.")
            }
            val data = if (status == 200) connection.inputStream.use { it.readBytes() } else null
            if (data == null || data.size < 5 || String(data, 0, 5) != "%PDF-") {
                throw UserError("Cambridge's ${kind.label} for this paper isn't available online yet.")
            }
            target.writeBytes(data)
            return target
        } finally {
            connection.disconnect()
        }
    }

    fun thresholds(m: PaperMetadata): Thresholds =
        ThresholdReader.parse(PdfTools.lines(get(m, Kind.THRESHOLDS)), "${m.paper ?: ""}${m.variant ?: ""}", m.qualification)

    companion object {
        const val DOWNLOAD_URL = "https://pastpapers.papacambridge.com/directories/CAIE/CAIE-pastpapers/upload/"
    }
}

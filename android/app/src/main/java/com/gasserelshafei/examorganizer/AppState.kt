package com.gasserelshafei.examorganizer

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class Screen(val title: String) { ORGANISE("Organise"), CHECK("Check papers"), CLASSES("Classes") }

enum class ExamStatus { WAITING, READING, READY, PROBLEM, SORTING, SORTED }

data class ExamItem(
    val id: String = UUID.randomUUID().toString(),
    val file: File,
    val name: String,
    val status: ExamStatus = ExamStatus.WAITING,
    val metadata: PaperMetadata? = null,
    val problem: String? = null,
    val answers: SolveResult? = null,
    val checkingAnswers: Boolean = false,
    val sortedTo: String? = null,
)

enum class PaperStatus { WAITING, READING, READY, QUEUED, CHECKING, DONE, PROBLEM }

data class CheckOutcome(
    val marking: Marking,
    val grade: String?,
    val local: File,          // the marked PDF in the app (for re-drawing and opening)
    val savedPdf: String?,    // where it was saved in the chosen folder
    val report: String?,
)

data class CheckPaper(
    val id: String = UUID.randomUUID().toString(),
    val file: File,
    val name: String,
    val status: PaperStatus = PaperStatus.WAITING,
    val metadata: PaperMetadata? = null,
    val answers: SolveResult? = null,
    val message: String = "",
    val progress: String = "",
    val studentId: String? = null,
    val dontSave: Boolean = false,
    val outcome: CheckOutcome? = null,
    val resultId: String? = null,   // its score book entry, replaced if checked again
) {
    val checkable get() = metadata?.isQuestionPaper == true
    val saved get() = resultId != null
}

/** A scan waiting for the teacher to say whose paper it is. */
data class ScanDraft(val file: File, val metadata: PaperMetadata, val found: Boolean, val pages: Int)

data class Toast(val message: String, val isError: Boolean = false, val id: String = UUID.randomUUID().toString())

/** Shared state: the screens, settings, the Organise and Check lists and the score book. */
class AppState(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val prefs = application.getSharedPreferences("settings", 0)

    val store = Store(application)
    val files = CambridgeFiles(application, store)
    val scoreBook = ScoreBook(File(application.filesDir, "scorebook.json"), store)

    var screen by mutableStateOf(Screen.ORGANISE)
    var toast by mutableStateOf<Toast?>(null)
    var geminiKey by mutableStateOf(prefs.getString("gemini_key", "") ?: "")
        private set
    var storageLabel by mutableStateOf(store.label)
        private set

    val exams = mutableStateListOf<ExamItem>()
    var sorting by mutableStateOf(false)
        private set

    val papers = mutableStateListOf<CheckPaper>()
    var busyWith by mutableStateOf<String?>(null)
        private set
    var lastResult by mutableStateOf<String?>(null)
    var scanDraft by mutableStateOf<ScanDraft?>(null)
    var readingScan by mutableStateOf(false)
        private set

    private var readingExams = false
    private var readingPapers = false
    private var checking = false

    init {
        Imports.clearAll(application)   // working copies from last time aren't in any list any more
        scoreBook.ensureFolders()
    }

    fun show(message: String, error: Boolean = false) { toast = Toast(message, error) }

    fun saveKey(key: String) {
        geminiKey = key.trim()
        prefs.edit().putString("gemini_key", geminiKey).apply()
    }

    fun chooseStorage(tree: Uri) {
        runCatching { store.choose(tree) }.onFailure { show("Couldn't use that folder. Try another one.", true); return }
        storageLabel = store.label
        scoreBook.ensureFolders()
        show("Your exams and class folders will be kept in ${store.label}.")
    }

    // ---------- opening and sharing ----------

    private fun localUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /** An intent that opens a saved file (path in the chosen folder) or a local file in another app. */
    fun viewIntent(path: String?, local: File? = null, mime: String = "application/pdf"): Intent? {
        val uri = when {
            path != null -> store.file(path)?.let(store::shareUri)
            local != null && local.exists() -> localUri(local)
            else -> null
        } ?: return null
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun shareIntent(file: File, mime: String): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, localUri(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")

    fun shareSaved(path: String?, mime: String): Intent? {
        val document = store.file(path) ?: return null
        return Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, store.shareUri(document))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")
    }

    fun readSavedText(path: String?): String? = runCatching { store.localCopy(path ?: return null)?.readText() }.getOrNull()

    fun exportCsv(records: List<CheckRecord>, name: String): Intent? = runCatching {
        val file = scoreBook.exportCsv(records, File(context.cacheDir, "exports/${Store.safeName(name)}.csv"))
        shareIntent(file, "text/csv")
    }.getOrElse { show("Couldn't make the spreadsheet.", true); null }

    // ---------- adding files ----------

    private suspend fun copyIn(uris: List<Uri>): Pair<List<File>, Int> = withContext(Dispatchers.IO) {
        var failed = 0
        val copied = uris.mapNotNull { uri ->
            runCatching { Imports.copy(context, uri, Imports.displayName(context, uri)) }.getOrElse { failed++; null }
        }
        copied to failed
    }

    private suspend fun copyFolder(tree: Uri): Pair<List<File>, Int> = withContext(Dispatchers.IO) {
        var failed = 0
        val copied = Imports.pdfsIn(context, tree).mapNotNull { document ->
            runCatching { Imports.copy(context, document.uri, document.name) }.getOrElse { failed++; null }
        }
        copied to failed
    }

    private fun summary(added: Int, failed: Int): String {
        var text = if (added == 0) "No PDFs found." else "Added $added PDF${if (added == 1) "" else "s"} to the list."
        if (failed > 0) text += " $failed couldn't be copied."
        return text
    }

    fun addExams(uris: List<Uri>, folder: Boolean = false) = viewModelScope.launch {
        val (files, failed) = if (folder) copyFolder(uris.first()) else copyIn(uris)
        files.forEach { exams += ExamItem(file = it, name = it.name) }
        show(summary(files.size, failed), files.isEmpty())
        readExams()
    }

    fun addPapers(uris: List<Uri>, folder: Boolean = false) = viewModelScope.launch {
        val (files, failed) = if (folder) copyFolder(uris.first()) else copyIn(uris)
        files.forEach { papers += CheckPaper(file = it, name = it.name) }
        show(summary(files.size, failed), files.isEmpty())
        readPapers()
    }

    /** PDFs shared to the app from another app. */
    fun openIncoming(uris: List<Uri>) {
        screen = Screen.ORGANISE
        addExams(uris)
    }

    // ---------- Organise ----------

    private fun updateExam(id: String, change: (ExamItem) -> ExamItem) {
        val index = exams.indexOfFirst { it.id == id }
        if (index >= 0) exams[index] = change(exams[index])
    }

    val readyExams get() = exams.count { it.status == ExamStatus.READY }

    fun examSummary(): String {
        if (exams.isEmpty()) return ""
        val parts = mutableListOf("${exams.size} file${if (exams.size == 1) "" else "s"}")
        listOf("being read" to setOf(ExamStatus.WAITING, ExamStatus.READING), "ready" to setOf(ExamStatus.READY),
            "sorted" to setOf(ExamStatus.SORTED), "need attention" to setOf(ExamStatus.PROBLEM)).forEach { (label, statuses) ->
            val count = exams.count { it.status in statuses }
            if (count > 0) parts += "$count $label"
        }
        return parts.joinToString(" · ")
    }

    fun removeExam(id: String) {
        val item = exams.firstOrNull { it.id == id } ?: return
        if (item.status == ExamStatus.SORTING) return
        exams.remove(item)
        Imports.remove(context, item.file)
    }

    fun clearSorted() {
        exams.filter { it.status == ExamStatus.SORTED }.forEach { Imports.remove(context, it.file) }
        exams.removeAll { it.status == ExamStatus.SORTED }
    }

    fun clearExams() {
        if (sorting) return
        exams.forEach { Imports.remove(context, it.file) }
        exams.clear()
    }

    fun applyExamDetails(id: String, metadata: PaperMetadata) {
        updateExam(id) { it.copy(metadata = metadata, problem = null, status = ExamStatus.READY, answers = null) }
        if (metadata.paperType == "QP") viewModelScope.launch { detectAnswers(id) }
    }

    fun sendToCheck(id: String) {
        val item = exams.firstOrNull { it.id == id } ?: return
        val copy = runCatching { Imports.copy(context, item.file, item.name) }.getOrNull() ?: return show("Couldn't copy this paper.", true)
        papers += CheckPaper(file = copy, name = item.name, metadata = item.metadata, answers = item.answers)
        screen = Screen.CHECK
        readPapers()
    }

    private fun readExams() {
        if (readingExams) return
        readingExams = true
        viewModelScope.launch {
            while (true) {
                val item = exams.firstOrNull { it.status == ExamStatus.WAITING } ?: break
                updateExam(item.id) { it.copy(status = ExamStatus.READING) }
                val (metadata, problem) = withContext(Dispatchers.IO) {
                    runCatching { PaperParser.analyse(PdfTools.text(item.file), item.name) }
                        .getOrElse { PaperMetadata() to friendlyMessage(it) }
                }
                updateExam(item.id) { it.copy(metadata = metadata, problem = problem, status = if (problem == null) ExamStatus.READY else ExamStatus.PROBLEM) }
                if (problem == null && metadata.paperType == "QP") detectAnswers(item.id)
            }
            readingExams = false
        }
    }

    private suspend fun detectAnswers(id: String) {
        val item = exams.firstOrNull { it.id == id } ?: return
        val metadata = item.metadata ?: return
        updateExam(id) { it.copy(checkingAnswers = true) }
        val result = withContext(Dispatchers.IO) {
            val clean = runCatching { files.get(metadata, CambridgeFiles.Kind.QUESTION_PAPER) }.getOrNull()
            runCatching { SolveDetector.detect(item.file, clean) }.getOrNull()
        }
        updateExam(id) { it.copy(answers = result, checkingAnswers = false) }
    }

    fun sortAll() {
        val ready = exams.filter { it.status == ExamStatus.READY }
        if (ready.isEmpty() || sorting) return
        sorting = true
        viewModelScope.launch {
            var sorted = 0
            var failed = 0
            for (item in ready) {
                val metadata = item.metadata ?: continue
                updateExam(item.id) { it.copy(status = ExamStatus.SORTING) }
                val result = withContext(Dispatchers.IO) {
                    runCatching { store.save(item.file, Library.folder(metadata), Library.fileName(metadata), "application/pdf") }
                }
                result.onSuccess { path ->
                    updateExam(item.id) { it.copy(status = ExamStatus.SORTED, sortedTo = path) }
                    sorted++
                }.onFailure { error ->
                    updateExam(item.id) { it.copy(status = ExamStatus.PROBLEM, problem = friendlyMessage(error)) }
                    failed++
                }
            }
            sorting = false
            show("Sorted $sorted exam${if (sorted == 1) "" else "s"} into ${store.label} › Exams." +
                (if (failed > 0) " $failed couldn't be sorted." else ""), sorted == 0 && failed > 0)
        }
    }

    // ---------- Check papers ----------

    private fun updatePaper(id: String, change: (CheckPaper) -> CheckPaper) {
        val index = papers.indexOfFirst { it.id == id }
        if (index >= 0) papers[index] = change(papers[index])
    }

    fun paper(id: String?) = papers.firstOrNull { it.id == id }

    val readyPapers get() = papers.count { it.status == PaperStatus.READY && it.checkable }

    fun paperSummary(): String {
        if (papers.isEmpty()) return ""
        val parts = mutableListOf("${papers.size} paper${if (papers.size == 1) "" else "s"}")
        listOf("being read" to setOf(PaperStatus.WAITING, PaperStatus.READING), "ready" to setOf(PaperStatus.READY),
            "checking" to setOf(PaperStatus.QUEUED, PaperStatus.CHECKING), "checked" to setOf(PaperStatus.DONE),
            "need attention" to setOf(PaperStatus.PROBLEM)).forEach { (label, statuses) ->
            val count = papers.count { it.status in statuses }
            if (count > 0) parts += "$count $label"
        }
        return parts.joinToString(" · ")
    }

    fun removePaper(id: String) {
        val paper = paper(id) ?: return
        if (paper.status == PaperStatus.CHECKING) return
        papers.remove(paper)
        Imports.remove(context, paper.file)
    }

    fun clearPapers() {
        papers.filter { it.status != PaperStatus.CHECKING }.forEach { Imports.remove(context, it.file) }
        papers.removeAll { it.status != PaperStatus.CHECKING }
    }

    fun applyPaperDetails(id: String, metadata: PaperMetadata) {
        updatePaper(id) { it.copy(metadata = metadata, answers = null, message = "", status = PaperStatus.WAITING) }
        readPapers()
    }

    private fun readPapers() {
        if (readingPapers) return
        readingPapers = true
        viewModelScope.launch {
            while (true) {
                val paper = papers.firstOrNull { it.status == PaperStatus.WAITING } ?: break
                updatePaper(paper.id) { it.copy(status = PaperStatus.READING) }
                val (metadata, answers, problem) = withContext(Dispatchers.IO) {
                    val known = paper.metadata
                    val found: Pair<PaperMetadata, String?> = if (known != null) known to null else
                        runCatching { PaperParser.analyse(PdfTools.text(paper.file), paper.name) }
                            .getOrElse { PaperMetadata() to friendlyMessage(it) }
                    val metadata = found.first
                    var problem = found.second
                    if (problem == null && metadata.paperType != null && metadata.paperType != "QP") {
                        val label = (Catalog.paperTypes[metadata.paperType] ?: "different file").lowercase()
                        problem = "This is a $label. Add the student's question paper instead."
                    }
                    var answers = paper.answers
                    if (problem == null && answers == null) {
                        val clean = runCatching { files.get(metadata, CambridgeFiles.Kind.QUESTION_PAPER) }.getOrNull()
                        answers = runCatching { SolveDetector.detect(paper.file, clean) }.getOrNull()
                    }
                    Triple(metadata, answers, problem)
                }
                updatePaper(paper.id) {
                    it.copy(metadata = metadata, answers = answers, message = problem ?: "",
                        status = if (problem == null) PaperStatus.READY else PaperStatus.PROBLEM)
                }
            }
            readingPapers = false
        }
    }

    /** Choose whose paper this is (null = not chosen yet). Saves straight away if it's already checked. */
    fun chooseStudent(id: String, studentId: String?, dontSave: Boolean = false) {
        updatePaper(id) { it.copy(studentId = studentId, dontSave = dontSave) }
        val paper = paper(id) ?: return
        if (paper.status == PaperStatus.DONE && !paper.saved && studentId != null) save(id)
    }

    fun assignAll(studentId: String) {
        var assigned = 0
        var skipped = 0
        for (paper in papers.toList()) {
            if (paper.status == PaperStatus.CHECKING) { skipped++; continue }
            updatePaper(paper.id) { it.copy(studentId = studentId, dontSave = false) }
            assigned++
            if (paper.status == PaperStatus.DONE && !paper.saved) save(paper.id, quiet = true)
        }
        val name = scoreBook.student(studentId)?.name ?: "that student"
        show("Assigned $assigned paper${if (assigned == 1) "" else "s"} to $name." +
            (if (skipped > 0) " Left $skipped that's still being checked." else ""))
    }

    fun queue(id: String) {
        val paper = paper(id) ?: return
        if (!paper.checkable || paper.status == PaperStatus.QUEUED || paper.status == PaperStatus.CHECKING) return
        updatePaper(id) { it.copy(status = PaperStatus.QUEUED) }
        runQueue()
    }

    fun checkAll() {
        papers.filter { it.status == PaperStatus.READY && it.checkable }.forEach { p -> updatePaper(p.id) { it.copy(status = PaperStatus.QUEUED) } }
        runQueue()
    }

    /** Checks run one at a time, so the shared free AI quota isn't used up in bursts. */
    private fun runQueue() {
        if (checking) return
        checking = true
        viewModelScope.launch {
            while (true) {
                val next = papers.firstOrNull { it.status == PaperStatus.QUEUED } ?: break
                check(next.id)
            }
            checking = false
        }
    }

    private fun markedFile(): File = File(context.filesDir, "marked/${UUID.randomUUID()}.pdf").apply { parentFile?.mkdirs() }

    private suspend fun check(id: String) {
        val paper = paper(id) ?: return
        val metadata = paper.metadata ?: return
        busyWith = id
        updatePaper(id) { it.copy(status = PaperStatus.CHECKING, progress = "Finding the mark scheme…") }
        val student = scoreBook.student(paper.studentId)
        val className = scoreBook.schoolClass(student?.classId)?.name
        val key = geminiKey
        try {
            val scheme = withContext(Dispatchers.IO) { files.get(metadata, CambridgeFiles.Kind.MARK_SCHEME) }
            updatePaper(id) { it.copy(progress = "Marking the answers… (this can take a minute)") }
            var marking = withContext(Dispatchers.IO) { Marker.mark(paper.file, scheme, metadata, key) }

            updatePaper(id) { it.copy(progress = "Working out the grade…") }
            var grade: String? = null
            // The AI's own "paper total" isn't reliable: only Cambridge's maximum mark says something is missing
            marking = marking.copy(complete = true)
            withContext(Dispatchers.IO) { runCatching { files.thresholds(metadata) }.getOrNull() }?.let { t ->
                grade = t.grade(marking.total, marking.maxTotal)
                marking = marking.copy(paperTotalMarks = t.maxMark, complete = marking.maxTotal == t.maxMark)
            }

            updatePaper(id) { it.copy(progress = "Making the marked copy…") }
            val local = markedFile()
            val finalMarking = marking
            val finalGrade = grade
            withContext(Dispatchers.IO) { MarkedPdf.make(paper.file, finalMarking, local, metadata.heading, student?.name, finalGrade) }
            updatePaper(id) { it.copy(outcome = CheckOutcome(finalMarking, finalGrade, local, null, null), status = PaperStatus.DONE, progress = "") }
            val current = paper(id)
            if (current?.studentId != null && !current.dontSave) save(id, quiet = true)
            else storeOutcome(id)   // keep a copy in "Not saved to a student"
            lastResult = "${metadata.title}: ${finalMarking.total}/${finalMarking.maxTotal}" + (finalGrade?.let { ", grade $it" } ?: "")
        } catch (error: Exception) {
            updatePaper(id) { it.copy(status = PaperStatus.PROBLEM, message = friendlyMessage(error), progress = "") }
        }
        busyWith = null
    }

    /** Copy the marked PDF and the mistakes report into the student's folder (or "Not saved to a student"). */
    private fun storeOutcome(id: String, onSaved: (CheckOutcome) -> Unit = {}) {
        val paper = paper(id) ?: return
        val outcome = paper.outcome ?: return
        val metadata = paper.metadata ?: return
        val student = scoreBook.student(paper.studentId).takeIf { !paper.dontSave }
        val className = scoreBook.schoolClass(student?.classId)?.name
        val folder = scoreBook.folder(student?.id) ?: listOf(ClassFolders.UNASSIGNED)
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    // Re-saving (corrected marks) replaces the earlier copy; a new check of the same paper gets
                    // "<paper> (2) - marked.pdf" with its own "<paper> (2) - mistakes.txt"
                    val base = outcome.savedPdf?.takeIf { it.substringBeforeLast('/') == folder.joinToString("/") { Store.safeName(it) } }
                        ?.substringAfterLast('/')?.removeSuffix(" - marked.pdf")
                        ?: run {
                            val stem = ClassFolders.paperName(metadata)
                            var candidate = stem
                            var number = 2
                            while (store.freeName(folder, "$candidate - marked.pdf") != "$candidate - marked.pdf") candidate = "$stem (${number++})"
                            candidate
                        }
                    val pdf = store.save(outcome.local, folder, "$base - marked.pdf", "application/pdf", replace = true)
                    val reportText = MistakesReport.text(outcome.marking, metadata, student?.name, className, outcome.grade)
                    val reportFile = File(context.cacheDir, "reports/${UUID.randomUUID()}.txt").apply { parentFile?.mkdirs(); writeText(reportText) }
                    val report = store.save(reportFile, folder, "$base - mistakes.txt", "text/plain", replace = true)
                    reportFile.delete()
                    // The old copy in another folder (e.g. "Not saved to a student") isn't needed any more
                    if (outcome.savedPdf != null && outcome.savedPdf != pdf) {
                        store.file(outcome.savedPdf)?.delete()
                        store.file(outcome.report)?.delete()
                    }
                    outcome.copy(savedPdf = pdf, report = report)
                }.getOrNull()
            }
            if (saved != null) {
                updatePaper(id) { it.copy(outcome = saved) }
                onSaved(saved)
            } else {
                show("Couldn't save the marked copy to ${store.label}. Check the folder in Settings.", true)
            }
        }
    }

    /** Save a checked paper to its student's scores (replacing an earlier save of the same paper). */
    fun save(id: String, quiet: Boolean = false) {
        val paper = paper(id) ?: return
        val studentId = paper.studentId ?: return
        val metadata = paper.metadata ?: return
        storeOutcome(id) { outcome ->
            val current = paper(id) ?: return@storeOutcome
            current.resultId?.let { scoreBook.deleteResult(it) }
            val resultId = scoreBook.saveResult(studentId, metadata, outcome.marking, outcome.grade, outcome.savedPdf, outcome.report)
            updatePaper(id) { it.copy(resultId = resultId) }
            if (!quiet) show("Saved to ${scoreBook.student(studentId)?.name ?: "the student"}'s scores.")
        }
    }

    /** Apply a teacher's mark changes: regrade, redraw the marked PDF and update the score book. */
    suspend fun revise(id: String, awards: List<Int>) {
        val paper = paper(id) ?: return
        val outcome = paper.outcome ?: return
        val metadata = paper.metadata ?: return
        val questions = outcome.marking.questions
        if (awards.size != questions.size) throw UserError("The mark list doesn't match the questions.")
        val changed = questions.mapIndexed { i, q ->
            if (awards[i] < 0 || awards[i] > q.maxMarks) throw UserError("${q.question} is out of ${q.maxMarks}.")
            q.copy(marksAwarded = awards[i])
        }
        var marking = outcome.marking.recounted(changed).copy(teacherAdjusted = true, needsReview = emptyList())
        var grade = outcome.grade
        withContext(Dispatchers.IO) { runCatching { files.thresholds(metadata) }.getOrNull() }?.let { t ->
            grade = t.grade(marking.total, marking.maxTotal)
            marking = marking.copy(paperTotalMarks = t.maxMark, complete = marking.maxTotal == t.maxMark)
        }
        val studentName = scoreBook.student(paper.studentId)?.name
        val finalMarking = marking
        val finalGrade = grade
        withContext(Dispatchers.IO) { MarkedPdf.make(paper.file, finalMarking, outcome.local, metadata.heading, studentName, finalGrade) }
        updatePaper(id) { it.copy(outcome = outcome.copy(marking = finalMarking, grade = finalGrade)) }
        storeOutcome(id) { saved ->
            paper(id)?.resultId?.let { scoreBook.updateResult(it, saved.marking, saved.grade, saved.savedPdf, saved.report) }
        }
    }

    // ---------- Scanning ----------

    /** After Google's scanner: copy the PDF in and read which exam it is from the first pages. */
    fun prepareScan(pdf: Uri, pageImages: List<Uri>) {
        readingScan = true
        viewModelScope.launch {
            val draft = withContext(Dispatchers.IO) {
                runCatching {
                    val name = "Scan ${java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.UK).format(java.util.Date())}.pdf"
                    val file = Imports.copy(context, pdf, name)
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    val text = pageImages.take(2).mapNotNull { uri ->
                        runCatching { recognizer.process(InputImage.fromFilePath(context, uri)).await().text }.getOrNull()
                    }.joinToString("\n")
                    recognizer.close()
                    val (metadata, problem) = PaperParser.analyse(text, "scan.pdf")
                    ScanDraft(file, metadata.copy(paperType = "QP"), problem == null, pageImages.size.coerceAtLeast(1))
                }.getOrNull()
            }
            readingScan = false
            if (draft == null) show("Couldn't read the scan. Try scanning again.", true) else scanDraft = draft
        }
    }

    /** Photos taken one by one with the phone's camera app (when Google's scanner isn't available). */
    val photoPages = mutableStateListOf<File>()

    fun newPhotoFile(): File = File(context.cacheDir, "photos/${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs() }

    fun photoUri(file: File): Uri = localUri(file)

    fun discardPhotos() {
        photoPages.forEach { it.delete() }
        photoPages.clear()
    }

    /** Turn the photos into a PDF (one page each, A4 width), then read which exam it is. */
    fun preparePhotoScan() {
        val photos = photoPages.toList()
        if (photos.isEmpty()) return
        readingScan = true
        viewModelScope.launch {
            val draft = withContext(Dispatchers.IO) {
                runCatching {
                    val name = "Scan ${java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.UK).format(java.util.Date())}.pdf"
                    val pdf = File(Imports.folder(context), "${UUID.randomUUID()}/$name").apply { parentFile?.mkdirs() }
                    val document = android.graphics.pdf.PdfDocument()
                    photos.forEachIndexed { index, photo ->
                        val bitmap = Photos.load(photo, 1600) ?: return@forEachIndexed
                        val width = 595
                        val height = (width.toFloat() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
                        val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                        page.canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, width, height),
                            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                        document.finishPage(page)
                        bitmap.recycle()
                    }
                    pdf.outputStream().use { document.writeTo(it) }
                    document.close()
                    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    val text = photos.take(2).mapNotNull { photo ->
                        runCatching { recognizer.process(InputImage.fromFilePath(context, Uri.fromFile(photo))).await().text }.getOrNull()
                    }.joinToString("\n")
                    recognizer.close()
                    val (metadata, problem) = PaperParser.analyse(text, "scan.pdf")
                    ScanDraft(pdf, metadata.copy(paperType = "QP"), problem == null, photos.size)
                }.getOrNull()
            }
            discardPhotos()
            readingScan = false
            if (draft == null) show("Couldn't make a PDF from the photos. Try again.", true) else scanDraft = draft
        }
    }

    fun cancelScan() {
        scanDraft?.let { Imports.remove(context, it.file) }
        scanDraft = null
    }

    /** The teacher said whose paper it is: add the class/student if new, then check it straight away. */
    fun finishScan(fields: PaperParser.ManualFields, classId: String?, newClassName: String, studentName: String): String? {
        val draft = scanDraft ?: return "The scan is gone. Scan again."
        val (metadata, error) = PaperParser.completeManual(fields.copy(paperType = "QP"))
        if (error != null) return error
        val name = studentName.trim()
        if (name.isEmpty()) return "Type the student's name."
        return try {
            val chosenClass = classId ?: run {
                val className = newClassName.trim()
                if (className.isEmpty()) return "Choose a class, or type a name for a new one."
                scoreBook.classes.firstOrNull { it.name.equals(className, true) }?.id ?: scoreBook.addClass(className).id
            }
            val student = scoreBook.members(chosenClass).firstOrNull { it.name.equals(name, true) } ?: scoreBook.addStudent(name, chosenClass)
            val paper = CheckPaper(file = draft.file, name = draft.file.name, status = PaperStatus.READY, metadata = metadata,
                answers = SolveResult(true, (1..draft.pages).toList(), "scanned", draft.pages), studentId = student.id)
            papers.add(0, paper)
            scanDraft = null
            screen = Screen.CHECK
            show("Checking ${student.name}'s paper…")
            queue(paper.id)
            null
        } catch (e: UserError) {
            e.message
        }
    }
}

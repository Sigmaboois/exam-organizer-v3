@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.gasserelshafei.examorganizer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gasserelshafei.examorganizer.AppState
import com.gasserelshafei.examorganizer.CheckPaper
import com.gasserelshafei.examorganizer.PaperStatus
import com.gasserelshafei.examorganizer.friendlyMessage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Pick a student, grouped by class. "Don't save" checks a paper without keeping the score. */
@Composable
fun StudentPicker(state: AppState, label: String, allowDontSave: Boolean, onPick: (String?, Boolean) -> Unit, enabled: Boolean = true) {
    val book = state.scoreBook
    val options = buildList<Pair<Pair<String?, Boolean>, String>> {
        if (allowDontSave) add((null to true) to "Don't save to a student")
        for ((_, classes) in book.classesByQualification()) for (schoolClass in classes) {
            for (student in book.members(schoolClass.id)) add((student.id to false) to "${student.name}  ·  ${schoolClass.name}")
        }
    }
    ChoiceButton(label, options, { (id, dontSave) -> onPick(id, dontSave) }, enabled = enabled, leading = Icons.Outlined.Person)
}

@Composable
fun CheckScreen(state: AppState, wide: Boolean) {
    val context = LocalContext.current
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) state.addPapers(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { state.addPapers(listOf(it), folder = true) }
    }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pdf = scan?.pdf?.uri
            if (pdf != null) state.prepareScan(pdf, scan.pages?.map { it.imageUri }.orEmpty())
            else state.show("The scan didn't make a PDF. Please try again.", true)
        }
    }
    // Backup for phones without Google's scanner: photos one page at a time with the camera app
    var pendingPhoto by remember { mutableStateOf<java.io.File?>(null) }
    var showPhotoPages by remember { mutableStateOf(false) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = pendingPhoto
        if (taken && file != null && file.length() > 0) state.photoPages += file else file?.delete()
        pendingPhoto = null
        showPhotoPages = state.photoPages.isNotEmpty()
    }
    fun startPhotos() {
        val file = state.newPhotoFile()
        pendingPhoto = file
        runCatching { takePhoto.launch(state.photoUri(file)) }.onFailure {
            state.show("This phone has no camera app I can use.", true)
            pendingPhoto = null
        }
    }

    fun startScan() {
        val activity = context.activity() ?: return
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(60)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { sender -> scanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener {
                state.show("Google's scanner isn't available on this phone, so I'll use the camera app instead.")
                startPhotos()
            }
    }

    var editing by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<String?>(null) }
    var assignTo by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(true) }
    // When a paper is added (a scan, files, or from Organise), scroll down so it can be seen
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var seenPapers by remember { mutableStateOf(state.papers.size) }
    androidx.compose.runtime.LaunchedEffect(state.papers.size) {
        if (state.papers.size > seenPapers) listState.animateScrollToItem(if (showInfo) 2 else 1)
        seenPapers = state.papers.size
    }
    val ready = state.readyPapers

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard("Add solved papers") {
                    Button(onClick = ::startScan, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.DocumentScanner, null)
                        Text("  Scan a paper with the camera")
                    }
                    Note("Take a photo of every page. I make a PDF, read which exam it is, ask whose it is, then check it.")
                    TextButton(onClick = ::startPhotos) { Text("Scanner not opening? Take photos with the camera app") }
                    HorizontalDivider()
                    Note("Or add students' papers as PDFs (exported from a notes app, or shared to Exam Organizer).")
                    AddFilesButtons({ pickFiles.launch(arrayOf("application/pdf")) }, { pickFolder.launch(null) })
                }
            }
            if (showInfo) item {
                SectionCard(null) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("How checking works", fontWeight = FontWeight.SemiBold)
                            Text("I find the official mark scheme for each paper (from your sorted exams, or I download it), then an AI " +
                                "examiner marks the answers question by question. Handwriting from any notes app is fine.",
                                style = MaterialTheme.typography.bodyMedium)
                            Note("The AI is good but not perfect: anything it's unsure about is flagged for you to double-check, and you can change any mark.")
                        }
                        IconButton(onClick = { showInfo = false }) { Icon(Icons.Outlined.Close, "Hide") }
                    }
                }
            }
            item {
                SectionCard("Papers") {
                    if (state.papers.isEmpty()) {
                        EmptyState(Icons.Outlined.FactCheck, "No papers yet", "Scan or add a student's solved question paper to check it.")
                    } else {
                        Note(state.paperSummary())
                        state.lastResult?.let { Text(it, color = Good, fontWeight = FontWeight.Medium) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StudentPicker(state, assignTo?.let { state.scoreBook.student(it)?.name } ?: "Assign every paper to…", false,
                                { id, _ -> assignTo = id })
                            OutlinedButton(onClick = { assignTo?.let(state::assignAll) }, enabled = assignTo != null) { Text("Assign") }
                            TextButton(onClick = state::clearPapers) { Text("Clear") }
                        }
                    }
                }
            }
            items(state.papers, key = { it.id }) { paper ->
                CheckRow(state, paper, busy = state.busyWith != null, onFix = { editing = paper.id }, onResults = { results = paper.id })
            }
        }
        ExtendedFloatingActionButton(
            onClick = { if (ready > 0) state.checkAll() },
            icon = { Icon(Icons.Outlined.FactCheck, null) },
            text = { Text(if (ready > 0) "Check $ready paper${if (ready == 1) "" else "s"}" else "Check all") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = if (ready > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        )
    }

    editing?.let { id ->
        state.paper(id)?.let { paper ->
            FixDetailsDialog(paper.name, paper.metadata, onDismiss = { editing = null }) { state.applyPaperDetails(id, it) }
        }
    }
    results?.let { id -> ResultsDialog(state, id) { results = null } }

    if (showPhotoPages && state.photoPages.isNotEmpty()) {
        val count = state.photoPages.size
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("$count page${if (count == 1) "" else "s"} taken") },
            text = { Text("Take the next page, or tap Done when every page is in. Start with the cover page: it says which exam it is.") },
            confirmButton = {
                TextButton(onClick = { showPhotoPages = false; state.preparePhotoScan() }) { Text("Done") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showPhotoPages = false; state.discardPhotos() }) { Text("Cancel") }
                    TextButton(onClick = { showPhotoPages = false; startPhotos() }) { Text("Next page") }
                }
            },
        )
    }
}

@Composable
private fun CheckRow(state: AppState, paper: CheckPaper, busy: Boolean, onFix: () -> Unit, onResults: () -> Unit) {
    val status = when (paper.status) {
        PaperStatus.WAITING, PaperStatus.READING, PaperStatus.QUEUED, PaperStatus.CHECKING -> RowStatus.WORKING
        PaperStatus.READY -> RowStatus.FILE
        PaperStatus.DONE -> RowStatus.DONE
        PaperStatus.PROBLEM -> RowStatus.WARNING
    }
    val studentLabel = if (paper.dontSave) "Not saved to a student" else state.scoreBook.student(paper.studentId)?.name ?: "Whose paper is this?"
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusIcon(status)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(paper.metadata?.title?.takeIf { it.isNotEmpty() } ?: paper.name, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    paper.metadata?.describe()?.takeIf { it.isNotEmpty() }?.let { Note(it) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val outcome = paper.outcome
                        if (outcome != null && paper.status == PaperStatus.DONE) {
                            Pill("${outcome.marking.total} / ${outcome.marking.maxTotal}  ·  ${outcome.marking.percent}%", Good)
                            outcome.grade?.let { Pill("Grade $it") }
                            if (outcome.marking.needsReview.isNotEmpty()) Pill("Double-check ${outcome.marking.needsReview.size}", Warn, Icons.Outlined.Visibility)
                        }
                        val answers = paper.answers
                        if (paper.metadata?.paperType == "QP" && answers != null) {
                            when {
                                answers.method == "scanned" -> Pill("Scanned · ${answers.pageCount} page${if (answers.pageCount == 1) "" else "s"}",
                                    MaterialTheme.colorScheme.secondary, Icons.Outlined.CameraAlt)
                                answers.solved -> Pill("Answers on ${answers.pages.size} page${if (answers.pages.size == 1) "" else "s"}",
                                    MaterialTheme.colorScheme.secondary, Icons.Outlined.Create)
                                else -> Pill("Looks unanswered", Warn, Icons.Outlined.Info)
                            }
                        }
                    }
                    when (paper.status) {
                        PaperStatus.WAITING -> Note("Waiting to be read…")
                        PaperStatus.READING -> Note("Reading the paper and looking for answers…")
                        PaperStatus.QUEUED -> Note("Waiting for its turn…")
                        PaperStatus.CHECKING -> Note(paper.progress)
                        PaperStatus.READY -> if (paper.studentId == null && !paper.dontSave) Note("Choose whose paper this is, then tap Check.")
                        PaperStatus.DONE -> Note(if (paper.saved) "Saved to the score book ✓" else "Not saved to a student yet.", if (paper.saved) Good else MaterialTheme.colorScheme.onSurfaceVariant)
                        PaperStatus.PROBLEM -> Note(paper.message, Warn)
                    }
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        if (paper.status == PaperStatus.READY || paper.status == PaperStatus.PROBLEM) {
                            DropdownMenuItem(text = { Text("Fix details") }, onClick = { menu = false; onFix() })
                        }
                        if (paper.status != PaperStatus.CHECKING) {
                            DropdownMenuItem(text = { Text("Remove from the list") }, onClick = { menu = false; state.removePaper(paper.id) })
                        }
                    }
                }
            }
            FlowRow(Modifier.padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StudentPicker(state, studentLabel, true, { id, dontSave -> state.chooseStudent(paper.id, id, dontSave) },
                    enabled = paper.status != PaperStatus.CHECKING)
                if (paper.status == PaperStatus.DONE) Button(onClick = onResults) { Text("Results") }
                if (paper.checkable && paper.status in setOf(PaperStatus.READY, PaperStatus.DONE, PaperStatus.PROBLEM)) {
                    FilledTonalButton(onClick = { state.queue(paper.id) }, enabled = !busy || paper.status == PaperStatus.DONE) {
                        Text(if (paper.status == PaperStatus.DONE) "Check again" else "Check")
                    }
                }
            }
        }
    }
}

/** A checked paper's results. Marks can be changed; Save corrections updates the grade, the PDF and the score book. */
@Composable
private fun ResultsDialog(state: AppState, id: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paper = state.paper(id) ?: return onDismiss()
    val outcome = paper.outcome ?: return onDismiss()
    val marking = outcome.marking
    val awards = remember(marking) { mutableStateListOf<Int>().apply { addAll(marking.questions.map { it.marksAwarded }) } }
    val changed = awards.toList() != marking.questions.map { it.marksAwarded }
    var saving by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(paper.metadata?.title ?: "Results", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close") } },
                actions = {
                    TextButton(enabled = changed && !saving, onClick = {
                        saving = true
                        scope.launch {
                            runCatching { state.revise(id, awards.toList()) }
                                .onSuccess { state.show("Corrections saved.") }
                                .onFailure { state.show(friendlyMessage(it), true) }
                            saving = false
                        }
                    }) { Text(if (saving) "Saving…" else "Save corrections") }
                },
            )
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("${if (changed) awards.sum() else marking.total} / ${marking.maxTotal}", style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Column {
                            Text("${marking.percent}%", style = MaterialTheme.typography.titleMedium)
                            Note(outcome.grade?.let { "Grade $it (Cambridge thresholds)" } ?: "Grade not available for this paper")
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        paper.metadata?.describe()?.let { Note(it) }
                        if (marking.summary.isNotEmpty()) Text(marking.summary, style = MaterialTheme.typography.bodyMedium)
                        if (!marking.complete) Note("The question marks add up to ${marking.maxTotal}, but the paper is out of " +
                            "${marking.paperTotalMarks}. Some parts may be missing.", Warn)
                        if (marking.needsReview.isNotEmpty()) Text("Please double-check: ${marking.needsReview.joinToString(", ")}",
                            color = Warn, fontWeight = FontWeight.SemiBold)
                        if (marking.teacherAdjusted) Note("Marks adjusted by you.")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                state.viewIntent(outcome.savedPdf, outcome.local)?.let { runCatching { context.startActivity(it) } }
                            }) { Icon(Icons.Outlined.PictureAsPdf, null); Text("  Open marked copy") }
                            OutlinedButton(onClick = { context.startActivity(state.shareIntent(outcome.local, "application/pdf")) }) {
                                Icon(Icons.Outlined.Share, null); Text("  Share")
                            }
                        }
                        Note("Change a mark with − and +, then tap Save corrections. The grade, the marked PDF and the score book update.")
                    }
                }
                itemsIndexed(marking.questions, key = { _, q -> q.id }) { index, q ->
                    val award = awards.getOrElse(index) { q.marksAwarded }
                    val colour = if (award == q.maxMarks) Good else if (award == 0) MaterialTheme.colorScheme.error else Warn
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(q.question, fontWeight = FontWeight.Bold)
                                Text("$award / ${q.maxMarks}", color = colour, fontWeight = FontWeight.Bold)
                                Row {
                                    IconButton(onClick = { if (award > 0) awards[index] = award - 1 }, enabled = award > 0) {
                                        Icon(Icons.Outlined.Remove, "One mark less")
                                    }
                                    IconButton(onClick = { if (award < q.maxMarks) awards[index] = award + 1 }, enabled = award < q.maxMarks) {
                                        Icon(Icons.Outlined.Add, "One mark more")
                                    }
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (q.topic.isNotEmpty()) Text(q.topic, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                Text(q.marking, style = MaterialTheme.typography.bodyMedium)
                                if (q.feedback.isNotEmpty()) Text(q.feedback, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (q.studentAnswer.isNotEmpty()) Note("Student wrote: ${q.studentAnswer}")
                                if (q.confidence == "low") Pill("Double-check", Warn, Icons.Outlined.Visibility)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.padding(8.dp)) }
            }
        }
    }
}

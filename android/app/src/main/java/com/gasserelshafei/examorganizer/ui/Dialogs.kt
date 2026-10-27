@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gasserelshafei.examorganizer.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gasserelshafei.examorganizer.AppState
import com.gasserelshafei.examorganizer.BuildConfig
import com.gasserelshafei.examorganizer.Catalog
import com.gasserelshafei.examorganizer.PaperMetadata
import com.gasserelshafei.examorganizer.PaperParser
import com.gasserelshafei.examorganizer.Subjects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The exam fields shared by Fix details and the scan form. */
@Composable
fun ExamFields(fields: PaperParser.ManualFields, onChange: (PaperParser.ManualFields) -> Unit, showType: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(fields.subjectCode, { onChange(fields.copy(subjectCode = it.trim())) }, label = { Text("Subject code, e.g. 0625") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        fields.subjectCode.takeIf { it.length == 4 }?.let { code ->
            val matches = Subjects.all(code)
            Note(if (matches.isEmpty()) "Not in Cambridge's current subject list" else matches.joinToString("\n") { "${it.first}: ${it.second}" })
        }
        ChoiceButton(fields.qualification ?: "Qualification: work it out from the code",
            listOf<Pair<String?, String>>(null to "Work it out from the code") + Catalog.qualifications.map { it to it },
            { onChange(fields.copy(qualification = it)) })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(fields.year, { onChange(fields.copy(year = it.trim())) }, label = { Text("Year") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            ChoiceButton(fields.session?.let(Catalog::sessionLabel) ?: "Session", Catalog.sessions.map { it to Catalog.sessionLabel(it) },
                { onChange(fields.copy(session = it)) }, Modifier.weight(1.4f))
        }
        if (showType) {
            ChoiceButton(fields.paperType?.let { Catalog.paperTypes[it] } ?: "File type", Catalog.paperTypes.map { it.key to it.value },
                { onChange(fields.copy(paperType = it)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(fields.paper, { onChange(fields.copy(paper = it.trim())) }, label = { Text("Paper") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(fields.variant, { onChange(fields.copy(variant = it.trim())) }, label = { Text("Variant") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun FixDetailsDialog(fileName: String, initial: PaperMetadata?, onDismiss: () -> Unit, onSave: (PaperMetadata) -> Unit) {
    var fields by remember { mutableStateOf(PaperParser.ManualFields.from(initial)) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fix details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Note(fileName)
                ExamFields(fields, { fields = it })
                Note("Grade thresholds and examiner reports cover the whole series, so they don't need a paper or variant.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (metadata, problem) = PaperParser.completeManual(fields)
                if (problem != null) error = problem else { onSave(metadata); onDismiss() }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, label: String, confirm: String, onDismiss: () -> Unit, onSave: (String) -> String?) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = { error = onSave(text); if (error == null) onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PasteListDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste a class list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Note("One name per line. Names already in the class are skipped.")
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(text); onDismiss() }, enabled = text.isNotBlank()) { Text("Add students") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The mistakes report for one checked paper. */
@Composable
fun ReportDialog(state: AppState, path: String?, fallback: () -> String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) { text = withContext(Dispatchers.IO) { state.readSavedText(path) } ?: fallback() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mistakes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(text ?: "Loading…", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            if (path != null) TextButton(onClick = { state.shareSaved(context, path, "text/plain") }) {
                Icon(Icons.Outlined.Share, null)
                Text(" Share")
            }
        },
    )
}

@Composable
fun SettingsDialog(state: AppState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(state.geminiKey) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(state::chooseStorage) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Where your files go", style = MaterialTheme.typography.titleSmall)
                Note("Sorted exams go in Exams, and each class gets a folder with a sub-folder per student holding " +
                    "their marked papers and mistakes. Now: ${state.storageLabel}")
                OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Choose a folder") }
                HorizontalDivider()
                Text("AI checking", style = MaterialTheme.typography.titleSmall)
                Note(if (BuildConfig.GEMINI_API_KEY.isEmpty()) "This copy of the app has no key built in, so add your own free key to check papers."
                else "Checking already works with the key built into the app. Add your own free key if the shared daily limit keeps running out.")
                OutlinedTextField(key, { key = it }, label = { Text("Your own Gemini key (optional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }) {
                    Text("Get a free key from Google AI Studio")
                }
                HorizontalDivider()
                Note("Checking uses Google's free Gemini AI. On the free tier Google may use what is sent (the student's pages " +
                    "and the mark scheme) to improve its services, so don't add anything private to the papers.")
                Note("Subjects known: ${Subjects.count} · Version ${BuildConfig.VERSION_NAME}")
            }
        },
        confirmButton = { TextButton(onClick = { state.saveKey(key); onDismiss() }) { Text("Done") } },
    )
}

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How it works") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Organise", style = MaterialTheme.typography.titleSmall)
                Text("Pick Cambridge papers (whole folders are fine), or share PDFs to Exam Organizer from another app. I read each one " +
                    "and work out the subject, series, paper and variant. Check what I found, fix anything I couldn't, then tap Sort.")
                Text("Check papers", style = MaterialTheme.typography.titleSmall)
                Text("Scan a student's paper with the camera, or add their PDFs. Say whose paper it is and tap Check: I download the " +
                    "mark scheme and grade thresholds, an AI examiner marks every question, and you get a marked PDF and a list of mistakes.")
                Text("Classes", style = MaterialTheme.typography.titleSmall)
                Text("Add classes and students, or paste a class list. Each class has a folder with one per student. Each class shows " +
                    "its average, its grades and the topics to revise, and exports to a spreadsheet.")
                Text("Lessons", style = MaterialTheme.typography.titleSmall)
                Text("Link your WhatsApp once, then give each class its Zoom link, WhatsApp group and lesson times. Before " +
                    "every lesson the link is sent to the group by itself, even with the app closed and the phone locked.")
                Note("Works with Cambridge (CAIE) IGCSE, O Level and AS & A Level. Only question papers can be checked.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** After scanning: whose paper is it, which class, and which exam (filled in from the scan). */
@Composable
fun ScanDialog(state: AppState) {
    val draft = state.scanDraft ?: return
    val book = state.scoreBook
    val sortedClasses = book.classes.sortedBy { it.name.lowercase() }
    var fields by remember(draft) { mutableStateOf(PaperParser.ManualFields.from(draft.metadata)) }
    var classId by remember(draft) { mutableStateOf(sortedClasses.firstOrNull()?.id) }
    var newClass by remember(draft) { mutableStateOf("") }
    var student by remember(draft) { mutableStateOf("") }
    var error by remember(draft) { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text("Scanned paper") },
                navigationIcon = { IconButton(onClick = state::cancelScan) { Icon(Icons.Outlined.Close, "Cancel") } },
                actions = {
                    Button(onClick = { error = state.finishScan(fields, classId, newClass, student) }, modifier = Modifier.padding(end = 8.dp)) {
                        Text("Check paper")
                    }
                },
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Whose paper is this?", style = MaterialTheme.typography.titleMedium)
                ChoiceButton(sortedClasses.firstOrNull { it.id == classId }?.name ?: "New class…",
                    sortedClasses.map<_, Pair<String?, String>> { it.id to it.name } + (null to "New class…"), { classId = it })
                if (classId == null) {
                    OutlinedTextField(newClass, { newClass = it }, label = { Text("New class name, e.g. Year 11 Physics") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(student, { student = it }, label = { Text("Student's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val suggestions = classId?.let { id -> book.members(id).filter { student.isBlank() || it.name.contains(student.trim(), true) } }.orEmpty()
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.take(12).forEach { s -> AssistChip(onClick = { student = s.name }, label = { Text(s.name) }) }
                    }
                }
                Note("A new name is added to the class. Their marked paper and a list of their mistakes are saved in the class folder.")
                HorizontalDivider()
                Text("Which exam?", style = MaterialTheme.typography.titleMedium)
                Note(if (draft.found) "Found: ${draft.metadata.title} · ${draft.metadata.describe()}. Change anything that's wrong."
                else "I couldn't read every detail from the photos, so please fill in what's missing. Tip: include the cover page in the scan.")
                ExamFields(fields, { fields = it }, showType = false)
                Note("${draft.pages} page${if (draft.pages == 1) "" else "s"} scanned.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
fun ReadingScanDialog() {
    Dialog(onDismissRequest = {}) {
        androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Reading the paper…", style = MaterialTheme.typography.titleMedium)
                Note("Making a PDF and finding which exam it is.")
            }
        }
    }
}

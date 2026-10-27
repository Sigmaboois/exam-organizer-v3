@file:OptIn(ExperimentalLayoutApi::class)

package com.gasserelshafei.examorganizer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gasserelshafei.examorganizer.AppState
import com.gasserelshafei.examorganizer.CheckRecord
import com.gasserelshafei.examorganizer.Catalog
import com.gasserelshafei.examorganizer.MistakesReport
import com.gasserelshafei.examorganizer.ScoreBook
import com.gasserelshafei.examorganizer.Student
import com.gasserelshafei.examorganizer.friendlyMessage
import java.text.DateFormat
import java.util.Date

/** Phones: the class list, then a class's page. Tablets: both side by side. */
@Composable
fun ClassesScreen(state: AppState, wide: Boolean) {
    var selected by remember { mutableStateOf<String?>(null) }
    val open = selected?.takeIf { state.scoreBook.schoolClass(it) != null }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(340.dp).fillMaxHeight()) { ClassList(state, open) { selected = it } }
            VerticalDivider()
            Box(Modifier.weight(1f)) {
                if (open != null) ClassDetail(state, open, onBack = null) { selected = null }
                else EmptyState(Icons.Outlined.Groups, "Choose a class", "Pick a class on the left, or add your first one.")
            }
        }
    } else if (open != null) {
        BackHandler { selected = null }
        ClassDetail(state, open, onBack = { selected = null }) { selected = null }
    } else {
        ClassList(state, null) { selected = it }
    }
}

@Composable
private fun ClassList(state: AppState, selected: String?, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val book = state.scoreBook
    var name by remember { mutableStateOf("") }
    fun add() {
        runCatching { book.addClass(name) }.onSuccess { name = ""; onOpen(it.id) }.onFailure { state.show(friendlyMessage(it), true) }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("New class, e.g. Year 11 Physics") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = ::add, enabled = name.isNotBlank()) { Text("Add") }
            }
        }
        if (book.results.isNotEmpty()) item {
            TextButton(onClick = { state.exportCsv(book.results.toList(), "All results")?.let(context::startActivity) }) {
                Icon(Icons.Outlined.TableChart, null); Text("  Export all results")
            }
        }
        if (book.classes.isEmpty()) item {
            Note("No classes yet. Checked papers are saved to students, so start by adding a class. Each class gets its own " +
                "folder, with a sub-folder for every student.")
        }
        for ((divider, classes) in book.classesByQualification()) {
            item(key = "divider-$divider") {
                Text(divider, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            }
            items(classes, key = { it.id }) { schoolClass ->
                val count = book.members(schoolClass.id).size
                OutlinedCard(Modifier.fillMaxWidth().clickable { onOpen(schoolClass.id) },
                    colors = if (schoolClass.id == selected) androidx.compose.material3.CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer) else androidx.compose.material3.CardDefaults.outlinedCardColors()) {
                    ListItem(headlineContent = { Text(schoolClass.name, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text("$count student${if (count == 1) "" else "s"}") })
                }
            }
        }
    }
}

@Composable
private fun ClassDetail(state: AppState, classId: String, onBack: (() -> Unit)?, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val book = state.scoreBook
    val schoolClass = book.schoolClass(classId) ?: return
    val records = book.recordsForClass(classId)
    val members = book.members(classId)
    var newStudent by remember { mutableStateOf("") }
    var renamingClass by remember { mutableStateOf(false) }
    var renamingStudent by remember { mutableStateOf<Student?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    fun addStudent() {
        runCatching { book.addStudent(newStudent, classId) }.onSuccess { newStudent = "" }.onFailure { state.show(friendlyMessage(it), true) }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(schoolClass.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Note(schoolClass.qualification ?: "Qualification is set by the first paper you check")
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Class options") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename class") }, onClick = { menu = false; renamingClass = true })
                        DropdownMenuItem(text = { Text("Export this class") }, enabled = records.isNotEmpty(), onClick = {
                            menu = false
                            state.exportCsv(records, schoolClass.name)?.let(context::startActivity)
                        })
                        DropdownMenuItem(text = { Text("Delete class") }, onClick = { menu = false; deleting = true })
                    }
                }
            }
        }
        item {
            SectionCard("Overview") {
                if (records.isEmpty()) Note("No papers checked yet.")
                else {
                    val average = Math.round(ScoreBook.average(records) ?: 0.0)
                    Text("Average $average% across ${records.size} paper${if (records.size == 1) "" else "s"}", fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ScoreBook.gradeCounts(records).forEach { (grade, count) -> Pill("$grade  $count") }
                    }
                    Text("Topics to revise", fontWeight = FontWeight.SemiBold)
                    val topics = ScoreBook.topicLosses(records.map { it.marking })
                    when {
                        topics.isNotEmpty() -> topics.take(6).forEach {
                            Text("${it.topic}  ·  ${it.lost} mark${if (it.lost == 1) "" else "s"} lost across ${it.questions} part${if (it.questions == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        ScoreBook.topicsRecorded(records) -> Note("No marks lost on a topic so far.")
                        else -> Note("Topic insights show up after the next paper you check.")
                    }
                }
                Note("Folder: ${state.storageLabel} › Classes › ${schoolClass.name}")
            }
        }
        item {
            SectionCard("Students") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newStudent, { newStudent = it }, label = { Text("Add a student") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = ::addStudent, enabled = newStudent.isNotBlank()) { Text("Add") }
                }
                OutlinedButton(onClick = { pasting = true }) { Icon(Icons.Outlined.ListAlt, null); Text("  Paste a class list") }
                if (members.isEmpty()) Note("No students yet.")
            }
        }
        items(members, key = { it.id }) { student ->
            StudentCard(state, student, book.recordsForStudent(student.id), onRename = { renamingStudent = student })
        }
    }

    if (renamingClass) TextInputDialog("Rename class", schoolClass.name, "Class name", "Save", { renamingClass = false }) { text ->
        runCatching { book.renameClass(classId, text) }.exceptionOrNull()?.let(::friendlyMessage)
    }
    renamingStudent?.let { student ->
        TextInputDialog("Rename student", student.name, "Name", "Save", { renamingStudent = null }) { text ->
            runCatching { book.renameStudent(student.id, text) }.exceptionOrNull()?.let(::friendlyMessage)
        }
    }
    if (pasting) PasteListDialog({ pasting = false }) { text ->
        val (added, skipped) = book.addStudents(text, classId)
        state.show("Added $added student${if (added == 1) "" else "s"}." + (if (skipped > 0) " $skipped were already in the class." else ""))
    }
    if (deleting) ConfirmDialog("Delete ${schoolClass.name}?", "This deletes the class, its students and their scores. " +
        "Files already saved in the class folder stay where they are.", "Delete", { deleting = false }) {
        book.deleteClass(classId)
        onDeleted()
    }
}

@Composable
private fun StudentCard(state: AppState, student: Student, records: List<CheckRecord>, onRename: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val subtitle = records.firstOrNull()?.let { latest ->
        val average = Math.round(ScoreBook.average(records) ?: 0.0)
        "${records.size} paper${if (records.size == 1) "" else "s"} checked · average $average% · latest ${latest.score}/${latest.maxScore}" +
            (latest.grade?.let { " ($it)" } ?: "")
    } ?: "No papers checked yet"
    OutlinedCard(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(student.name, fontWeight = FontWeight.Medium) },
            supportingContent = { Text(subtitle) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Student options") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                            DropdownMenuItem(text = { Text("Delete student") }, onClick = { menu = false; deleting = true })
                        }
                    }
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
            },
            modifier = Modifier.clickable { expanded = !expanded },
        )
        if (expanded) {
            HorizontalDivider()
            if (records.isEmpty()) Box(Modifier.padding(16.dp)) { Note("Check a paper and choose ${student.name} to see scores here.") }
            records.forEach { ResultRow(state, it) }
        }
    }
    if (deleting) ConfirmDialog("Delete ${student.name}?", "This deletes ${student.name} and their scores. Files already saved in " +
        "their folder stay where they are.", "Delete", { deleting = false }) { state.scoreBook.deleteStudent(student.id) }
}

@Composable
private fun ResultRow(state: AppState, record: CheckRecord) {
    val context = LocalContext.current
    var showReport by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val m = record.metadata
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("${m.subjectName ?: "Paper"} · Paper ${m.paper ?: ""}${m.variant ?: ""}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Note("${Catalog.sessionLabel(m.session ?: "")} ${m.year ?: ""} · checked ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(record.checkedAt))}")
        }
        Text("${record.score}/${record.maxScore}", fontWeight = FontWeight.SemiBold)
        record.grade?.let { Pill(it) }
        IconButton(onClick = { showReport = true }) { Icon(Icons.Outlined.ListAlt, "Mistakes") }
        IconButton(onClick = { state.open(context, record.markedPdf, missing = "The marked copy isn't in the folder any more.") }) {
            Icon(Icons.Outlined.PictureAsPdf, "Open marked copy")
        }
        IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.Delete, "Delete this result") }
    }
    if (showReport) {
        val student = state.scoreBook.student(record.studentId)
        ReportDialog(state, record.report, {
            MistakesReport.text(record.marking, record.metadata, student?.name, state.scoreBook.schoolClass(student?.classId)?.name,
                record.grade, Date(record.checkedAt))
        }) { showReport = false }
    }
    if (deleting) ConfirmDialog("Delete this result?", "The score is removed from the score book. The marked PDF stays in the folder.",
        "Delete", { deleting = false }) { state.scoreBook.deleteResult(record.id) }
}

@file:OptIn(ExperimentalLayoutApi::class)

package com.gasserelshafei.examorganizer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gasserelshafei.examorganizer.AppState
import com.gasserelshafei.examorganizer.ExamItem
import com.gasserelshafei.examorganizer.ExamStatus

@Composable
fun AddFilesButtons(onFiles: () -> Unit, onFolder: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onFiles) { Icon(Icons.Outlined.UploadFile, null); Text("  Choose files") }
        OutlinedButton(onClick = onFolder) { Icon(Icons.Outlined.CreateNewFolder, null); Text("  Choose a folder") }
    }
}

@Composable
fun OrganiseScreen(state: AppState, wide: Boolean) {
    val context = LocalContext.current
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) state.addExams(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { state.addExams(listOf(it), folder = true) }
    }
    val pickStorage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(state::chooseStorage) }
    var editing by remember { mutableStateOf<String?>(null) }
    val ready = state.readyExams

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard("Add exams", 1) {
                    Note("Pick Cambridge PDFs: one file, lots of files, or a whole folder. You can also share PDFs to " +
                        "Exam Organizer from Files, WhatsApp, Gmail or a notes app.")
                    AddFilesButtons({ pickFiles.launch(arrayOf("application/pdf")) }, { pickFolder.launch(null) })
                }
            }
            item {
                SectionCard("Where they go", 2) {
                    Text("${state.storageLabel} › Exams", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    OutlinedButton(onClick = { pickStorage.launch(null) }) { Icon(Icons.Outlined.FolderOpen, null); Text("  Choose a folder") }
                    Note("Your files are copied and sorted: the originals stay where they are. Pick a folder in Documents, " +
                        "Downloads or Google Drive to find the sorted exams easily.")
                }
            }
            item {
                SectionCard("Check and sort", 3) {
                    if (state.exams.isEmpty()) {
                        EmptyState(Icons.Outlined.FileOpen, "No exams yet", "Exams you add show up here so you can check them before sorting.")
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Note(state.examSummary())
                            Spacer(Modifier.weight(1f))
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { menu = true }) { Text("Clear") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("Clear sorted exams") }, onClick = { menu = false; state.clearSorted() })
                                    DropdownMenuItem(text = { Text("Clear the whole list") }, onClick = { menu = false; state.clearExams() })
                                }
                            }
                        }
                    }
                }
            }
            items(state.exams, key = { it.id }) { item ->
                ExamRow(item,
                    onFix = { editing = item.id },
                    onCheck = { state.sendToCheck(item.id) },
                    onOpen = { state.open(context, item.sortedTo, item.file) },
                    onRemove = { state.removeExam(item.id) })
            }
        }
        ExtendedFloatingActionButton(
            onClick = { if (ready > 0 && !state.sorting) state.sortAll() },
            icon = { Icon(Icons.Outlined.AutoAwesome, null) },
            text = { Text(if (ready > 0) "Sort $ready exam${if (ready == 1) "" else "s"}" else "Sort exams") },
            expanded = true,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = if (ready > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        )
    }

    editing?.let { id ->
        state.exams.firstOrNull { it.id == id }?.let { item ->
            FixDetailsDialog(item.name, item.metadata, onDismiss = { editing = null }) { state.applyExamDetails(id, it) }
        }
    }
}

@Composable
private fun ExamRow(item: ExamItem, onFix: () -> Unit, onCheck: () -> Unit, onOpen: () -> Unit, onRemove: () -> Unit) {
    val status = when (item.status) {
        ExamStatus.WAITING, ExamStatus.READING, ExamStatus.SORTING -> RowStatus.WORKING
        ExamStatus.READY -> RowStatus.FILE
        ExamStatus.SORTED -> RowStatus.DONE
        ExamStatus.PROBLEM -> RowStatus.WARNING
    }
    val solved = item.metadata?.paperType == "QP" && item.answers?.solved == true
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusIcon(status)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.metadata?.title?.takeIf { it.isNotEmpty() } ?: item.name, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                item.metadata?.describe(withType = true)?.takeIf { it.isNotEmpty() }?.let { Note(it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.metadata?.paperType == "QP") {
                        when {
                            item.checkingAnswers -> Pill("Looking for answers…", MaterialTheme.colorScheme.outline, Icons.Outlined.Search)
                            item.answers?.solved == true -> Pill("Answers on ${item.answers.pages.size} page${if (item.answers.pages.size == 1) "" else "s"}",
                                MaterialTheme.colorScheme.secondary, Icons.Outlined.Create)
                            item.answers != null -> Pill("Looks unanswered", Warn, Icons.Outlined.Info)
                        }
                    }
                }
                when (item.status) {
                    ExamStatus.WAITING -> Note("Waiting to be read…")
                    ExamStatus.READING -> Note("Reading the paper…")
                    ExamStatus.SORTING -> Note("Sorting…")
                    ExamStatus.SORTED -> Note("Sorted ✓  ${item.sortedTo?.substringAfterLast('/') ?: ""}", Good)
                    ExamStatus.PROBLEM -> Note(item.problem ?: "Something went wrong.", Warn)
                    ExamStatus.READY -> {}
                }
                if (solved && item.status != ExamStatus.PROBLEM) {
                    Spacer(Modifier.height(2.dp))
                    FilledTonalButton(onClick = onCheck) { Text("Check this paper") }
                }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    if (item.status == ExamStatus.READY || item.status == ExamStatus.PROBLEM) {
                        DropdownMenuItem(text = { Text("Fix details") }, onClick = { menu = false; onFix() })
                    }
                    if (item.metadata?.paperType == "QP") DropdownMenuItem(text = { Text("Check this paper") }, onClick = { menu = false; onCheck() })
                    DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                    if (item.status != ExamStatus.SORTING) DropdownMenuItem(text = { Text("Remove from the list") }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

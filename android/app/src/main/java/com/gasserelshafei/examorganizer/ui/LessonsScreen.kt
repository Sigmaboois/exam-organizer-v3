@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gasserelshafei.examorganizer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gasserelshafei.examorganizer.AppState
import com.gasserelshafei.examorganizer.LessonAlarms
import com.gasserelshafei.examorganizer.LessonBook
import com.gasserelshafei.examorganizer.LessonSetup
import com.gasserelshafei.examorganizer.Lessons
import com.gasserelshafei.examorganizer.SentRecord
import com.gasserelshafei.examorganizer.WhatsAppLink
import com.gasserelshafei.examorganizer.WhatsAppLink.Phase
import com.gasserelshafei.examorganizer.friendlyMessage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val WhatsAppGreen = Color(0xFF25D366)

/**
 * Online lessons: link WhatsApp once, then give every class its Zoom link, WhatsApp group and
 * lesson times. The link is sent to the group before every lesson by itself (see LessonSender).
 */
@Composable
fun LessonsScreen(state: AppState, wide: Boolean) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<String?>(null) }
    val open = selected?.takeIf { state.scoreBook.schoolClass(it) != null }

    // Go online while this screen shows (to list the groups), and offline again after
    DisposableEffect(Unit) {
        WhatsAppLink.screenOpen = true
        Thread { runCatching { if (WhatsAppLink.isLinked(context)) WhatsAppLink.connect(context) } }.start()
        onDispose {
            WhatsAppLink.screenOpen = false
            Thread { runCatching { WhatsAppLink.disconnect() } }.start()
        }
    }
    LaunchedEffect(WhatsAppLink.phase) {
        if (WhatsAppLink.phase == Phase.CONNECTED && WhatsAppLink.groups.isEmpty()) {
            withContext(Dispatchers.IO) { runCatching { WhatsAppLink.loadGroups() } }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(340.dp).fillMaxHeight()) { LessonClassList(state, open) { selected = it } }
            VerticalDivider()
            Box(Modifier.weight(1f)) {
                if (open != null) LessonForm(state, open, onBack = null)
                else EmptyState(Icons.Outlined.VideoCall, "Choose a class",
                    "Paste the class's Zoom link once, pick its WhatsApp group and add its lesson times.")
            }
        }
    } else if (open != null) {
        BackHandler { selected = null }
        LessonForm(state, open, onBack = { selected = null })
    } else {
        LessonClassList(state, null) { selected = it }
    }
}

@Composable
private fun LessonClassList(state: AppState, selected: String?, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val book = remember { LessonBook.get(context) }
    book.revision   // redraw when lessons change
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { WhatsAppCard(state) }
        item { Text("Classes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp)) }
        if (state.scoreBook.classes.isEmpty()) item { Note("No classes yet. Add them on the Classes tab first.") }
        items(state.scoreBook.classes.sortedBy { it.name.lowercase() }, key = { it.id }) { schoolClass ->
            val setup = book.setup(schoolClass.id)
            val missing = Lessons.problems(setup)
            val (status, colour) = when {
                setup.link.isEmpty() && setup.groupId == null && setup.times.isEmpty() ->
                    "Not set up" to MaterialTheme.colorScheme.onSurfaceVariant
                missing.isNotEmpty() -> "Still needs ${missing.joinToString(" and ")}" to MaterialTheme.colorScheme.error
                setup.paused -> "Paused" to MaterialTheme.colorScheme.onSurfaceVariant
                else -> (Lessons.nextMessage(setup, sent = book.sentKeys(schoolClass.id))?.let {
                    if (!it.first.isAfter(LocalDateTime.now())) "Sending the ${it.second.toLocalTime()} lesson's link now"
                    else "Next: ${it.first.format(DateTimeFormatter.ofPattern("EEE HH:mm"))} to ${setup.groupName ?: "the group"}"
                } ?: "No lessons coming up") to MaterialTheme.colorScheme.primary
            }
            OutlinedCard(Modifier.fillMaxWidth().clickable { onOpen(schoolClass.id) },
                colors = if (schoolClass.id == selected) androidx.compose.material3.CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer) else androidx.compose.material3.CardDefaults.outlinedCardColors()) {
                ListItem(leadingContent = { Icon(Icons.Outlined.VideoCall, null) },
                    headlineContent = { Text(schoolClass.name, fontWeight = FontWeight.Medium) },
                    supportingContent = { Text(status, color = colour) })
            }
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun WhatsAppCard(state: AppState) {
    val context = LocalContext.current
    var linking by remember { mutableStateOf(false) }
    var unlinking by remember { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val power = context.getSystemService(PowerManager::class.java)
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    val linked = WhatsAppLink.account != null || WhatsAppLink.phase == Phase.CONNECTED

    SectionCard("WhatsApp", number = 1) {
        val (title, detail, colour) = when (WhatsAppLink.phase) {
            Phase.CONNECTED -> Triple("WhatsApp is linked", "Sending as ${WhatsAppLink.account ?: "your number"}", WhatsAppGreen)
            Phase.STARTING, Phase.OFFLINE -> Triple("Connecting to WhatsApp…", "This takes a few seconds.", Warn)
            Phase.WAITING -> Triple("Waiting for you to link it", "Finish linking in WhatsApp.", Warn)
            Phase.ERROR -> Triple("WhatsApp isn't connected", WhatsAppLink.problem ?: "", MaterialTheme.colorScheme.error)
            else -> if (linked) Triple("WhatsApp is linked", "Sending as ${WhatsAppLink.account}", WhatsAppGreen)
            else Triple("WhatsApp isn't linked yet", "Link it once, like WhatsApp Web.", MaterialTheme.colorScheme.outline)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(12.dp).background(colour, RoundedCornerShape(50)))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (detail.isNotEmpty()) Note(detail)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!linked || WhatsAppLink.phase == Phase.ERROR) FilledTonalButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                linking = true
            }) { Icon(Icons.Outlined.QrCode2, null); Text("  Link WhatsApp") }
            // Connecting failed (offline, say): whatsmeow doesn't try again by itself
            if (linked && WhatsAppLink.phase == Phase.ERROR) OutlinedButton(onClick = {
                Thread { runCatching { WhatsAppLink.connect(context) } }.start()
            }) { Icon(Icons.Outlined.Refresh, null); Text("  Try again") }
            if (linked) TextButton(onClick = { unlinking = true }) { Text("Unlink") }
        }
        Note("Links are sent by themselves, even with the app closed and the phone locked. The phone just needs the internet.")
        Note("Set up each class on one device only (this phone or the PC app), or its group gets the link twice.")
        if (!unrestricted) TextButton(onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }
            unrestricted = true
        }) { Text("Let links go out on time when the battery saver is on") }
    }

    if (linking) LinkDialog(state) { linking = false }
    if (unlinking) ConfirmDialog("Unlink WhatsApp?", "Lesson links stop until you link it again. Your classes, links and lesson times are kept.",
        "Unlink", onDismiss = { unlinking = false }) {
        unlinking = false
        Thread {
            val unlinked = WhatsAppLink.unlink()
            LessonAlarms.schedule(context)
            if (unlinked) state.show("WhatsApp is unlinked from this phone.")
            else state.show("Couldn't unlink WhatsApp. Try again when the phone is online.", error = true)
        }.start()
    }
}

/** Linking: a code to type in WhatsApp on this phone, or a QR code to scan with another phone. */
@Composable
private fun LinkDialog(state: AppState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var otherPhone by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(WhatsAppLink.phase) {
        if (WhatsAppLink.phase == Phase.CONNECTED) {
            state.show("WhatsApp is linked. Lesson links will be sent from this phone.")
            LessonAlarms.schedule(context)
            onDismiss()
        }
    }
    LaunchedEffect(otherPhone) {
        if (otherPhone) withContext(Dispatchers.IO) { runCatching { WhatsAppLink.connect(context) }.onFailure { error = friendlyMessage(it) } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link WhatsApp") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(!otherPhone, { otherPhone = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("This phone") }
                    SegmentedButton(otherPhone, { otherPhone = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Another phone") }
                }
                if (!otherPhone) {
                    if (code == null) {
                        Text("Type your WhatsApp number, starting with the country code.")
                        OutlinedTextField(number, { number = it; error = null }, label = { Text("Your number") },
                            placeholder = { Text("+20 100 123 4567") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    } else {
                        Text("In WhatsApp, open Settings (⋮ on Android) › Linked devices › Link a device, then tap " +
                            "\"Link with phone number instead\" and type this code:")
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(code!!, fontSize = 30.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        OutlinedButton(onClick = {
                            val launch = listOf("com.whatsapp", "com.whatsapp.w4b").firstNotNullOfOrNull {
                                context.packageManager.getLaunchIntentForPackage(it)
                            }
                            if (launch != null) context.startActivity(launch) else state.show("WhatsApp isn't installed on this phone.", true)
                        }) { Text("Open WhatsApp") }
                        Note("This screen closes by itself once it's linked.")
                    }
                } else {
                    Text("On the phone with your WhatsApp: Settings (⋮ on Android) › Linked devices › Link a device, then scan this code.")
                    Box(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp),
                        contentAlignment = Alignment.Center) {
                        val text = WhatsAppLink.qr
                        when {
                            text != null -> Image(remember(text) { qrBitmap(text) }, "WhatsApp link code", Modifier.size(240.dp))
                            WhatsAppLink.phase == Phase.EXPIRED -> TextButton(onClick = {
                                scope.launch(Dispatchers.IO) { runCatching { WhatsAppLink.connect(context) } }
                            }) { Text("The code expired. Show a new one") }
                            else -> CircularProgressIndicator(Modifier.size(36.dp))
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Note("Your phone stays the main WhatsApp. Exam Organizer only sends the lesson links you set up, and you " +
                    "can unlink it any time.")
            }
        },
        confirmButton = {
            if (!otherPhone && code == null) Button(enabled = number.count(Char::isDigit) >= 8 && !busy, onClick = {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { WhatsAppLink.pairingCode(context, number) } }
                    busy = false
                    result.onSuccess { code = it }.onFailure { error = friendlyMessage(it) }
                }
            }) { Text(if (busy) "Getting a code…" else "Get a code") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun qrBitmap(text: String, size: Int = 512): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private const val ONE_DATE = 0

@Composable
private fun LessonForm(state: AppState, classId: String, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val book = remember { LessonBook.get(context) }
    book.revision
    val schoolClass = state.scoreBook.schoolClass(classId) ?: return
    val setup = book.setup(classId)
    val connected = WhatsAppLink.phase == Phase.CONNECTED

    fun change(update: (LessonSetup) -> LessonSetup) {
        book.update(classId, update)
        LessonAlarms.schedule(context)
    }

    var link by remember(classId) { mutableStateOf(setup.link) }
    var linkError by remember(classId) { mutableStateOf<String?>(null) }
    var passcode by remember(classId) { mutableStateOf(setup.passcode) }
    var message by remember(classId) { mutableStateOf(setup.message) }
    var day by remember(classId) { mutableStateOf(LocalDate.now().dayOfWeek.value) }
    var oneDate by remember(classId) { mutableStateOf<LocalDate?>(null) }
    var timeError by remember(classId) { mutableStateOf<String?>(null) }
    var confirmSend by remember { mutableStateOf(false) }

    fun saveLink() {
        if (link.trim() == setup.link) return
        runCatching { Lessons.checkLink(link) }
            .onSuccess { clean -> link = clean; linkError = null; change { it.copy(link = clean) } }
            .onFailure { linkError = friendlyMessage(it) }
    }
    fun savePasscode() { if (passcode.trim() != setup.passcode) change { it.copy(passcode = passcode.trim()) } }
    fun saveMessage() { if (message.isNotBlank() && message != setup.message) change { it.copy(message = message.trim()) } }

    fun addTime() {
        val now = java.time.LocalTime.now()
        TimePickerDialog(context, { _, hour, minute ->
            runCatching { book.addTime(classId, "%02d:%02d".format(hour, minute), day.takeIf { it != ONE_DATE }, oneDate) }
                .onSuccess { timeError = null; LessonAlarms.schedule(context) }
                .onFailure { timeError = friendlyMessage(it) }
        }, now.hour, 0, true).show()
    }

    val upcoming = Lessons.nextMessage(setup, sent = book.sentKeys(classId))
    val preview = Lessons.render(setup.copy(message = message.ifBlank { Lessons.DEFAULT_MESSAGE },
        link = setup.link.ifEmpty { "https://zoom.us/j/…" }, passcode = passcode.trim()), schoolClass.name, upcoming?.second)

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back") }
                Text(schoolClass.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { confirmSend = true }, enabled = connected && setup.link.isNotEmpty() && setup.groupId != null) {
                    Icon(Icons.Outlined.Send, null, Modifier.size(18.dp)); Text("  Send now")
                }
            }
            val missing = Lessons.problems(setup)
            Text(when {
                missing.isNotEmpty() -> "Not sending yet: add ${missing.joinToString(" and ")}."
                setup.paused -> "Paused. Turn on the switch to send links again."
                upcoming != null && !upcoming.first.isAfter(LocalDateTime.now()) ->
                    "The link for the ${upcoming.second.toLocalTime()} lesson is being sent now. If it fails, it's tried again every 2 minutes until the lesson starts."
                upcoming != null -> "Next message: ${upcoming.first.format(DateTimeFormatter.ofPattern("EEEE d MMM 'at' HH:mm"))} to ${setup.groupName}"
                else -> "No lessons coming up."
            }, color = if (missing.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold)
        }
        item {
            SectionCard("Zoom link", number = 2) {
                OutlinedTextField(link, { link = it; linkError = null }, label = { Text("Zoom link") },
                    placeholder = { Text("https://us02web.zoom.us/j/…") }, singleLine = true, isError = linkError != null,
                    supportingText = linkError?.let { { Text(it) } }, leadingIcon = { Icon(Icons.Outlined.Link, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { saveLink() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) saveLink() })
                OutlinedTextField(passcode, { passcode = it }, label = { Text("Passcode (optional)") }, singleLine = true,
                    keyboardActions = KeyboardActions(onDone = { savePasscode() }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) savePasscode() })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChoiceButton(setup.groupName ?: "Choose the WhatsApp group", WhatsAppLink.groups, onPick = { id ->
                        val name = WhatsAppLink.groups.firstOrNull { it.first == id }?.second
                        change { it.copy(groupId = id, groupName = name) }
                    }, enabled = connected, leading = Icons.Outlined.Groups, modifier = Modifier.weight(1f))
                    IconButton(enabled = connected, onClick = {
                        scope.launch(Dispatchers.IO) {
                            runCatching { WhatsAppLink.loadGroups() }.onFailure { state.show(friendlyMessage(it), true) }
                        }
                    }) { Icon(Icons.Outlined.Refresh, "Reload my WhatsApp groups") }
                }
                if (!connected) Note("Link WhatsApp to choose a group.")
            }
        }
        item {
            SectionCard("Lesson times", number = 3) {
                if (setup.times.isEmpty()) Note("No lesson times yet.")
                setup.times.forEach { time ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (time.date == null) Icons.Outlined.EventRepeat else Icons.Outlined.Event, null,
                            tint = MaterialTheme.colorScheme.primary)
                        Text(time.describe, Modifier.weight(1f).padding(start = 10.dp))
                        IconButton(onClick = { change { it.copy(times = it.times - time) } }) {
                            Icon(Icons.Outlined.Delete, "Remove this lesson time")
                        }
                    }
                }
                val dayLabel = if (day == ONE_DATE) oneDate?.let { "Once, " + it.format(DateTimeFormatter.ofPattern("EEE d MMM")) } ?: "One date…"
                else "Every " + DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.UK)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceButton(dayLabel, (1..7).map { it to "Every " + DayOfWeek.of(it).getDisplayName(TextStyle.FULL, Locale.UK) } +
                        listOf(ONE_DATE to "One date…"), onPick = { picked ->
                        day = picked
                        if (picked == ONE_DATE) {
                            val today = LocalDate.now()
                            DatePickerDialog(context, { _, year, month, dayOfMonth -> oneDate = LocalDate.of(year, month + 1, dayOfMonth) },
                                today.year, today.monthValue - 1, today.dayOfMonth).apply {
                                datePicker.minDate = System.currentTimeMillis() - 1000
                            }.show()
                        } else oneDate = null
                    })
                    FilledTonalButton(onClick = ::addTime, enabled = day != ONE_DATE || oneDate != null) { Text("Pick the time and add") }
                }
                timeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            SectionCard("Message", number = 4) {
                ChoiceButton(Lessons.SEND_OPTIONS.firstOrNull { it.first == setup.minutesBefore }?.second ?: "${setup.minutesBefore} minutes before",
                    Lessons.SEND_OPTIONS, onPick = { minutes -> change { it.copy(minutesBefore = minutes) } })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Send automatically before every lesson", Modifier.weight(1f))
                    Switch(!setup.paused, { on -> change { it.copy(paused = !on) } })
                }
                OutlinedTextField(message, { message = it }, label = { Text("Message") }, minLines = 3,
                    supportingText = { Text("{class}, {day}, {time}, {link} and {passcode} are filled in for you") },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) saveMessage() })
                TextButton(onClick = { message = Lessons.DEFAULT_MESSAGE; change { it.copy(message = Lessons.DEFAULT_MESSAGE) } }) {
                    Text("Use the standard message")
                }
                Text("What the group will see", style = MaterialTheme.typography.labelLarge)
                Surface(shape = RoundedCornerShape(12.dp), color = WhatsAppGreen.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
                    Text(preview, Modifier.padding(12.dp))
                }
            }
        }
        item {
            SectionCard("Recently sent", number = null) {
                val history = book.history(classId)
                if (history.isEmpty()) Note("Nothing sent yet.")
                history.forEach { HistoryRow(it) }
            }
        }
    }

    if (confirmSend) {
        val text = Lessons.render(setup, schoolClass.name, Lessons.nextMessage(setup)?.second)
        AlertDialog(
            onDismissRequest = { confirmSend = false },
            title = { Text("Send to ${setup.groupName} now?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Everyone in the group will get this message:")
                    Surface(shape = RoundedCornerShape(12.dp), color = WhatsAppGreen.copy(alpha = 0.14f)) { Text(text, Modifier.padding(12.dp)) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    confirmSend = false
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { WhatsAppLink.send(setup.groupId!!, text) } }
                        book.record(classId, null, null, result.isSuccess, result.exceptionOrNull()?.let(::friendlyMessage) ?: "")
                        result.onSuccess { state.show("Sent to ${setup.groupName}.") }.onFailure { state.show(friendlyMessage(it), true) }
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryRow(record: SentRecord) {
    val sentAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(record.sentAt), ZoneId.systemDefault())
    val what = if (record.lessonId == null) "Sent now by you"
    else "Lesson at " + (record.lessonAt?.substringAfter('T') ?: "")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (record.ok) Icons.Filled.CheckCircle else Icons.Filled.Error, null, Modifier.size(16.dp),
            tint = if (record.ok) Good else MaterialTheme.colorScheme.error)
        Text(sentAt.format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")) + " · " + what +
            if (record.ok) "" else " · not sent: ${record.detail}", style = MaterialTheme.typography.bodySmall)
    }
}

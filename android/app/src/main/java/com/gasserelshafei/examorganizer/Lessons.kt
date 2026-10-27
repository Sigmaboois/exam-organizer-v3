package com.gasserelshafei.examorganizer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/** One lesson: every week on [weekday] (1 = Monday … 7 = Sunday), or once on [date] (yyyy-MM-dd). */
data class LessonTime(val id: String = UUID.randomUUID().toString(), val weekday: Int? = null, val date: String? = null,
                      val start: String) {
    val describe: String get() = if (date != null)
        "Once, ${LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))} at $start"
    else "Every ${DayOfWeek.of(weekday!!).getDisplayName(TextStyle.FULL, Locale.UK)} at $start"
}

/** A class's online lessons: the meeting link (pasted once), its WhatsApp group and lesson times. */
data class LessonSetup(
    val classId: String,
    val link: String = "",
    val passcode: String = "",
    val groupId: String? = null,
    val groupName: String? = null,
    val message: String = Lessons.DEFAULT_MESSAGE,
    val minutesBefore: Int = 10,
    val paused: Boolean = false,
    val times: List<LessonTime> = emptyList(),
)

/** How sending went. One per lesson (a retry replaces it); "Send now" ones have no lesson. */
data class SentRecord(val classId: String, val lessonId: String?, val lessonAt: String?, val sentAt: Long,
                      val ok: Boolean, val detail: String = "")

data class DueMessage(val classId: String, val className: String, val setup: LessonSetup, val lesson: LessonTime,
                      val lessonAt: LocalDateTime)

/** The rules for when and what to send. The same as the desktop app's meetings.py. */
object Lessons {
    const val DEFAULT_MESSAGE = "Hi {class}! Our lesson starts at {time}.\nJoin on Zoom: {link}\nPasscode: {passcode}"
    val SEND_OPTIONS = listOf(0 to "When the lesson starts", 5 to "5 minutes before", 10 to "10 minutes before",
        15 to "15 minutes before", 30 to "30 minutes before", 60 to "1 hour before", 120 to "2 hours before")
    /** A link is still worth sending this long after the lesson started (the phone was off, say). */
    val LATE_LIMIT: Duration = Duration.ofMinutes(10)
    /** After a failed send, wait this long before trying again. */
    val RETRY_AFTER: Duration = Duration.ofMinutes(2)

    fun key(lessonId: String, lessonAt: LocalDateTime) = "$lessonId@${minutes(lessonAt)}"
    fun minutes(time: LocalDateTime): String = time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))

    /** "9", "9:30", "09.30", "9:30 am", "2pm", "14:00" -> "09:30". */
    fun parseTime(text: String): String {
        val cleaned = text.trim().lowercase().replace(" ", "").replace(".", ":")
        val match = Regex("""(\d{1,2})(?::(\d{2}))?(am|pm)?""").matchEntire(cleaned)
            ?: throw UserError("Type a time like 9:30 or 14:00.")
        var hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
        val half = match.groupValues[3]
        if (half.isNotEmpty()) {
            if (hour !in 1..12) throw UserError("Type a time like 9:30 am or 2:15 pm.")
            hour = hour % 12 + if (half == "pm") 12 else 0
        }
        if (hour > 23 || minute > 59) throw UserError("That isn't a real time. Type one like 9:30 or 14:00.")
        return "%02d:%02d".format(hour, minute)
    }

    /** The meeting link, tidied up. Empty is allowed (not set yet). */
    fun checkLink(text: String): String {
        var link = text.trim()
        if (link.isEmpty()) return ""
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(link)) {
            if (Regex("""^[\w.-]+\.(zoom\.us|zoom\.com)/""", RegexOption.IGNORE_CASE).containsMatchIn(link)) link = "https://$link"
            else throw UserError("Paste the whole meeting link, starting with https://")
        }
        if (link.any { it.isWhitespace() }) throw UserError("The link has a space in it. Copy it again from Zoom.")
        return link
    }

    /** Fill in the teacher's message. Lines about a passcode are left out when there isn't one. */
    fun render(setup: LessonSetup, className: String, lessonAt: LocalDateTime? = null): String {
        val at = lessonAt ?: LocalDateTime.now()
        val passcode = setup.passcode.trim()
        val values = mapOf(
            "class" to className, "link" to setup.link, "passcode" to passcode,
            "time" to at.format(DateTimeFormatter.ofPattern("HH:mm")),
            "day" to at.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK),
            "date" to at.format(DateTimeFormatter.ofPattern("d MMMM", Locale.UK)),
        )
        val lines = setup.message.ifBlank { DEFAULT_MESSAGE }.lines()
            .filterNot { "{passcode}" in it && passcode.isEmpty() }
            .map { line -> values.entries.fold(line) { text, (key, value) -> text.replace("{$key}", value) } }
        var text = lines.joinToString("\n").trim()
        if (setup.link.isNotEmpty() && setup.link !in text) text += "\n" + setup.link   // never leave the link out
        return text
    }

    /** What's still missing before links can go out (empty = ready). */
    fun problems(setup: LessonSetup): List<String> = buildList {
        if (setup.link.isEmpty()) add("the meeting link")
        if (setup.groupId == null) add("a WhatsApp group")
        if (setup.times.isEmpty()) add("a lesson time")
    }

    /** Start times of this lesson on the day before, the day of and the day after [around]. */
    fun lessonStarts(time: LessonTime, around: LocalDateTime): List<LocalDateTime> {
        val start = LocalTime.parse(time.start)
        if (time.date != null) return listOf(LocalDate.parse(time.date).atTime(start))
        return (-1L..1L).map { around.toLocalDate().plusDays(it) }.filter { it.dayOfWeek.value == time.weekday }
            .map { it.atTime(start) }
    }

    /** (send at, lesson at) of the next message not sent yet, or null. */
    fun nextMessage(setup: LessonSetup, now: LocalDateTime = LocalDateTime.now(), sent: Set<String> = emptySet()):
            Pair<LocalDateTime, LocalDateTime>? {
        var best: Pair<LocalDateTime, LocalDateTime>? = null
        for (time in setup.times) {
            // From yesterday: a lesson just before midnight can still be inside its late window
            val starts = if (time.date != null) lessonStarts(time, now)
            else (-1L..7L).map { now.toLocalDate().plusDays(it) }.filter { it.dayOfWeek.value == time.weekday }
                .map { it.atTime(LocalTime.parse(time.start)) }
            for (lessonAt in starts) {
                if (!lessonAt.plus(LATE_LIMIT).isAfter(now) || key(time.id, lessonAt) in sent) continue
                val sendAt = lessonAt.minusMinutes(setup.minutesBefore.toLong())
                if (best == null || sendAt.isBefore(best.first)) best = sendAt to lessonAt
            }
        }
        return best
    }
}

/**
 * Every class's lesson setup and what was sent, in lessons.json. Used by the screen and by the
 * background sender, so every method is synchronised. [revision] changes on every save, so
 * screens redraw.
 */
class LessonBook internal constructor(private val file: File, private val scoreBookFile: File) {
    companion object {
        @Volatile private var instance: LessonBook? = null
        fun get(context: Context): LessonBook = instance ?: synchronized(this) {
            instance ?: LessonBook(File(context.applicationContext.filesDir, "lessons.json"),
                File(context.applicationContext.filesDir, "scorebook.json")).also { instance = it }
        }
    }

    var revision by mutableIntStateOf(0)
        private set
    private val setups = mutableMapOf<String, LessonSetup>()
    private val sent = mutableListOf<SentRecord>()

    init {
        runCatching {
            if (file.exists()) {
                val json = JSONObject(file.readText())
                json.optJSONArray("classes").objects().forEach {
                    val times = it.optJSONArray("times").objects().map { t ->
                        LessonTime(t.getString("id"), t.optInt("weekday").takeIf { d -> d in 1..7 },
                            t.optStringOrNull("date"), t.getString("start"))
                    }
                    setups[it.getString("classId")] = LessonSetup(it.getString("classId"), it.optString("link"),
                        it.optString("passcode"), it.optStringOrNull("groupId"), it.optStringOrNull("groupName"),
                        it.optStringOrNull("message") ?: Lessons.DEFAULT_MESSAGE, it.optInt("minutesBefore", 10),
                        it.optBoolean("paused"), times)
                }
                json.optJSONArray("sent").objects().forEach {
                    sent += SentRecord(it.getString("classId"), it.optStringOrNull("lessonId"), it.optStringOrNull("lessonAt"),
                        it.getLong("sentAt"), it.getBoolean("ok"), it.optString("detail"))
                }
            }
        }
    }

    private fun save() {
        val json = JSONObject()
            .put("classes", JSONArray(setups.values.map { s ->
                JSONObject().put("classId", s.classId).put("link", s.link).put("passcode", s.passcode)
                    .put("groupId", s.groupId).put("groupName", s.groupName).put("message", s.message)
                    .put("minutesBefore", s.minutesBefore).put("paused", s.paused)
                    .put("times", JSONArray(s.times.map { t ->
                        JSONObject().put("id", t.id).put("weekday", t.weekday ?: 0).put("date", t.date).put("start", t.start)
                    }))
            }))
            .put("sent", JSONArray(sent.takeLast(300).map {
                JSONObject().put("classId", it.classId).put("lessonId", it.lessonId).put("lessonAt", it.lessonAt)
                    .put("sentAt", it.sentAt).put("ok", it.ok).put("detail", it.detail)
            }))
        val temp = File(file.path + ".tmp")
        temp.writeText(json.toString())
        temp.renameTo(file)
        revision++
    }

    @Synchronized fun setup(classId: String): LessonSetup = setups[classId] ?: LessonSetup(classId)

    @Synchronized fun update(classId: String, change: (LessonSetup) -> LessonSetup) {
        setups[classId] = change(setup(classId))
        save()
    }

    @Synchronized fun addTime(classId: String, rawStart: String, weekday: Int?, date: LocalDate?) {
        val start = Lessons.parseTime(rawStart)
        if (date != null && date.atTime(LocalTime.parse(start)).isBefore(LocalDateTime.now()))
            throw UserError("That date and time have already passed.")
        val time = LessonTime(weekday = if (date == null) weekday else null, date = date?.toString(), start = start)
        val current = setup(classId)
        if (current.times.any { it.weekday == time.weekday && it.date == time.date && it.start == start })
            throw UserError("That lesson time is already in the list.")
        val sorted = (current.times + time).sortedWith(compareBy({ it.date != null }, { it.date ?: "" }, { it.weekday ?: 0 }, { it.start }))
        update(classId) { it.copy(times = sorted) }
    }

    @Synchronized fun record(classId: String, lessonId: String?, lessonAt: LocalDateTime?, ok: Boolean, detail: String = "") {
        val at = lessonAt?.let { Lessons.minutes(it) }
        if (lessonId != null) sent.removeAll { it.lessonId == lessonId && it.lessonAt == at }
        sent += SentRecord(classId, lessonId, at, System.currentTimeMillis(), ok, detail)
        save()
    }

    @Synchronized fun history(classId: String, limit: Int = 6) =
        sent.filter { it.classId == classId }.sortedByDescending { it.sentAt }.take(limit)

    @Synchronized fun sentKeys(classId: String): Set<String> =
        sent.filter { it.classId == classId && it.ok && it.lessonId != null }.map { "${it.lessonId}@${it.lessonAt}" }.toSet()

    /** Class names by id, read from the score book file (the sender runs without the app's screens). */
    fun classNames(): Map<String, String> = runCatching {
        JSONObject(scoreBookFile.readText()).optJSONArray("classes").objects().associate { it.getString("id") to it.getString("name") }
    }.getOrDefault(emptyMap())

    /** Links that should go out now. */
    @Synchronized fun due(now: LocalDateTime = LocalDateTime.now()): List<DueMessage> {
        val names = classNames()
        val due = mutableListOf<DueMessage>()
        val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        for ((classId, setup) in setups) {
            val name = names[classId] ?: continue   // the class was deleted
            if (setup.paused || Lessons.problems(setup).isNotEmpty()) continue
            for (time in setup.times) for (lessonAt in Lessons.lessonStarts(time, now)) {
                val sendAt = lessonAt.minusMinutes(setup.minutesBefore.toLong())
                if (now.isBefore(sendAt) || !now.isBefore(lessonAt.plus(Lessons.LATE_LIMIT))) continue
                val attempt = sent.lastOrNull { it.lessonId == time.id && it.lessonAt == Lessons.minutes(lessonAt) }
                if (attempt != null && (attempt.ok || nowMillis - attempt.sentAt < Lessons.RETRY_AFTER.toMillis())) continue
                due += DueMessage(classId, name, setup, time, lessonAt)
            }
        }
        return due
    }

    /** When the background sender should next wake up: the next link due, or a retry. Null = nothing planned. */
    @Synchronized fun nextWake(now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val names = classNames()
        var wake: LocalDateTime? = null
        fun consider(time: LocalDateTime) { if (wake?.let { time.isBefore(it) } != false) wake = time }
        for ((classId, setup) in setups) {
            if (classId !in names || setup.paused || Lessons.problems(setup).isNotEmpty()) continue
            val next = Lessons.nextMessage(setup, now, sentKeys(classId)) ?: continue
            if (next.first.isAfter(now)) { consider(next.first); continue }
            // Due already: straight away, or after the wait following a failed try
            val attempt = sent.lastOrNull { it.lessonAt == Lessons.minutes(next.second) && it.classId == classId && !it.ok }
            val retry = attempt?.let {
                LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.sentAt), ZoneId.systemDefault()).plus(Lessons.RETRY_AFTER)
            }
            consider(if (retry != null && retry.isAfter(now)) retry else now.plusSeconds(5))
        }
        return wake
    }
}

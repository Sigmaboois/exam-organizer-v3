package com.gasserelshafei.examorganizer

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.UUID

data class SchoolClass(val id: String = UUID.randomUUID().toString(), val name: String, val qualification: String? = null)

data class Student(val id: String = UUID.randomUUID().toString(), val classId: String, val name: String)

data class CheckRecord(
    val id: String = UUID.randomUUID().toString(),
    val studentId: String,
    val checkedAt: Long,
    val metadata: PaperMetadata,
    val score: Int,
    val maxScore: Int,
    val grade: String?,
    val marking: Marking,
    val markedPdf: String?,    // paths relative to the chosen folder
    val report: String?,
) {
    val percent: Double? get() = if (maxScore > 0) 100.0 * score / maxScore else null
}

data class TopicLoss(val topic: String, val lost: Int, val max: Int, val questions: Int)

/**
 * The teacher's score book: classes, students and checked papers, saved as one JSON file.
 * Every class gets a folder with a sub-folder per student (Classes/<class>/<student>), and
 * renaming a class or student renames the folder too.
 * The lists are Compose state, so screens update by themselves. Use from the main thread.
 */
class ScoreBook(private val file: File, private val store: Store) {
    val classes = mutableStateListOf<SchoolClass>()
    val students = mutableStateListOf<Student>()
    val results = mutableStateListOf<CheckRecord>()

    init {
        runCatching {
            if (file.exists()) {
                val json = JSONObject(file.readText())
                classes += json.optJSONArray("classes").objects().map {
                    SchoolClass(it.getString("id"), it.getString("name"), it.optStringOrNull("qualification"))
                }
                students += json.optJSONArray("students").objects().map {
                    Student(it.getString("id"), it.getString("classId"), it.getString("name"))
                }
                results += json.optJSONArray("results").objects().map {
                    CheckRecord(it.getString("id"), it.getString("studentId"), it.getLong("checkedAt"),
                        PaperMetadata.fromJson(it.optJSONObject("metadata")), it.getInt("score"), it.getInt("maxScore"),
                        it.optStringOrNull("grade"), Marking.fromJson(it.getJSONObject("marking")),
                        it.optStringOrNull("markedPdf"), it.optStringOrNull("report"))
                }
            }
        }
    }

    private fun save() {
        val json = JSONObject()
            .put("classes", JSONArray(classes.map { JSONObject().put("id", it.id).put("name", it.name).put("qualification", it.qualification) }))
            .put("students", JSONArray(students.map { JSONObject().put("id", it.id).put("classId", it.classId).put("name", it.name) }))
            .put("results", JSONArray(results.map {
                JSONObject().put("id", it.id).put("studentId", it.studentId).put("checkedAt", it.checkedAt)
                    .put("metadata", it.metadata.toJson()).put("score", it.score).put("maxScore", it.maxScore)
                    .put("grade", it.grade).put("marking", it.marking.toJson()).put("markedPdf", it.markedPdf)
                    .put("report", it.report)
            }))
        val temp = File(file.path + ".tmp")
        temp.writeText(json.toString())
        temp.renameTo(file)
    }

    private fun same(a: String, b: String) = a.equals(b, ignoreCase = true)

    /** Make folders for every class and student (in the background: the folder may be on Google Drive). */
    fun ensureFolders() {
        val snapshot = classes.toList() to students.toList()
        folderWork.execute {
            for (schoolClass in snapshot.first) {
                runCatching { store.folder(ClassFolders.path(schoolClass.name)) }
                snapshot.second.filter { it.classId == schoolClass.id }.forEach {
                    runCatching { store.folder(ClassFolders.path(schoolClass.name, it.name)) }
                }
            }
        }
    }

    private fun makeFolder(path: List<String>) = folderWork.execute { runCatching { store.folder(path) } }

    // ---------- classes ----------

    fun addClass(rawName: String): SchoolClass {
        val name = rawName.trim()
        if (name.isEmpty()) throw UserError("Please give the class a name.")
        if (classes.any { same(it.name, name) }) throw UserError("There is already a class called \"$name\".")
        val added = SchoolClass(name = name)
        classes += added
        save()
        makeFolder(ClassFolders.path(name))
        return added
    }

    fun renameClass(id: String, rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) throw UserError("Please give the class a name.")
        if (classes.any { it.id != id && same(it.name, name) }) throw UserError("There is already a class called \"$name\".")
        val index = classes.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val old = classes[index].name
        classes[index] = classes[index].copy(name = name)
        save()
        folderWork.execute {
            val moves = runCatching { store.rename(ClassFolders.path(old), name) }.getOrDefault(emptyMap())
            runCatching { store.folder(ClassFolders.path(name)) }
            relocate(moves)
        }
    }

    fun deleteClass(id: String) {
        val gone = students.filter { it.classId == id }.map { it.id }.toSet()
        results.removeAll { it.studentId in gone }
        students.removeAll { it.classId == id }
        classes.removeAll { it.id == id }
        save()
    }

    fun schoolClass(id: String?) = classes.firstOrNull { it.id == id }

    /** [(divider, classes)] in a sensible order. Unknown qualification goes under "Other". */
    fun classesByQualification(): List<Pair<String, List<SchoolClass>>> {
        val order = listOf("IGCSE", "O Level", "AS", "AS & A Level")
        val groups = classes.groupBy { it.qualification ?: "Other" }
        return (order + groups.keys.filter { it !in order }.sorted()).mapNotNull { key ->
            groups[key]?.let { key to it.sortedBy { c -> c.name.lowercase() } }
        }
    }

    // ---------- students ----------

    fun addStudent(rawName: String, classId: String): Student {
        val name = rawName.trim()
        if (name.isEmpty()) throw UserError("Please type the student's name.")
        if (students.any { it.classId == classId && same(it.name, name) }) throw UserError("$name is already in this class.")
        val added = Student(classId = classId, name = name)
        students += added
        save()
        schoolClass(classId)?.let { makeFolder(ClassFolders.path(it.name, name)) }
        return added
    }

    /** One name per line ("Smith, John" is one student, as on the desktop). Returns (added, skipped as already there). */
    fun addStudents(text: String, classId: String): Pair<Int, Int> {
        var added = 0
        var skipped = 0
        val className = schoolClass(classId)?.name
        for (name in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            if (students.any { it.classId == classId && same(it.name, name) }) skipped++
            else {
                students += Student(classId = classId, name = name)
                className?.let { makeFolder(ClassFolders.path(it, name)) }
                added++
            }
        }
        if (added > 0) save()
        return added to skipped
    }

    fun renameStudent(id: String, rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) throw UserError("Please type the student's name.")
        val index = students.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        val student = students[index]
        if (students.any { it.id != id && it.classId == student.classId && same(it.name, name) }) {
            throw UserError("$name is already in this class.")
        }
        students[index] = student.copy(name = name)
        val className = schoolClass(student.classId)?.name
        if (className != null) {
            folderWork.execute { relocate(runCatching { store.rename(ClassFolders.path(className, student.name), name) }.getOrDefault(emptyMap())) }
        }
        save()
    }

    fun deleteStudent(id: String) {
        results.removeAll { it.studentId == id }
        students.removeAll { it.id == id }
        save()
    }

    fun student(id: String?) = students.firstOrNull { it.id == id }

    fun members(classId: String) = students.filter { it.classId == classId }.sortedBy { it.name.lowercase() }

    /** Where a student's checked papers are kept: Classes/<class>/<student>. */
    fun folder(studentId: String?): List<String>? {
        val student = student(studentId) ?: return null
        val schoolClass = schoolClass(student.classId) ?: return null
        return ClassFolders.path(schoolClass.name, student.name)
    }

    /** Folders were renamed (in the background): point the saved results at where the files really went. */
    private fun relocate(moves: Map<String, String>) {
        if (moves.isEmpty()) return
        Handler(Looper.getMainLooper()).post {
            for (i in results.indices) {
                val r = results[i]
                fun moved(path: String?): String? {
                    if (path == null) return null
                    val (from, to) = moves.entries.firstOrNull { (old, _) ->
                        if (old.endsWith("/")) path.startsWith(old, ignoreCase = true) else path.equals(old, ignoreCase = true)
                    } ?: return path
                    return to + path.substring(from.length)
                }
                results[i] = r.copy(markedPdf = moved(r.markedPdf), report = moved(r.report))
            }
            save()
        }
    }

    // ---------- results ----------

    fun saveResult(studentId: String, metadata: PaperMetadata, marking: Marking, grade: String?, markedPdf: String?, report: String?): String {
        // A class takes the qualification of the papers its students sit (first one wins)
        student(studentId)?.let { s ->
            val index = classes.indexOfFirst { it.id == s.classId }
            if (index >= 0 && classes[index].qualification == null && metadata.qualification != null) {
                classes[index] = classes[index].copy(qualification = metadata.qualification)
            }
        }
        val record = CheckRecord(studentId = studentId, checkedAt = System.currentTimeMillis(), metadata = metadata,
            score = marking.total, maxScore = marking.maxTotal, grade = grade, marking = marking, markedPdf = markedPdf, report = report)
        results += record
        save()
        return record.id
    }

    fun updateResult(id: String, marking: Marking, grade: String?, markedPdf: String?, report: String?) {
        val index = results.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        results[index] = results[index].copy(marking = marking, score = marking.total, maxScore = marking.maxTotal,
            grade = grade, markedPdf = markedPdf ?: results[index].markedPdf, report = report ?: results[index].report)
        save()
    }

    fun deleteResult(id: String) {
        results.removeAll { it.id == id }
        save()
    }

    fun recordsForStudent(id: String) = results.filter { it.studentId == id }.sortedByDescending { it.checkedAt }

    fun recordsForClass(id: String): List<CheckRecord> {
        val members = students.filter { it.classId == id }.map { it.id }.toSet()
        return results.filter { it.studentId in members }.sortedByDescending { it.checkedAt }
    }

    // ---------- spreadsheet ----------

    /** A CSV file (opens in Excel, Sheets and Numbers) of these results. */
    fun exportCsv(records: List<CheckRecord>, target: File): File {
        fun field(value: String) = if (value.any { it in ",\"\n" }) "\"" + value.replace("\"", "\"\"") + "\"" else value
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.UK)
        val lines = mutableListOf("Student,Class,Qualification,Subject,Code,Paper,Series,Score,Out of,%,Grade,Checked on")
        for (r in records.sortedWith(compareBy<CheckRecord> { student(it.studentId)?.name?.lowercase() ?: "" }.thenByDescending { it.checkedAt })) {
            val s = student(r.studentId)
            val m = r.metadata
            lines += listOf(s?.name ?: "", schoolClass(s?.classId)?.name ?: "", m.qualification ?: "", m.subjectName ?: "",
                m.subjectCode ?: "", "${m.paper ?: ""}${m.variant ?: ""}", "${(m.session ?: "").replace("-", "/")} ${m.year ?: ""}".trim(),
                r.score.toString(), r.maxScore.toString(), r.percent?.let { String.format(java.util.Locale.UK, "%.1f", it) } ?: "",
                r.grade ?: "", format.format(Date(r.checkedAt))).joinToString(",") { field(it) }
        }
        target.parentFile?.mkdirs()
        target.writeText("﻿" + lines.joinToString("\r\n"))
        return target
    }

    companion object {
        /** Folder work runs one job at a time: two at once could both create "Classes/<class>" and leave a "<class> (1)". */
        private val folderWork = java.util.concurrent.Executors.newSingleThreadExecutor()

        fun average(records: List<CheckRecord>): Double? = records.mapNotNull { it.percent }.takeIf { it.isNotEmpty() }?.average()

        fun topicsRecorded(records: List<CheckRecord>) = records.any { r -> r.marking.questions.any { it.topic.isNotBlank() } }

        /** Topics where marks were lost, worst first. */
        fun topicLosses(markings: List<Marking>): List<TopicLoss> {
            val buckets = linkedMapOf<String, TopicLoss>()
            for (marking in markings) for (q in marking.questions) {
                val topic = q.topic.trim().takeIf { it.isNotEmpty() } ?: continue
                val b = buckets[topic] ?: TopicLoss(topic, 0, 0, 0)
                buckets[topic] = b.copy(lost = b.lost + (q.maxMarks - q.marksAwarded).coerceAtLeast(0),
                    max = b.max + q.maxMarks, questions = b.questions + 1)
            }
            return buckets.values.filter { it.lost > 0 }.sortedWith(compareBy<TopicLoss> { -it.lost }.thenBy { it.topic.lowercase() })
        }

        fun gradeCounts(records: List<CheckRecord>): List<Pair<String, Int>> {
            val counts = records.groupingBy { it.grade ?: "Ungraded" }.eachCount()
            val ordered = Catalog.gradeOrder.filter { it in counts }
            return (ordered + counts.keys.filter { it !in ordered }.sorted()).map { it to (counts[it] ?: 0) }
        }
    }
}

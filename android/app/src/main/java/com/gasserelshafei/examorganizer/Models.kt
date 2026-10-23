package com.gasserelshafei.examorganizer

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A problem that is safe to show to a teacher as it is. */
class UserError(message: String) : Exception(message)

fun friendlyMessage(error: Throwable): String = when (error) {
    is UserError -> error.message ?: "Something went wrong."
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
        "Couldn't connect. Check the internet connection and try again."
    is java.io.FileNotFoundException -> "This file was moved or deleted before I could use it."
    is java.io.IOException -> "This file couldn't be read or saved: ${error.message}"
    else -> "Something went wrong: ${error.message ?: error.javaClass.simpleName}"
}

/** What the parser found out about one Cambridge PDF. Same fields as the desktop and iPhone apps. */
data class PaperMetadata(
    val qualification: String? = null,
    val subjectName: String? = null,
    val subjectCode: String? = null,
    val year: String? = null,
    val session: String? = null,
    val paper: String? = null,
    val variant: String? = null,
    val paperType: String? = null,
) {
    /** "Physics (0625)" */
    val title: String get() = if (subjectCode == null) "" else "${subjectName ?: "Unknown subject"} ($subjectCode)"

    /** "IGCSE · May–June 2024 · Paper 4, variant 2" */
    fun describe(withType: Boolean = false): String {
        if (subjectCode == null) return ""
        val parts = mutableListOf<String>()
        qualification?.let { parts += it }
        if (session != null && year != null) parts += "${Catalog.sessionLabel(session)} $year"
        if (paper != null) parts += "Paper $paper" + (variant?.let { ", variant $it" } ?: "")
        if (withType && paperType != null) parts += Catalog.paperTypes[paperType] ?: paperType
        return parts.joinToString(" · ")
    }

    /** "AS & A Level Mathematics (9709) Paper 1 v2, October/November 2023" */
    val heading: String
        get() = "${qualification ?: ""} ${subjectName ?: ""} (${subjectCode ?: ""}) Paper ${paper ?: ""} v${variant ?: ""}, " +
            "${(session ?: "").replace("-", "/")} ${year ?: ""}"

    val isQuestionPaper: Boolean get() = paperType == "QP" && subjectCode != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("qualification", qualification); put("subjectName", subjectName); put("subjectCode", subjectCode)
        put("year", year); put("session", session); put("paper", paper); put("variant", variant)
        put("paperType", paperType)
    }

    companion object {
        fun fromJson(json: JSONObject?): PaperMetadata = if (json == null) PaperMetadata() else PaperMetadata(
            json.optStringOrNull("qualification"), json.optStringOrNull("subjectName"),
            json.optStringOrNull("subjectCode"), json.optStringOrNull("year"), json.optStringOrNull("session"),
            json.optStringOrNull("paper"), json.optStringOrNull("variant"), json.optStringOrNull("paperType"),
        )
    }
}

object Catalog {
    val paperTypes = linkedMapOf(
        "QP" to "Question paper",
        "MS" to "Mark scheme",
        "IN" to "Insert",
        "ER" to "Examiner report",
        "GT" to "Grade thresholds",
        "CI" to "Confidential instructions",
    )
    val qualifications = listOf("IGCSE", "O Level", "AS", "AS & A Level")
    val sessions = listOf("February-March", "May-June", "October-November")
    val gradeOrder = listOf("9", "8", "7", "6", "5", "4", "3", "2", "1", "A*", "A", "B", "C", "D", "E", "F", "G",
        "a", "b", "c", "d", "e", "U")

    fun sessionLabel(session: String) = session.replace("-", "–")
}

data class MarkedQuestion(
    val id: String = UUID.randomUUID().toString(),
    val question: String,
    val page: Int,
    val maxMarks: Int,
    val marksAwarded: Int,
    val studentAnswer: String,
    val marking: String,
    val feedback: String,
    val confidence: String,
    val topic: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("question", question); put("page", page); put("maxMarks", maxMarks)
        put("marksAwarded", marksAwarded); put("studentAnswer", studentAnswer); put("marking", marking)
        put("feedback", feedback); put("confidence", confidence); put("topic", topic)
    }

    companion object {
        fun fromJson(json: JSONObject) = MarkedQuestion(
            json.optString("id", UUID.randomUUID().toString()), json.optString("question"), json.optInt("page"),
            json.optInt("maxMarks"), json.optInt("marksAwarded"), json.optString("studentAnswer"),
            json.optString("marking"), json.optString("feedback"), json.optString("confidence", "medium"),
            json.optString("topic"),
        )
    }
}

data class Marking(
    val questions: List<MarkedQuestion>,
    val total: Int,
    val maxTotal: Int,
    val paperTotalMarks: Int,
    val complete: Boolean,
    val needsReview: List<String>,
    val summary: String,
    val model: String,
    val teacherAdjusted: Boolean = false,
) {
    val percent: Int get() = if (maxTotal > 0) Math.round(100.0 * total / maxTotal).toInt() else 0

    fun recounted(questions: List<MarkedQuestion>) = copy(
        questions = questions,
        total = questions.sumOf { it.marksAwarded },
        maxTotal = questions.sumOf { it.maxMarks },
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("questions", JSONArray(questions.map { it.toJson() }))
        put("total", total); put("maxTotal", maxTotal); put("paperTotalMarks", paperTotalMarks)
        put("complete", complete); put("needsReview", JSONArray(needsReview)); put("summary", summary)
        put("model", model); put("teacherAdjusted", teacherAdjusted)
    }

    companion object {
        fun fromJson(json: JSONObject): Marking {
            val questions = json.optJSONArray("questions").objects().map { MarkedQuestion.fromJson(it) }
            return Marking(
                questions, json.optInt("total"), json.optInt("maxTotal"), json.optInt("paperTotalMarks"),
                json.optBoolean("complete", true), json.optJSONArray("needsReview").strings(),
                json.optString("summary"), json.optString("model"), json.optBoolean("teacherAdjusted"),
            )
        }
    }
}

// ---------- small JSON helpers ----------

fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotEmpty() }

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

fun String.nullIfBlank(): String? = trim().takeIf { it.isNotEmpty() }

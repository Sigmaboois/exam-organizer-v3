package com.gasserelshafei.examorganizer

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Checks a solved paper against its official mark scheme with Google Gemini (free tier).
 * The student's pages go as high-resolution images (handwriting needs the detail) with the
 * mark scheme PDF. Gemini returns marks per question as JSON; totals are added up here.
 */
object Marker {
    // Tried in order, all on the free tier. Each model has its own free daily limit, so when one is
    // overloaded, retired or out of quota the next one is used.
    private val models = listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.5-flash")
    private val tryNextModel = setOf(404, 429, 500, 502, 503, 504)
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent"
    private const val PAGE_SCALE = 2f     // 144 dpi: small handwriting stays readable
    private const val JPEG_QUALITY = 82

    private val EXAMINER_INSTRUCTIONS = """
        You are an experienced, strict Cambridge International examiner.
        You are given a student's answers to a Cambridge question paper (one image per page, written
        by hand or typed on the paper) and the official mark scheme for that exact paper.

        Mark the student's work exactly as a Cambridge examiner would:
        - Follow the mark scheme line by line. Only award a mark when the mark scheme's requirement is met.
        - Apply Cambridge conventions: M = method mark, A = accuracy mark (depends on the preceding M mark
          unless the scheme says otherwise), B = independent mark, DM/DB = dependent marks, FT = follow
          through from an earlier error, SC = special case, OE = or equivalent, AWRT = answers which round
          to, CAO = correct answer only, ISW = ignore subsequent working.
        - Accept equivalent correct methods where the mark scheme allows (OE / alternative methods).
        - Do not credit crossed-out work if there is a non-crossed-out attempt. Do not give marks for
          answers that are not shown. A blank or missing answer scores 0.
        - Read handwriting carefully. If something is genuinely illegible or ambiguous, mark it as you
          best can and set confidence to "low" so a teacher reviews it.

        Before deciding each mark, transcribe the student's key working and final answer for that part.
        Include EVERY question part from the mark scheme, in order, including parts the student left blank.
        marks_awarded must never exceed max_marks. Use the question labels as printed, e.g. "3(b)(ii)".
        Set topic to the syllabus topic this part belongs to, in a few words (for example "Kinematics" or
        "Organic chemistry"). Use the same topic name for every part of that topic.
        Keep feedback short, kind and specific, written to the student.
    """.trimIndent()

    private fun schema(): JSONObject {
        fun type(name: String) = JSONObject().put("type", name)
        val question = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("question", type("STRING")).put("page", type("INTEGER")).put("max_marks", type("INTEGER"))
            .put("marks_awarded", type("INTEGER")).put("student_answer", type("STRING")).put("marking", type("STRING"))
            .put("feedback", type("STRING"))
            .put("confidence", type("STRING").put("enum", JSONArray(listOf("high", "medium", "low"))))
            .put("topic", type("STRING")))
            .put("required", JSONArray(listOf("question", "page", "max_marks", "marks_awarded", "student_answer",
                "marking", "feedback", "confidence", "topic")))
        return JSONObject().put("type", "OBJECT").put("properties", JSONObject()
            .put("questions", JSONObject().put("type", "ARRAY").put("items", question))
            .put("paper_total_marks", type("INTEGER")).put("summary", type("STRING")))
            .put("required", JSONArray(listOf("questions", "paper_total_marks", "summary")))
    }

    /** Teacher's own key (Settings) first, then the key built into the app. */
    fun apiKey(override: String?): String = override?.trim()?.takeIf { it.isNotEmpty() } ?: BuildConfig.GEMINI_API_KEY.trim()

    private fun pageImages(file: File): List<String> = PdfTools.renderer(file) { renderer ->
        (0 until renderer.pageCount).map { index ->
            renderer.openPage(index).use { page ->
                val bitmap = PdfTools.render(page, (page.width * PAGE_SCALE).toInt(), (page.height * PAGE_SCALE).toInt())
                val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
                bitmap.recycle()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }
    }

    private fun requestBody(student: File, markScheme: File, m: PaperMetadata): String {
        val paper = "${m.qualification} ${m.subjectName} (${m.subjectCode}), Paper ${m.paper} variant ${m.variant}, ${m.session} ${m.year}"
        val parts = JSONArray()
        parts.put(JSONObject().put("text", "OFFICIAL MARK SCHEME for $paper:"))
        parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "application/pdf")
            .put("data", Base64.encodeToString(markScheme.readBytes(), Base64.NO_WRAP))))
        parts.put(JSONObject().put("text", "STUDENT'S ANSWERS for $paper, one image per page:"))
        pageImages(student).forEachIndexed { index, image ->
            parts.put(JSONObject().put("text", "Page ${index + 1}:"))
            parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", image)))
        }
        parts.put(JSONObject().put("text", "Mark the student's answers against the mark scheme."))
        return JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", EXAMINER_INSTRUCTIONS))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generation_config", JSONObject().put("response_mime_type", "application/json").put("response_schema", schema()))
            .toString()
    }

    private fun friendlyHttpError(status: Int, body: String): String {
        val detail = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull() ?: body.take(200)
        return when {
            status == 429 -> "The free checking limit has been reached for now. Please wait a minute and try again " +
                "(if it keeps happening, the daily free limit is used up; it resets tomorrow)."
            status == 401 || status == 403 || "API key" in detail -> "The AI checker's key isn't working. Add your own free key in Settings."
            status == 503 -> "Google's free AI checker is very busy right now (it happens at busy times of day). " +
                "Tap Check again in a few minutes."
            status >= 500 -> "Google's AI service is having trouble right now. Please try again in a few minutes."
            else -> "The AI checker couldn't mark this paper ($status): $detail"
        }
    }

    /** Mark a paper. Slow (a minute or so): call it from a background thread. */
    fun mark(student: File, markScheme: File, m: PaperMetadata, keyOverride: String?): Marking {
        val key = apiKey(keyOverride)
        if (key.isEmpty()) throw UserError("No AI key has been set up. Add a free Gemini key in Settings.")
        val body = requestBody(student, markScheme, m).toByteArray()
        android.util.Log.i("Marker", "request: ${body.size / 1000} kB")

        var status = 0
        var reply = ""
        var used = models.first()
        // Busy hours on the free tier can turn every model away for a short while: after a full round of
        // "busy" answers, wait 45 seconds and go round once more before giving up
        rounds@ for (round in 0..1) {
        if (round == 1) {
            if (status !in setOf(429, 500, 502, 503, 504)) break@rounds
            android.util.Log.w("Marker", "every model was busy; trying again in 45 seconds")
            Thread.sleep(45_000L)
        }
        models@ for (model in models) {
            // Google's free tier is sometimes briefly overloaded (500/503): wait and try once more, then the next model
            for (attempt in 0..1) {
                if (attempt > 0) Thread.sleep(8_000L)
                val connection = (URL(ENDPOINT.format(model)).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 30_000
                    readTimeout = 300_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-goog-api-key", key)
                }
                try {
                    connection.outputStream.use { it.write(body) }
                    status = connection.responseCode
                    reply = (if (status in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (e: SocketTimeoutException) {
                    throw UserError("Checking took too long. Please try again.")
                } catch (e: java.io.IOException) {
                    throw UserError("Couldn't reach the AI checker. Check the internet connection.")
                } finally {
                    connection.disconnect()
                }
                used = model
                if (status != 200) android.util.Log.w("Marker", "$model attempt ${attempt + 1}: HTTP $status ${reply.take(400)}")
                if (status !in setOf(500, 502, 503, 504)) break
            }
            if (status !in tryNextModel) break@models
        }
        if (status == 200) break@rounds
        }
        if (status != 200) throw UserError(friendlyHttpError(status, reply))

        val json = runCatching { JSONObject(reply) }.getOrNull()
            ?: throw UserError("The AI checker returned an unreadable result. Please try again.")
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: run {
            val reason = json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() } ?: "no answer"
            throw UserError("The AI checker didn't return a result ($reason). Please try again.")
        }
        val text = candidate.optJSONObject("content")?.optJSONArray("parts").objects().joinToString("") { it.optString("text") }
        val result = runCatching { JSONObject(text) }.getOrNull()
            ?: throw UserError("The AI checker returned an unreadable result. Please try again.")

        val questions = result.optJSONArray("questions").objects().map { q ->
            val maxMarks = q.optInt("max_marks").coerceAtLeast(0)
            MarkedQuestion(
                question = q.optString("question", "?"),
                page = q.optInt("page"),
                maxMarks = maxMarks,
                marksAwarded = q.optInt("marks_awarded").coerceIn(0, maxMarks),
                studentAnswer = q.optString("student_answer"),
                marking = q.optString("marking"),
                feedback = q.optString("feedback"),
                confidence = q.optString("confidence", "medium"),
                topic = q.optString("topic").trim(),
            )
        }
        val total = questions.sumOf { it.marksAwarded }
        val maxTotal = questions.sumOf { it.maxMarks }
        val paperTotal = if (result.has("paper_total_marks")) result.optInt("paper_total_marks") else maxTotal
        return Marking(questions, total, maxTotal, paperTotal, maxTotal == paperTotal,
            questions.filter { it.confidence == "low" }.map { it.question }, result.optString("summary"), used)
    }
}

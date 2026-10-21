import Foundation
import PDFKit
import UIKit

/// Checks a solved paper against its official mark scheme with Google Gemini (free tier).
/// The student's pages go as high-resolution images (handwriting needs the detail) with the
/// mark scheme PDF. Gemini returns marks per question as JSON; totals are added up here.
enum Marker {
    // Tried in order, all on the free tier. Each model has its own free daily limit, so when one is
    // overloaded, retired or out of quota the next one is used.
    static let models = ["gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.5-flash"]
    static let tryNextModel: Set<Int> = [404, 429, 500, 502, 503, 504]
    static let endpoint = "https://generativelanguage.googleapis.com/v1beta/models/%@:generateContent"
    static let pageScale: CGFloat = 2.0     // 144 dpi: small handwriting stays readable
    static let jpegQuality: CGFloat = 0.82

    static let examinerInstructions = """
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
    """

    static let resultSchema: [String: Any] = [
        "type": "OBJECT",
        "properties": [
            "questions": [
                "type": "ARRAY",
                "items": [
                    "type": "OBJECT",
                    "properties": [
                        "question": ["type": "STRING"],
                        "page": ["type": "INTEGER"],
                        "max_marks": ["type": "INTEGER"],
                        "marks_awarded": ["type": "INTEGER"],
                        "student_answer": ["type": "STRING"],
                        "marking": ["type": "STRING"],
                        "feedback": ["type": "STRING"],
                        "confidence": ["type": "STRING", "enum": ["high", "medium", "low"]],
                        "topic": ["type": "STRING"],
                    ],
                    "required": ["question", "page", "max_marks", "marks_awarded", "student_answer",
                                 "marking", "feedback", "confidence", "topic"],
                ],
            ],
            "paper_total_marks": ["type": "INTEGER"],
            "summary": ["type": "STRING"],
        ],
        "required": ["questions", "paper_total_marks", "summary"],
    ]

    /// Teacher's own key (Settings) first, then the key built into the app.
    static func apiKey(override: String?) -> String {
        if let own = override?.trimmed, !own.isEmpty { return own }
        return Secrets.geminiAPIKey.trimmed
    }

    /// Each page of the student's PDF as base64 JPEG.
    static func pageImages(_ url: URL) throws -> [String] {
        let document = try PDFTools.open(url)
        return PDFTools.pages(of: document).compactMap { page in
            autoreleasepool {
                PDFTools.image(of: page, scale: pageScale).jpegData(compressionQuality: jpegQuality)?.base64EncodedString()
            }
        }
    }

    static func requestBody(student: URL, markScheme: URL, metadata: PaperMetadata) throws -> Data {
        let scheme = try Data(contentsOf: markScheme).base64EncodedString()
        let paper = "\(metadata.qualification ?? "") \(metadata.subjectName ?? "") (\(metadata.subjectCode ?? "")), "
            + "Paper \(metadata.paper ?? "") variant \(metadata.variant ?? ""), \(metadata.session ?? "") \(metadata.year ?? "")"
        var parts: [[String: Any]] = [
            ["text": "OFFICIAL MARK SCHEME for \(paper):"],
            ["inline_data": ["mime_type": "application/pdf", "data": scheme]],
            ["text": "STUDENT'S ANSWERS for \(paper), one image per page:"],
        ]
        for (index, image) in try pageImages(student).enumerated() {
            parts.append(["text": "Page \(index + 1):"])
            parts.append(["inline_data": ["mime_type": "image/jpeg", "data": image]])
        }
        parts.append(["text": "Mark the student's answers against the mark scheme."])
        let body: [String: Any] = [
            "system_instruction": ["parts": [["text": examinerInstructions]]],
            "contents": [["role": "user", "parts": parts]],
            "generation_config": [
                "response_mime_type": "application/json",
                "response_schema": resultSchema,
            ],
        ]
        return try JSONSerialization.data(withJSONObject: body)
    }

    static func friendlyHTTPError(status: Int, body: Data) -> String {
        var detail = String(data: body.prefix(200), encoding: .utf8) ?? ""
        if let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
           let error = json["error"] as? [String: Any], let message = error["message"] as? String {
            detail = message
        }
        if status == 429 {
            return "The free checking limit has been reached for now. Please wait a minute and try again "
                + "(if it keeps happening, the daily free limit is used up; it resets tomorrow)."
        }
        if status == 401 || status == 403 || detail.contains("API key") {
            return "The AI checker's key isn't working. Add your own free key in Settings."
        }
        if status == 503 {
            return "Google's free AI checker is very busy right now (it happens at busy times of day). "
                + "Tap Check again in a few minutes."
        }
        if status >= 500 {
            return "Google's AI service is having trouble right now. Please try again in a few minutes."
        }
        return "The AI checker couldn't mark this paper (\(status)): \(detail)"
    }

    private struct Reply: Decodable {
        struct Question: Decodable {
            var question: String?
            var page: Int?
            var maxMarks: Int?
            var marksAwarded: Int?
            var studentAnswer: String?
            var marking: String?
            var feedback: String?
            var confidence: String?
            var topic: String?

            enum CodingKeys: String, CodingKey {
                case question, page, marking, feedback, confidence, topic
                case maxMarks = "max_marks"
                case marksAwarded = "marks_awarded"
                case studentAnswer = "student_answer"
            }
        }
        var questions: [Question]?
        var paperTotalMarks: Int?
        var summary: String?

        enum CodingKeys: String, CodingKey {
            case questions, summary
            case paperTotalMarks = "paper_total_marks"
        }
    }

    /// Mark a paper. Slow (a minute or so): call it from a background task.
    static func mark(student: URL, markScheme: URL, metadata: PaperMetadata, keyOverride: String?) async throws -> Marking {
        let key = apiKey(override: keyOverride)
        if key.isEmpty {
            throw UserError("No AI key has been set up. Add a free Gemini key in Settings.")
        }
        let body = try requestBody(student: student, markScheme: markScheme, metadata: metadata)

        var data = Data()
        var status = 0
        var usedModel = models[0]
        // Busy hours on the free tier can turn every model away for a short while: after a full round of
        // "busy" answers, wait 45 seconds and go round once more before giving up
        rounds: for round in 0..<2 {
        if round == 1 {
            guard [429, 500, 502, 503, 504].contains(status) else { break rounds }
            try? await Task.sleep(nanoseconds: 45_000_000_000)
        }
        models: for model in models {
            guard let url = URL(string: String(format: endpoint, model)) else { continue }
            // Google's free tier is sometimes briefly overloaded (500/503): wait and try once more, then the next model
            for attempt in 0..<2 {
                if attempt > 0 { try? await Task.sleep(nanoseconds: 8_000_000_000) }
                var request = URLRequest(url: url, timeoutInterval: 300)
                request.httpMethod = "POST"
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.setValue(key, forHTTPHeaderField: "x-goog-api-key")
                request.httpBody = body
                do {
                    let (reply, response) = try await URLSession.shared.data(for: request)
                    data = reply
                    status = (response as? HTTPURLResponse)?.statusCode ?? 0
                } catch let error as URLError where error.code == .timedOut {
                    throw UserError("Checking took too long. Please try again.")
                } catch {
                    throw UserError("Couldn't reach the AI checker. Check the internet connection.")
                }
                usedModel = model
                if ![500, 502, 503, 504].contains(status) { break }
            }
            if !tryNextModel.contains(status) { break models }
        }
        if status == 200 { break rounds }
        }
        guard status == 200 else {
            throw UserError(friendlyHTTPError(status: status, body: data))
        }

        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw UserError("The AI checker returned an unreadable result. Please try again.")
        }
        guard let candidates = json["candidates"] as? [[String: Any]], let first = candidates.first else {
            let feedback = json["promptFeedback"] as? [String: Any]
            let reason = feedback?["blockReason"] as? String ?? "no answer"
            throw UserError("The AI checker didn't return a result (\(reason)). Please try again.")
        }
        let content = first["content"] as? [String: Any]
        let parts = content?["parts"] as? [[String: Any]] ?? []
        let text = parts.compactMap { $0["text"] as? String }.joined()
        guard let reply = try? JSONDecoder().decode(Reply.self, from: Data(text.utf8)) else {
            throw UserError("The AI checker returned an unreadable result. Please try again.")
        }

        let questions = (reply.questions ?? []).map { item -> MarkedQuestion in
            let maxMarks = max(0, item.maxMarks ?? 0)
            return MarkedQuestion(
                question: item.question ?? "?",
                page: item.page ?? 0,
                maxMarks: maxMarks,
                marksAwarded: min(max(0, item.marksAwarded ?? 0), maxMarks),
                studentAnswer: item.studentAnswer ?? "",
                marking: item.marking ?? "",
                feedback: item.feedback ?? "",
                confidence: item.confidence ?? "medium",
                topic: (item.topic ?? "").trimmed)
        }
        let total = questions.reduce(0) { $0 + $1.marksAwarded }
        let maxTotal = questions.reduce(0) { $0 + $1.maxMarks }
        let paperTotal = reply.paperTotalMarks ?? maxTotal
        return Marking(
            questions: questions,
            total: total,
            maxTotal: maxTotal,
            paperTotalMarks: paperTotal,
            complete: maxTotal == paperTotal,
            needsReview: questions.filter { $0.confidence == "low" }.map(\.question),
            summary: reply.summary ?? "",
            model: usedModel,
            teacherAdjusted: false)
    }
}

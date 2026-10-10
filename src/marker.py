"""
Check a solved paper against its official mark scheme with Google Gemini (free tier).

The student's pages are sent as high-resolution images (handwriting needs more
detail than Gemini's built-in PDF reading gives), together with the mark scheme
PDF. Gemini returns marks per question as structured JSON; totals are always
added up here rather than trusted from the model.
"""

import base64
import io
import json
import os

import requests

from src import solve_detect

MODELS = ["gemini-3.8-flash", "gemini-2.5-flash"]  # tried in order, all on the free tier
ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
PAGE_SCALE = 2.0     # 144 dpi: small handwriting stays readable
JPEG_QUALITY = 82
TIMEOUT = 300

EXAMINER_INSTRUCTIONS = """You are an experienced, strict Cambridge International examiner.
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
Keep feedback short, kind and specific, written to the student."""

RESULT_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "questions": {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {
                    "question": {"type": "STRING"},
                    "page": {"type": "INTEGER"},
                    "max_marks": {"type": "INTEGER"},
                    "marks_awarded": {"type": "INTEGER"},
                    "student_answer": {"type": "STRING"},
                    "marking": {"type": "STRING"},
                    "feedback": {"type": "STRING"},
                    "confidence": {"type": "STRING", "enum": ["high", "medium", "low"]},
                },
                "required": ["question", "page", "max_marks", "marks_awarded", "student_answer",
                             "marking", "feedback", "confidence"],
            },
        },
        "paper_total_marks": {"type": "INTEGER"},
        "summary": {"type": "STRING"},
    },
    "required": ["questions", "paper_total_marks", "summary"],
}


class MarkingError(Exception):
    """Something went wrong; the message is safe to show to a teacher."""


def api_key(override=None):
    """Teacher's own key (settings) > environment variable > the key bundled with the app."""
    if override:
        return override.strip()
    if os.getenv("GEMINI_API_KEY"):
        return os.environ["GEMINI_API_KEY"].strip()
    try:
        from src.api_key import GEMINI_API_KEY
        return GEMINI_API_KEY.strip()
    except ImportError:
        return ""


def _page_images(pdf_path):
    """Each page as a base64 JPEG."""
    encoded = []
    for image in solve_detect.render_pages(pdf_path, scale=PAGE_SCALE, mode="RGB"):
        buffer = io.BytesIO()
        image.save(buffer, format="JPEG", quality=JPEG_QUALITY)
        encoded.append(base64.b64encode(buffer.getvalue()).decode("ascii"))
    return encoded


def _build_request(student_pdf, mark_scheme_pdf, metadata):
    with open(mark_scheme_pdf, "rb") as file:
        mark_scheme = base64.b64encode(file.read()).decode("ascii")

    paper = (f"{metadata.get('qualification')} {metadata.get('subject_name')} ({metadata.get('subject_code')}), "
             f"Paper {metadata.get('paper')} variant {metadata.get('variant')}, "
             f"{metadata.get('session')} {metadata.get('year')}")
    parts = [{"text": f"OFFICIAL MARK SCHEME for {paper}:"},
             {"inline_data": {"mime_type": "application/pdf", "data": mark_scheme}},
             {"text": f"STUDENT'S ANSWERS for {paper}, one image per page:"}]
    for number, image in enumerate(_page_images(student_pdf), start=1):
        parts.append({"text": f"Page {number}:"})
        parts.append({"inline_data": {"mime_type": "image/jpeg", "data": image}})
    parts.append({"text": "Mark the student's answers against the mark scheme."})

    return {
        "system_instruction": {"parts": [{"text": EXAMINER_INSTRUCTIONS}]},
        "contents": [{"role": "user", "parts": parts}],
        "generation_config": {
            "response_mime_type": "application/json",
            "response_schema": RESULT_SCHEMA,
        },
    }


def _call(model, body, key):
    try:
        response = requests.post(ENDPOINT.format(model=model), json=body, timeout=TIMEOUT,
                                 headers={"x-goog-api-key": key, "Content-Type": "application/json"})
    except requests.Timeout as error:
        raise MarkingError("Checking took too long. Please try again.") from error
    except requests.RequestException as error:
        raise MarkingError("Couldn't reach the AI checker. Check the internet connection.") from error
    return response


def _friendly_http_error(response):
    try:
        detail = response.json().get("error", {}).get("message", "")
    except ValueError:
        detail = response.text[:200]
    if response.status_code == 429:
        return ("The free checking limit has been reached for now. Please wait a minute and try again "
                "(if it keeps happening, the daily free limit is used up; it resets tomorrow).")
    if response.status_code in (401, 403) or "API key" in detail:
        return "The AI checker's key isn't working. Add your own free key in Settings."
    if response.status_code >= 500:
        return "Google's AI service is having trouble right now. Please try again in a few minutes."
    return f"The AI checker couldn't mark this paper ({response.status_code}): {detail}"


def mark(student_pdf, mark_scheme_pdf, metadata, key_override=None):
    """Return a dict: questions, total, max_total, paper_total_marks, summary, needs_review, model."""
    key = api_key(key_override)
    if not key:
        raise MarkingError("No AI key has been set up. Add a free Gemini key in Settings.")

    body = _build_request(student_pdf, mark_scheme_pdf, metadata)
    response = None
    for model in MODELS:
        response = _call(model, body, key)
        if response.status_code != 404:  # 404 = this model isn't available, try the next one
            break
    if response.status_code != 200:
        raise MarkingError(_friendly_http_error(response))

    data = response.json()
    candidates = data.get("candidates") or []
    if not candidates:
        reason = data.get("promptFeedback", {}).get("blockReason", "no answer")
        raise MarkingError(f"The AI checker didn't return a result ({reason}). Please try again.")
    text = "".join(part.get("text", "") for part in candidates[0].get("content", {}).get("parts", []))
    try:
        result = json.loads(text)
    except ValueError as error:
        raise MarkingError("The AI checker returned an unreadable result. Please try again.") from error

    questions = []
    for q in result.get("questions", []):
        max_marks = max(0, int(q.get("max_marks") or 0))
        awarded = min(max(0, int(q.get("marks_awarded") or 0)), max_marks)
        questions.append({**q, "max_marks": max_marks, "marks_awarded": awarded})

    total = sum(q["marks_awarded"] for q in questions)
    max_total = sum(q["max_marks"] for q in questions)
    paper_total = int(result.get("paper_total_marks") or max_total)
    return {
        "questions": questions,
        "total": total,
        "max_total": max_total,
        "paper_total_marks": paper_total,
        # If the parts don't add up to the paper's total, something was missed
        "complete": max_total == paper_total,
        "needs_review": [q["question"] for q in questions if q.get("confidence") == "low"],
        "summary": result.get("summary", ""),
        "model": model,
    }

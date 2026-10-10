"""
Find the official files that go with an exam: the clean question paper (for solve
detection), the mark scheme (for checking) and the grade thresholds (for grades).

Files are looked for next to the paper first (renamer puts QPs and MSs in the same
folder), then in the local download cache, and only then downloaded.
"""

import os
import re

import requests

DOWNLOAD_URL = "https://pastpapers.papacambridge.com/directories/CAIE/CAIE-pastpapers/upload/{name}"
CACHE_DIR = os.path.join("data", "papers")
SESSION_LETTERS = {"February-March": "m", "May-June": "s", "October-November": "w"}
HEADERS = {"User-Agent": "Mozilla/5.0 (Exam Organizer)"}


class NotAvailableError(Exception):
    """The file couldn't be found locally or online."""


def official_name(metadata, kind):
    """
    Cambridge's own file name, e.g. 9709_w23_ms_12.pdf.
    kind is "qp" or "ms" (paper-specific) or "gt" (grade thresholds for the whole series).
    """
    letter = SESSION_LETTERS.get(metadata.get("session"))
    year = str(metadata.get("year") or "")
    if not letter or len(year) != 4 or not metadata.get("subject_code"):
        raise NotAvailableError("This paper is missing the details needed to find its official files.")
    series = f"{metadata['subject_code']}_{letter}{year[2:]}"
    if kind == "gt":
        return f"{series}_gt.pdf"
    return f"{series}_{kind}_{metadata['paper']}{metadata['variant']}.pdf"


def _looks_like_pdf(path):
    try:
        with open(path, "rb") as file:
            return file.read(5) == b"%PDF-"
    except OSError:
        return False


def find_local(metadata, kind, near=None):
    """Return a local copy if one exists: next to `near` (a sorted exam) or in the cache."""
    if near and kind in ("qp", "ms"):
        folder = os.path.dirname(near)
        suffix = f" {kind.upper()}.pdf"
        if os.path.isdir(folder):
            for name in os.listdir(folder):
                path = os.path.join(folder, name)
                if name.endswith(suffix) and os.path.abspath(path) != os.path.abspath(near) and _looks_like_pdf(path):
                    return path
    cached = os.path.join(CACHE_DIR, official_name(metadata, kind))
    return cached if _looks_like_pdf(cached) else None


def download(metadata, kind, timeout=30):
    """Download an official file into the cache and return its path."""
    name = official_name(metadata, kind)
    os.makedirs(CACHE_DIR, exist_ok=True)
    target = os.path.join(CACHE_DIR, name)
    try:
        response = requests.get(DOWNLOAD_URL.format(name=name), headers=HEADERS, timeout=timeout)
    except requests.RequestException as error:
        raise NotAvailableError("Couldn't connect to download the official file. Check the internet connection.") from error
    if response.status_code != 200 or not response.content.startswith(b"%PDF-"):
        raise NotAvailableError(f"Cambridge's {describe(kind)} for this paper isn't available online yet.")
    with open(target, "wb") as file:
        file.write(response.content)
    return target


def get(metadata, kind, near=None):
    """Local copy if there is one, otherwise download it."""
    return find_local(metadata, kind, near) or download(metadata, kind)


def describe(kind):
    return {"qp": "question paper", "ms": "mark scheme", "gt": "grade thresholds"}[kind]


# --------------------------------------------------------------------------
# Grade thresholds: turn a mark into a grade using Cambridge's real boundaries
# --------------------------------------------------------------------------

GRADE_LETTERS = {
    # Letter columns in the "Component" rows, in order
    "IGCSE": ["A", "B", "C", "D", "E", "F", "G"],
    "O Level": ["A", "B", "C", "D", "E"],
    "AS": ["a", "b", "c", "d", "e"],
    "AS & A Level": ["A", "B", "C", "D", "E"],
}


def component_thresholds(metadata, near=None):
    """
    Return (max_mark, {grade: minimum_mark}) for this exact paper/variant,
    read from Cambridge's grade threshold document.
    """
    from pypdf import PdfReader  # imported here so the module stays light

    path = get(metadata, "gt", near)
    text = "\n".join(page.extract_text() or "" for page in PdfReader(path).pages)
    component = f"{metadata['paper']}{metadata['variant']}"
    match = re.search(rf"Component\s+{component}\s+((?:\d+\s+){{2,8}}\d+)", text)
    if not match:
        raise NotAvailableError("This paper isn't listed in Cambridge's grade thresholds.")
    numbers = [int(n) for n in match.group(1).split()]
    max_mark, boundaries = numbers[0], numbers[1:]
    letters = GRADE_LETTERS.get(metadata.get("qualification"), ["A", "B", "C", "D", "E", "F", "G"])
    # IGCSE (9-1) and some syllabuses have different columns; zip only what lines up
    return max_mark, dict(zip(letters, boundaries))


def grade_for(score, max_score, thresholds_max, thresholds):
    """Scale the score to the threshold paper's max mark and return the grade letter."""
    if not thresholds or max_score <= 0:
        return None
    scaled = score * thresholds_max / max_score
    for letter, minimum in thresholds.items():
        if scaled >= minimum:
            return letter
    return "U"

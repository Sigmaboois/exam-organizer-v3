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

# Used only when the threshold PDF's own header line can't be read, and only when
# the number of columns matches what this qualification normally has.
GRADE_LETTERS = {
    "IGCSE": ["A", "B", "C", "D", "E", "F", "G"],
    "O Level": ["A", "B", "C", "D", "E"],
    "AS": ["a", "b", "c", "d", "e"],
    "AS & A Level": ["A", "B", "C", "D", "E"],
}
# Cells for grades that aren't awarded on a paper: a dash, or a glyph pypdf can't decode
_MISSING_CHARS = "-–—−‐‑~�"
# A header row of grades: "A B C D E F G", "A C E F", "AA BB CC", "A* A B", "9 8 7 ... 1".
# Older tables put it after "available" or "grade:" instead of on its own line.
_GRADE = r"(?:A\*A\*|A\*|[A-G]{1,2}|[a-g]|[1-9])"
_HEADER = re.compile(
    rf"(?m)(?:^|available|:)[ \t]*({_GRADE}(?:[ \t]+{_GRADE}){{2,9}})(?=[ \t]*(?:$|Component\b))"
)


def _grade_header(text, before):
    """The grade columns of the table header nearest above this component's row."""
    found = None
    for match in _HEADER.finditer(text):
        if match.start() >= before:
            break
        found = match.group(1).split()
    return found


def _infer_grades(count, metadata):
    name = metadata.get("subject_name") or ""
    if count == 9 and ("9-1" in name or "9–1" in name):
        return list("987654321")
    usual = GRADE_LETTERS.get(metadata.get("qualification"))
    return usual if usual and len(usual) == count else None


def _component_values(line):
    """
    Max mark, then one cell per grade (None = not awarded).
    Older PDFs leave a blank cell as an extra space, so "56  34 22 18" is 56, blank, 34, 22, 18
    and "70 53 32 20  " ends with a blank cell.
    """
    line = line.lstrip(" \t")
    if line.endswith(" "):   # the row's own closing space
        line = line[:-1]
    values = []
    for token in line.split(" "):
        if token.isdigit():
            values.append(int(token))
        elif token.lower() in ("", "n/a", "na") or all(ch in _MISSING_CHARS for ch in token):
            values.append(None)
        else:
            break
    return values


def _match_columns(letters, cells):
    """Pair grades with cells, or None if the columns can't be lined up for certain."""
    if len(cells) == len(letters):
        return list(zip(letters, cells))
    trimmed = list(cells)
    while trimmed and trimmed[-1] is None:
        trimmed.pop()
    if len(trimmed) <= len(letters) and (len(trimmed) == len(letters) or len(trimmed) < len(cells)):
        # blank cells at the end of the row are the lowest grades that weren't awarded
        return list(zip(letters, trimmed + [None] * (len(letters) - len(trimmed))))
    filled = [cell for cell in cells if cell is not None]
    if len(filled) == len(letters):   # stray double spaces that weren't blank cells
        return list(zip(letters, filled))
    return None


def component_thresholds(metadata, near=None):
    """
    Return (max_mark, {grade: minimum_mark}) for this exact paper/variant,
    read from Cambridge's grade threshold document.

    The columns come from the PDF's own header, so IGCSE 9-1 isn't forced into
    A-G and old tables with only A C E F columns map correctly. A dash or blank
    cell means that grade isn't available on this component (core papers have
    no A/B). If the columns can't be lined up for certain the grades are left
    empty: no grade is better than a wrong one.
    """
    from pypdf import PdfReader  # imported here so the module stays light

    path = get(metadata, "gt", near)
    text = "\n".join(page.extract_text() or "" for page in PdfReader(path).pages)
    component = f"{metadata['paper']}{metadata['variant']}"
    match = re.search(rf"Component\s+{re.escape(component)}(?!\d)[ \t]([^\n]+)", text)
    values = _component_values(match.group(1)) if match else []
    if len(values) < 2 or not isinstance(values[0], int):
        raise NotAvailableError("This paper isn't listed in Cambridge's grade thresholds.")
    max_mark, cells = values[0], values[1:]
    letters = _grade_header(text, match.start()) or _infer_grades(len(cells), metadata)
    pairs = _match_columns(letters, cells) if letters else None
    thresholds = {letter: minimum for letter, minimum in pairs or [] if minimum is not None}
    minimums = list(thresholds.values())
    if minimums != sorted(minimums, reverse=True) or any(m > max_mark for m in minimums):
        thresholds = {}
    return max_mark, thresholds


def grade_for(score, max_score, thresholds_max, thresholds):
    """
    Return the grade letter for a raw score. thresholds_max is the paper's real maximum raw mark,
    so the score isn't scaled by the AI's own total: that is too low when blank parts were left out
    (they score 0 anyway) and too high when both optional questions were listed.
    """
    if not thresholds or max_score <= 0:
        return None
    raw = min(score, thresholds_max)
    for letter, minimum in thresholds.items():
        if raw >= minimum:
            return letter
    return "U"

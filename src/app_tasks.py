"""
The slow jobs the app runs in the background (never on the UI loop):
reading papers, sorting them, detecting answers and checking papers.

Backend modules are imported by load_backend(), because the first import of the
parser downloads Cambridge's subject list, which needs the internet.
"""

import json
import os
import shutil
import uuid

from src.gui_common import MARKED_DIR, STAGING_DIR_NAME, safe_name

# Required by renamer to build the folder path and file name
REQUIRED_FIELDS = ("qualification", "subject_code", "year", "session", "paper", "variant", "paper_type")

reader = parser = renamer = scraper = None
cambridge_files = solve_detect = marker = scorebook = reports = None


def load_backend():
    global reader, parser, renamer, scraper, cambridge_files, solve_detect, marker, scorebook, reports
    import src.reader as reader_module
    import src.parser as parser_module
    import src.renamer as renamer_module
    import src.scraper as scraper_module
    import src.cambridge_files as files_module
    import src.solve_detect as solve_module
    import src.marker as marker_module
    import src.scorebook as scorebook_module
    import src.reports as reports_module
    reader, parser, renamer, scraper = reader_module, parser_module, renamer_module, scraper_module
    cambridge_files, solve_detect, marker = files_module, solve_module, marker_module
    scorebook, reports = scorebook_module, reports_module


def refresh_subject_list():
    scraper.generate_file(scraper.search())
    with open("data/subjects.json", "r", encoding="utf-8") as file:
        parser.subjects = json.load(file)


# --------------------------------------------------------------------------
# Reading and sorting
# --------------------------------------------------------------------------

def analyse(pdf_path):
    """Read one PDF and return (metadata, problem). problem is None if it can be sorted."""
    text = reader.read_file(pdf_path)
    if not text.strip():
        return None, "I couldn't find any text in this PDF. It might be a scanned image."

    metadata = parser.meta_extract(text)

    if metadata["subject_code"] is None:
        return metadata, "This doesn't look like a Cambridge question paper or mark scheme."
    if metadata["year"] is None or metadata["session"] is None:
        return metadata, ("I couldn't tell which exam series this is from. If it was re-saved "
                          "by a note-taking app, try the original PDF.")
    if metadata["qualification"] is None:
        return metadata, "I couldn't tell whether this is IGCSE, O Level or AS & A Level."
    missing = [field for field in REQUIRED_FIELDS if metadata[field] is None]
    if missing:
        return metadata, "Some details are missing: " + ", ".join(missing).replace("_", " ") + "."

    # Subjects Cambridge no longer offers aren't on their website, so give them a fallback name
    if metadata["subject_name"] is None:
        metadata["subject_name"] = f"Subject {metadata['subject_code']}"
    return metadata, None


def place(pdf_path, metadata, destination, keep_original):
    """
    Sort one exam into the destination and return the path it ended up at.

    The file is first copied into a staging folder inside the destination, so
    renamer.rename always moves within the same drive (os.rename can't move
    across drives) and the naming rules stay in renamer.py.
    """
    folder = renamer.folder_create(metadata, destination)
    staging = os.path.join(destination, STAGING_DIR_NAME)
    os.makedirs(staging, exist_ok=True)
    staged_copy = os.path.join(staging, f"{uuid.uuid4().hex}.pdf")
    shutil.copy2(pdf_path, staged_copy)

    files_before = set(os.listdir(folder))
    try:
        renamer.rename(metadata, staged_copy, folder)
    except BaseException:
        if os.path.exists(staged_copy):
            os.remove(staged_copy)
        raise
    new_files = set(os.listdir(folder)) - files_before
    final_path = os.path.join(folder, new_files.pop()) if new_files else folder

    if not keep_original:
        os.remove(pdf_path)
    return final_path


def remove_staging_folder(destination):
    try:
        os.rmdir(os.path.join(destination, STAGING_DIR_NAME))  # only works when empty, which it should be
    except OSError:
        pass


# --------------------------------------------------------------------------
# Solve detection
# --------------------------------------------------------------------------

def detect_answers(pdf_path, metadata):
    """
    Compare a question paper with Cambridge's clean original.
    Returns a SolveResult; falls back to an estimate if the original can't be fetched.
    """
    try:
        clean = cambridge_files.get(metadata, "qp", near=pdf_path)
        if os.path.samefile(clean, pdf_path):
            clean = cambridge_files.find_local(metadata, "qp") or cambridge_files.download(metadata, "qp")
    except cambridge_files.NotAvailableError:
        clean = None
    return solve_detect.detect(pdf_path, clean)


# --------------------------------------------------------------------------
# Checking
# --------------------------------------------------------------------------

def check_paper(pdf_path, metadata, student=None, class_name=None, key_override=None, progress=None):
    """
    Mark a solved paper against its mark scheme and make a marked PDF copy.
    progress(text) is called with status updates. Returns a dict with everything the UI needs.
    """
    def say(text):
        if progress:
            progress(text)

    say("Finding the mark scheme…")
    mark_scheme = cambridge_files.get(metadata, "ms", near=pdf_path)

    say("Marking the answers… (this can take a minute)")
    marking = marker.mark(pdf_path, mark_scheme, metadata, key_override=key_override)

    say("Working out the grade…")
    grade = None
    # The AI's own "paper total" isn't reliable, so only warn about missing parts
    # when Cambridge's official maximum mark says something is missing
    marking["complete"] = True
    try:
        thresholds_max, thresholds = cambridge_files.component_thresholds(metadata, near=pdf_path)
        grade = cambridge_files.grade_for(marking["total"], marking["max_total"], thresholds_max, thresholds)
        marking["paper_total_marks"] = thresholds_max
        marking["complete"] = marking["max_total"] == thresholds_max
    except Exception:
        pass  # grade thresholds aren't published for every paper; the score is still useful

    say("Making the marked copy…")
    title = (f"{metadata.get('qualification')} {metadata.get('subject_name')} ({metadata.get('subject_code')}) "
             f"Paper {metadata.get('paper')} v{metadata.get('variant')}, "
             f"{(metadata.get('session') or '').replace('-', '/')} {metadata.get('year')}")
    folder = os.path.join(MARKED_DIR, safe_name(class_name or "Not in a class"), safe_name(student or "Unnamed"))
    file_name = safe_name(f"{metadata.get('subject_name')} P{metadata.get('paper')}{metadata.get('variant')} "
                          f"{metadata.get('session')} {metadata.get('year')} - marked") + ".pdf"
    marked_path = _unique_path(os.path.join(folder, file_name))
    reports.marked_pdf(pdf_path, marking, marked_path, title=title, student=student, grade=grade)

    return {"marking": marking, "grade": grade, "marked_pdf": marked_path, "mark_scheme": mark_scheme}


def _unique_path(path):
    """path, or 'path (2)', 'path (3)'… if it already exists."""
    if not os.path.exists(path):
        return path
    stem, ext = os.path.splitext(path)
    number = 2
    while os.path.exists(f"{stem} ({number}){ext}"):
        number += 1
    return f"{stem} ({number}){ext}"

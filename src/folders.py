"""
Class folders: every class has a folder with one sub-folder per student, holding each checked
paper as a marked PDF and a text file that explains the mistakes:

    Documents/Exam Organizer/Classes/<class>/<student>/<paper> - marked.pdf
                                                       <paper> - mistakes.txt

Papers checked without choosing a student go in Documents/Exam Organizer/Marked/Not saved to a student.
The same layout is used by the iPhone/iPad and Android apps.
"""

import os
import uuid
from datetime import datetime


def _documents_folder():
    """The real Documents folder (inside OneDrive when its folder backup is on), or ~/Documents."""
    if os.name == "nt":
        try:
            import ctypes
            folder_id = ctypes.create_string_buffer(uuid.UUID("FDD39AD0-238F-46AF-ADB4-6C85480369C7").bytes_le, 16)
            path = ctypes.c_wchar_p()
            if ctypes.windll.shell32.SHGetKnownFolderPath(folder_id, 0, None, ctypes.byref(path)) == 0:
                try:
                    if path.value:
                        return path.value
                finally:
                    ctypes.windll.ole32.CoTaskMemFree(path)
        except Exception:
            pass
    return os.path.join(os.path.expanduser("~"), "Documents")


def _base_dir():
    base = os.path.join(_documents_folder(), "Exam Organizer")
    # Older versions always used ~/Documents. Keep using it if it has anything in it and the real one
    # isn't there yet, so no file is left behind and the score book's links keep working.
    old = os.path.join(os.path.expanduser("~"), "Documents", "Exam Organizer")
    try:
        if not os.path.exists(base) and os.path.isdir(old) and os.listdir(old):
            return old
    except OSError:
        pass
    return base


BASE_DIR = _base_dir()
CLASSES_DIR = os.path.join(BASE_DIR, "Classes")
UNASSIGNED_DIR = os.path.join(BASE_DIR, "Marked", "Not saved to a student")


def safe_name(text):
    """Make text safe to use as a Windows file or folder name."""
    cleaned = "".join("-" if c in '<>:"/\\|?*' else c for c in str(text)).strip(" .")
    return cleaned or "Untitled"


def class_folder(class_name, student=None, create=True):
    """Classes/<class> or Classes/<class>/<student>."""
    path = os.path.join(CLASSES_DIR, safe_name(class_name))
    if student:
        path = os.path.join(path, safe_name(student))
    if create:
        os.makedirs(path, exist_ok=True)
    return path


def rename_folder(old, new):
    """
    Rename a folder, merging into the other one if both exist. Returns the moves made, [(from, to)].
    All or nothing: if a file inside is open in another program, anything already moved is put back
    and the OSError is raised.
    """
    if os.path.normcase(os.path.abspath(old)) == os.path.normcase(os.path.abspath(new)):
        return []
    if not os.path.isdir(old):
        os.makedirs(new, exist_ok=True)
        return []
    created = not os.path.exists(new)
    if created:
        os.makedirs(os.path.dirname(new), exist_ok=True)
        try:
            os.rename(old, new)
            return [(old, new)]
        except OSError:
            pass
    os.makedirs(new, exist_ok=True)
    moves = []
    try:
        for name in os.listdir(old):
            source = os.path.join(old, name)
            if not os.path.lexists(source):
                continue  # a mistakes report, already moved with its marked PDF
            if name.endswith(" - mistakes.txt") and os.path.exists(
                    os.path.join(old, name[: -len(" - mistakes.txt")] + " - marked.pdf")):
                continue  # moved with its marked PDF
            if name.endswith(" - marked.pdf"):  # if the name is taken, the PDF and its report get the same number
                target = free_marked_path(new, name[: -len(" - marked.pdf")])
                pairs = [(source, target), (report_path(source), report_path(target))]
            else:
                pairs = [(source, unique_path(os.path.join(new, name)))]
            for source, target in pairs:
                if os.path.lexists(source):
                    os.rename(source, target)  # a whole file or folder, or nothing
                    moves.append((source, target))
    except OSError:
        undo_moves(moves)
        if created:
            try:
                os.rmdir(new)
            except OSError:
                pass
        raise
    try:
        os.rmdir(old)
    except OSError:
        pass
    return moves


def undo_moves(moves):
    """Put back what rename_folder moved (as far as possible)."""
    for source, target in reversed(moves):
        try:
            os.makedirs(os.path.dirname(source), exist_ok=True)
            os.rename(target, source)
        except OSError:
            pass


def unique_path(path):
    """path, or 'path (2)', 'path (3)'… if it already exists."""
    if not os.path.exists(path):
        return path
    stem, ext = os.path.splitext(path)
    number = 2
    while os.path.exists(f"{stem} ({number}){ext}"):
        number += 1
    return f"{stem} ({number}){ext}"


def free_marked_path(folder, base):
    """'<base> - marked.pdf', or '<base> (2) - marked.pdf'… if taken, so its mistakes report gets the same number."""
    candidate, number = base, 2
    while (os.path.exists(os.path.join(folder, f"{candidate} - marked.pdf"))
           or os.path.exists(os.path.join(folder, f"{candidate} - mistakes.txt"))):
        candidate = f"{base} ({number})"
        number += 1
    return os.path.join(folder, f"{candidate} - marked.pdf")


def paper_name(metadata):
    """'Physics P42 May-June 2024'"""
    return safe_name(f"{metadata.get('subject_name') or 'Paper'} P{metadata.get('paper') or ''}{metadata.get('variant') or ''} "
                     f"{metadata.get('session') or ''} {metadata.get('year') or ''}")


def report_path(marked_pdf):
    """The mistakes report that sits next to a marked PDF."""
    folder, name = os.path.split(marked_pdf)
    stem = os.path.splitext(name)[0]
    if stem.endswith(" - marked"):
        stem = stem[: -len(" - marked")]
    return os.path.join(folder, f"{stem} - mistakes.txt")


def _heading(metadata):
    return (f"{metadata.get('qualification')} {metadata.get('subject_name')} ({metadata.get('subject_code')}) "
            f"Paper {metadata.get('paper')} v{metadata.get('variant')}, "
            f"{(metadata.get('session') or '').replace('-', '/')} {metadata.get('year')}")


def mistakes_report(marking, metadata, student=None, class_name=None, grade=None, checked_at=None):
    """Plain text: the score, every mark that was lost and why, the feedback and the topics to revise."""
    rule = "=" * 36
    checked_at = checked_at or datetime.now()
    total, max_total = marking["total"], marking["max_total"]
    percent = round(100 * total / max_total) if max_total else 0
    lines = ["EXAM ORGANIZER - MARKED PAPER", rule]
    if student:
        lines.append(f"Student: {student}" + (f" ({class_name})" if class_name else ""))
    lines.append(f"Paper:   {_heading(metadata)}")
    lines.append(f"Checked: {checked_at:%d %B %Y, %H:%M}")
    lines.append("")
    lines.append(f"Score:   {total} / {max_total} ({percent}%)")
    lines.append("Grade:   " + (f"{grade} (Cambridge grade thresholds)" if grade else "not available for this paper"))
    if not marking.get("complete", True):
        lines.append(f"Note:    the question marks add up to {max_total}, but the paper is out of "
                     f"{marking.get('paper_total_marks')}. Some parts may be missing.")
    if marking.get("teacher_adjusted"):
        lines.append("Note:    marks were adjusted by the teacher.")
    if marking.get("needs_review"):
        lines.append("Please double-check: " + ", ".join(marking["needs_review"]))
    if marking.get("summary"):
        lines += ["", marking["summary"]]

    questions = marking.get("questions") or []
    lost = [q for q in questions if q["marks_awarded"] < q["max_marks"]]
    lines += ["", f"WHERE MARKS WERE LOST ({len(lost)} of {len(questions)} parts)", rule]
    if not lost:
        lines.append("None - full marks on every part.")
    for q in lost:
        topic = (q.get("topic") or "").strip()
        lines += ["", f"{q['question']}   {q['marks_awarded']}/{q['max_marks']}   lost {q['max_marks'] - q['marks_awarded']}"
                  + (f"   [{topic}]" if topic else "")]
        if q.get("student_answer"):
            lines.append(f"  Student wrote: {q['student_answer']}")
        if q.get("marking"):
            lines.append(f"  Why:           {q['marking']}")
        if q.get("feedback"):
            lines.append(f"  Advice:        {q['feedback']}")

    topics = {}
    for q in questions:
        topic = (q.get("topic") or "").strip()
        if topic:
            bucket = topics.setdefault(topic, [0, 0])
            bucket[0] += max(0, q["max_marks"] - q["marks_awarded"])
            bucket[1] += q["max_marks"]
    ranked = sorted(((t, l, m) for t, (l, m) in topics.items() if l > 0), key=lambda x: (-x[1], x[0].lower()))
    if ranked:
        lines += ["", "TOPICS TO REVISE", rule]
        lines += [f"- {t}: lost {l} of {m} mark{'s' if m != 1 else ''}" for t, l, m in ranked]

    lines += ["", "EVERY QUESTION", rule]
    for q in questions:
        mark = "full marks" if q["marks_awarded"] == q["max_marks"] else f"lost {q['max_marks'] - q['marks_awarded']}"
        lines.append(f"{q['question']}   {q['marks_awarded']}/{q['max_marks']}   {mark}"
                     + ("   (please double-check)" if q.get("confidence") == "low" else ""))
    lines += ["", "Marked by an AI examiner against Cambridge's official mark scheme. It can make mistakes,",
              "so check anything that looks wrong."]
    return "\n".join(lines) + "\n"


def write_report(marked_pdf, marking, metadata, student=None, class_name=None, grade=None):
    path = report_path(marked_pdf)
    with open(path, "w", encoding="utf-8") as file:
        file.write(mistakes_report(marking, metadata, student, class_name, grade))
    return path


def move_into(marked_pdf, folder):
    """Move a marked PDF (and its report) into another folder. Returns the PDF's new path."""
    if not marked_pdf or not os.path.exists(marked_pdf):
        return marked_pdf
    if os.path.normcase(os.path.dirname(os.path.abspath(marked_pdf))) == os.path.normcase(os.path.abspath(folder)):
        return marked_pdf
    os.makedirs(folder, exist_ok=True)
    stem = os.path.splitext(os.path.basename(marked_pdf))[0]
    new_pdf = free_marked_path(folder, stem[: -len(" - marked")] if stem.endswith(" - marked") else stem)
    os.rename(marked_pdf, new_pdf)  # same drive; fails cleanly (no half-made copy) while the PDF is open
    report = report_path(marked_pdf)
    if os.path.exists(report):
        try:
            os.rename(report, report_path(new_pdf))
        except OSError:
            pass  # the report is written again next to the PDF
    return new_pdf

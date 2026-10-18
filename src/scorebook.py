"""
The teacher's score book: classes, students and the results of checked papers.

Stored in a small SQLite database (data/scorebook.db). Classes are grouped under
qualification dividers (IGCSE, O Level, AS, AS & A Level) automatically, based on
the papers their students take. Every class and student also gets a folder (see folders.py),
renamed along with them.
"""

import json
import os
import sqlite3
from datetime import datetime

from src import folders

DB_PATH = os.path.join("data", "scorebook.db")
QUALIFICATION_ORDER = ["IGCSE", "O Level", "AS", "AS & A Level"]

SCHEMA = """
CREATE TABLE IF NOT EXISTS classes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE COLLATE NOCASE,
    qualification TEXT
);
CREATE TABLE IF NOT EXISTS students (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    class_id INTEGER NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    UNIQUE (class_id, name COLLATE NOCASE)
);
CREATE TABLE IF NOT EXISTS results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id INTEGER NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    checked_at TEXT NOT NULL,
    qualification TEXT, subject_code TEXT, subject_name TEXT,
    year TEXT, session TEXT, paper TEXT, variant TEXT,
    score INTEGER NOT NULL, max_score INTEGER NOT NULL, grade TEXT,
    details TEXT, marked_pdf TEXT, source_pdf TEXT
);
"""


def connect():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    connection = sqlite3.connect(DB_PATH)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    connection.executescript(SCHEMA)
    return connection


# ---------- classes ----------

def add_class(name, qualification=None):
    name = name.strip()
    if not name:
        raise ValueError("Please give the class a name.")
    with connect() as db:
        try:
            cursor = db.execute("INSERT INTO classes (name, qualification) VALUES (?, ?)", (name, qualification))
        except sqlite3.IntegrityError as error:
            raise ValueError(f'There is already a class called "{name}".') from error
    folders.class_folder(name)
    return cursor.lastrowid


def _move_paths(db, old_folder, new_folder):
    """Folders were renamed: point the saved results at the files' new place."""
    old_prefix = os.path.join(old_folder, "")
    new_prefix = os.path.join(new_folder, "")
    for row in db.execute("SELECT id, marked_pdf FROM results WHERE marked_pdf IS NOT NULL").fetchall():
        path = row["marked_pdf"]
        if os.path.normcase(path).startswith(os.path.normcase(old_prefix)):
            db.execute("UPDATE results SET marked_pdf = ? WHERE id = ?", (new_prefix + path[len(old_prefix):], row["id"]))


def rename_class(class_id, name):
    name = name.strip()
    if not name:
        raise ValueError("Please give the class a name.")
    with connect() as db:
        old = db.execute("SELECT name FROM classes WHERE id = ?", (class_id,)).fetchone()
        try:
            db.execute("UPDATE classes SET name = ? WHERE id = ?", (name, class_id))
        except sqlite3.IntegrityError as error:
            raise ValueError(f'There is already a class called "{name}".') from error
        if old:
            old_folder = folders.class_folder(old["name"], create=False)
            new_folder = folders.class_folder(name, create=False)
            folders.rename_folder(old_folder, new_folder)
            _move_paths(db, old_folder, new_folder)


def ensure_folders():
    """Make a folder for every class and student (for score books from before folders existed)."""
    with connect() as db:
        rows = db.execute("""SELECT c.name AS class_name, s.name AS student_name
                             FROM classes c LEFT JOIN students s ON s.class_id = c.id""").fetchall()
    for row in rows:
        try:
            folders.class_folder(row["class_name"], row["student_name"])
        except OSError:
            pass


def delete_class(class_id):
    with connect() as db:
        db.execute("DELETE FROM classes WHERE id = ?", (class_id,))


def classes_by_qualification():
    """{divider: [class rows]} in a sensible order. Unknown qualification goes under "Other"."""
    with connect() as db:
        rows = db.execute("""
            SELECT c.*, COUNT(DISTINCT s.id) AS student_count
            FROM classes c LEFT JOIN students s ON s.class_id = c.id
            GROUP BY c.id ORDER BY c.name COLLATE NOCASE""").fetchall()
    groups = {}
    for row in rows:
        groups.setdefault(row["qualification"] or "Other", []).append(dict(row))
    order = QUALIFICATION_ORDER + sorted(k for k in groups if k not in QUALIFICATION_ORDER)
    return {key: groups[key] for key in order if key in groups}


def _update_class_qualification(db, class_id, qualification):
    """A class takes the qualification of the papers its students sit (first one wins)."""
    if qualification:
        db.execute("UPDATE classes SET qualification = ? WHERE id = ? AND qualification IS NULL",
                   (qualification, class_id))


# ---------- students ----------

def add_student(class_id, name):
    name = name.strip()
    if not name:
        raise ValueError("Please type the student's name.")
    with connect() as db:
        try:
            cursor = db.execute("INSERT INTO students (class_id, name) VALUES (?, ?)", (class_id, name))
        except sqlite3.IntegrityError as error:
            raise ValueError(f'{name} is already in this class.') from error
        class_row = db.execute("SELECT name FROM classes WHERE id = ?", (class_id,)).fetchone()
    if class_row:
        folders.class_folder(class_row["name"], name)
    return cursor.lastrowid


def rename_student(student_id, name):
    name = name.strip()
    if not name:
        raise ValueError("Please type the student's name.")
    with connect() as db:
        old = db.execute("""SELECT s.name AS student_name, c.name AS class_name FROM students s
                            JOIN classes c ON c.id = s.class_id WHERE s.id = ?""", (student_id,)).fetchone()
        try:
            db.execute("UPDATE students SET name = ? WHERE id = ?", (name, student_id))
        except sqlite3.IntegrityError as error:
            raise ValueError(f'{name} is already in this class.') from error
        if old:
            old_folder = folders.class_folder(old["class_name"], old["student_name"], create=False)
            new_folder = folders.class_folder(old["class_name"], name, create=False)
            folders.rename_folder(old_folder, new_folder)
            _move_paths(db, old_folder, new_folder)


def delete_student(student_id):
    with connect() as db:
        db.execute("DELETE FROM students WHERE id = ?", (student_id,))


def students(class_id):
    with connect() as db:
        rows = db.execute("""
            SELECT s.*, COUNT(r.id) AS result_count,
                   ROUND(AVG(100.0 * r.score / NULLIF(r.max_score, 0)), 1) AS average
            FROM students s LEFT JOIN results r ON r.student_id = s.id
            WHERE s.class_id = ? GROUP BY s.id ORDER BY s.name COLLATE NOCASE""", (class_id,)).fetchall()
    return [dict(row) for row in rows]


# ---------- results ----------

def save_result(student_id, metadata, marking, grade=None, marked_pdf=None, source_pdf=None):
    with connect() as db:
        class_id = db.execute("SELECT class_id FROM students WHERE id = ?", (student_id,)).fetchone()["class_id"]
        _update_class_qualification(db, class_id, metadata.get("qualification"))
        cursor = db.execute("""
            INSERT INTO results (student_id, checked_at, qualification, subject_code, subject_name, year,
                                 session, paper, variant, score, max_score, grade, details, marked_pdf, source_pdf)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (student_id, datetime.now().isoformat(timespec="seconds"),
             metadata.get("qualification"), metadata.get("subject_code"), metadata.get("subject_name"),
             metadata.get("year"), metadata.get("session"), metadata.get("paper"), metadata.get("variant"),
             marking["total"], marking["max_total"], grade, json.dumps(marking), marked_pdf, source_pdf))
        return cursor.lastrowid


def update_result(result_id, marking, grade=None, marked_pdf=None):
    """Replace the score after a teacher corrects the AI's marks."""
    with connect() as db:
        db.execute("""UPDATE results SET score = ?, max_score = ?, grade = ?, details = ?, marked_pdf = ?
                      WHERE id = ?""",
                   (marking["total"], marking["max_total"], grade, json.dumps(marking), marked_pdf, result_id))


def delete_result(result_id):
    with connect() as db:
        db.execute("DELETE FROM results WHERE id = ?", (result_id,))


def results(student_id=None, class_id=None):
    query = """SELECT r.*, s.name AS student_name, c.name AS class_name
               FROM results r JOIN students s ON s.id = r.student_id JOIN classes c ON c.id = s.class_id"""
    where, params = [], []
    if student_id is not None:
        where.append("r.student_id = ?")
        params.append(student_id)
    if class_id is not None:
        where.append("s.class_id = ?")
        params.append(class_id)
    if where:
        query += " WHERE " + " AND ".join(where)
    query += " ORDER BY r.checked_at DESC"
    with connect() as db:
        return [dict(row) for row in db.execute(query, params).fetchall()]


def topics_recorded(rows):
    """True once a checked paper includes a topic on any question."""
    for row in rows:
        try:
            marking = json.loads(row.get("details") or "{}")
        except (ValueError, AttributeError):
            continue
        if any((question.get("topic") or "").strip() for question in marking.get("questions") or []):
            return True
    return False


def topic_losses(rows):
    """Topics where marks were lost, worst first. Questions checked before topics existed are skipped."""
    buckets = {}
    for row in rows:
        try:
            marking = json.loads(row.get("details") or "{}")
        except ValueError:
            continue
        for question in marking.get("questions") or []:
            topic = (question.get("topic") or "").strip()
            if not topic:
                continue
            max_marks = int(question.get("max_marks") or 0)
            awarded = int(question.get("marks_awarded") or 0)
            bucket = buckets.setdefault(topic, {"lost": 0, "max": 0, "questions": 0})
            bucket["lost"] += max(0, max_marks - awarded)
            bucket["max"] += max_marks
            bucket["questions"] += 1
    ranked = [{"topic": topic, **bucket} for topic, bucket in buckets.items() if bucket["lost"] > 0]
    ranked.sort(key=lambda item: (-item["lost"], item["topic"].lower()))
    return ranked

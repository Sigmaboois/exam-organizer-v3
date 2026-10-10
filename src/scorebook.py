"""
The teacher's score book: classes, students and the results of checked papers.

Stored in a small SQLite database (data/scorebook.db). Classes are grouped under
qualification dividers (IGCSE, O Level, AS, AS & A Level) automatically, based on
the papers their students take.
"""

import json
import os
import sqlite3
from datetime import datetime

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
        return cursor.lastrowid


def rename_class(class_id, name):
    with connect() as db:
        db.execute("UPDATE classes SET name = ? WHERE id = ?", (name.strip(), class_id))


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
        return cursor.lastrowid


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

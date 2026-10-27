"""
Online lessons: each class can have a meeting link (pasted once, e.g. for the whole year), a
WhatsApp group and its lesson times. Shortly before every lesson the link is sent to the group
(see whatsapp.py; the sending loop is in gui_lessons.py).

Stored next to the score book in data/scorebook.db, so deleting a class deletes its lessons too.
"""

import re
from datetime import date, datetime, timedelta

from src import scorebook

WEEKDAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
DEFAULT_MINUTES_BEFORE = 10
SEND_OPTIONS = [(0, "When the lesson starts"), (5, "5 minutes before"), (10, "10 minutes before"),
                (15, "15 minutes before"), (30, "30 minutes before"), (60, "1 hour before"),
                (120, "2 hours before")]
DEFAULT_MESSAGE = ("Hi {class}! Our lesson starts at {time}.\n"
                   "Join on Zoom: {link}\n"
                   "Passcode: {passcode}")
# A message is still worth sending this long after the lesson started (e.g. the PC was asleep)
LATE_LIMIT = timedelta(minutes=10)
# After a failed send, wait this long before trying again
RETRY_AFTER = timedelta(minutes=2)

SCHEMA = """
CREATE TABLE IF NOT EXISTS online_lessons (
    class_id INTEGER PRIMARY KEY REFERENCES classes(id) ON DELETE CASCADE,
    link TEXT NOT NULL DEFAULT '',
    passcode TEXT NOT NULL DEFAULT '',
    group_id TEXT,
    group_name TEXT,
    message TEXT,
    minutes_before INTEGER NOT NULL DEFAULT 10,
    paused INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS lesson_times (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    class_id INTEGER NOT NULL REFERENCES classes(id) ON DELETE CASCADE,
    weekday INTEGER,
    on_date TEXT,
    start TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS lesson_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    class_id INTEGER REFERENCES classes(id) ON DELETE CASCADE,
    lesson_id INTEGER,
    lesson_at TEXT,
    sent_at TEXT NOT NULL,
    ok INTEGER NOT NULL,
    detail TEXT,
    UNIQUE (lesson_id, lesson_at)
);
"""
# weekday: 0 = Monday, for lessons every week. on_date: YYYY-MM-DD for a one-off lesson.
# lesson_messages keeps one row per lesson (the latest attempt). A "Send now" that went out counts for
# the lesson it was about; failed ones (or with no lesson coming up) have no lesson_id.


def connect():
    db = scorebook.connect()
    db.executescript(SCHEMA)
    return db


# ---------- typing helpers ----------

def parse_time(text):
    """'9', '9:30', '09.30', '9:30 am', '2pm', '14:00' -> '09:30'. Raises ValueError."""
    cleaned = (text or "").strip().lower().replace(" ", "").replace(".", ":")
    match = re.fullmatch(r"(\d{1,2})(?::(\d{2}))?(am|pm)?", cleaned)
    if not match:
        raise ValueError("Type a time like 9:30 or 14:00.")
    hour, minute, half = int(match.group(1)), int(match.group(2) or 0), match.group(3)
    if half:
        if not 1 <= hour <= 12:
            raise ValueError("Type a time like 9:30 am or 2:15 pm.")
        hour = hour % 12 + (12 if half == "pm" else 0)
    if hour > 23 or minute > 59:
        raise ValueError("That isn't a real time. Type one like 9:30 or 14:00.")
    return f"{hour:02d}:{minute:02d}"


def check_link(text):
    """The meeting link, tidied up. Raises ValueError if it isn't a web link."""
    link = (text or "").strip()
    if not link:
        return ""
    if not re.match(r"https?://", link, re.I):
        if re.match(r"[\w.-]+\.(zoom\.us|zoom\.com)/", link, re.I):
            link = "https://" + link
        else:
            raise ValueError("Paste the whole meeting link, starting with https://")
    if re.search(r"\s", link):
        raise ValueError("The link has a space in it. Copy it again from Zoom.")
    return link


def describe_time(row):
    """'Every Sunday at 09:00' or 'Once, Thu 23 Oct 2026 at 15:30'."""
    if row.get("on_date"):
        day = date.fromisoformat(row["on_date"]).strftime("%a %d %b %Y")
        return f"Once, {day} at {row['start']}"
    return f"Every {WEEKDAYS[row['weekday']]} at {row['start']}"


# ---------- setup ----------

def get_setup(class_id):
    with connect() as db:
        row = db.execute("SELECT * FROM online_lessons WHERE class_id = ?", (class_id,)).fetchone()
    setup = dict(row) if row else {"class_id": class_id, "link": "", "passcode": "", "group_id": None,
                                   "group_name": None, "message": None,
                                   "minutes_before": DEFAULT_MINUTES_BEFORE, "paused": 0}
    setup["message"] = setup["message"] or DEFAULT_MESSAGE
    return setup


def save_setup(class_id, **changes):
    """Save some of: link, passcode, group_id, group_name, message, minutes_before, paused."""
    allowed = {"link", "passcode", "group_id", "group_name", "message", "minutes_before", "paused"}
    unknown = set(changes) - allowed
    if unknown:
        raise ValueError(f"Unknown setting: {', '.join(sorted(unknown))}")
    if "link" in changes:
        changes["link"] = check_link(changes["link"])
    with connect() as db:
        db.execute("INSERT OR IGNORE INTO online_lessons (class_id) VALUES (?)", (class_id,))
        for key, value in changes.items():
            db.execute(f"UPDATE online_lessons SET {key} = ? WHERE class_id = ?", (value, class_id))


def lesson_times(class_id):
    with connect() as db:
        rows = db.execute("SELECT * FROM lesson_times WHERE class_id = ?", (class_id,)).fetchall()
    rows = [dict(row) for row in rows]
    # Weekly lessons Monday to Sunday, then one-off dates
    rows.sort(key=lambda row: (row["on_date"] is not None, row["on_date"] or "", row["weekday"] or 0, row["start"]))
    return rows


def add_lesson_time(class_id, start, weekday=None, on_date=None):
    start = parse_time(start)
    if (weekday is None) == (on_date is None):
        raise ValueError("Choose a day of the week or a date.")
    if on_date is not None:
        on_date = on_date.isoformat() if isinstance(on_date, date) else str(on_date)
        if datetime.combine(date.fromisoformat(on_date), datetime.strptime(start, "%H:%M").time()) < datetime.now():
            raise ValueError("That date and time have already passed.")
    with connect() as db:
        duplicate = db.execute("""SELECT 1 FROM lesson_times WHERE class_id = ? AND start = ?
                                  AND weekday IS ? AND on_date IS ?""", (class_id, start, weekday, on_date)).fetchone()
        if duplicate:
            raise ValueError("That lesson time is already in the list.")
        db.execute("INSERT INTO lesson_times (class_id, weekday, on_date, start) VALUES (?, ?, ?, ?)",
                   (class_id, weekday, on_date, start))


def delete_lesson_time(lesson_id):
    with connect() as db:
        db.execute("DELETE FROM lesson_times WHERE id = ?", (lesson_id,))


def classes_overview():
    """Every class with its setup and lesson times, for the list on the left."""
    with connect() as db:
        classes = [dict(row) for row in db.execute(
            "SELECT id, name FROM classes ORDER BY name COLLATE NOCASE").fetchall()]
    for school_class in classes:
        school_class["setup"] = get_setup(school_class["id"])
        school_class["times"] = lesson_times(school_class["id"])
        school_class["sent"] = sent_lessons(school_class["id"])
    return classes


def sent_lessons(class_id):
    """{(lesson_id, 'YYYY-MM-DDTHH:MM')} of lessons whose link already went out."""
    with connect() as db:
        rows = db.execute("""SELECT lesson_id, lesson_at FROM lesson_messages
                             WHERE class_id = ? AND ok = 1 AND lesson_id IS NOT NULL""", (class_id,)).fetchall()
    return {(row["lesson_id"], row["lesson_at"]) for row in rows}


# ---------- the message ----------

def render_message(setup, class_name, lesson_at=None):
    """Fill in the teacher's message. Lines about a passcode are left out when there isn't one."""
    lesson_at = lesson_at or datetime.now()
    values = {
        "class": class_name,
        "link": setup.get("link") or "",
        "passcode": (setup.get("passcode") or "").strip(),
        "time": lesson_at.strftime("%H:%M"),
        "day": lesson_at.strftime("%A"),
        "date": lesson_at.strftime("%d %B"),
    }
    lines = []
    for line in (setup.get("message") or DEFAULT_MESSAGE).splitlines():
        if "{passcode}" in line and not values["passcode"]:
            continue
        for key, value in values.items():
            line = line.replace("{" + key + "}", value)
        lines.append(line)
    text = "\n".join(lines).strip()
    if values["link"] and values["link"] not in text:
        text += "\n" + values["link"]   # the link is the whole point, never leave it out
    return text


def problems(setup, times):
    """What's still missing before messages can go out automatically (empty list = ready)."""
    missing = []
    if not setup.get("link"):
        missing.append("the meeting link")
    if not setup.get("group_id"):
        missing.append("a WhatsApp group")
    if not times:
        missing.append("a lesson time")
    return missing


# ---------- when to send ----------

def lesson_starts(row, around):
    """Start times of this lesson on the day before, the day of and the day after `around`."""
    start = datetime.strptime(row["start"], "%H:%M").time()
    if row.get("on_date"):
        return [datetime.combine(date.fromisoformat(row["on_date"]), start)]
    starts = []
    for offset in (-1, 0, 1):
        day = around.date() + timedelta(days=offset)
        if day.weekday() == row["weekday"]:
            starts.append(datetime.combine(day, start))
    return starts


def next_message(setup, times, now=None, sent=()):
    """(send_at, lesson_at, row) of the next automatic message, or None. Lessons in `sent` are skipped."""
    now = now or datetime.now()
    best = None
    before = timedelta(minutes=setup.get("minutes_before") or 0)
    for row in times:
        if row.get("on_date"):
            candidates = lesson_starts(row, now)
        else:
            candidates = [datetime.combine(now.date() + timedelta(days=offset),
                                           datetime.strptime(row["start"], "%H:%M").time())
                          for offset in range(8)
                          if (now.date() + timedelta(days=offset)).weekday() == row["weekday"]]
        for lesson_at in candidates:
            if lesson_at + LATE_LIMIT <= now or (row["id"], lesson_at.isoformat(timespec="minutes")) in sent:
                continue
            send_at = lesson_at - before
            if best is None or send_at < best[0]:
                best = (send_at, lesson_at, row)
    return best


def due_messages(now=None):
    """Messages that should go out now: [{class_id, class_name, setup, lesson, lesson_at}]."""
    now = now or datetime.now()
    due = []
    with connect() as db:
        attempts = {(row["lesson_id"], row["lesson_at"]): dict(row) for row in db.execute(
            "SELECT * FROM lesson_messages WHERE lesson_id IS NOT NULL AND lesson_at >= ?",
            ((now - timedelta(days=2)).isoformat(timespec="minutes"),)).fetchall()}
    for school_class in classes_overview():
        setup, times = school_class["setup"], school_class["times"]
        if setup["paused"] or problems(setup, times):
            continue
        before = timedelta(minutes=setup["minutes_before"] or 0)
        for row in times:
            for lesson_at in lesson_starts(row, now):
                if not (lesson_at - before <= now < lesson_at + LATE_LIMIT):
                    continue
                attempt = attempts.get((row["id"], lesson_at.isoformat(timespec="minutes")))
                if attempt and (attempt["ok"] or now - datetime.fromisoformat(attempt["sent_at"]) < RETRY_AFTER):
                    continue
                due.append({"class_id": school_class["id"], "class_name": school_class["name"],
                            "setup": setup, "lesson": row, "lesson_at": lesson_at})
    return due


def record(class_id, lesson_id, lesson_at, ok, detail=""):
    """Remember how sending went. One row per lesson: a retry replaces the failed attempt."""
    sent_at = datetime.now().isoformat(timespec="seconds")
    lesson_text = lesson_at.isoformat(timespec="minutes") if lesson_at else None
    with connect() as db:
        if lesson_id is None:
            db.execute("""INSERT INTO lesson_messages (class_id, lesson_id, lesson_at, sent_at, ok, detail)
                          VALUES (?, NULL, ?, ?, ?, ?)""", (class_id, lesson_text, sent_at, int(ok), detail))
        else:
            db.execute("""INSERT INTO lesson_messages (class_id, lesson_id, lesson_at, sent_at, ok, detail)
                          VALUES (?, ?, ?, ?, ?, ?)
                          ON CONFLICT (lesson_id, lesson_at) DO UPDATE
                          SET sent_at = excluded.sent_at, ok = excluded.ok, detail = excluded.detail""",
                       (class_id, lesson_id, lesson_text, sent_at, int(ok), detail))


def recent_messages(class_id, limit=6):
    with connect() as db:
        rows = db.execute("""SELECT * FROM lesson_messages WHERE class_id = ?
                             ORDER BY sent_at DESC LIMIT ?""", (class_id, limit)).fetchall()
    return [dict(row) for row in rows]

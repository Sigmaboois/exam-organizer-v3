"""Pieces shared by every screen of the app: layout helpers, the drop area, file helpers."""

import os
import subprocess
import sys

import flet as ft
import flet_dropzone as ftd

BRAND_COLOR = "#2575FC"
STAGING_DIR_NAME = ".exam-organizer-staging"
PAPER_TYPES = {"QP": "Question paper", "MS": "Mark scheme"}

# Flet only sets FLET_PLATFORM inside apps made with `flet build`, which is also
# the only place the Dropzone extension exists
DROP_SUPPORTED = os.getenv("FLET_PLATFORM") is not None

# Where marked copies of checked papers are saved
MARKED_DIR = os.path.join(os.path.expanduser("~"), "Documents", "Exam Organizer", "Marked")


# --------------------------------------------------------------------------
# Layout
# --------------------------------------------------------------------------

def section_title(number, text):
    badge = ft.Container(
        ft.Text(str(number), color=ft.Colors.ON_PRIMARY, weight=ft.FontWeight.BOLD, size=13),
        width=26, height=26, border_radius=ft.BorderRadius.all(13),
        bgcolor=ft.Colors.PRIMARY, alignment=ft.Alignment.CENTER,
    )
    return ft.Row([badge, ft.Text(text, size=16, weight=ft.FontWeight.W_600)], spacing=10)


def card(content, expand=False, width=None):
    return ft.Container(
        content=content,
        padding=ft.Padding.all(18),
        border_radius=ft.BorderRadius.all(16),
        bgcolor=ft.Colors.SURFACE_CONTAINER,
        expand=expand,
        width=width,
    )


def row_box(content):
    """The rounded box used for each item in a list."""
    return ft.Container(
        content=content,
        padding=ft.Padding.symmetric(horizontal=12, vertical=10),
        border_radius=ft.BorderRadius.all(12),
        bgcolor=ft.Colors.SURFACE_CONTAINER_LOW,
        border=ft.Border.all(1, ft.Colors.OUTLINE_VARIANT),
    )


def pill(text, colour=ft.Colors.PRIMARY, icon=None):
    """A small rounded label, e.g. "Has answers"."""
    items = [ft.Text(text, size=11, weight=ft.FontWeight.W_600, color=colour)]
    if icon:
        items.insert(0, ft.Icon(icon, size=13, color=colour))
    return ft.Container(
        content=ft.Row(items, spacing=4, tight=True),
        padding=ft.Padding.symmetric(horizontal=8, vertical=3),
        border_radius=ft.BorderRadius.all(20),
        bgcolor=ft.Colors.with_opacity(0.12, colour),
    )


def empty_state(icon, title, text):
    return ft.Container(
        content=ft.Column([
            ft.Icon(icon, size=48, color=ft.Colors.OUTLINE),
            ft.Text(title, size=16, weight=ft.FontWeight.W_600),
            ft.Text(text, color=ft.Colors.ON_SURFACE_VARIANT, text_align=ft.TextAlign.CENTER),
        ], horizontal_alignment=ft.CrossAxisAlignment.CENTER, alignment=ft.MainAxisAlignment.CENTER, spacing=6),
        alignment=ft.Alignment.CENTER,
        expand=True,
    )


def drop_area(on_paths, title, subtitle, height=210, icon=ft.Icons.CLOUD_UPLOAD_OUTLINED):
    """A big drop target for files and folders. on_paths(list_of_paths) is awaited on drop."""
    title_text = ft.Text(title, size=18, weight=ft.FontWeight.W_600, text_align=ft.TextAlign.CENTER)
    subtitle_text = ft.Text(subtitle, size=13, color=ft.Colors.ON_SURFACE_VARIANT, text_align=ft.TextAlign.CENTER)
    area = ft.Container(
        content=ft.Column([ft.Icon(icon, size=52, color=ft.Colors.PRIMARY), title_text, subtitle_text],
                          horizontal_alignment=ft.CrossAxisAlignment.CENTER,
                          alignment=ft.MainAxisAlignment.CENTER, spacing=8),
        alignment=ft.Alignment.CENTER,
        height=height,
        border_radius=ft.BorderRadius.all(16),
        border=ft.Border.all(2, ft.Colors.OUTLINE_VARIANT),
        bgcolor=ft.Colors.SURFACE_CONTAINER_LOW,
        animate=ft.Animation(180, ft.AnimationCurve.EASE_OUT),
    )

    if not DROP_SUPPORTED:
        # `py main.py` / `flet run` use the stock Flet client, which doesn't include
        # the drop extension. Only `flet build` apps can receive files from Explorer.
        title_text.value = "Use the buttons below to add files"
        subtitle_text.value = "Drag & drop works in the built app\n(build.ps1)."
        return area

    def highlight(active):
        area.border = ft.Border.all(2, ft.Colors.PRIMARY if active else ft.Colors.OUTLINE_VARIANT)
        area.bgcolor = ft.Colors.PRIMARY_CONTAINER if active else ft.Colors.SURFACE_CONTAINER_LOW
        title_text.value = "Let go to add them" if active else title
        area.update()

    async def dropped(e: ftd.DropzoneEvent):
        highlight(False)
        await on_paths([file.path for file in e.files if file.path])

    return ftd.Dropzone(content=area, on_dropped=dropped,
                        on_entered=lambda e: highlight(True), on_exited=lambda e: highlight(False))


# --------------------------------------------------------------------------
# Files
# --------------------------------------------------------------------------

def find_pdfs(paths):
    """Expand files and folders into a list of PDF paths. Returns (pdfs, skipped_count)."""
    pdfs, skipped = [], 0
    for path in paths:
        if os.path.isdir(path):
            for root, dirs, files in os.walk(path):
                dirs[:] = [d for d in dirs if d != STAGING_DIR_NAME]
                for name in sorted(files):
                    if name.lower().endswith(".pdf"):
                        pdfs.append(os.path.join(root, name))
                    else:
                        skipped += 1
        elif path.lower().endswith(".pdf"):
            pdfs.append(path)
        else:
            skipped += 1
    return pdfs, skipped


def same_file_key(path):
    return os.path.normcase(os.path.abspath(path))


def open_in_explorer(path, select=False):
    if sys.platform == "win32":
        if select and os.path.isfile(path):
            subprocess.Popen(["explorer", "/select,", os.path.normpath(path)])
        else:
            os.startfile(path)
    elif sys.platform == "darwin":
        subprocess.Popen(["open", "-R", path] if select else ["open", path])
    else:
        subprocess.Popen(["xdg-open", os.path.dirname(path) if select else path])


def safe_name(text):
    """Make text safe to use as a Windows file or folder name."""
    cleaned = "".join("-" if c in '<>:"/\\|?*' else c for c in str(text)).strip(" .")
    return cleaned or "Untitled"


def friendly_error(error):
    if isinstance(error, FileExistsError):
        return "Already sorted. An exam with the same name is already in the destination folder."
    if isinstance(error, PermissionError):
        return "Windows blocked access to this file. Close it if it's open in another program, then try again."
    if isinstance(error, FileNotFoundError):
        return "This file was moved or deleted before I could use it."
    if getattr(error, "winerror", None) == 206 or "too long" in str(error).lower():
        return ("The folder path is too long for Windows. Pick a destination closer to the top of "
                "your drive, like C:\\Exams.")
    if type(error).__module__.startswith("pypdf"):
        return "This PDF couldn't be opened. It may be damaged or password-protected."
    return f"Something went wrong: {error}"


def describe_paper(meta, with_type=False):
    """'IGCSE · May–June 2024 · Paper 4, variant 2' for display."""
    if not meta or meta.get("subject_code") is None:
        return ""
    parts = []
    if meta.get("qualification"):
        parts.append(meta["qualification"])
    if meta.get("session") and meta.get("year"):
        parts.append(f"{meta['session'].replace('-', '–')} {meta['year']}")
    if meta.get("paper"):
        parts.append(f"Paper {meta['paper']}" + (f", variant {meta['variant']}" if meta.get("variant") else ""))
    if with_type and meta.get("paper_type"):
        parts.append(PAPER_TYPES.get(meta["paper_type"], meta["paper_type"]))
    return " · ".join(parts)


def paper_title(meta, fallback=""):
    """'Physics (0625)' for display."""
    if not meta or meta.get("subject_code") is None:
        return fallback
    return f"{meta.get('subject_name') or 'Unknown subject'} ({meta['subject_code']})"

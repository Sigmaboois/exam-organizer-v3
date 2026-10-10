"""
Exam Organizer desktop app.

Three screens, picked from the side menu:
    Organise      drop Cambridge papers, check what was detected, sort them into folders
    Check papers  mark solved papers against the official mark scheme with an AI examiner
    Classes       classes, students and their scores (the score book)
"""

import asyncio
import os
import webbrowser

import flet as ft

from src import app_tasks as tasks
from src.gui_check import CheckScreen
from src.gui_classes import ClassesScreen
from src.gui_common import BRAND_COLOR, MARKED_DIR, open_in_explorer
from src.gui_organise import OrganiseScreen

APP_NAME = "Exam Organizer"
GET_KEY_URL = "https://aistudio.google.com/apikey"
SCREENS = ["organise", "check", "classes"]


class App:
    """Shared things every screen needs: the page, settings, file picker and messages."""

    def __init__(self, page):
        self.page = page
        self.prefs = ft.SharedPreferences()
        self.picker = ft.FilePicker()
        self.backend_ready = asyncio.Event()
        self.gemini_key = None   # a teacher's own key from Settings, if they added one
        self.organise = self.check = self.classes = None
        self.go_to = lambda name: None

    def toast(self, message, error=False):
        self.page.show_dialog(ft.SnackBar(
            ft.Text(message, color=ft.Colors.ON_ERROR_CONTAINER if error else None),
            bgcolor=ft.Colors.ERROR_CONTAINER if error else None,
            show_close_icon=True,
            behavior=ft.SnackBarBehavior.FLOATING,
        ))


async def main(page: ft.Page):
    page.title = APP_NAME
    page.theme = ft.Theme(color_scheme_seed=BRAND_COLOR, use_material3=True)
    page.dark_theme = ft.Theme(color_scheme_seed=BRAND_COLOR, use_material3=True)
    page.window.width = 1240
    page.window.height = 820
    page.window.min_width = 980
    page.window.min_height = 640
    page.padding = 0

    app = App(page)
    app.organise = OrganiseScreen(app)
    app.check = CheckScreen(app)
    app.classes = ClassesScreen(app)
    screens = {"organise": app.organise, "check": app.check, "classes": app.classes}

    # ---------- side menu ----------

    content = ft.Container(content=app.organise.view, expand=True,
                           padding=ft.Padding.only(left=8, right=24, bottom=24))

    def go_to(name):
        content.content = screens[name].view
        rail.selected_index = SCREENS.index(name)
        page.update()

    app.go_to = go_to
    rail = ft.NavigationRail(
        selected_index=0,
        label_type=ft.NavigationRailLabelType.ALL,
        min_width=96,
        bgcolor=ft.Colors.TRANSPARENT,
        destinations=[
            ft.NavigationRailDestination(icon=ft.Icons.FOLDER_COPY_OUTLINED, selected_icon=ft.Icons.FOLDER_COPY,
                                         label="Organise"),
            ft.NavigationRailDestination(icon=ft.Icons.FACT_CHECK_OUTLINED, selected_icon=ft.Icons.FACT_CHECK,
                                         label="Check papers"),
            ft.NavigationRailDestination(icon=ft.Icons.GROUPS_OUTLINED, selected_icon=ft.Icons.GROUPS,
                                         label="Classes"),
        ],
        on_change=lambda e: go_to(SCREENS[e.control.selected_index]),
    )

    # ---------- header ----------

    theme_button = ft.IconButton(icon=ft.Icons.DARK_MODE, tooltip="Switch to dark mode")

    def apply_theme(dark):
        page.theme_mode = ft.ThemeMode.DARK if dark else ft.ThemeMode.LIGHT
        theme_button.icon = ft.Icons.LIGHT_MODE if dark else ft.Icons.DARK_MODE
        theme_button.tooltip = "Switch to light mode" if dark else "Switch to dark mode"

    async def toggle_theme(e):
        dark = page.theme_mode != ft.ThemeMode.DARK
        apply_theme(dark)
        page.update()
        await app.prefs.set("dark_mode", dark)

    theme_button.on_click = toggle_theme

    def show_help(e):
        def heading(text):
            return ft.Text(text, weight=ft.FontWeight.BOLD)

        def note(text):
            return ft.Text(text, size=13, color=ft.Colors.ON_SURFACE_VARIANT)

        page.show_dialog(ft.AlertDialog(
            icon=ft.Icon(ft.Icons.HELP_OUTLINE),
            title=ft.Text("How it works"),
            content=ft.Container(width=520, content=ft.Column([
                heading("Organise"),
                ft.Text("Drag Cambridge papers onto the window (whole folders are fine), check what I found, "
                        "choose a destination and press Sort. Papers with answers written on them get a "
                        "\"Check it\" button."),
                heading("Check papers"),
                ft.Text("Add students' solved papers, choose whose paper each one is, and press Check. I fetch "
                        "the official mark scheme, an AI examiner marks every question, and you get the score, "
                        "the Cambridge grade, feedback and a marked PDF copy."),
                heading("Classes"),
                ft.Text("Create classes and add students. Checked papers are saved to each student's scores, "
                        "and you can export everything to a spreadsheet."),
                ft.Divider(),
                note("Works with Cambridge (CAIE) IGCSE, O Level and AS & A Level question papers and mark schemes."),
                note("With \"Keep the originals\" on, your files stay where they are and copies are sorted. "
                     "Nothing is ever overwritten."),
                note("Checking uses Google's free Gemini AI. On the free tier Google may use what is sent "
                     "(the student's answers) to improve its products. The AI can make mistakes, so anything "
                     "it's unsure about is flagged for you to double-check."),
                note("The first launch needs the internet to download Cambridge's subject list."),
            ], tight=True, spacing=10, scroll=ft.ScrollMode.AUTO)),
            actions=[ft.TextButton("Got it", on_click=lambda e: page.pop_dialog())],
        ))

    def show_settings(e):
        key_field = ft.TextField(label="Your own Gemini key (optional)", password=True, can_reveal_password=True,
                                 value=app.gemini_key or "", hint_text="Paste a key that starts with AIza…")

        async def save(e):
            app.gemini_key = (key_field.value or "").strip() or None
            await app.prefs.set("gemini_key", app.gemini_key or "")
            page.pop_dialog()
            app.toast("Settings saved.")

        page.show_dialog(ft.AlertDialog(
            icon=ft.Icon(ft.Icons.SETTINGS_OUTLINED),
            title=ft.Text("Settings"),
            content=ft.Container(width=500, content=ft.Column([
                ft.Text("AI checking", weight=ft.FontWeight.BOLD),
                ft.Text("The app comes with a free key that everyone shares. If checking says the free limit is "
                        "reached, make your own free key (takes 2 minutes with a Google account) and paste it "
                        "here. Leave it empty to use the shared key.", size=13),
                ft.TextButton("Get a free key", icon=ft.Icons.KEY, on_click=lambda e: webbrowser.open(GET_KEY_URL)),
                key_field,
                ft.Divider(),
                ft.Text("Marked copies", weight=ft.FontWeight.BOLD),
                ft.Text(f"Saved in {MARKED_DIR}", size=13, selectable=True),
                ft.TextButton("Open the folder", icon=ft.Icons.FOLDER_OPEN, on_click=lambda e: (
                    os.makedirs(MARKED_DIR, exist_ok=True), open_in_explorer(MARKED_DIR))),
            ], tight=True, spacing=10)),
            actions=[ft.TextButton("Cancel", on_click=lambda e: page.pop_dialog()),
                     ft.FilledButton("Save", on_click=save)],
        ))

    async def update_subjects(e):
        if not app.backend_ready.is_set():
            app.toast("Still loading the subject list, please wait a moment.")
            return
        app.toast("Updating the subject list from Cambridge…")
        try:
            await asyncio.to_thread(tasks.refresh_subject_list)
            app.toast("Subject list is up to date.")
        except Exception:
            app.toast("Couldn't reach the Cambridge website. Check your internet connection and try again.",
                      error=True)

    header = ft.Container(
        content=ft.Row([
            ft.Container(ft.Icon(ft.Icons.SCHOOL, color=ft.Colors.ON_PRIMARY, size=26),
                         width=46, height=46, border_radius=ft.BorderRadius.all(14),
                         bgcolor=ft.Colors.PRIMARY, alignment=ft.Alignment.CENTER),
            ft.Column([
                ft.Text(APP_NAME, size=22, weight=ft.FontWeight.BOLD),
                ft.Text("Sort, check and track Cambridge past papers", size=13, color=ft.Colors.ON_SURFACE_VARIANT),
            ], spacing=0, expand=True),
            ft.IconButton(icon=ft.Icons.SYNC, tooltip="Update the subject list from Cambridge", on_click=update_subjects),
            ft.IconButton(icon=ft.Icons.SETTINGS_OUTLINED, tooltip="Settings", on_click=show_settings),
            ft.IconButton(icon=ft.Icons.HELP_OUTLINE, tooltip="How it works", on_click=show_help),
            theme_button,
        ], spacing=14),
        padding=ft.Padding.only(left=24, right=16, top=16, bottom=8),
    )

    # Shown while the subject list loads (or if it fails)
    banner_ring = ft.ProgressRing(width=16, height=16, stroke_width=2)
    banner_text = ft.Text("Getting ready… downloading Cambridge's subject list (first launch only).", expand=True)
    banner_retry = ft.TextButton("Try again", visible=False)
    banner = ft.Container(
        content=ft.Row([banner_ring, banner_text, banner_retry], spacing=12),
        padding=ft.Padding.symmetric(horizontal=16, vertical=10),
        margin=ft.Margin.only(left=24, right=24, bottom=8),
        border_radius=ft.BorderRadius.all(12),
        bgcolor=ft.Colors.SECONDARY_CONTAINER,
        visible=False,
    )

    async def start_backend(e=None):
        banner.visible = True
        banner.bgcolor = ft.Colors.SECONDARY_CONTAINER
        banner_text.value = "Getting ready… downloading Cambridge's subject list (first launch only)."
        banner_retry.visible = False
        banner_ring.visible = True
        page.update()
        try:
            await asyncio.to_thread(tasks.load_backend)
        except Exception:
            banner_text.value = ("I couldn't download Cambridge's subject list. Check your internet "
                                 "connection; this is only needed the first time.")
            banner_retry.visible = True
            banner_ring.visible = False
            banner.bgcolor = ft.Colors.ERROR_CONTAINER
            page.update()
            return
        app.backend_ready.set()
        banner.visible = False
        await app.classes.refresh()
        app.organise.refresh_summary()
        app.check.refresh_summary()
        page.update()

    banner_retry.on_click = start_backend

    # ---------- layout ----------

    page.add(ft.SafeArea(expand=True, content=ft.Column([
        header,
        banner,
        ft.Row([rail, content], spacing=0, expand=True, vertical_alignment=ft.CrossAxisAlignment.STRETCH),
    ], spacing=0, expand=True)))

    # ---------- restore saved settings, then load the backend ----------

    apply_theme(bool(await app.prefs.get("dark_mode")))
    app.gemini_key = (await app.prefs.get("gemini_key")) or None
    await app.organise.restore_settings()
    page.update()

    app.organise.start_workers()
    app.check.start_workers()
    await start_backend()


def run():
    ft.run(main)

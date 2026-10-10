"""The "Organise" screen: add exam PDFs, check what was detected, sort them into folders."""

import asyncio
import os

import flet as ft

from src import app_tasks as tasks
from src.gui_common import (PAPER_TYPES, card, describe_paper, drop_area, empty_state, find_pdfs,
                            friendly_error, open_in_explorer, pill, same_file_key, section_title)


class ExamFile:
    """One row in the Organise list."""

    def __init__(self, path, on_remove, on_check):
        self.path = path
        self.name = os.path.basename(path)
        self.status = "waiting"   # waiting, reading, ready, problem, sorting, sorted, skipped
        self.metadata = None
        self.message = ""
        self.sorted_path = None
        self.answers = None       # None = not looked at yet, otherwise a SolveResult
        self._on_remove = on_remove
        self._on_check = on_check

        self.icon_slot = ft.Container(width=28, alignment=ft.Alignment.CENTER)
        self.title = ft.Text(self.name, weight=ft.FontWeight.W_600, max_lines=1,
                             overflow=ft.TextOverflow.ELLIPSIS, tooltip=path)
        self.details = ft.Text("", size=12, color=ft.Colors.ON_SURFACE_VARIANT)
        self.note = ft.Text("", size=12, visible=False)
        self.answers_pill = ft.Container(visible=False)
        self.check_button = ft.TextButton("Check it", icon=ft.Icons.FACT_CHECK_OUTLINED, visible=False,
                                          tooltip="Mark this paper against the mark scheme",
                                          on_click=lambda e: self._on_check(self))
        self.action = ft.IconButton(icon=ft.Icons.CLOSE, icon_size=18, tooltip="Remove from the list",
                                    on_click=self._action_clicked)
        self.row = ft.Container(
            content=ft.Row(
                [self.icon_slot,
                 ft.Column([self.title, self.details, ft.Row([self.answers_pill], spacing=6), self.note],
                           spacing=2, expand=True),
                 self.check_button,
                 self.action],
                vertical_alignment=ft.CrossAxisAlignment.CENTER,
            ),
            padding=ft.Padding.symmetric(horizontal=12, vertical=10),
            border_radius=ft.BorderRadius.all(12),
            bgcolor=ft.Colors.SURFACE_CONTAINER_LOW,
            border=ft.Border.all(1, ft.Colors.OUTLINE_VARIANT),
        )
        self.render()

    @property
    def current_path(self):
        """Where the file is now (the sorted copy if it has been sorted)."""
        return self.sorted_path if self.status == "sorted" and self.sorted_path else self.path

    def _action_clicked(self, e):
        if self.status == "sorted" and self.sorted_path:
            open_in_explorer(self.sorted_path, select=True)
        else:
            self._on_remove(self)

    def render(self):
        meta = self.metadata or {}
        if meta.get("subject_code"):
            subject = meta.get("subject_name") or "Unknown subject"
            kind = PAPER_TYPES.get(meta.get("paper_type"), "")
            self.title.value = f"{subject} ({meta['subject_code']})" + (f"  ·  {kind}" if kind else "")
        else:
            self.title.value = self.name
        self.details.value = describe_paper(meta)

        self.note.visible = bool(self.message)
        self.note.value = self.message
        self.note.color = ft.Colors.ON_SURFACE_VARIANT
        self.action.icon = ft.Icons.CLOSE
        self.action.tooltip = "Remove from the list"
        self.action.visible = True
        self.row.border = ft.Border.all(1, ft.Colors.OUTLINE_VARIANT)

        if self.status in ("waiting", "reading", "sorting"):
            self.icon_slot.content = ft.ProgressRing(width=18, height=18, stroke_width=2.5)
            if self.status == "waiting":
                self.details.value = "Waiting to be read…"
            elif self.status == "reading":
                self.details.value = "Reading the paper…"
            else:
                self.details.value = describe_paper(meta) or "Sorting…"
            self.action.visible = self.status == "waiting"
        elif self.status == "ready":
            self.icon_slot.content = ft.Icon(ft.Icons.CHECK_CIRCLE_OUTLINE, color=ft.Colors.PRIMARY, size=22)
        elif self.status == "sorted":
            self.icon_slot.content = ft.Icon(ft.Icons.CHECK_CIRCLE, color=ft.Colors.GREEN_600, size=22)
            self.note.visible = True
            self.note.value = "Sorted ✓"
            self.note.color = ft.Colors.GREEN_700
            self.action.icon = ft.Icons.FOLDER_OPEN
            self.action.tooltip = "Show in folder"
        else:  # problem / skipped
            self.icon_slot.content = ft.Icon(ft.Icons.WARNING_AMBER_ROUNDED, color=ft.Colors.AMBER_800, size=22)
            self.note.color = ft.Colors.AMBER_900
            self.row.border = ft.Border.all(1, ft.Colors.with_opacity(0.5, ft.Colors.AMBER_800))

        solved = bool(self.answers and self.answers.solved)
        self.answers_pill.visible = solved
        if solved:
            pages = len(self.answers.pages)
            self.answers_pill.content = pill(f"Has answers on {pages} page{'s' if pages != 1 else ''}",
                                             ft.Colors.TERTIARY, ft.Icons.DRAW)
        self.check_button.visible = solved and self.status not in ("sorting", "waiting", "reading")
        self.details.visible = bool(self.details.value)


class OrganiseScreen:
    def __init__(self, app):
        self.app = app
        self.files = []
        self.sorting = False
        self.analysis_queue = asyncio.Queue()
        self.answers_queue = asyncio.Queue()
        self.destination = None
        self.keep_original = True
        self._build()

    # ---------- layout ----------

    def _build(self):
        app = self.app
        add_buttons = ft.Row([
            ft.OutlinedButton("Choose files", icon=ft.Icons.UPLOAD_FILE, on_click=self._choose_files, expand=True),
            ft.OutlinedButton("Choose a folder", icon=ft.Icons.DRIVE_FOLDER_UPLOAD,
                              on_click=self._choose_source_folder, expand=True),
        ], spacing=10)
        step_add = card(ft.Column([
            section_title(1, "Add exams"),
            drop_area(self.add_paths, "Drag & drop exam PDFs here",
                      "One file, lots of files, or whole folders.\nI'll find every PDF inside."),
            add_buttons,
        ], spacing=14))

        self.destination_text = ft.Text("No folder chosen yet", max_lines=2, overflow=ft.TextOverflow.ELLIPSIS,
                                        color=ft.Colors.ON_SURFACE_VARIANT, expand=True)
        self.keep_switch = ft.Switch(label="Keep the originals where they are", value=True,
                                     on_change=self._keep_changed)
        self.keep_hint = ft.Text("Copies are sorted and your files stay put.", size=12,
                                 color=ft.Colors.ON_SURFACE_VARIANT)
        step_destination = card(ft.Column([
            section_title(2, "Choose where they go"),
            ft.Container(
                content=ft.Row([ft.Icon(ft.Icons.FOLDER, color=ft.Colors.PRIMARY), self.destination_text], spacing=10),
                padding=ft.Padding.symmetric(horizontal=12, vertical=12),
                border_radius=ft.BorderRadius.all(12),
                bgcolor=ft.Colors.SURFACE_CONTAINER_LOW,
                border=ft.Border.all(1, ft.Colors.OUTLINE_VARIANT),
            ),
            ft.OutlinedButton("Choose destination folder", icon=ft.Icons.FOLDER_OPEN, on_click=self._choose_destination),
            ft.Divider(height=8),
            self.keep_switch,
            self.keep_hint,
        ], spacing=12))

        self.summary_text = ft.Text("", size=13, color=ft.Colors.ON_SURFACE_VARIANT)
        self.clear_button = ft.TextButton("Clear list", icon=ft.Icons.CLEAR_ALL, on_click=self._clear_list)
        self.file_list = ft.ListView(spacing=8, expand=True, padding=ft.Padding.only(right=8))
        self.empty = empty_state(ft.Icons.PICTURE_AS_PDF, "No exams yet",
                                 "Exams you add will show up here so you can check them before sorting.")
        self.list_holder = ft.Container(content=self.empty, expand=True)
        self.progress = ft.ProgressBar(value=0, visible=False, bar_height=6, border_radius=ft.BorderRadius.all(3))
        self.result_text = ft.Text("", size=13, expand=True)
        self.open_result_button = ft.TextButton("Open folder", icon=ft.Icons.OPEN_IN_NEW, visible=False,
                                                on_click=lambda e: open_in_explorer(self.destination))
        self.sort_button = ft.FilledButton(
            "Sort exams", icon=ft.Icons.AUTO_AWESOME, disabled=True, on_click=self._sort_exams,
            style=ft.ButtonStyle(padding=ft.Padding.symmetric(horizontal=28, vertical=20),
                                 text_style=ft.TextStyle(size=16, weight=ft.FontWeight.W_600)),
        )
        step_sort = card(ft.Column([
            ft.Row([section_title(3, "Check and sort"), ft.Container(expand=True), self.clear_button]),
            self.summary_text,
            self.list_holder,
            self.progress,
            ft.Row([self.result_text, self.open_result_button, self.sort_button], spacing=12,
                   vertical_alignment=ft.CrossAxisAlignment.CENTER),
        ], spacing=12, expand=True), expand=True)

        self.view = ft.Row([
            ft.Container(ft.Column([step_add, step_destination], spacing=16, scroll=ft.ScrollMode.AUTO), width=400),
            step_sort,
        ], spacing=16, expand=True, vertical_alignment=ft.CrossAxisAlignment.STRETCH)

    async def restore_settings(self):
        prefs = self.app.prefs
        saved_destination = await prefs.get("destination")
        if saved_destination and os.path.isdir(saved_destination):
            self.destination = saved_destination
        saved_keep = await prefs.get("keep_original")
        if saved_keep is not None:
            self.keep_original = bool(saved_keep)
            self.keep_switch.value = self.keep_original
            self._update_keep_hint()
        self._show_destination()
        self.refresh_summary()

    def start_workers(self):
        self.app.page.run_task(self._analysis_worker)
        self.app.page.run_task(self._answers_worker)

    # ---------- step 1 ----------

    async def _choose_files(self, e):
        files = await self.app.picker.pick_files(dialog_title="Choose exam PDFs", allowed_extensions=["pdf"],
                                                 file_type=ft.FilePickerFileType.CUSTOM, allow_multiple=True)
        if files:
            await self.add_paths([f.path for f in files if f.path])

    async def _choose_source_folder(self, e):
        folder = await self.app.picker.get_directory_path(dialog_title="Choose a folder of exam PDFs")
        if folder:
            await self.add_paths([folder])

    async def add_paths(self, paths):
        pdfs, skipped = await asyncio.to_thread(find_pdfs, paths)
        known = {same_file_key(f.path) for f in self.files}
        added = duplicates = 0
        for path in pdfs:
            key = same_file_key(path)
            if key in known:
                duplicates += 1
                continue
            known.add(key)
            exam = ExamFile(path, on_remove=self._remove_file, on_check=self._send_to_check)
            self.files.append(exam)
            self.file_list.controls.append(exam.row)
            self.analysis_queue.put_nowait(exam)
            added += 1

        self.result_text.value = ""
        self.open_result_button.visible = False
        self.refresh_summary()
        self.app.page.update()

        notes = []
        if added == 0 and not pdfs:
            notes.append("I couldn't find any PDFs there.")
        if duplicates:
            notes.append(f"{duplicates} already in the list.")
        if skipped:
            notes.append(f"Ignored {skipped} file{'s' if skipped != 1 else ''} that "
                         f"{'aren' if skipped != 1 else 'isn'}'t a PDF.")
        if notes:
            self.app.toast(" ".join(notes))

    # ---------- step 2 ----------

    def _show_destination(self):
        dest = self.destination
        self.destination_text.value = dest if dest else "No folder chosen yet"
        self.destination_text.tooltip = dest
        self.destination_text.color = None if dest else ft.Colors.ON_SURFACE_VARIANT
        self.destination_text.weight = ft.FontWeight.W_600 if dest else None

    async def _choose_destination(self, e):
        folder = await self.app.picker.get_directory_path(dialog_title="Where should the sorted exams go?",
                                                          initial_directory=self.destination)
        if folder:
            self.destination = folder
            self._show_destination()
            self.refresh_summary()
            self.app.page.update()
            await self.app.prefs.set("destination", folder)

    def _update_keep_hint(self):
        self.keep_hint.value = ("Copies are sorted and your files stay put." if self.keep_switch.value
                                else "Files are moved into the sorted folders.")

    async def _keep_changed(self, e):
        self.keep_original = self.keep_switch.value
        self._update_keep_hint()
        self.keep_hint.update()
        await self.app.prefs.set("keep_original", self.keep_switch.value)

    # ---------- the list ----------

    def _count(self, *statuses):
        return sum(1 for f in self.files if f.status in statuses)

    def refresh_summary(self):
        files = self.files
        self.list_holder.content = self.file_list if files else self.empty
        ready = self._count("ready")
        parts = []
        if files:
            parts.append(f"{len(files)} exam{'s' if len(files) != 1 else ''}")
            if self._count("waiting", "reading"):
                parts.append(f"{self._count('waiting', 'reading')} being read")
            if ready:
                parts.append(f"{ready} ready to sort")
            if self._count("problem", "skipped"):
                parts.append(f"{self._count('problem', 'skipped')} need attention")
            if self._count("sorted"):
                parts.append(f"{self._count('sorted')} sorted")
            solved = sum(1 for f in files if f.answers and f.answers.solved)
            if solved:
                parts.append(f"{solved} with answers")
        self.summary_text.value = " · ".join(parts)
        self.clear_button.visible = bool(files) and not self.sorting

        self.sort_button.content = f"Sort {ready} exam{'s' if ready != 1 else ''}" if ready else "Sort exams"
        self.sort_button.disabled = (self.sorting or ready == 0 or not self.destination
                                     or not self.app.backend_ready.is_set())
        self.sort_button.tooltip = ("Choose a destination folder first (step 2)"
                                    if ready and not self.destination else None)

    def _remove_file(self, exam):
        if self.sorting or exam not in self.files:
            return
        self.files.remove(exam)
        self.file_list.controls.remove(exam.row)
        self.refresh_summary()
        self.app.page.update()

    def _clear_list(self, e):
        if self.sorting:
            return
        keep = [f for f in self.files if f.status in ("waiting", "reading")]
        self.files = keep
        self.file_list.controls = [f.row for f in keep]
        self.result_text.value = ""
        self.open_result_button.visible = False
        self.refresh_summary()
        self.app.page.update()

    def _send_to_check(self, exam):
        self.app.check.add_paper(exam.current_path, exam.metadata, exam.answers)
        self.app.go_to("check")

    # ---------- background work ----------

    async def _analysis_worker(self):
        await self.app.backend_ready.wait()
        while True:
            exam = await self.analysis_queue.get()
            if exam not in self.files:
                continue
            exam.status = "reading"
            exam.render()
            self.refresh_summary()
            self.app.page.update()
            try:
                exam.metadata, problem = await asyncio.to_thread(tasks.analyse, exam.path)
            except Exception as error:
                exam.metadata, problem = None, friendly_error(error)
            exam.status = "problem" if problem else "ready"
            exam.message = problem or ""
            exam.render()
            if not problem and exam.metadata.get("paper_type") == "QP":
                self.answers_queue.put_nowait(exam)
            self.refresh_summary()
            self.app.page.update()

    async def _answers_worker(self):
        """Look for answers written on question papers, so the teacher can be offered a check."""
        await self.app.backend_ready.wait()
        while True:
            exam = await self.answers_queue.get()
            if exam not in self.files:
                continue
            try:
                exam.answers = await asyncio.to_thread(tasks.detect_answers, exam.current_path, exam.metadata)
            except Exception:
                continue  # answer detection is a bonus; never block sorting because of it
            exam.render()
            self.refresh_summary()
            self.app.page.update()

    async def _sort_exams(self, e):
        destination = self.destination
        to_sort = [f for f in self.files if f.status == "ready"]
        if not to_sort or not destination:
            return
        if not os.path.isdir(destination):
            self.app.toast("The destination folder doesn't exist any more. Please choose it again.", error=True)
            return

        self.sorting = True
        self.progress.visible = True
        self.progress.value = 0
        self.result_text.value = ""
        self.open_result_button.visible = False
        self.refresh_summary()
        self.app.page.update()

        sorted_count = 0
        for index, exam in enumerate(to_sort, start=1):
            exam.status = "sorting"
            exam.render()
            self.app.page.update()
            try:
                exam.sorted_path = await asyncio.to_thread(
                    tasks.place, exam.path, exam.metadata, destination, self.keep_original)
                exam.status = "sorted"
                exam.message = ""
                sorted_count += 1
            except Exception as error:
                exam.status = "skipped"
                exam.message = friendly_error(error)
            exam.render()
            self.progress.value = index / len(to_sort)
            self.refresh_summary()
            self.app.page.update()

        await asyncio.to_thread(tasks.remove_staging_folder, destination)

        # Put anything that needs attention at the top so it isn't missed
        self.files.sort(key=lambda f: 0 if f.status in ("problem", "skipped") else 1)
        self.file_list.controls = [f.row for f in self.files]

        self.sorting = False
        self.progress.visible = False
        skipped = len(to_sort) - sorted_count
        self.result_text.value = f"Done! Sorted {sorted_count} exam{'s' if sorted_count != 1 else ''}"
        self.result_text.value += f". {skipped} couldn't be sorted, see the top of the list." if skipped else "."
        self.result_text.color = ft.Colors.GREEN_700 if not skipped else ft.Colors.AMBER_900
        self.open_result_button.visible = sorted_count > 0
        self.refresh_summary()
        self.app.page.update()

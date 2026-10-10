"""The "Check papers" screen: mark solved papers against the official mark scheme."""

import asyncio
import os

import flet as ft

from src import app_tasks as tasks
from src.gui_common import (card, describe_paper, drop_area, empty_state, find_pdfs, friendly_error,
                            open_in_explorer, paper_title, pill, same_file_key, section_title)

NO_STUDENT = "none"


class CheckPaper:
    """One paper waiting to be (or already) checked."""

    def __init__(self, screen, path, metadata=None, answers=None):
        self.screen = screen
        self.path = path
        self.name = os.path.basename(path)
        self.metadata = metadata
        self.answers = answers
        self.status = "waiting"   # waiting, reading, ready, queued, checking, done, problem
        self.message = ""
        self.progress_text = ""
        self.result = None        # dict from app_tasks.check_paper
        self.saved = False
        self.result_id = None     # score book row, replaced if the paper is checked again

        self.icon_slot = ft.Container(width=28, alignment=ft.Alignment.CENTER)
        self.title = ft.Text(self.name, weight=ft.FontWeight.W_600, max_lines=1,
                             overflow=ft.TextOverflow.ELLIPSIS, tooltip=path)
        self.details = ft.Text("", size=12, color=ft.Colors.ON_SURFACE_VARIANT)
        self.pills = ft.Row([], spacing=6, wrap=True)
        self.note = ft.Text("", size=12, visible=False)
        self.student = ft.Dropdown(label="Whose paper is this?", width=250, dense=True, text_size=13,
                                   on_select=self._student_chosen)
        self.check_button = ft.FilledTonalButton("Check", icon=ft.Icons.FACT_CHECK_OUTLINED,
                                                 on_click=lambda e: screen.queue_check(self))
        self.view_button = ft.FilledButton("Results", icon=ft.Icons.GRADING, visible=False,
                                           on_click=lambda e: screen.show_results(self))
        self.remove_button = ft.IconButton(icon=ft.Icons.CLOSE, icon_size=18, tooltip="Remove from the list",
                                           on_click=lambda e: screen.remove(self))
        self.row = ft.Container(
            content=ft.Row([
                self.icon_slot,
                ft.Column([self.title, self.details, self.pills, self.note], spacing=3, expand=True),
                self.student,
                ft.Column([self.check_button, self.view_button], spacing=4,
                          horizontal_alignment=ft.CrossAxisAlignment.END),
                self.remove_button,
            ], vertical_alignment=ft.CrossAxisAlignment.CENTER, spacing=10),
            padding=ft.Padding.symmetric(horizontal=12, vertical=10),
            border_radius=ft.BorderRadius.all(12),
            bgcolor=ft.Colors.SURFACE_CONTAINER_LOW,
            border=ft.Border.all(1, ft.Colors.OUTLINE_VARIANT),
        )
        self.set_student_options(screen.student_options)
        self.render()

    # ---------- students ----------

    def set_student_options(self, options):
        current = self.student.value
        self.student.options = [ft.DropdownOption(key=NO_STUDENT, text="Don't save to a student")] + [
            ft.DropdownOption(key=str(student_id), text=label) for student_id, label in options]
        keys = {option.key for option in self.student.options}
        self.student.value = current if current in keys else None

    @property
    def student_id(self):
        value = self.student.value
        return int(value) if value and value != NO_STUDENT else None

    async def _student_chosen(self, e):
        # Already checked but not saved yet: save it to the chosen student now
        if self.status == "done" and not self.saved and self.student_id:
            await self.screen.save(self)
        self.render()
        self.screen.app.page.update()

    # ---------- display ----------

    def render(self):
        meta = self.metadata or {}
        self.title.value = paper_title(meta, self.name)
        self.details.value = describe_paper(meta)
        self.pills.controls = []
        self.note.visible = False
        self.note.color = ft.Colors.ON_SURFACE_VARIANT
        self.row.border = ft.Border.all(1, ft.Colors.OUTLINE_VARIANT)
        self.check_button.visible = self.status in ("ready", "done", "problem") and self._checkable()
        self.check_button.content = "Check again" if self.status == "done" else "Check"
        self.check_button.disabled = self.screen.busy_with is not None and self.status != "done"
        self.view_button.visible = self.status == "done"
        self.remove_button.visible = self.status not in ("checking",)
        self.student.disabled = self.status == "checking"

        if self.answers is not None and meta.get("paper_type") == "QP":
            if self.answers.solved:
                pages = len(self.answers.pages)
                self.pills.controls.append(pill(f"Answers on {pages} page{'s' if pages != 1 else ''}",
                                                ft.Colors.TERTIARY, ft.Icons.DRAW))
            else:
                self.pills.controls.append(pill("Looks unanswered", ft.Colors.AMBER_800, ft.Icons.INFO_OUTLINE))

        if self.status in ("waiting", "reading", "queued", "checking"):
            self.icon_slot.content = ft.ProgressRing(width=18, height=18, stroke_width=2.5)
            self.note.visible = True
            self.note.value = {"waiting": "Waiting to be read…", "reading": "Reading the paper…",
                               "queued": "Waiting for its turn…"}.get(self.status, self.progress_text)
        elif self.status == "ready":
            self.icon_slot.content = ft.Icon(ft.Icons.DESCRIPTION_OUTLINED, color=ft.Colors.PRIMARY, size=22)
            if not self.student_id and self.student.value != NO_STUDENT:
                self.note.visible = True
                self.note.value = "Choose whose paper this is, then press Check."
        elif self.status == "done":
            result = self.result
            marking = result["marking"]
            percent = round(100 * marking["total"] / marking["max_total"]) if marking["max_total"] else 0
            self.icon_slot.content = ft.Icon(ft.Icons.CHECK_CIRCLE, color=ft.Colors.GREEN_600, size=22)
            self.pills.controls.insert(0, pill(f"{marking['total']} / {marking['max_total']}  ·  {percent}%",
                                               ft.Colors.GREEN_700, ft.Icons.GRADING))
            if result.get("grade"):
                self.pills.controls.insert(1, pill(f"Grade {result['grade']}", ft.Colors.PRIMARY))
            if marking.get("needs_review"):
                self.pills.controls.append(pill(f"Double-check {len(marking['needs_review'])}",
                                                ft.Colors.AMBER_800, ft.Icons.RATE_REVIEW))
            self.note.visible = True
            self.note.value = "Saved to the score book ✓" if self.saved else "Not saved to a student yet."
            self.note.color = ft.Colors.GREEN_700 if self.saved else ft.Colors.ON_SURFACE_VARIANT
        else:  # problem
            self.icon_slot.content = ft.Icon(ft.Icons.WARNING_AMBER_ROUNDED, color=ft.Colors.AMBER_800, size=22)
            self.note.visible = True
            self.note.value = self.message
            self.note.color = ft.Colors.AMBER_900
            self.row.border = ft.Border.all(1, ft.Colors.with_opacity(0.5, ft.Colors.AMBER_800))
        self.details.visible = bool(self.details.value)
        self.pills.visible = bool(self.pills.controls)

    def _checkable(self):
        # bool(): Flutter needs a real True/False (an `and` chain would return the subject code)
        return bool(self.metadata and self.metadata.get("paper_type") == "QP" and self.metadata.get("subject_code"))


class CheckScreen:
    def __init__(self, app):
        self.app = app
        self.papers = []
        self.queue = asyncio.Queue()
        self.read_queue = asyncio.Queue()
        self.busy_with = None
        self.student_options = []   # [(student_id, "Class · Student")]
        self.student_names = {}     # student_id -> (student name, class name)
        self._build()

    # ---------- layout ----------

    def _build(self):
        add_card = card(ft.Column([
            section_title(1, "Add solved papers"),
            drop_area(self.add_paths, "Drop students' papers here",
                      "PDFs written on with a pen app or typed.\nWhole folders are fine.", height=190,
                      icon=ft.Icons.DRAW),
            ft.Row([
                ft.OutlinedButton("Choose files", icon=ft.Icons.UPLOAD_FILE, on_click=self._choose_files, expand=True),
                ft.OutlinedButton("Choose a folder", icon=ft.Icons.DRIVE_FOLDER_UPLOAD,
                                  on_click=self._choose_folder, expand=True),
            ], spacing=10),
        ], spacing=14))

        how = card(ft.Column([
            ft.Row([ft.Icon(ft.Icons.INFO_OUTLINE, color=ft.Colors.PRIMARY), ft.Text("How checking works", size=15,
                    weight=ft.FontWeight.W_600)], spacing=8),
            ft.Text("I find the official mark scheme for each paper (from your sorted folders, or I download it), "
                    "then an AI examiner marks every question part, mark by mark.", size=13),
            ft.Text("You get the score, the Cambridge grade, feedback for each question and a marked PDF copy.",
                    size=13),
            ft.Text("The AI is good but not perfect: anything it's unsure about is flagged for you to double-check.",
                    size=13, color=ft.Colors.ON_SURFACE_VARIANT),
        ], spacing=8))

        self.summary_text = ft.Text("", size=13, color=ft.Colors.ON_SURFACE_VARIANT)
        self.clear_button = ft.TextButton("Clear list", icon=ft.Icons.CLEAR_ALL, on_click=self._clear)
        self.paper_list = ft.ListView(spacing=8, expand=True, padding=ft.Padding.only(right=8))
        self.empty = empty_state(ft.Icons.FACT_CHECK_OUTLINED, "No papers to check yet",
                                 "Add students' solved papers, or press \"Check it\" on a paper in Organise.")
        self.list_holder = ft.Container(content=self.empty, expand=True)
        self.result_text = ft.Text("", size=13, expand=True)
        self.check_all_button = ft.FilledButton(
            "Check all", icon=ft.Icons.FACT_CHECK, disabled=True, on_click=self._check_all,
            style=ft.ButtonStyle(padding=ft.Padding.symmetric(horizontal=28, vertical=20),
                                 text_style=ft.TextStyle(size=16, weight=ft.FontWeight.W_600)),
        )
        list_card = card(ft.Column([
            ft.Row([section_title(2, "Choose the student and check"), ft.Container(expand=True), self.clear_button]),
            self.summary_text,
            self.list_holder,
            ft.Row([self.result_text, self.check_all_button], spacing=12,
                   vertical_alignment=ft.CrossAxisAlignment.CENTER),
        ], spacing=12, expand=True), expand=True)

        self.view = ft.Row([
            ft.Container(ft.Column([add_card, how], spacing=16, scroll=ft.ScrollMode.AUTO), width=400),
            list_card,
        ], spacing=16, expand=True, vertical_alignment=ft.CrossAxisAlignment.STRETCH)

    def start_workers(self):
        self.app.page.run_task(self._read_worker)
        self.app.page.run_task(self._check_worker)

    # ---------- students ----------

    async def refresh_students(self):
        groups = await asyncio.to_thread(tasks.scorebook.classes_by_qualification)
        options, names = [], {}
        for classes in groups.values():
            for school_class in classes:
                for student in await asyncio.to_thread(tasks.scorebook.students, school_class["id"]):
                    options.append((student["id"], f"{school_class['name']} · {student['name']}"))
                    names[student["id"]] = (student["name"], school_class["name"])
        self.student_options, self.student_names = options, names
        for paper in self.papers:
            paper.set_student_options(options)
            paper.render()
        self.refresh_summary()

    # ---------- adding papers ----------

    async def _choose_files(self, e):
        files = await self.app.picker.pick_files(dialog_title="Choose students' papers", allowed_extensions=["pdf"],
                                                 file_type=ft.FilePickerFileType.CUSTOM, allow_multiple=True)
        if files:
            await self.add_paths([f.path for f in files if f.path])

    async def _choose_folder(self, e):
        folder = await self.app.picker.get_directory_path(dialog_title="Choose a folder of students' papers")
        if folder:
            await self.add_paths([folder])

    async def add_paths(self, paths):
        pdfs, skipped = await asyncio.to_thread(find_pdfs, paths)
        added = sum(1 for path in pdfs if self.add_paper(path))
        notes = []
        if not pdfs:
            notes.append("I couldn't find any PDFs there.")
        elif added < len(pdfs):
            notes.append(f"{len(pdfs) - added} already in the list.")
        if skipped:
            notes.append(f"Ignored {skipped} file{'s' if skipped != 1 else ''} that "
                         f"{'aren' if skipped != 1 else 'isn'}'t a PDF.")
        if notes:
            self.app.toast(" ".join(notes))

    def add_paper(self, path, metadata=None, answers=None):
        """Add one paper (also used by the Organise screen's "Check it"). Returns False if already listed."""
        if any(same_file_key(p.path) == same_file_key(path) for p in self.papers):
            return False
        paper = CheckPaper(self, path, metadata, answers)
        self.papers.append(paper)
        self.paper_list.controls.append(paper.row)
        self.read_queue.put_nowait(paper)
        self.result_text.value = ""
        self.refresh_summary()
        self.app.page.update()
        return True

    def remove(self, paper):
        if paper.status == "checking" or paper not in self.papers:
            return
        self.papers.remove(paper)
        self.paper_list.controls.remove(paper.row)
        self.refresh_summary()
        self.app.page.update()

    def _clear(self, e):
        keep = [p for p in self.papers if p.status in ("checking", "queued", "waiting", "reading")]
        self.papers = keep
        self.paper_list.controls = [p.row for p in keep]
        self.result_text.value = ""
        self.refresh_summary()
        self.app.page.update()

    def refresh_summary(self):
        self.list_holder.content = self.paper_list if self.papers else self.empty
        count = lambda *s: sum(1 for p in self.papers if p.status in s)
        parts = []
        if self.papers:
            parts.append(f"{len(self.papers)} paper{'s' if len(self.papers) != 1 else ''}")
            for label, statuses in (("being read", ("waiting", "reading")), ("ready", ("ready",)),
                                    ("checking", ("queued", "checking")), ("checked", ("done",)),
                                    ("need attention", ("problem",))):
                if count(*statuses):
                    parts.append(f"{count(*statuses)} {label}")
        self.summary_text.value = " · ".join(parts)
        self.clear_button.visible = bool(self.papers)
        ready = count("ready")
        self.check_all_button.content = f"Check {ready} paper{'s' if ready != 1 else ''}" if ready else "Check all"
        self.check_all_button.disabled = ready == 0 or not self.app.backend_ready.is_set()

    # ---------- reading ----------

    async def _read_worker(self):
        await self.app.backend_ready.wait()
        while True:
            paper = await self.read_queue.get()
            if paper not in self.papers:
                continue
            paper.status = "reading"
            paper.render()
            self.refresh_summary()
            self.app.page.update()
            try:
                if paper.metadata is None:
                    paper.metadata, problem = await asyncio.to_thread(tasks.analyse, paper.path)
                else:
                    problem = None
                if not problem and paper.metadata.get("paper_type") == "MS":
                    problem = "This is a mark scheme. Add the student's question paper instead."
                if not problem and paper.answers is None:
                    paper.answers = await asyncio.to_thread(tasks.detect_answers, paper.path, paper.metadata)
            except Exception as error:
                problem = friendly_error(error)
            paper.status = "problem" if problem else "ready"
            paper.message = problem or ""
            paper.render()
            self.refresh_summary()
            self.app.page.update()

    # ---------- checking ----------

    def queue_check(self, paper):
        if paper.status in ("queued", "checking") or not paper._checkable():
            return
        paper.status = "queued"
        paper.render()
        self.queue.put_nowait(paper)
        self.refresh_summary()
        self.app.page.update()

    async def _check_all(self, e):
        for paper in list(self.papers):
            if paper.status == "ready":
                self.queue_check(paper)

    async def _check_worker(self):
        """Checks run one at a time, so the shared free AI quota isn't used up in bursts."""
        await self.app.backend_ready.wait()
        while True:
            paper = await self.queue.get()
            if paper not in self.papers:
                continue
            await self._check(paper)

    async def _check(self, paper):
        self.busy_with = paper
        paper.status = "checking"
        paper.progress_text = "Starting…"
        paper.render()
        self.refresh_summary()
        self.app.page.update()

        student_name, class_name = self.student_names.get(paper.student_id, (None, None))

        def progress(text):
            paper.progress_text = text  # read by the loop below; never touch the UI from the thread

        job = asyncio.create_task(asyncio.to_thread(
            tasks.check_paper, paper.path, paper.metadata, student=student_name, class_name=class_name,
            key_override=self.app.gemini_key, progress=progress))
        shown = None
        while not job.done():
            if paper.progress_text != shown:
                shown = paper.progress_text
                paper.render()
                self.app.page.update()
            await asyncio.sleep(0.3)

        try:
            paper.result = job.result()
            paper.status = "done"
            paper.saved = False
            if paper.student_id:
                await self.save(paper)
        except Exception as error:
            paper.status = "problem"
            paper.message = str(error) if type(error).__name__ in ("MarkingError", "NotAvailableError") \
                else friendly_error(error)
        self.busy_with = None
        for other in self.papers:
            other.render()
        self.refresh_summary()
        if paper.status == "done":
            marking = paper.result["marking"]
            self.result_text.value = (f"{paper_title(paper.metadata)}: {marking['total']}/{marking['max_total']}"
                                      + (f", grade {paper.result['grade']}" if paper.result.get("grade") else ""))
            self.result_text.color = ft.Colors.GREEN_700
        self.app.page.update()

    async def save(self, paper):
        result = paper.result
        if paper.result_id:  # checked again: replace the old score instead of adding a second one
            await asyncio.to_thread(tasks.scorebook.delete_result, paper.result_id)
        paper.result_id = await asyncio.to_thread(
            tasks.scorebook.save_result, paper.student_id, paper.metadata, result["marking"],
            result.get("grade"), result.get("marked_pdf"), paper.path)
        paper.saved = True
        name, _ = self.student_names.get(paper.student_id, ("the student", None))
        self.app.toast(f"Saved to {name}'s scores.")
        await self.app.classes.refresh()

    # ---------- results ----------

    def show_results(self, paper):
        result = paper.result
        marking = result["marking"]
        percent = round(100 * marking["total"] / marking["max_total"]) if marking["max_total"] else 0

        rows = []
        for q in marking["questions"]:
            full, zero = q["marks_awarded"] == q["max_marks"], q["marks_awarded"] == 0
            colour = ft.Colors.GREEN_700 if full else ft.Colors.RED_700 if zero else ft.Colors.AMBER_800
            flag = [pill("Double-check", ft.Colors.AMBER_800, ft.Icons.RATE_REVIEW)] if q.get("confidence") == "low" else []
            rows.append(ft.Container(
                content=ft.Row([
                    ft.Text(q["question"], weight=ft.FontWeight.BOLD, width=70),
                    ft.Text(f"{q['marks_awarded']} / {q['max_marks']}", weight=ft.FontWeight.BOLD, color=colour,
                            width=60),
                    ft.Column([
                        ft.Text(q.get("marking", ""), size=13),
                        ft.Text(q.get("feedback", ""), size=12, color=ft.Colors.ON_SURFACE_VARIANT, italic=True),
                        ft.Text(f"Student wrote: {q.get('student_answer', '')}", size=11,
                                color=ft.Colors.ON_SURFACE_VARIANT),
                        *flag,
                    ], spacing=3, expand=True),
                ], vertical_alignment=ft.CrossAxisAlignment.START),
                padding=ft.Padding.symmetric(horizontal=10, vertical=8),
                border_radius=ft.BorderRadius.all(10),
                bgcolor=ft.Colors.SURFACE_CONTAINER_LOW,
            ))

        header = ft.Row([
            ft.Text(f"{marking['total']} / {marking['max_total']}", size=30, weight=ft.FontWeight.BOLD,
                    color=ft.Colors.PRIMARY),
            ft.Column([
                ft.Text(f"{percent}%", size=16, weight=ft.FontWeight.W_600),
                ft.Text(f"Grade {result['grade']} (Cambridge thresholds)" if result.get("grade")
                        else "Grade not available for this paper", size=12, color=ft.Colors.ON_SURFACE_VARIANT),
            ], spacing=0),
        ], spacing=20)
        extra = []
        if not marking.get("complete", True):
            extra.append(ft.Text(f"Note: the question marks add up to {marking['max_total']}, but the paper is out "
                                 f"of {marking['paper_total_marks']}. Some parts may be missing.",
                                 size=12, color=ft.Colors.AMBER_900))
        if marking.get("needs_review"):
            extra.append(ft.Text("Please double-check: " + ", ".join(marking["needs_review"]), size=13,
                                 color=ft.Colors.AMBER_900, weight=ft.FontWeight.W_600))

        def open_marked(e):
            open_in_explorer(result["marked_pdf"])

        self.app.page.show_dialog(ft.AlertDialog(
            title=ft.Text(f"{paper_title(paper.metadata)}  ·  {describe_paper(paper.metadata)}", size=18),
            content=ft.Container(width=720, height=520, content=ft.Column(
                [header, ft.Text(marking.get("summary", ""), size=13), *extra, ft.Divider(), *rows],
                spacing=8, scroll=ft.ScrollMode.AUTO)),
            actions=[ft.TextButton("Open marked copy", icon=ft.Icons.PICTURE_AS_PDF, on_click=open_marked),
                     ft.TextButton("Close", on_click=lambda e: self.app.page.pop_dialog())],
        ))

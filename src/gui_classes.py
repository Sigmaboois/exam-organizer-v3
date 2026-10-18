"""The "Classes" screen: classes, students and their checked papers (the score book)."""

import asyncio
import os
from datetime import datetime

import flet as ft

from src import app_tasks as tasks
from src.gui_common import card, empty_state, open_in_explorer, pill, section_title

GRADE_ORDER = ["9", "8", "7", "6", "5", "4", "3", "2", "1", "A*", "A", "B", "C", "D", "E", "F", "G",
                "a", "b", "c", "d", "e", "U"]

# Which school years usually sit each qualification, shown on the dividers
DIVIDER_HINTS = {
    "IGCSE": "Usually Grades 9–10",
    "O Level": "Usually Grades 9–10",
    "AS": "Usually Grade 11",
    "AS & A Level": "Usually Grades 11–12",
    "Other": "Gets sorted automatically once a paper is checked",
}


class ClassesScreen:
    def __init__(self, app):
        self.app = app
        self.selected_class = None   # class row dict
        self._build()

    # ---------- layout ----------

    def _build(self):
        self.new_class_field = ft.TextField(label="New class name", hint_text="e.g. 11A Physics", dense=True,
                                            expand=True, on_submit=self._add_class)
        self.class_list = ft.ListView(spacing=4, expand=True)
        classes_card = card(ft.Column([
            section_title(1, "Classes"),
            ft.Row([self.new_class_field,
                    ft.IconButton(icon=ft.Icons.ADD, tooltip="Add class", on_click=self._add_class)]),
            ft.Text("Classes are grouped by qualification automatically, from the papers their students sit.",
                    size=12, color=ft.Colors.ON_SURFACE_VARIANT),
            self.class_list,
            ft.OutlinedButton("Export all results", icon=ft.Icons.TABLE_VIEW, on_click=self._export_all),
        ], spacing=12, expand=True), width=360)

        self.class_title = ft.Text("", size=20, weight=ft.FontWeight.BOLD)
        self.class_pill = ft.Container()
        # A fresh Column is built on every refresh: re-filling the same one left duplicated rows on screen
        self.overview = ft.Container()
        self.new_student_field = ft.TextField(label="Add a student", hint_text="Full name", dense=True, expand=True,
                                              on_submit=self._add_student)
        self.student_list = ft.ListView(spacing=6, expand=True)
        self.class_detail = ft.Column([
            ft.Row([self.class_title, self.class_pill, ft.Container(expand=True),
                    ft.OutlinedButton("Export class", icon=ft.Icons.TABLE_VIEW, on_click=self._export_class)],
                   vertical_alignment=ft.CrossAxisAlignment.CENTER, spacing=12),
            self.overview,
            ft.Row([self.new_student_field,
                    ft.IconButton(icon=ft.Icons.PERSON_ADD, tooltip="Add student", on_click=self._add_student),
                    ft.IconButton(icon=ft.Icons.PLAYLIST_ADD, tooltip="Paste a list of names",
                                  on_click=self._paste_students)]),
            self.student_list,
        ], spacing=12, expand=True)
        self.no_class = empty_state(ft.Icons.GROUPS_OUTLINED, "Choose or create a class",
                                    "Add a class on the left, then add your students. Checked papers saved to a "
                                    "student show up here with their scores and grades.")
        self.detail_holder = ft.Container(content=self.no_class, expand=True)
        detail_card = card(ft.Column([section_title(2, "Students and scores"), self.detail_holder],
                                     spacing=12, expand=True), expand=True)

        self.view = ft.Row([classes_card, detail_card], spacing=16, expand=True,
                           vertical_alignment=ft.CrossAxisAlignment.STRETCH)

    # ---------- loading ----------

    async def refresh(self):
        """Reload classes, the selected class's students, and the Check screen's student pickers."""
        groups = await asyncio.to_thread(tasks.scorebook.classes_by_qualification)
        controls = []
        all_classes = {}
        for divider, classes in groups.items():
            controls.append(ft.Container(
                content=ft.Column([
                    ft.Text(divider, size=13, weight=ft.FontWeight.BOLD, color=ft.Colors.PRIMARY),
                    ft.Text(DIVIDER_HINTS.get(divider, ""), size=11, color=ft.Colors.ON_SURFACE_VARIANT),
                ], spacing=0),
                padding=ft.Padding.only(left=4, top=10, bottom=2),
            ))
            for school_class in classes:
                all_classes[school_class["id"]] = school_class
                controls.append(self._class_tile(school_class))
        if not controls:
            controls.append(ft.Text("No classes yet.", color=ft.Colors.ON_SURFACE_VARIANT))
        self.class_list.controls = controls

        if self.selected_class and self.selected_class["id"] in all_classes:
            self.selected_class = all_classes[self.selected_class["id"]]
            await self._show_class()
        else:
            self.selected_class = None
            self.detail_holder.content = self.no_class
        await self.app.check.refresh_students()
        self.app.page.update()

    def _class_tile(self, school_class):
        selected = self.selected_class is not None and self.selected_class["id"] == school_class["id"]
        count = school_class["student_count"]

        async def select(e):
            self.selected_class = school_class
            await self.refresh()

        async def delete(e):
            self._confirm(f"Delete {school_class['name']}?",
                          "This deletes the class, its students and all their saved scores. "
                          "Marked PDF copies stay on your computer.",
                          lambda: self._delete_class(school_class))

        def rename(e):
            self._rename_dialog("Rename class", school_class["name"], lambda name: self._rename_class(school_class, name))

        return ft.Container(
            content=ft.Row([
                ft.Icon(ft.Icons.CLASS_, color=ft.Colors.PRIMARY if selected else ft.Colors.ON_SURFACE_VARIANT),
                ft.Column([
                    ft.Text(school_class["name"], weight=ft.FontWeight.W_600),
                    ft.Text(f"{count} student{'s' if count != 1 else ''}", size=12,
                            color=ft.Colors.ON_SURFACE_VARIANT),
                ], spacing=0, expand=True),
                ft.IconButton(icon=ft.Icons.EDIT_OUTLINED, icon_size=18, tooltip="Rename class", on_click=rename),
                ft.IconButton(icon=ft.Icons.DELETE_OUTLINE, icon_size=18, tooltip="Delete class", on_click=delete),
            ]),
            padding=ft.Padding.symmetric(horizontal=10, vertical=6),
            border_radius=ft.BorderRadius.all(10),
            bgcolor=ft.Colors.PRIMARY_CONTAINER if selected else None,
            on_click=select,
            ink=True,
        )

    async def _show_class(self):
        school_class = self.selected_class
        self.class_title.value = school_class["name"]
        self.class_pill.content = pill(school_class["qualification"] or "Qualification not set yet",
                                       ft.Colors.PRIMARY if school_class["qualification"] else ft.Colors.OUTLINE)
        students = await asyncio.to_thread(tasks.scorebook.students, school_class["id"])
        results = await asyncio.to_thread(tasks.scorebook.results, None, school_class["id"])
        self.overview.content = ft.Column(self._overview(results), spacing=8)
        by_student = {}
        for result in results:
            by_student.setdefault(result["student_id"], []).append(result)

        controls = [self._student_tile(student, by_student.get(student["id"], [])) for student in students]
        if not controls:
            controls.append(ft.Text("No students yet. Type a name above and press Enter.",
                                    color=ft.Colors.ON_SURFACE_VARIANT))
        self.student_list.controls = controls
        self.detail_holder.content = self.class_detail

    def _overview(self, results):
        if not results:
            return [ft.Text("No papers checked yet.", size=12, color=ft.Colors.ON_SURFACE_VARIANT)]
        percents = [100 * row["score"] / row["max_score"] for row in results if row["max_score"]]
        average = round(sum(percents) / len(percents)) if percents else 0
        counts = {}
        for row in results:
            counts[row["grade"] or "Ungraded"] = counts.get(row["grade"] or "Ungraded", 0) + 1
        ordered = [grade for grade in GRADE_ORDER if grade in counts]
        ordered += [grade for grade in counts if grade not in ordered]
        grade_pills = ft.Row([pill(f"{grade} {counts[grade]}", ft.Colors.PRIMARY) for grade in ordered],
                             spacing=6, wrap=True)
        topics = tasks.scorebook.topic_losses(results)
        topic_lines = [ft.Text("Topics to revise", size=13, weight=ft.FontWeight.W_600)]
        if topics:
            topic_lines.append(ft.Column([
                ft.Text(f"{item['topic']}  ·  {item['lost']} mark{'s' if item['lost'] != 1 else ''} lost "
                        f"across {item['questions']} part{'s' if item['questions'] != 1 else ''}", size=12)
                for item in topics[:6]
            ], spacing=2))
        elif tasks.scorebook.topics_recorded(results):
            topic_lines.append(ft.Text("No marks lost on a topic so far.", size=12,
                                       color=ft.Colors.ON_SURFACE_VARIANT))
        else:
            topic_lines.append(ft.Text("Topic insights show up after the next paper you check.", size=12,
                                       color=ft.Colors.ON_SURFACE_VARIANT))
        return [
            ft.Text(f"Average {average}% across {len(results)} paper{'s' if len(results) != 1 else ''}",
                    size=13, weight=ft.FontWeight.W_600),
            grade_pills,
            *topic_lines,
        ]

    def _student_tile(self, student, results):
        if results:
            latest = results[0]
            subtitle = (f"{len(results)} paper{'s' if len(results) != 1 else ''} checked · "
                        f"average {student['average']:.0f}% · latest {latest['score']}/{latest['max_score']}"
                        + (f" ({latest['grade']})" if latest["grade"] else ""))
        else:
            subtitle = "No papers checked yet"
        # No trailing widget: it would replace the arrow that shows the row can be opened
        trailing = None

        async def delete_student(e):
            self._confirm(f"Remove {student['name']}?", "Their saved scores will be deleted too.",
                          lambda: self._delete_student(student))

        rows = [self._result_row(result) for result in results] or [
            ft.Text("Check a paper and choose this student to save it here.", size=12,
                    color=ft.Colors.ON_SURFACE_VARIANT)]
        weak = tasks.scorebook.topic_losses(results)[:3]
        if weak:
            rows.append(ft.Text("Revise: " + ", ".join(f"{item['topic']} ({item['lost']} lost)" for item in weak),
                                size=12, color=ft.Colors.PRIMARY))
        rows.append(ft.Row([
            ft.TextButton("Rename", icon=ft.Icons.EDIT_OUTLINED,
                          on_click=lambda e: self._rename_dialog(
                              "Rename student", student["name"], lambda name: self._rename_student(student, name))),
            ft.TextButton("Remove student", icon=ft.Icons.PERSON_REMOVE_OUTLINED, on_click=delete_student),
        ], alignment=ft.MainAxisAlignment.END))
        return ft.ExpansionTile(
            title=ft.Text(student["name"], weight=ft.FontWeight.W_600),
            subtitle=ft.Text(subtitle, size=12, color=ft.Colors.ON_SURFACE_VARIANT),
            leading=ft.Icon(ft.Icons.PERSON),
            trailing=trailing,
            controls=[ft.Container(ft.Column(rows, spacing=6), padding=ft.Padding.only(left=16, right=8, bottom=8))],
        )

    def _result_row(self, result):
        percent = round(100 * result["score"] / result["max_score"]) if result["max_score"] else 0
        when = datetime.fromisoformat(result["checked_at"]).strftime("%d %b %Y")
        series = f"{(result['session'] or '').replace('-', '–')} {result['year'] or ''}".strip()

        async def delete(e):
            await asyncio.to_thread(tasks.scorebook.delete_result, result["id"])
            await self.refresh()

        buttons = []
        if result["marked_pdf"] and os.path.exists(result["marked_pdf"]):
            buttons.append(ft.IconButton(icon=ft.Icons.PICTURE_AS_PDF, tooltip="Open marked copy",
                                         on_click=lambda e: open_in_explorer(result["marked_pdf"])))
        buttons.append(ft.IconButton(icon=ft.Icons.DELETE_OUTLINE, tooltip="Delete this score", on_click=delete))
        return ft.Container(
            content=ft.Row([
                ft.Column([
                    ft.Text(f"{result['subject_name']} ({result['subject_code']}) · Paper {result['paper']}"
                            f"{result['variant']}", weight=ft.FontWeight.W_600, size=13),
                    ft.Text(f"{series} · checked {when}", size=12, color=ft.Colors.ON_SURFACE_VARIANT),
                ], spacing=0, expand=True),
                ft.Text(f"{result['score']}/{result['max_score']} ({percent}%)", weight=ft.FontWeight.BOLD),
                pill(f"Grade {result['grade']}", ft.Colors.PRIMARY) if result["grade"] else ft.Container(),
                *buttons,
            ], vertical_alignment=ft.CrossAxisAlignment.CENTER, spacing=10),
            padding=ft.Padding.symmetric(horizontal=10, vertical=6),
            border_radius=ft.BorderRadius.all(10),
            bgcolor=ft.Colors.SURFACE_CONTAINER_LOW,
        )

    # ---------- actions ----------

    def _rename_dialog(self, title, current, on_save):
        field = ft.TextField(label="Name", value=current, dense=True, autofocus=True)

        async def save(e):
            self.app.page.pop_dialog()
            await on_save(field.value or "")

        field.on_submit = save
        self.app.page.show_dialog(ft.AlertDialog(
            title=ft.Text(title),
            content=ft.Container(width=360, content=field),
            actions=[ft.TextButton("Cancel", on_click=lambda e: self.app.page.pop_dialog()),
                     ft.FilledButton("Save", on_click=save)],
        ))

    async def _rename_class(self, school_class, name):
        try:
            await asyncio.to_thread(tasks.scorebook.rename_class, school_class["id"], name)
        except ValueError as error:
            self.app.toast(str(error), error=True)
            return
        await self.refresh()

    async def _rename_student(self, student, name):
        try:
            await asyncio.to_thread(tasks.scorebook.rename_student, student["id"], name)
        except ValueError as error:
            self.app.toast(str(error), error=True)
            return
        await self.refresh()

    async def _paste_students(self, e):
        if not self.selected_class:
            return
        box = ft.TextField(label="Student names", multiline=True, min_lines=6, max_lines=12,
                           hint_text="One name per line")

        async def save(e):
            names = [line.strip() for line in (box.value or "").splitlines() if line.strip()]
            self.app.page.pop_dialog()
            if not names:
                return
            added = skipped = 0
            for name in names:
                try:
                    await asyncio.to_thread(tasks.scorebook.add_student, self.selected_class["id"], name)
                    added += 1
                except ValueError:
                    skipped += 1
            await self.refresh()
            note = f" {skipped} already in the class." if skipped else ""
            self.app.toast(f"Added {added} student{'s' if added != 1 else ''}.{note}")

        self.app.page.show_dialog(ft.AlertDialog(
            title=ft.Text("Paste a class list"),
            content=ft.Container(width=420, content=box),
            actions=[ft.TextButton("Cancel", on_click=lambda e: self.app.page.pop_dialog()),
                     ft.FilledButton("Add students", on_click=save)],
        ))

    async def _add_class(self, e):
        try:
            class_id = await asyncio.to_thread(tasks.scorebook.add_class, self.new_class_field.value or "")
        except ValueError as error:
            self.app.toast(str(error), error=True)
            return
        self.new_class_field.value = ""
        self.selected_class = {"id": class_id}
        await self.refresh()

    async def _add_student(self, e):
        if not self.selected_class:
            return
        try:
            await asyncio.to_thread(tasks.scorebook.add_student, self.selected_class["id"],
                                    self.new_student_field.value or "")
        except ValueError as error:
            self.app.toast(str(error), error=True)
            return
        self.new_student_field.value = ""
        await self.refresh()
        try:
            # Ready to type the next name. Only possible while this screen is showing.
            await self.new_student_field.focus()
        except Exception:
            pass

    def _confirm(self, title, text, on_yes):
        async def yes(e):
            self.app.page.pop_dialog()
            await on_yes()

        self.app.page.show_dialog(ft.AlertDialog(
            title=ft.Text(title), content=ft.Text(text),
            actions=[ft.TextButton("Cancel", on_click=lambda e: self.app.page.pop_dialog()),
                     ft.FilledButton("Delete", on_click=yes, style=ft.ButtonStyle(bgcolor=ft.Colors.ERROR))],
        ))

    async def _delete_class(self, school_class):
        await asyncio.to_thread(tasks.scorebook.delete_class, school_class["id"])
        if self.selected_class and self.selected_class["id"] == school_class["id"]:
            self.selected_class = None
        await self.refresh()

    async def _delete_student(self, student):
        await asyncio.to_thread(tasks.scorebook.delete_student, student["id"])
        await self.refresh()

    async def _export(self, rows, file_name):
        if not rows:
            self.app.toast("There are no checked papers to export yet.")
            return
        path = await self.app.picker.save_file(dialog_title="Save the results spreadsheet", file_name=file_name,
                                               allowed_extensions=["xlsx"], file_type=ft.FilePickerFileType.CUSTOM)
        if not path:
            return
        if not path.lower().endswith(".xlsx"):
            path += ".xlsx"
        try:
            await asyncio.to_thread(tasks.reports.export_spreadsheet, rows, path)
        except PermissionError:
            self.app.toast("Couldn't save. Is that spreadsheet open in Excel? Close it and try again.", error=True)
            return
        self.app.toast("Spreadsheet saved.")
        open_in_explorer(path, select=True)

    async def _export_all(self, e):
        rows = await asyncio.to_thread(tasks.scorebook.results)
        await self._export(rows, "Exam results.xlsx")

    async def _export_class(self, e):
        if not self.selected_class:
            return
        rows = await asyncio.to_thread(tasks.scorebook.results, None, self.selected_class["id"])
        await self._export(rows, f"{self.selected_class['name']} results.xlsx")

"""
The "Online lessons" screen: every class gets its Zoom link (pasted once), a WhatsApp group and its
lesson times, and the link is sent to the group by itself shortly before each lesson.
"""

import asyncio
import logging
from datetime import date, datetime, timedelta

import flet as ft

from src import meetings, whatsapp
from src.gui_common import card, empty_state, pill, row_box, section_title

CHECK_EVERY = 20   # seconds between looks at the timetable
ONE_DATE = "date"  # the "One date…" choice in the day menu

log = logging.getLogger("exam_organizer.lessons")


class LessonsScreen:
    def __init__(self, app):
        self.app = app
        self.wa = whatsapp.WhatsApp()
        self.wa.on_change = lambda: app.page.run_task(self._whatsapp_changed)
        self.selected_id = None
        self.groups = []            # [(group_id, name)] once WhatsApp is linked
        self.qr_dialog = None
        self._warned = set()        # lessons whose failed send was already shown as a message
        self._form_class, self._form_boxes = None, {}   # the open class's text boxes, see _class_form
        self._build()

    # ---------- layout ----------

    def _build(self):
        self.wa_icon = ft.Icon(ft.Icons.CIRCLE, size=14)
        self.wa_title = ft.Text("", weight=ft.FontWeight.W_600)
        self.wa_text = ft.Text("", size=12, color=ft.Colors.ON_SURFACE_VARIANT)
        self.wa_button = ft.FilledTonalButton("Link WhatsApp", icon=ft.Icons.QR_CODE_2, on_click=self._link)
        self.unlink_button = ft.TextButton("Unlink", icon=ft.Icons.LINK_OFF, on_click=self._unlink, visible=False)
        self.class_list = ft.ListView(spacing=4, expand=True)
        left = card(ft.Column([
            section_title(1, "WhatsApp"),
            ft.Row([self.wa_icon, ft.Column([self.wa_title, self.wa_text], spacing=0, expand=True)], spacing=10),
            ft.Row([self.wa_button, self.unlink_button], wrap=True),
            ft.Text("Keep Exam Organizer open (minimised is fine) and the PC awake, and every lesson link "
                    "goes out by itself.", size=12, color=ft.Colors.ON_SURFACE_VARIANT),
            ft.Text("Set up each class on one device only (this PC or the Android app), or its group gets "
                    "the link twice.", size=12, color=ft.Colors.ON_SURFACE_VARIANT),
            ft.Divider(height=1),
            section_title(2, "Classes"),
            self.class_list,
        ], spacing=12, expand=True), width=360)

        self.no_class = empty_state(ft.Icons.VIDEO_CALL_OUTLINED, "Choose a class",
                                    "Paste the class's Zoom link once, pick its WhatsApp group and add its lesson "
                                    "times. The link is sent to the group before every lesson.")
        self.detail_holder = ft.Container(content=self.no_class, expand=True)
        right = card(ft.Column([section_title(3, "Zoom link and lesson times"), self.detail_holder],
                               spacing=12, expand=True), expand=True)
        self.view = ft.Row([left, right], spacing=16, expand=True, vertical_alignment=ft.CrossAxisAlignment.STRETCH)
        self._show_whatsapp()

    # ---------- starting up ----------

    def start(self):
        """Reconnect WhatsApp if this PC was linked before, and start watching the timetable."""
        self.app.page.run_task(self._scheduler)

    async def _reconnect_if_linked(self):
        try:
            linked = await asyncio.to_thread(self.wa.has_login)
        except Exception:
            log.exception("Couldn't check the WhatsApp login")
            return
        if linked:
            self.wa.start()

    # ---------- WhatsApp ----------

    def _show_whatsapp(self):
        state = self.wa.state
        colours = {whatsapp.CONNECTED: ft.Colors.GREEN, whatsapp.ERROR: ft.Colors.ERROR}
        self.wa_icon.color = colours.get(state, ft.Colors.AMBER if state != whatsapp.OFF else ft.Colors.OUTLINE)
        self.unlink_button.visible = state in (whatsapp.CONNECTED, whatsapp.RECONNECTING, whatsapp.ERROR)
        self.wa_button.visible = state != whatsapp.CONNECTED
        if state == whatsapp.CONNECTED:
            self.wa_title.value = "WhatsApp is linked"
            self.wa_text.value = f"Sending as {self.wa.account}" if self.wa.account else "Ready to send"
        elif state in (whatsapp.STARTING, whatsapp.RECONNECTING):
            self.wa_title.value = "Connecting to WhatsApp…"
            self.wa_text.value = "This takes a few seconds."
            self.wa_button.visible = False
        elif state == whatsapp.SCAN:
            self.wa_title.value = "Waiting for you to scan the code"
            self.wa_text.value = "Scan it with WhatsApp on your phone."
            self.wa_button.content = "Show the code"
        elif state == whatsapp.EXPIRED:
            self.wa_title.value = "The code expired"
            self.wa_text.value = "Press the button to get a new one."
            self.wa_button.content = "Show a new code"
        elif state == whatsapp.ERROR:
            self.wa_title.value = "WhatsApp isn't connected"
            self.wa_text.value = self.wa.error or ""
            self.wa_button.content = "Reconnect"
        else:
            self.wa_title.value = "WhatsApp isn't linked yet"
            self.wa_text.value = "Link it once by scanning a code with your phone, like WhatsApp Web."
            self.wa_button.content = "Link WhatsApp"

    async def _whatsapp_changed(self):
        """Called (on the app's loop) whenever the WhatsApp link changes."""
        self._show_whatsapp()
        if self.qr_dialog:
            self._fill_qr_dialog()
        if self.wa.state == whatsapp.CONNECTED:
            if self.qr_dialog:
                self._close(self.qr_dialog)
                self.qr_dialog = None
                self.app.toast("WhatsApp is linked. Lesson links will be sent from this PC.")
            await self._load_groups()
        if self.selected_id is not None and self.wa.state in (whatsapp.CONNECTED, whatsapp.OFF, whatsapp.ERROR):
            await self.refresh()
        self.app.page.update()

    async def _load_groups(self, refresh=False):
        try:
            self.groups = await asyncio.to_thread(self.wa.groups, refresh)
        except Exception as error:
            log.exception("Couldn't load the WhatsApp groups")
            self.app.toast(f"Couldn't load your WhatsApp groups: {error}", error=True)

    async def _link(self, e):
        self.wa.start()
        self._open_qr_dialog()

    def _open_qr_dialog(self):
        self.qr_holder = ft.Container(alignment=ft.Alignment.CENTER, width=290, height=290,
                                      bgcolor=ft.Colors.WHITE, border_radius=ft.BorderRadius.all(12))
        self.qr_note = ft.Text("Getting a code from WhatsApp…", size=13, text_align=ft.TextAlign.CENTER)
        self.qr_again = ft.FilledTonalButton("Show a new code", icon=ft.Icons.REFRESH, visible=False,
                                             on_click=lambda e: self.wa.start())
        steps = ft.Column([
            ft.Text("1. Open WhatsApp on your phone", size=13),
            ft.Text("2. Tap Settings (or ⋮ on Android), then Linked devices", size=13),
            ft.Text("3. Tap Link a device and point the phone at this code", size=13),
        ], spacing=4)

        def closed(e):
            self.qr_dialog = None

        dialog = self.qr_dialog = ft.AlertDialog(
            title=ft.Text("Link WhatsApp"),
            content=ft.Container(width=420, content=ft.Column([
                steps,
                self.qr_holder,
                self.qr_note,
                ft.Row([self.qr_again], alignment=ft.MainAxisAlignment.CENTER),
                ft.Text("Your phone stays the main WhatsApp. This PC only sends the lesson links you set up, "
                        "and you can unlink it any time from the phone or here.", size=12,
                        color=ft.Colors.ON_SURFACE_VARIANT),
            ], tight=True, spacing=12, horizontal_alignment=ft.CrossAxisAlignment.CENTER)),
            actions=[ft.TextButton("Close", on_click=lambda e: self._close(dialog))],
            on_dismiss=closed,
        )
        self._fill_qr_dialog()
        self.app.page.show_dialog(dialog)

    def _close(self, dialog):
        """Close this dialog. page.pop_dialog() closes whatever is on top, which may be a message just shown.
        False if it was closed already (e.g. its button was pressed twice)."""
        if not dialog.open:
            return False
        dialog.open = False
        dialog.update()
        return True

    def _fill_qr_dialog(self):
        state = self.wa.state
        self.qr_again.visible = state in (whatsapp.EXPIRED, whatsapp.ERROR)
        if state == whatsapp.SCAN and self.wa.qr:
            self.qr_holder.content = ft.Image(src=whatsapp.qr_image(self.wa.qr), width=280, height=280)
            self.qr_note.value = "The code changes every few seconds. That's normal."
        else:
            waiting = state in (whatsapp.STARTING, whatsapp.RECONNECTING)
            self.qr_holder.content = ft.ProgressRing(width=36, height=36) if waiting else ft.Icon(
                ft.Icons.CHECK_CIRCLE if state == whatsapp.CONNECTED else ft.Icons.QR_CODE_2, size=64,
                color=ft.Colors.GREEN if state == whatsapp.CONNECTED else ft.Colors.GREY)
            self.qr_note.value = {
                whatsapp.EXPIRED: "The code expired before it was scanned.",
                whatsapp.ERROR: self.wa.error or "Something went wrong.",
                whatsapp.CONNECTED: "Linked!",
            }.get(state, "Getting a code from WhatsApp…")

    async def _unlink(self, e):
        async def yes(e):
            if not self._close(dialog):
                return
            try:
                told = await asyncio.to_thread(self.wa.unlink)
            except RuntimeError as error:
                self.app.toast(str(error), error=True)
            else:
                self.app.toast("WhatsApp is unlinked from this PC." if told else
                               "WhatsApp is unlinked from this PC. If your phone still lists it under Linked "
                               "devices, remove it there too.")
            self.groups = []
            await self.refresh()

        dialog = ft.AlertDialog(
            title=ft.Text("Unlink WhatsApp?"),
            content=ft.Text("Lesson links stop being sent until you link it again. Your classes, links and "
                            "lesson times are kept."),
            actions=[ft.TextButton("Cancel", on_click=lambda e: self._close(dialog)),
                     ft.FilledButton("Unlink", on_click=yes)],
        )
        self.app.page.show_dialog(dialog)

    # ---------- classes ----------

    async def refresh(self):
        classes = await asyncio.to_thread(meetings.classes_overview)
        tiles = [self._class_tile(school_class) for school_class in classes]
        if not tiles:
            tiles.append(ft.Text("No classes yet. Add them on the Classes screen first.",
                                 color=ft.Colors.ON_SURFACE_VARIANT))
        self.class_list.controls = tiles
        chosen = next((c for c in classes if c["id"] == self.selected_id), None)
        if chosen:
            self.detail_holder.content = await self._class_form(chosen)
        else:
            self.selected_id = None
            self.detail_holder.content = self.no_class
        self._show_whatsapp()
        self.app.page.update()

    def _class_tile(self, school_class):
        setup, times = school_class["setup"], school_class["times"]
        selected = school_class["id"] == self.selected_id
        missing = meetings.problems(setup, times)
        if not setup["link"] and not setup["group_id"] and not times:
            status, colour = "Not set up", ft.Colors.ON_SURFACE_VARIANT
        elif missing:
            status, colour = "Still needs " + " and ".join(missing), ft.Colors.ERROR
        elif setup["paused"]:
            status, colour = "Paused", ft.Colors.ON_SURFACE_VARIANT
        else:
            upcoming = meetings.next_message(setup, times, sent=school_class["sent"])
            if not upcoming:
                status = "No lessons coming up"
            elif upcoming[0] <= datetime.now():
                status = f"Sending the {upcoming[1].strftime('%H:%M')} lesson's link now"
            else:
                status = f"Next: {upcoming[0].strftime('%a %H:%M')} to {setup['group_name'] or 'the group'}"
            colour = ft.Colors.PRIMARY

        async def select(e):
            self.selected_id = school_class["id"]
            await self.refresh()

        return ft.Container(
            content=ft.Row([
                ft.Icon(ft.Icons.VIDEO_CALL if not missing else ft.Icons.VIDEO_CALL_OUTLINED,
                        color=ft.Colors.PRIMARY if selected else ft.Colors.ON_SURFACE_VARIANT),
                ft.Column([
                    ft.Text(school_class["name"], weight=ft.FontWeight.W_600),
                    ft.Text(status, size=12, color=colour),
                ], spacing=0, expand=True),
            ]),
            padding=ft.Padding.symmetric(horizontal=10, vertical=8),
            border_radius=ft.BorderRadius.all(10),
            bgcolor=ft.Colors.PRIMARY_CONTAINER if selected else None,
            on_click=select,
            ink=True,
        )

    async def _class_form(self, school_class):
        """The settings of one class. Every change is saved straight away."""
        class_id, class_name = school_class["id"], school_class["name"]
        setup, times = school_class["setup"], school_class["times"]
        recent = await asyncio.to_thread(meetings.recent_messages, class_id)
        linked = self.wa.state == whatsapp.CONNECTED
        # The form is rebuilt after every send and WhatsApp change, and these boxes only save when left:
        # keep what's typed in them
        typed = {name: box.value for name, box in self._form_boxes.items()} if self._form_class == class_id else {}

        async def save(**changes):
            try:
                await asyncio.to_thread(meetings.save_setup, class_id, **changes)
            except ValueError as error:
                return str(error)
            setup.update(changes)
            await self._refresh_tiles()
            show_status()
            return None

        # Zoom link
        link = ft.TextField(label="Zoom link", value=typed.get("link", setup["link"]),
                            hint_text="https://us02web.zoom.us/j/…", prefix_icon=ft.Icons.LINK, dense=True, expand=True)
        passcode = ft.TextField(label="Passcode (optional)", value=typed.get("passcode", setup["passcode"]), dense=True,
                                width=200)

        async def save_link(e):
            if (link.value or "").strip() == setup["link"]:
                return
            link.error = await save(link=link.value or "")
            if not link.error:
                link.value = meetings.check_link(link.value)
                setup["link"] = link.value
            update_preview()

        async def save_passcode(e):
            if (passcode.value or "").strip() != setup["passcode"]:
                await save(passcode=(passcode.value or "").strip())
                update_preview()

        link.on_blur = link.on_submit = save_link
        passcode.on_blur = passcode.on_submit = save_passcode

        # WhatsApp group
        options = [ft.DropdownOption(key=group_id, text=name) for group_id, name in self.groups]
        if setup["group_id"] and setup["group_id"] not in {group_id for group_id, _ in self.groups}:
            options.insert(0, ft.DropdownOption(key=setup["group_id"], text=setup["group_name"] or "Saved group"))
        group = ft.Dropdown(label="WhatsApp group", value=setup["group_id"], options=options, dense=True,
                            expand=True, enable_filter=True, editable=True, menu_height=360,
                            disabled=not linked,
                            helper_text=None if linked else "Link WhatsApp on the left to choose a group")

        async def save_group(e):
            chosen = next((name for group_id, name in self.groups if group_id == group.value), None)
            if group.value and chosen:
                await save(group_id=group.value, group_name=chosen)
                update_preview()

        group.on_select = save_group

        async def reload_groups(e):
            await self._load_groups(refresh=True)
            await self.refresh()
            self.app.toast("Group list updated.")

        # Lesson times
        time_rows = ft.Column(spacing=6)
        for row in times:
            time_rows.controls.append(self._time_row(row))
        if not times:
            time_rows.controls.append(ft.Text("No lesson times yet.", size=12, color=ft.Colors.ON_SURFACE_VARIANT))

        day = ft.Dropdown(label="Day", dense=True, width=190, value="6" if date.today().weekday() != 6 else "0",
                          options=[ft.DropdownOption(key=str(index), text=f"Every {name}")
                                   for index, name in enumerate(meetings.WEEKDAYS)]
                                  + [ft.DropdownOption(key=ONE_DATE, text="One date…")])
        start = ft.TextField(label="Starts at", hint_text="9:30", dense=True, width=120)
        picked = {"date": None}
        picked_text = ft.Text("", size=12, color=ft.Colors.PRIMARY)

        def pick_date(e=None):
            def chosen(ev):
                if picker.value:
                    # Flet sends local midnight as UTC (a day early east of UTC): back to local time first.
                    # astimezone() leaves a naive (already local) value as it is.
                    value = picker.value
                    picked["date"] = value.astimezone().date() if isinstance(value, datetime) else value
                    picked_text.value = picked["date"].strftime("%a %d %b %Y")
                    self.app.page.update()

            picker = ft.DatePicker(first_date=date.today(), last_date=date.today() + timedelta(days=400),
                                   on_change=chosen)
            self.app.page.show_dialog(picker)

        async def day_chosen(e):
            if day.value == ONE_DATE:
                pick_date()
            else:
                picked["date"] = None
                picked_text.value = ""
                self.app.page.update()

        day.on_select = day_chosen

        async def add_time(e):
            weekday = None if day.value == ONE_DATE else int(day.value)
            if day.value == ONE_DATE and not picked["date"]:
                start.error = "Pick the date first"
                self.app.page.update()
                return
            try:
                await asyncio.to_thread(meetings.add_lesson_time, class_id, start.value,
                                        weekday, picked["date"] if weekday is None else None)
            except ValueError as error:
                start.error = str(error)
                self.app.page.update()
                return
            await self.refresh()

        start.on_submit = add_time

        # When and what to send
        when = ft.Dropdown(label="Send the link", dense=True, width=230, value=str(setup["minutes_before"]),
                           options=[ft.DropdownOption(key=str(minutes), text=text)
                                    for minutes, text in meetings.SEND_OPTIONS])

        async def save_when(e):
            await save(minutes_before=int(when.value))

        when.on_select = save_when

        message = ft.TextField(label="Message", value=typed.get("message", setup["message"]), multiline=True,
                               min_lines=3, max_lines=6,
                               dense=True, helper="{class}, {day}, {time}, {link} and {passcode} are filled in for you")
        preview = ft.Text("", size=13, selectable=True)

        def update_preview():
            draft = dict(setup, message=message.value or meetings.DEFAULT_MESSAGE,
                         link=setup["link"] or "https://zoom.us/j/…", passcode=(passcode.value or "").strip())
            upcoming = meetings.next_message(setup, times)
            preview.value = meetings.render_message(draft, class_name, upcoming[1] if upcoming else None)

        async def save_message(e):
            text = (message.value or "").strip()
            if text and text != setup["message"]:
                await save(message=text)
            update_preview()
            self.app.page.update()

        message.on_blur = save_message
        message.on_change = lambda e: (update_preview(), self.app.page.update())

        def reset_message(e):
            message.value = meetings.DEFAULT_MESSAGE
            self.app.page.run_task(save_message, None)

        auto = ft.Switch(label="Send automatically before every lesson", value=not setup["paused"])

        async def save_auto(e):
            await save(paused=0 if auto.value else 1)

        auto.on_change = save_auto

        # Status and history
        status = ft.Text("", size=13, weight=ft.FontWeight.W_600)

        def show_status():
            missing = meetings.problems(setup, times)
            if missing:
                status.value = "Not sending yet: add " + " and ".join(missing) + "."
                status.color = ft.Colors.ERROR
            elif setup["paused"]:
                status.value = "Paused. Turn on the switch to send links again."
                status.color = ft.Colors.ON_SURFACE_VARIANT
            else:
                upcoming = meetings.next_message(setup, times, sent=school_class["sent"])
                status.color = ft.Colors.PRIMARY
                if not upcoming:
                    status.value = "No lessons coming up."
                elif upcoming[0] <= datetime.now():
                    status.value = (f"The link for the {upcoming[1].strftime('%H:%M')} lesson is being sent now. "
                                    "If it fails, I try again every 2 minutes until the lesson starts.")
                else:
                    status.value = (f"Next message: {upcoming[0].strftime('%A %d %b at %H:%M')} to "
                                    f"{setup['group_name']}")

        history = [self._history_row(row) for row in recent] or [
            ft.Text("Nothing sent yet.", size=12, color=ft.Colors.ON_SURFACE_VARIANT)]

        async def send_now(e):
            upcoming = meetings.next_message(setup, times)   # the lesson about to start, sent or not
            await self._confirm_send_now(class_id, class_name, dict(setup, message=message.value or setup["message"]),
                                         upcoming[1] if upcoming else None, upcoming[2]["id"] if upcoming else None)

        show_status()
        update_preview()
        self._form_class, self._form_boxes = class_id, {"link": link, "passcode": passcode, "message": message}
        return ft.Column([
            ft.Row([ft.Text(class_name, size=20, weight=ft.FontWeight.BOLD), ft.Container(expand=True),
                    ft.OutlinedButton("Send now", icon=ft.Icons.SEND, on_click=send_now,
                                      disabled=not (linked and setup["link"] and setup["group_id"]))],
                   vertical_alignment=ft.CrossAxisAlignment.CENTER),
            status,
            ft.Row([link, passcode], spacing=10),
            ft.Row([group, ft.IconButton(icon=ft.Icons.REFRESH, tooltip="Reload my WhatsApp groups",
                                         on_click=reload_groups, disabled=not linked)], spacing=4),
            ft.Text("Lesson times", size=14, weight=ft.FontWeight.W_600),
            time_rows,
            ft.Row([day, start, ft.FilledTonalButton("Add", icon=ft.Icons.ADD, on_click=add_time), picked_text],
                   spacing=10, vertical_alignment=ft.CrossAxisAlignment.START, wrap=True),
            ft.Divider(height=1),
            ft.Row([when, auto], spacing=16, wrap=True, vertical_alignment=ft.CrossAxisAlignment.CENTER),
            message,
            ft.Row([ft.Text("What the group will see", size=13, weight=ft.FontWeight.W_600),
                    ft.TextButton("Use the standard message", on_click=reset_message)],
                   alignment=ft.MainAxisAlignment.SPACE_BETWEEN),
            ft.Container(preview, padding=ft.Padding.all(12), border_radius=ft.BorderRadius.all(12),
                         bgcolor=ft.Colors.with_opacity(0.10, ft.Colors.GREEN)),
            ft.Text("Recently sent", size=14, weight=ft.FontWeight.W_600),
            ft.Column(history, spacing=4),
        ], spacing=12, scroll=ft.ScrollMode.AUTO, expand=True, horizontal_alignment=ft.CrossAxisAlignment.STRETCH)

    def _time_row(self, row):
        async def delete(e):
            await asyncio.to_thread(meetings.delete_lesson_time, row["id"])
            await self.refresh()

        return row_box(ft.Row([
            ft.Icon(ft.Icons.EVENT_REPEAT if row["weekday"] is not None else ft.Icons.EVENT, size=18,
                    color=ft.Colors.PRIMARY),
            ft.Text(meetings.describe_time(row), expand=True),
            ft.IconButton(icon=ft.Icons.DELETE_OUTLINE, icon_size=18, tooltip="Remove this lesson time",
                          on_click=delete),
        ], vertical_alignment=ft.CrossAxisAlignment.CENTER))

    def _history_row(self, row):
        when = datetime.fromisoformat(row["sent_at"]).strftime("%a %d %b, %H:%M")
        what = "Sent now by you" if row["lesson_id"] is None else (
            "Lesson at " + datetime.fromisoformat(row["lesson_at"]).strftime("%H:%M"))
        return ft.Row([
            ft.Icon(ft.Icons.CHECK_CIRCLE if row["ok"] else ft.Icons.ERROR_OUTLINE, size=16,
                    color=ft.Colors.GREEN if row["ok"] else ft.Colors.ERROR),
            ft.Text(f"{when} · {what}" + ("" if row["ok"] else f" · not sent: {row['detail']}"), size=12,
                    expand=True),
        ], spacing=8)

    async def _refresh_tiles(self):
        classes = await asyncio.to_thread(meetings.classes_overview)
        self.class_list.controls = [self._class_tile(school_class) for school_class in classes]
        self.app.page.update()

    async def _confirm_send_now(self, class_id, class_name, setup, lesson_at=None, lesson_id=None):
        text = meetings.render_message(setup, class_name, lesson_at)

        async def yes(e):
            if not self._close(dialog):
                return   # Send was pressed twice
            try:
                await asyncio.to_thread(self.wa.send, setup["group_id"], text)
            except RuntimeError as error:
                await asyncio.to_thread(meetings.record, class_id, None, None, False, str(error))
                self.app.toast(str(error), error=True)
            else:
                # Counts as that lesson's message, so it isn't sent again automatically
                await asyncio.to_thread(meetings.record, class_id, lesson_id, lesson_at, True)
                self.app.toast(f"Sent to {setup['group_name']}.")
            await self.refresh()

        dialog = ft.AlertDialog(
            title=ft.Text(f"Send to {setup['group_name']} now?"),
            content=ft.Container(width=420, content=ft.Column([
                ft.Text("Everyone in the group will get this message:", size=13),
                ft.Container(ft.Text(text, size=13, selectable=True), padding=ft.Padding.all(12),
                             border_radius=ft.BorderRadius.all(12),
                             bgcolor=ft.Colors.with_opacity(0.10, ft.Colors.GREEN)),
            ], tight=True, spacing=10)),
            actions=[ft.TextButton("Cancel", on_click=lambda e: self._close(dialog)),
                     ft.FilledButton("Send", icon=ft.Icons.SEND, on_click=yes)],
        )
        self.app.page.show_dialog(dialog)

    # ---------- sending on time ----------

    async def _scheduler(self):
        # Reconnect first: until then WhatsApp looks unlinked, and a lesson due now would fail for nothing
        await self._reconnect_if_linked()
        while True:
            try:
                await self._send_due()
            except Exception:
                log.exception("Sending lesson links failed")
            await asyncio.sleep(CHECK_EVERY)

    async def _send_due(self):
        now = datetime.now()
        due = await asyncio.to_thread(meetings.due_messages, now)
        # Still connecting: look again in a moment. But a lesson whose last chance may pass before then is
        # tried anyway, so its failure is recorded and shown instead of the link silently never going out
        closing = now - meetings.LATE_LIMIT + timedelta(seconds=2 * CHECK_EVERY)
        if self.wa.state in (whatsapp.STARTING, whatsapp.RECONNECTING):
            due = [item for item in due if item["lesson_at"] <= closing]
        if not due:
            return
        for item in due:
            setup, lesson_at = item["setup"], item["lesson_at"]
            text = meetings.render_message(setup, item["class_name"], lesson_at)
            key = (item["lesson"]["id"], lesson_at)
            try:
                await asyncio.to_thread(self.wa.send, setup["group_id"], text)
            except RuntimeError as error:
                await asyncio.to_thread(meetings.record, item["class_id"], item["lesson"]["id"], lesson_at,
                                        False, str(error))
                if key not in self._warned:
                    self._warned.add(key)
                    retry = "" if lesson_at <= closing else " I'll keep trying until the lesson starts."
                    self.app.toast(f"Couldn't send {item['class_name']}'s lesson link: {error}{retry}", error=True)
                continue
            await asyncio.to_thread(meetings.record, item["class_id"], item["lesson"]["id"], lesson_at, True)
            self.app.toast(f"Sent {item['class_name']}'s Zoom link to {setup['group_name']}.")
        await self.refresh()

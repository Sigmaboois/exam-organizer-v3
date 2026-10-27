"""
Sends messages to the teacher's WhatsApp groups from this PC.

The app links to WhatsApp the same way WhatsApp Web does: the teacher scans a QR code once
(WhatsApp > Settings > Linked devices > Link a device) and it stays linked, across restarts, until
they unlink it on their phone. Nothing is typed or clicked for them: messages go straight to
WhatsApp, as from any linked device.

WhatsApp has no official way to post in normal groups, so this uses neonize (Python bindings for
whatsmeow, an unofficial WhatsApp client). The login is kept in data/whatsapp.db.
"""

import logging
import os
import sqlite3
import threading
import uuid
from contextlib import closing

DB_PATH = os.path.join("data", "whatsapp.db")

OFF = "off"                  # not linked
STARTING = "starting"        # connecting to WhatsApp
SCAN = "scan"                # showing a QR code, waiting for the phone
EXPIRED = "expired"          # nobody scanned the codes in time
CONNECTED = "connected"      # linked and online
RECONNECTING = "reconnecting"
ERROR = "error"

# WhatsApp shows a code for 60 seconds, then a new one every 20 seconds, six in all. After the last
# one nothing more happens, so the codes count as expired once none has come for a while.
FIRST_CODE_SECONDS = 60
NEXT_CODE_SECONDS = 20

log = logging.getLogger("exam_organizer.whatsapp")


class WhatsApp:
    """One link to WhatsApp for the whole app. Safe to call from any thread."""

    def __init__(self):
        self.state = OFF
        self.qr = None            # text of the current QR code while state == SCAN
        self.error = None         # what went wrong, while state == ERROR
        self.account = None       # "+20 100 123 4567", once linked
        self.on_change = lambda: None
        self._client = None
        self._thread = None
        self._lock = threading.Lock()
        self._groups = None
        self._codes = 0
        self._expiry = None

    # ---------- linking ----------

    def has_login(self):
        """True if this PC was linked before (it reconnects without a QR code)."""
        if not os.path.exists(DB_PATH):
            return False
        # Read the table directly: neonize's get_all_devices_from_db breaks on a WhatsApp name with a comma
        try:
            with closing(sqlite3.connect(DB_PATH)) as db:
                return db.execute("SELECT 1 FROM whatsmeow_device LIMIT 1").fetchone() is not None
        except Exception:
            log.exception("Couldn't read the WhatsApp login")
            return False

    def start(self):
        """Connect (showing a QR code if this PC isn't linked yet). Does nothing if already running."""
        with self._lock:
            if self._thread and self._thread.is_alive():
                if self.state not in (EXPIRED, ERROR):
                    return
                # The old connection is stuck waiting (or broken): start a fresh one
                self._stop_client()
            self._codes = 0
            self._set(STARTING)
            self._thread = threading.Thread(target=self._run, name="whatsapp", daemon=True)
            self._thread.start()

    def _new_client(self):
        import neonize.client as neonize_client
        from neonize.events import (ClientOutdatedEv, ConnectedEv, ConnectFailureEv, DisconnectedEv,
                                    LoggedOutEv, PairStatusEv, StreamReplacedEv, TemporaryBanEv)

        # neonize keeps its own worker thread non-daemon, which would stop the app from closing
        real_thread = threading.Thread
        neonize_client.Thread = lambda *args, daemon=None, **kwargs: real_thread(*args, daemon=True, **kwargs)
        os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)

        # A fresh id each time: neonize stops a client by its id once the Python object is thrown away,
        # so with a shared id an old client would stop the current one. The login stays in DB_PATH.
        client = neonize_client.NewClient(DB_PATH, uuid=f"exam-organizer-{uuid.uuid4().hex}")

        def only_current(handler):
            # A client given up on (e.g. its QR codes expired) can still fire events: ignore them
            def run(*args):
                if client is self._client:
                    handler(*args)
            return run

        def fail(message):
            return only_current(lambda c, e: self._fail(message))

        client.event.qr(only_current(lambda c, data: self._got_qr(data)))
        client.event(ConnectedEv)(only_current(lambda c, e: self._connected()))
        client.event(PairStatusEv)(only_current(lambda c, e: self._set(STARTING)))
        client.event(DisconnectedEv)(only_current(
            lambda c, e: self._set(RECONNECTING) if self.state == CONNECTED else None))
        client.event(LoggedOutEv)(only_current(lambda c, e: self._logged_out()))
        client.event(StreamReplacedEv)(fail(
            "WhatsApp was opened by another copy of Exam Organizer. Close the other one, then press Reconnect."))
        client.event(TemporaryBanEv)(fail(
            "WhatsApp has blocked this link for a while. Try again later, and send fewer messages."))
        client.event(ConnectFailureEv)(fail("WhatsApp refused the connection. Press Reconnect."))
        client.event(ClientOutdatedEv)(fail(
            "This version of Exam Organizer is too old for WhatsApp. Install the latest version."))
        return client

    def _run(self):
        try:
            client = self._client = self._new_client()
            client.connect()   # blocks until stopped, or until nobody scanned the QR codes in time
        except Exception as error:
            log.exception("WhatsApp stopped")
            if self.state not in (OFF, ERROR):
                self._fail(f"Couldn't connect to WhatsApp: {error}")
            return
        if client is self._client and self.state in (SCAN, STARTING):
            self._set(EXPIRED)

    def _stop_client(self):
        client, self._client = self._client, None
        if client:
            try:
                client.stop()
            except Exception:
                log.exception("Couldn't stop the WhatsApp client")

    def unlink(self):
        """Log this PC out of WhatsApp (it disappears from Linked devices on the phone).

        Returns False if WhatsApp couldn't be told (e.g. while reconnecting), so the phone may still list
        this PC. Raises RuntimeError if the login couldn't be deleted either: it would reconnect next time.
        """
        with self._lock:
            client = self._client
            told = False
            # Also while reconnecting or after an error, as long as the client still runs: it fails if offline
            if client and self._thread and self._thread.is_alive():
                try:
                    client.logout()   # also deletes the login from DB_PATH
                    told = True
                except Exception:
                    log.exception("Logout failed")
            self._stop_client()
            self._groups = None
            self.account = None
            failed = None
            try:
                for suffix in ("", "-wal", "-shm"):
                    if os.path.exists(DB_PATH + suffix):
                        os.remove(DB_PATH + suffix)
            except OSError as error:
                log.exception("Couldn't delete the WhatsApp login")
                failed = error
            self._set(OFF)
            if failed and not told:
                raise RuntimeError(f"Couldn't remove the WhatsApp login from this PC ({failed}). "
                                   "Remove this PC under Linked devices on your phone.")
            return told

    # ---------- events (called on WhatsApp's threads) ----------

    def _set(self, state):
        self.state = state
        if state != SCAN:
            self.qr = None
        if state != ERROR:
            self.error = None
        try:
            self.on_change()
        except Exception:
            log.exception("on_change failed")

    def _got_qr(self, data):
        self.qr = data.decode() if isinstance(data, bytes) else str(data)
        self._codes += 1
        self.state = SCAN
        if self._expiry:
            self._expiry.cancel()
        code = self.qr
        wait = (FIRST_CODE_SECONDS if self._codes == 1 else NEXT_CODE_SECONDS) + 8
        self._expiry = threading.Timer(wait, lambda: self._set(EXPIRED) if self.state == SCAN and self.qr == code
                                       else None)
        self._expiry.daemon = True
        self._expiry.start()
        try:
            self.on_change()
        except Exception:
            log.exception("on_change failed")

    def _connected(self):
        try:
            me = self._client.get_me()
            number = me.JID.User if me and me.JID.User else ""
            self.account = f"+{number}" if number else None
        except Exception:
            self.account = None
        self._groups = None
        self._set(CONNECTED)

    def _logged_out(self):
        self._groups = None
        self.account = None
        self._fail("This PC was unlinked from WhatsApp on the phone. Press Link WhatsApp to scan a new code.")

    def _fail(self, message):
        self.error = message
        self._set(ERROR)

    # ---------- using it ----------

    @property
    def ready(self):
        return self.state == CONNECTED and self._client is not None

    def groups(self, refresh=False):
        """[(group_id, name)] of every group this WhatsApp account is in, sorted by name."""
        if not self.ready:
            raise RuntimeError("Link WhatsApp first.")
        if self._groups is None or refresh:
            from neonize.utils.jid import Jid2String
            found = []
            for group in self._client.get_joined_groups():
                name = (group.GroupName.Name or "").strip() or "Unnamed group"
                found.append((Jid2String(group.JID), name))
            found.sort(key=lambda item: item[1].lower())
            self._groups = found
        return list(self._groups)

    def send(self, group_id, text):
        """Send text to a group. Raises RuntimeError with a message for the teacher if it can't."""
        if not self.ready:
            if self.state in (STARTING, RECONNECTING):
                raise RuntimeError("WhatsApp was still connecting.")
            raise RuntimeError("WhatsApp isn't linked on this PC.")
        from neonize.utils.jid import build_jid
        user, _, server = group_id.partition("@")
        try:
            self._client.send_message(build_jid(user, server or "g.us"), text)
        except Exception as error:
            log.exception("Sending failed")
            raise RuntimeError(f"WhatsApp didn't accept the message: {error}") from error


def qr_image(text):
    """The QR code as PNG bytes, for showing on screen."""
    import io

    import segno
    buffer = io.BytesIO()
    segno.make_qr(text).save(buffer, kind="png", scale=8, border=2, dark="#111111", light="#ffffff")
    return buffer.getvalue()

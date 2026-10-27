package com.gasserelshafei.examorganizer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import java.io.File

/**
 * The phone's link to the teacher's WhatsApp, as its own linked device (like WhatsApp Web).
 * Messages go straight to WhatsApp, so they're sent with the screen locked and nothing is tapped.
 *
 * The work is done by wabridge (Go, see android/wabridge): whatsmeow, an unofficial WhatsApp
 * client, because WhatsApp has no official way to post in normal groups. The login is kept in
 * the app's no-backup folder, so it never ends up in a phone backup.
 * Calls that talk to WhatsApp block: make them off the main thread.
 */
object WhatsAppLink {
    enum class Phase { OFF, STARTING, WAITING, CONNECTED, OFFLINE, EXPIRED, ERROR }

    var phase by mutableStateOf(Phase.OFF)
        private set
    var qr by mutableStateOf<String?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set
    var account by mutableStateOf<String?>(null)
        private set
    var groups by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set

    /** True while the Lessons screen is showing, so the background sender leaves the link online. */
    @Volatile var screenOpen = false
    /** True while the background sender connects and sends, so leaving the screen doesn't cut it off. */
    @Volatile var sending = false
    private var opened = false

    private val listener = object : wabridge.Listener {
        override fun onState(state: String, detail: String) {
            when (state) {
                "starting" -> phase = Phase.STARTING
                "code" -> { qr = detail; phase = Phase.WAITING }
                "connected" -> { account = detail.ifEmpty { null }; qr = null; problem = null; phase = Phase.CONNECTED }
                "disconnected" -> if (phase == Phase.CONNECTED) phase = Phase.OFFLINE
                "expired" -> { qr = null; phase = Phase.EXPIRED }
                "logged_out" -> { account = null; groups = emptyList(); problem = detail; phase = Phase.ERROR; opened = false }
                "error" -> { problem = detail; phase = Phase.ERROR }
            }
        }
    }

    @Synchronized fun open(context: Context) {
        if (opened) return
        val db = File(context.applicationContext.noBackupFilesDir, "whatsapp.db")
        wabridge.Wabridge.open(db.path, listener)
        opened = true
        if (wabridge.Wabridge.isLinked()) account = wabridge.Wabridge.account()
    }

    fun isLinked(context: Context): Boolean { open(context); return wabridge.Wabridge.isLinked() }

    /** Go online. Shows QR codes (state WAITING) if this phone isn't linked yet. */
    fun connect(context: Context) {
        open(context)
        if (phase == Phase.EXPIRED || phase == Phase.ERROR) qr = null
        try {
            wabridge.Wabridge.connect()
        } catch (error: Exception) {
            // whatsmeow doesn't retry a first connect that failed: say so, instead of "Connecting…" for ever
            problem = "Couldn't reach WhatsApp. Is the phone online?"
            phase = Phase.ERROR
            throw error
        }
    }

    /** The 8-letter code to type in WhatsApp on this phone (Link with phone number instead). */
    fun pairingCode(context: Context, phone: String): String {
        open(context)
        return wabridge.Wabridge.linkWithPhoneNumber(phone)
    }

    fun waitConnected(seconds: Int): Boolean = wabridge.Wabridge.waitConnected(seconds.toLong())

    fun loadGroups(): List<Pair<String, String>> {
        val json = JSONArray(wabridge.Wabridge.groups())
        val found = (0 until json.length()).map { json.getJSONObject(it).let { g -> g.getString("id") to g.getString("name") } }
        groups = found
        return found
    }

    fun send(groupId: String, text: String, seconds: Int = 60) = wabridge.Wabridge.send(groupId, text, seconds.toLong())

    fun disconnect() {
        if (!screenOpen && !sending) wabridge.Wabridge.disconnect()
    }

    /** False when the saved login couldn't be removed (links would keep going out). */
    @Synchronized fun unlink(): Boolean {
        val failed = runCatching { wabridge.Wabridge.unlink() }.exceptionOrNull()
        if (failed != null) {
            // The login is still saved, so links would keep going out: say so instead of "unlinked"
            problem = "Couldn't unlink WhatsApp: ${failed.message}"
            phase = Phase.ERROR
            return false
        }
        opened = false
        account = null
        groups = emptyList()
        qr = null
        problem = null
        phase = Phase.OFF
        return true
    }
}

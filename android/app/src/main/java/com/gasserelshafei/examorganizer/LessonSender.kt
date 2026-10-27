package com.gasserelshafei.examorganizer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends the lesson links on time, also when the app is closed and the phone is locked:
 * an exact alarm wakes [LessonAlarmReceiver] when the next link is due, which starts
 * [LessonSendService] for the few seconds it takes to send.
 */
object LessonAlarms {
    const val ACTION_SEND = "com.gasserelshafei.examorganizer.SEND_LESSON_LINKS"
    private const val TAG = "LessonAlarms"

    /** Set the alarm for the next link (or clear it). Call after every change to lessons. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(app, 0, Intent(app, LessonAlarmReceiver::class.java).setAction(ACTION_SEND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val wake = LessonBook.get(app).nextWake()
        if (wake == null) {
            alarms.cancel(intent)
            return
        }
        val at = wake.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } catch (error: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
        Log.i(TAG, "Next lesson link check at $wake")
    }
}

/** The alarm went off (or the phone restarted, or the clock changed). */
class LessonAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LessonAlarms.ACTION_SEND) {
            LessonAlarms.schedule(context)
            return
        }
        val service = Intent(context, LessonSendService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service) else context.startService(service)
        } catch (error: Exception) {
            // Android didn't allow a background service (an inexact alarm): send from here instead,
            // quickly, as Android stops a receiver's goAsync work after 60 s
            Log.w("LessonAlarms", "Couldn't start the sending service", error)
            val pending = goAsync()
            Thread {
                try { LessonSendService.sendDue(context.applicationContext, quick = true) } finally { pending.finish() }
            }.start()
        }
    }
}

/** Connects to WhatsApp, sends every link that's due, then stops. Shows a notification while it works. */
class LessonSendService : Service() {
    // Runs started and not finished yet, and the newest start: the last run to end stops the service
    private var running = 0
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.channels(this)
        val working = Notification.Builder(this, Notifications.WORKING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Sending lesson links…")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(Notifications.WORKING_ID, working, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(Notifications.WORKING_ID, working)
        synchronized(this) { running++; lastStartId = startId }
        Thread {
            try {
                sendDue(applicationContext)
            } finally {
                synchronized(this@LessonSendService) {
                    if (--running == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStartId)
                    }
                }
            }
        }.start()
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "LessonSend"
        private val busy = AtomicBoolean(false)

        /**
         * Send everything due now and plan the next alarm. Blocks: run off the main thread.
         * A [quick] run (inside a receiver's goAsync) ends within about 45 s: links it has no time
         * left for stay due, and the next alarm, seconds later, sends them.
         */
        fun sendDue(context: Context, quick: Boolean = false) {
            val book = LessonBook.get(context)
            // One run at a time: an alarm set while a run is sending would send the same links again.
            // A second run just ends; the one in progress plans the next alarm when it's done.
            if (!busy.compareAndSet(false, true)) return
            // Keeps the phone awake while connecting and sending, also with the screen locked.
            // The timeout is only a cap: connecting takes up to 45 s and each send up to 60 s.
            val wake = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ExamOrganizer:LessonSend")
                .apply { setReferenceCounted(false) }
            // In a quick run every wait is cut to what's left of its 45 s
            val deadline = System.currentTimeMillis() + 45_000
            fun seconds(most: Int) = if (!quick) most else minOf(most.toLong(), (deadline - System.currentTimeMillis()) / 1000).toInt()
            try {
                wake.acquire(10 * 60 * 1000L)
                val due = book.due(LocalDateTime.now())
                if (due.isEmpty()) return
                WhatsAppLink.sending = true
                val problem: String? = try {
                    if (!WhatsAppLink.isLinked(context)) "WhatsApp isn't linked on this phone."
                    else {
                        WhatsAppLink.connect(context)
                        if (WhatsAppLink.waitConnected(seconds(if (quick) 25 else 45))) null else "Couldn't reach WhatsApp. Is the phone online?"
                    }
                } catch (error: Exception) {
                    "Couldn't connect to WhatsApp: ${error.message}"
                }
                for (item in due) {
                    val limit = seconds(60)
                    if (problem == null && limit < 5) break   // out of time: the rest stay due for the next alarm
                    val failure = problem ?: try {
                        WhatsAppLink.send(item.setup.groupId!!, Lessons.render(item.setup, item.className, item.lessonAt), limit)
                        null
                    } catch (error: Exception) {
                        "WhatsApp didn't accept the message: ${error.message}"
                    }
                    book.record(item.classId, item.lesson.id, item.lessonAt, failure == null, failure ?: "")
                    if (failure == null) Notifications.tell(context, "Sent ${item.className}'s lesson link",
                        "To ${item.setup.groupName ?: "the group"}, for the lesson at ${item.lessonAt.toLocalTime()}.")
                    else Notifications.tell(context, "Couldn't send ${item.className}'s lesson link",
                        "$failure I'll keep trying until the lesson starts.", error = true)
                    Log.i(TAG, "${item.className} ${item.lessonAt}: ${failure ?: "sent"}")
                }
                WhatsAppLink.sending = false
                WhatsAppLink.disconnect()
            } catch (error: Exception) {
                Log.e(TAG, "Sending lesson links failed", error)
            } finally {
                WhatsAppLink.sending = false
                busy.set(false)
                try { LessonAlarms.schedule(context) } finally { wake.release() }
            }
        }
    }
}

object Notifications {
    const val WORKING = "sending"
    const val RESULTS = "lesson_links"
    const val WORKING_ID = 7001

    fun channels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(WORKING, "Sending lesson links", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(RESULTS, "Lesson links sent", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun tell(context: Context, title: String, text: String, error: Boolean = false) {
        channels(context)
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.OPEN_LESSONS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, RESULTS)
            .setSmallIcon(if (error) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(title.hashCode(), notification)
        }
    }
}

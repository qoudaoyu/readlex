package com.readvocab.app.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.readvocab.app.R
import com.readvocab.app.ReadVocabApp
import com.readvocab.app.data.AppSettings
import com.readvocab.app.data.Stats
import com.readvocab.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Daily study reminder. Uses an inexact alarm (no exact-alarm permission needed); the
 * alarm reschedules itself each time it fires, and again after reboot or a settings change.
 */
object Reminders {
    const val CHANNEL_ID = "daily_reminder"
    private const val NOTIFICATION_ID = 1
    private const val REQUEST_CODE = 100
    /** Android may deliver the alarm anywhere inside this window, which saves battery. */
    private const val WINDOW_MS = 10 * 60 * 1000L
    const val EXTRA_TAB = "tab"

    fun sync(context: Context, settings: AppSettings) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(context)
        am.cancel(pi)
        if (!settings.reminderEnabled) return
        val at = nextTrigger(System.currentTimeMillis(), settings.reminderMinuteOfDay, ZoneId.systemDefault())
        am.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pi)
    }

    /** The next time-of-day occurrence strictly after [now]. */
    fun nextTrigger(now: Long, minuteOfDay: Int, zone: ZoneId): Long {
        val nowDt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
        var t = nowDt.toLocalDate().atTime(minuteOfDay / 60, minuteOfDay % 60)
        if (!t.isAfter(nowDt)) t = t.plusDays(1)
        return t.atZone(zone).toInstant().toEpochMilli()
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "每日学习提醒", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "每天定时提醒你复习生词、读一会儿英文" }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Title and body for today's reminder, or null when there is nothing worth interrupting for. */
    fun message(stats: Stats): Pair<String, String>? {
        val streak = if (stats.streak > 0) "已连续 ${stats.streak} 天，" else ""
        return when {
            stats.dueNow > 0 -> "有 ${stats.dueNow} 个词等你复习" to "${streak}读一篇复习短文，或者去复习页翻几张卡片。"
            !stats.today.active -> "今天还没读英文" to "${streak}读 10 分钟，碰到的生词会自动排进复习。"
            else -> null
        }
    }

    fun show(context: Context, stats: Stats) {
        val (title, body) = message(stats) ?: return
        if (!canNotify(context)) return
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_TAB, if (stats.dueNow > 0) "review" else "library")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ReadVocabApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val c = app.container
                Reminders.sync(context, c.settings.current)
                if (c.settings.current.reminderEnabled) Reminders.show(context, c.stats.observe().first())
            } finally {
                pending.finish()
            }
        }
    }
}

/** Alarms are cleared on reboot and when the clock or time zone changes; put ours back. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ReadVocabApp
        Reminders.sync(context, app.container.settings.current)
    }
}

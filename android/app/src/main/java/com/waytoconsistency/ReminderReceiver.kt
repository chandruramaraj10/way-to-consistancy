package com.waytoconsistency

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SNOOZE -> ReminderScheduler.snooze(context, intent)
            else -> showReminder(context, intent)
        }
    }

    private fun showReminder(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels(manager)

        val title = intent.getStringExtra("title") ?: "Way to Consistency"
        val body = intent.getStringExtra("body") ?: "A reminder is due."
        val silent = isQuietNow(intent) || intent.getStringExtra("sound") in listOf("none", "silent")
        val vibrate = intent.getBooleanExtra("vibrate", true)
        val channelId = channelId(silent, vibrate)
        val openIntent = activityIntent(context, id, "open")
        val snoozeIntent = PendingIntent.getBroadcast(
            context,
            id.hashCode() xor 0x51A,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_SNOOZE
                data = Uri.parse("wtc://snooze/${Uri.encode(id)}")
                putExtra("id", id)
                putExtra("snoozeMinutes", intent.getIntExtra("snoozeMinutes", 10))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val doneIntent = activityIntent(context, id, "done")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context).setSound(if (silent) null else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(android.R.drawable.checkbox_on_background, "Done", doneIntent)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "Snooze", snoozeIntent)
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            notification.flags = notification.flags or Notification.FLAG_INSISTENT
            if (!vibrate) notification.vibrate = longArrayOf(0)
        }
        manager.notify(id.hashCode(), notification)
    }

    private fun activityIntent(context: Context, id: String, action: String): PendingIntent = PendingIntent.getActivity(
        context,
        id.hashCode() xor action.hashCode(),
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("reminderId", id)
            putExtra("reminderAction", action)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun isQuietNow(intent: Intent): Boolean {
        if (!intent.getBooleanExtra("quietOn", false)) return false
        val now = java.util.Calendar.getInstance()
        val current = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val start = parseTime(intent.getStringExtra("quietStart"), 22 * 60)
        val end = parseTime(intent.getStringExtra("quietEnd"), 7 * 60)
        return if (start <= end) current in start until end else current >= start || current < end
    }

    private fun parseTime(value: String?, fallback: Int): Int {
        val parts = value?.split(":") ?: return fallback
        return try { parts[0].toInt() * 60 + parts[1].toInt() } catch (_: Exception) { fallback }
    }

    private fun channelId(silent: Boolean, vibrate: Boolean): String = when {
        silent && vibrate -> "reminders_silent_vibrate"
        silent -> "reminders_silent"
        vibrate -> "reminders_sound_vibrate"
        else -> "reminders_sound"
    }

    private fun createChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
        listOf(
            NotificationChannel("reminders_sound_vibrate", "Reminders", NotificationManager.IMPORTANCE_HIGH).apply { setSound(sound, attrs); enableVibration(true) },
            NotificationChannel("reminders_sound", "Reminders without vibration", NotificationManager.IMPORTANCE_HIGH).apply { setSound(sound, attrs); enableVibration(false) },
            NotificationChannel("reminders_silent_vibrate", "Silent reminders with vibration", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(true) },
            NotificationChannel("reminders_silent", "Silent reminders", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); enableVibration(false) }
        ).forEach(manager::createNotificationChannel)
    }

    companion object {
        const val ACTION_SNOOZE = "com.waytoconsistency.SNOOZE_REMINDER"
    }
}
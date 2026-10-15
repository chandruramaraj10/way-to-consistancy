package com.waytoconsistency

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

object ReminderScheduler {
    private const val PREFS = "native_reminders"
    private const val KEY_ITEMS = "items"
    private const val ACTION_TRIGGER = "com.waytoconsistency.TRIGGER_REMINDER"

    fun replaceAll(context: Context, json: String) {
        val items = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString(KEY_ITEMS, "[]")) } catch (_: Exception) { JSONArray() }
        cancelItems(context, old)
        prefs.edit().putString(KEY_ITEMS, items.toString()).apply()
        scheduleItems(context, items)
    }

    fun restore(context: Context) {
        val items = try {
            JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        scheduleItems(context, items)
    }

    fun snooze(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val snoozeMinutes = intent.getIntExtra("snoozeMinutes", 10).coerceIn(1, 120)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = try { JSONArray(prefs.getString(KEY_ITEMS, "[]")) } catch (_: Exception) { JSONArray() }
        var reminder: JSONObject? = null
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            if (item.optString("id") == id) {
                item.put("triggerAt", System.currentTimeMillis() + snoozeMinutes * 60_000L)
                reminder = item
                break
            }
        }
        prefs.edit().putString(KEY_ITEMS, items.toString()).apply()
        reminder?.let { scheduleOne(context, it) }
    }

    fun cancel(context: Context, id: String) {
        val intent = alarmIntent(context, id)
        val pending = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pending != null) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
            pending.cancel()
        }
    }

    private fun cancelItems(context: Context, items: JSONArray) {
        for (index in 0 until items.length()) {
            items.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }?.let { cancel(context, it) }
        }
    }

    private fun scheduleItems(context: Context, items: JSONArray) {
        for (index in 0 until items.length()) {
            items.optJSONObject(index)?.let { scheduleOne(context, it) }
        }
    }

    private fun scheduleOne(context: Context, reminder: JSONObject) {
        val id = reminder.optString("id")
        val triggerAt = reminder.optLong("triggerAt")
        if (id.isBlank() || triggerAt <= System.currentTimeMillis() - 20 * 60_000L) return

        val intent = alarmIntent(context, id).apply {
            putExtra("id", id)
            putExtra("title", reminder.optString("title", "Way to Consistency"))
            putExtra("body", reminder.optString("body", "A reminder is due."))
            putExtra("sound", reminder.optString("sound", "tone:chime"))
            putExtra("vibrate", reminder.optBoolean("vibrate", true))
            putExtra("quietOn", reminder.optBoolean("quietOn", false))
            putExtra("quietStart", reminder.optString("quietStart", "22:00"))
            putExtra("quietEnd", reminder.optString("quietEnd", "07:00"))
            putExtra("snoozeMinutes", reminder.optInt("snoozeMinutes", 10).coerceIn(1, 120))
            putExtra("unlockAt", reminder.optLong("unlockAt"))
        }
        val pending = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    private fun alarmIntent(context: Context, id: String): Intent = Intent(context, ReminderReceiver::class.java).apply {
        action = ACTION_TRIGGER
        data = Uri.parse("wtc://reminder/${Uri.encode(id)}")
    }
}

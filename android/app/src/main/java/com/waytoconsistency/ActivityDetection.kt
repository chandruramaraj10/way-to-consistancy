package com.waytoconsistency

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

object ActivityDetection {
    private const val PREFS = "activity-detection"
    private const val ENABLED = "enabled"
    private const val LAST_PROMPT = "last-prompt"
    private const val COOLDOWN_MS = 30 * 60 * 1000L

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean, done: (Boolean, String) -> Unit) {
        if (enabled && Build.VERSION.SDK_INT >= 29 && context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            done(false, "permission_required")
            return
        }
        val pending = transitionPendingIntent(context)
        val client = ActivityRecognition.getClient(context)
        if (!enabled) {
            client.removeActivityTransitionUpdates(pending).addOnCompleteListener {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, false).apply()
                done(true, "off")
            }
            return
        }
        val transitions = listOf(DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_FOOT).map {
            ActivityTransition.Builder().setActivityType(it).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()
        }
        client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pending)
            .addOnSuccessListener {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, true).apply()
                done(true, "watching")
            }
            .addOnFailureListener { done(false, it.message ?: "subscription_failed") }
    }

    fun restore(context: Context) {
        if (isEnabled(context)) setEnabled(context, true) { _, _ -> }
    }

    private fun transitionPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 813, Intent(context, ActivityTransitionReceiver::class.java).setAction(ActivityTransitionReceiver.ACTION_TRANSITION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    fun mayPrompt(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return System.currentTimeMillis() - prefs.getLong(LAST_PROMPT, 0L) >= COOLDOWN_MS
    }

    fun markPrompt(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(LAST_PROMPT, System.currentTimeMillis()).apply()
    }
}

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISS) {
            ActivityDetection.markPrompt(context)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            return
        }
        if (intent.action != ACTION_TRANSITION || !ActivityTransitionResult.hasResult(intent) || !ActivityDetection.mayPrompt(context)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val event = result.transitionEvents.lastOrNull { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER && it.activityType in supported } ?: return
        showPrompt(context, event)
    }

    private fun showPrompt(context: Context, event: ActivityTransitionEvent) {
        ActivityDetection.markPrompt(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Activity suggestions", NotificationManager.IMPORTANCE_DEFAULT))
        val label = if (event.activityType == DetectedActivity.RUNNING) "running" else "walking"
        val start = PendingIntent.getActivity(context, 814, Intent(context, MainActivity::class.java).putExtra("activityAction", "offerStart").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getBroadcast(context, 815, Intent(context, ActivityTransitionReceiver::class.java).setAction(ACTION_DISMISS), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Looks like you’re $label")
            .setContentText("Start a GPS run? Nothing starts until you confirm.")
            .setContentIntent(start).setAutoCancel(true)
            .addAction(0, "Start run", start).addAction(0, "Not now", dismiss).build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_TRANSITION = "com.waytoconsistency.ACTIVITY_TRANSITION"
        const val ACTION_DISMISS = "com.waytoconsistency.ACTIVITY_DISMISS"
        private const val CHANNEL = "activity-suggestions"
        private const val NOTIFICATION_ID = 933
        private val supported = setOf(DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_FOOT)
    }
}

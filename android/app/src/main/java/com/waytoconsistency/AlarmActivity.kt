package com.waytoconsistency

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class AlarmActivity : Activity() {
    private var player: MediaPlayer? = null
    private val reminderId: String get() = intent.getStringExtra("id").orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(alarmView())
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ReminderReceiver.notificationId(reminderId))
        startAlarmSound()
    }

    private fun alarmView(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(40), dp(28), dp(40))
            setBackgroundColor(Color.rgb(8, 18, 34))
            addView(ImageView(context).apply {
                setImageResource(com.waytoconsistency.R.drawable.app_logo)
                contentDescription = "Way to Consistency reminder"
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
            }, LinearLayout.LayoutParams(dp(84), dp(84)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(TextView(context).apply {
                text = intent.getStringExtra("title") ?: "Way to Consistency"
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, dp(8))
            })
            addView(TextView(context).apply {
                text = intent.getStringExtra("body") ?: "A reminder is due."
                setTextColor(Color.rgb(190, 205, 225))
                textSize = 17f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(28))
            })
            addView(Button(context).apply {
                text = "Snooze ${intent.getIntExtra("snoozeMinutes", 10).coerceIn(1, 120)} min"
                setOnClickListener { snoozeAndClose() }
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Button(context).apply {
                text = "Open task"
                setOnClickListener { openTaskAndClose() }
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(context).apply {
                text = "Press either volume button to silence and snooze"
                setTextColor(Color.rgb(135, 160, 190))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, 0)
            })
        }
    }

    private fun startAlarmSound() {
        val spec = intent.getStringExtra("sound") ?: "device:default"
        val uri = when {
            spec.startsWith("audio:") -> SoundStorage.uri(this, spec)
            spec.startsWith("device:") && spec != "device:default" -> android.net.Uri.parse(spec.removePrefix("device:"))
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } ?: return
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setDataSource(this@AlarmActivity, uri)
            isLooping = true
            setOnPreparedListener { it.start() }
            setOnErrorListener { media, _, _ -> media.release(); player = null; true }
            prepareAsync()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            snoozeAndClose()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun snoozeAndClose() {
        stopSound()
        val locked = intent.getLongExtra("unlockAt", 0L) > System.currentTimeMillis()
        if (!locked) ReminderScheduler.snooze(this, intent)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ReminderReceiver.notificationId(reminderId))
        finishAndRemoveTask()
    }

    private fun openTaskAndClose() {
        stopSound()
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("reminderId", reminderId)
            putExtra("reminderAction", "open")
        })
        finishAndRemoveTask()
    }

    private fun stopSound() {
        player?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
    }

    override fun onDestroy() {
        stopSound()
        super.onDestroy()
    }
}

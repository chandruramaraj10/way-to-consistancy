package com.waytoconsistency

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class RunTrackingService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private var state = JSONObject()
    private val locationModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.MODE_CHANGED_ACTION && !isLocationEnabled()) pauseForLocationDisabled()
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        state = loadState(this)
        val filter = IntentFilter(LocationManager.MODE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(locationModeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(locationModeReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRun()
            ACTION_PAUSE -> pauseRun()
            ACTION_RESUME -> resumeRun()
            ACTION_FINISH -> finishRun()
            else -> if (state.optString("status") == STATUS_RUNNING) {
                recoverRunningState()
                if (state.optString("status") == STATUS_RUNNING) {
                    startForeground(NOTIFICATION_ID, notification())
                    requestLocationUpdates()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        if (state.optString("status") != STATUS_RUNNING) return
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        if (ageMs !in -MAX_FUTURE_FIX_MS..MAX_FIX_AGE_MS) return
        if (!location.hasAccuracy() || location.accuracy > RunDistanceCalculator.MAX_ACCURACY_METERS) {
            state.put("gpsStatus", "poor_accuracy").put("gpsAccuracy", location.accuracy.toDouble())
            broadcastState()
            return
        }
        val fix = RunDistanceCalculator.Fix(location.latitude, location.longitude, location.accuracy.toDouble(), location.time, location.elapsedRealtimeNanos / 1_000_000L)
        val points = state.optJSONArray("points") ?: JSONArray()
        val previous = state.optJSONObject("lastFix")?.let(::fixFromJson)
        val movement = RunDistanceCalculator.movement(previous, fix)
        if (!movement.accepted) {
            state.put("gpsStatus", "recording").put("gpsAccuracy", location.accuracy.toDouble())
            broadcastState()
            return
        }
        val delta = movement.distanceMeters
        val oldDistance = state.optDouble("distanceMeters")
        val distance = oldDistance + delta
        val elapsed = elapsedMillis()
        if (previous != null && delta > 0) {
            val splits = state.optJSONArray("splits") ?: JSONArray()
            RunDistanceCalculator.crossedSplits(oldDistance, distance, state.optLong("lastAcceptedRunElapsedMs"), elapsed).forEach { split ->
                splits.put(JSONObject().put("km", split.kilometer).put("elapsedMs", split.elapsedMs))
            }
            state.put("splits", splits)
        }
        val sequence = state.optLong("nextPointSequence", points.length().toLong())
        points.put(JSONObject()
            .put("lat", location.latitude)
            .put("lon", location.longitude)
            .put("time", location.time)
            .put("elapsedMs", elapsed)
            .put("accuracy", location.accuracy)
            .put("segment", state.optInt("segment"))
            .put("seq", sequence))
        state.put("lastFix", fixToJson(fix))
            .put("lastAcceptedRunElapsedMs", elapsed)
            .put("nextPointSequence", sequence + 1)
            .put("gpsStatus", "recording")
            .put("gpsAccuracy", location.accuracy.toDouble())
        state.put("points", points)
        state.put("distanceMeters", distance)
        persistAndBroadcast()
        updateNotification()
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) pauseForLocationDisabled()
    }

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        try { unregisterReceiver(locationModeReceiver) } catch (_: IllegalArgumentException) {}
        super.onDestroy()
    }

    private fun startRun() {
        if (state.optString("status") == STATUS_RUNNING || state.optString("status") == STATUS_PAUSED) return
        if (!isLocationEnabled()) {
            Log.i(TAG, "Run start blocked because device location services are disabled")
            return
        }
        state = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("status", STATUS_RUNNING)
            .put("startedAt", System.currentTimeMillis())
            .put("segmentStartedAt", SystemClock.elapsedRealtime())
            .put("bootEpochMs", bootEpochMs())
            .put("elapsedMs", 0L)
            .put("distanceMeters", 0.0)
            .put("points", JSONArray())
            .put("splits", JSONArray())
            .put("segment", 0)
            .put("gpsStatus", "acquiring")
        startForeground(NOTIFICATION_ID, notification())
        persistAndBroadcast()
        requestLocationUpdates()
    }

    private fun pauseRun() {
        if (state.optString("status") != STATUS_RUNNING) return
        state.put("elapsedMs", elapsedMillis())
            .put("status", STATUS_PAUSED)
            .put("segment", state.optInt("segment") + 1)
        state.remove("segmentStartedAt")
        state.remove("lastFix")
        locationManager.removeUpdates(this)
        persistAndBroadcast()
        updateNotification()
    }

    private fun resumeRun() {
        if (state.optString("status") != STATUS_PAUSED) return
        if (!isLocationEnabled()) {
            state.put("gpsStatus", "location_disabled")
            persistAndBroadcast()
            return
        }
        state.put("status", STATUS_RUNNING).put("segmentStartedAt", SystemClock.elapsedRealtime()).put("bootEpochMs", bootEpochMs())
        startForeground(NOTIFICATION_ID, notification())
        persistAndBroadcast()
        requestLocationUpdates()
    }

    private fun finishRun() {
        val status = state.optString("status")
        if (status != STATUS_RUNNING && status != STATUS_PAUSED) return
        if (status == STATUS_RUNNING) state.put("elapsedMs", elapsedMillis())
        state.put("status", STATUS_COMPLETED).put("finishedAt", System.currentTimeMillis()).remove("segmentStartedAt")
        locationManager.removeUpdates(this)
        persistAndBroadcast()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun elapsedMillis(): Long {
        val accumulated = state.optLong("elapsedMs")
        val started = state.optLong("segmentStartedAt")
        return if (started > 0 && state.optString("status") == STATUS_RUNNING) {
            accumulated + (SystemClock.elapsedRealtime() - started).coerceAtLeast(0)
        } else accumulated
    }

    private fun fixFromJson(value: JSONObject) = RunDistanceCalculator.Fix(
        value.optDouble("lat", Double.NaN),
        value.optDouble("lon", Double.NaN),
        value.optDouble("accuracy", Double.NaN),
        value.optLong("time"),
        value.optLong("elapsedRealtimeMs", value.optLong("elapsedMs"))
    )

    private fun fixToJson(fix: RunDistanceCalculator.Fix) = JSONObject()
        .put("lat", fix.latitude)
        .put("lon", fix.longitude)
        .put("accuracy", fix.accuracyMeters)
        .put("time", fix.wallTimeMs)
        .put("elapsedRealtimeMs", fix.elapsedRealtimeMs)

    private fun bootEpochMs(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun recoverRunningState() {
        val savedBootEpoch = state.optLong("bootEpochMs")
        val started = state.optLong("segmentStartedAt")
        val rebootedOrClockShifted = savedBootEpoch > 0 && kotlin.math.abs(savedBootEpoch - bootEpochMs()) > BOOT_EPOCH_TOLERANCE_MS
        val invalidMonotonicStart = started <= 0 || started > SystemClock.elapsedRealtime()
        if (rebootedOrClockShifted || invalidMonotonicStart) {
            state.put("status", STATUS_PAUSED)
                .put("gpsStatus", "paused_after_restart")
                .put("segment", state.optInt("segment") + 1)
            state.remove("segmentStartedAt")
            state.remove("lastFix")
            persistAndBroadcast()
        }
    }

    private fun requestLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            state.put("elapsedMs", elapsedMillis()).put("status", STATUS_PAUSED)
            state.remove("segmentStartedAt")
            state.remove("lastFix")
            persistAndBroadcast()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        try {
            if (!isLocationEnabled()) {
                pauseForLocationDisabled()
                return
            }
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 1f, this)
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { lastKnown ->
                val ageMs = (SystemClock.elapsedRealtimeNanos() - lastKnown.elapsedRealtimeNanos) / 1_000_000L
                if (ageMs in 0..MAX_LAST_KNOWN_AGE_MS) onLocationChanged(lastKnown)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to request GPS updates", error)
            state.put("gpsStatus", "waiting_for_fix")
            persistAndBroadcast()
        }
    }

    private fun isLocationEnabled(): Boolean = try {
        val masterEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locationManager.isLocationEnabled else true
        masterEnabled && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (_: Exception) { false }

    private fun pauseForLocationDisabled() {
        if (state.optString("status") == STATUS_RUNNING) {
            state.put("elapsedMs", elapsedMillis())
                .put("status", STATUS_PAUSED)
                .put("segment", state.optInt("segment") + 1)
            state.remove("segmentStartedAt")
            state.remove("lastFix")
        }
        state.put("gpsStatus", "location_disabled")
        locationManager.removeUpdates(this)
        persistAndBroadcast()
        if (state.optString("status") == STATUS_PAUSED) updateNotification()
    }

    private fun persistAndBroadcast() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_STATE, state.toString()).apply()
        broadcastState()
    }

    private fun broadcastState() {
        val elapsed = elapsedMillis()
        val distance = state.optDouble("distanceMeters")
        val pace = if (distance > 0) elapsed / 60_000.0 / (distance / METERS_PER_KILOMETER) else 0.0
        val update = JSONObject(state.toString()).put("elapsedMs", elapsed).put("paceMinPerKm", pace)
        if (state.optString("status") != STATUS_COMPLETED) {
            val points = state.optJSONArray("points") ?: JSONArray()
            val latest = if (points.length() > 0) points.optJSONObject(points.length() - 1) else null
            update.remove("points")
            update.put("pointCount", points.length())
            if (latest != null) update.put("latestPoint", latest)
        }
        sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName).putExtra(EXTRA_STATE, update.toString()))
    }

    private fun notification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Live run", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val elapsedMinutes = elapsedMillis() / 60_000
        val distanceKm = state.optDouble("distanceMeters") / METERS_PER_KILOMETER
        val status = if (state.optString("status") == STATUS_PAUSED) "Paused" else "Recording"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("$status · ${"%.2f".format(java.util.Locale.US, distanceKm)} km")
            .setContentText("Run in progress · $elapsedMinutes min")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification())
    }

    companion object {
        const val ACTION_START = "com.waytoconsistency.RUN_START"
        const val ACTION_PAUSE = "com.waytoconsistency.RUN_PAUSE"
        const val ACTION_RESUME = "com.waytoconsistency.RUN_RESUME"
        const val ACTION_FINISH = "com.waytoconsistency.RUN_FINISH"
        const val ACTION_UPDATE = "com.waytoconsistency.RUN_UPDATE"
        const val EXTRA_STATE = "runState"
        const val STATUS_RUNNING = "running"
        const val STATUS_PAUSED = "paused"
        const val STATUS_COMPLETED = "completed"
        private const val PREFS = "run_tracking"
        private const val TAG = "RunTrackingService"
        private const val KEY_STATE = "state"
        private const val CHANNEL_ID = "live_run"
        private const val NOTIFICATION_ID = 7401
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val MAX_FIX_AGE_MS = 30_000L
        private const val MAX_LAST_KNOWN_AGE_MS = 10_000L
        private const val MAX_FUTURE_FIX_MS = 5_000L
        private const val BOOT_EPOCH_TOLERANCE_MS = 120_000L
        private const val METERS_PER_KILOMETER = 1000.0

        fun loadState(context: android.content.Context): JSONObject = try {
            JSONObject(context.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STATE, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }

        fun currentState(context: android.content.Context): JSONObject {
            val current = loadState(context)
            if (current.optString("status") == STATUS_RUNNING) {
                val started = current.optLong("segmentStartedAt")
                val segmentElapsed = if (started > 0 && started <= SystemClock.elapsedRealtime()) SystemClock.elapsedRealtime() - started else 0L
                val elapsed = current.optLong("elapsedMs") + segmentElapsed
                val distance = current.optDouble("distanceMeters")
                current.put("elapsedMs", elapsed)
                    .put("paceMinPerKm", if (distance > 0) elapsed / 60_000.0 / (distance / 1000.0) else 0.0)
            }
            return current
        }

        fun acknowledgeCompletedRun(context: android.content.Context, id: String) {
            val current = loadState(context)
            if (current.optString("status") == STATUS_COMPLETED && current.optString("id") == id) {
                context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_STATE).apply()
            }
        }
    }
}

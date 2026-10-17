package com.waytoconsistency

import android.Manifest
import android.app.AlarmManager
import android.media.RingtoneManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.location.LocationManager
import android.util.Base64
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var fileCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var webReady = false
    private var queuedReminderAction: Pair<String, String>? = null
    private var pendingRunStart = false
    private var pendingRunResume = false
    private var locationSettingsOpened = false
    private var pendingGpx = ""
    private var pendingMusicProvider = ""
    private var pendingMusicValue = ""
    private var pendingActivityDetectionEnable = false
    private var queuedActivityOffer = false
    private var healthSummary = org.json.JSONObject().put("status", "loading").toString()
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var assetLoader: WebViewAssetLoader
    private val healthPermissionLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        refreshHealthSummary()
    }

    private val runReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(RunTrackingService.EXTRA_STATE) ?: return
            if (webReady) webView.evaluateJavascript("window.handleNativeRunUpdate && window.handleNativeRunUpdate($state)", null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BackupWorker.schedule(this)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(-1, -1)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.setSupportMultipleWindows(false)
        webView.addJavascriptInterface(ReminderBridge(), "AndroidReminders")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != "appassets.androidplatform.net" ||
                    (request.url.path != "/index.html" && request.url.path?.startsWith("/assets/") != true)

            override fun onPageFinished(view: WebView, url: String) {
                webReady = true
                view.evaluateJavascript("window.refreshAndroidPermissions && window.refreshAndroidPermissions()", null)
                view.evaluateJavascript("window.refreshNativeRun && window.refreshNativeRun()", null)
                deliverQueuedReminderAction()
                deliverActivityOffer()
                refreshHealthSummary()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: android.webkit.ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = params.acceptTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
                return try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                    true
                } catch (_: Exception) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val localAsset = request.origin.scheme == "https" && request.origin.host == "appassets.androidplatform.net"
                if (!localAsset) {
                    request.deny()
                    return
                }
                val needed = request.resources.mapNotNull { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                        else -> null
                    }
                }.distinct()
                if (needed.size != request.resources.size) {
                    request.deny()
                    return
                }
                val missing = needed.filter { this@MainActivity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) request.grant(request.resources)
                else {
                    pendingWebPermission = request
                    this@MainActivity.requestPermissions(missing.toTypedArray(), WEB_MEDIA_REQUEST)
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingWebPermission == request) pendingWebPermission = null
            }
        }
        setContentView(webView)
        val runFilter = IntentFilter(RunTrackingService.ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(runReceiver, runFilter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(runReceiver, runFilter)
        handleReminderIntent(intent)
        handleActivityIntent(intent)
        ActivityDetection.restore(this)
        webView.loadUrl("https://appassets.androidplatform.net/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReminderIntent(intent)
        handleActivityIntent(intent)
        deliverQueuedReminderAction()
        deliverActivityOffer()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized && webReady) {
            webView.evaluateJavascript("window.refreshAndroidPermissions && window.refreshAndroidPermissions()", null)
            webView.evaluateJavascript("window.refreshNativeRun && window.refreshNativeRun()", null)
            refreshHealthSummary()
            deliverActivityOffer()
            if (RunTrackingService.loadState(this).optString("status") == RunTrackingService.STATUS_RUNNING) {
                val intent = Intent(this, RunTrackingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            }
        }
    }

    @Deprecated("Used for Android permission and document-picker callbacks")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val result = if (resultCode == RESULT_OK && data != null) {
                data.clipData?.let { clip -> Array(clip.itemCount) { index -> clip.getItemAt(index).uri } }
                    ?: data.data?.let { arrayOf(it) }
            } else null
            fileCallback?.onReceiveValue(result)
            fileCallback = null
            return
        }
        if (requestCode == GPX_EXPORT_REQUEST) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            val saved = try {
                if (uri == null) false else contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(pendingGpx.toByteArray(Charsets.UTF_8))
                    true
                } ?: false
            } catch (_: Exception) {
                false
            }
            pendingGpx = ""
            android.widget.Toast.makeText(this, if (saved) "GPX route saved." else "GPX export cancelled or failed.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (requestCode == RINGTONE_PICKER_REQUEST) {
            if (resultCode != RESULT_OK) return
            val selected = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            val spec = selected?.toString()?.let { "device:$it" } ?: "device:default"
            val title = selected?.let { uri ->
                try { RingtoneManager.getRingtone(this, uri)?.getTitle(this) } catch (_: Exception) { null }
            } ?: "Default device ringtone"
            val quotedSpec = org.json.JSONObject.quote(spec)
            val quotedTitle = org.json.JSONObject.quote(title)
            if (webReady) webView.evaluateJavascript("window.handleDeviceRingtonePicked && window.handleDeviceRingtonePicked($quotedSpec,$quotedTitle)", null)
            return
        }
        if (requestCode == RUN_MUSIC_PICKER_REQUEST) {
            if (resultCode != RESULT_OK) return
            val uri = data?.data ?: return
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val title = try {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (_: Exception) { null } ?: "Local audio"
            val quotedUri = org.json.JSONObject.quote(uri.toString())
            val quotedTitle = org.json.JSONObject.quote(title)
            if (webReady) webView.evaluateJavascript("window.handleRunMusicPicked && window.handleRunMusicPicked($quotedUri,$quotedTitle)", null)
            return
        }
        if (requestCode == EXACT_ALARM_REQUEST) {
            webView.evaluateJavascript("window.refreshAndroidPermissions && window.refreshAndroidPermissions()", null)
            return
        }
        if (requestCode == LOCATION_SETTINGS_REQUEST) {
            locationSettingsOpened = false
            if (isLocationEnabled()) continuePendingRunAction()
            else {
                clearPendingRunAction()
                if (webReady) webView.evaluateJavascript("window.handleLocationServicesRequired && window.handleLocationServicesRequired()", null)
            }
        }
    }

    @Deprecated("Permission result is needed for WebView camera/microphone capture")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == WEB_MEDIA_REQUEST) {
            val request = pendingWebPermission
            pendingWebPermission = null
            if (request != null && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                request.grant(request.resources)
            } else request?.deny()
        } else if (requestCode == NOTIFICATION_REQUEST) {
            requestExactAlarmAccess()
            webView.evaluateJavascript("window.refreshAndroidPermissions && window.refreshAndroidPermissions()", null)
        } else if (requestCode == RUN_LOCATION_REQUEST) {
            val fineGranted = permissions.indices.any { permissions[it] == Manifest.permission.ACCESS_FINE_LOCATION && grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED }
            if (fineGranted) continuePendingRunAction()
            else if (webReady) webView.evaluateJavascript("window.handleRunPermissionResult && window.handleRunPermissionResult(false)", null)
            if (!fineGranted) clearPendingRunAction()
        } else if (requestCode == ACTIVITY_RECOGNITION_REQUEST) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted && pendingActivityDetectionEnable) setActivityDetection(true)
            else notifyActivityDetection(false, "permission_denied")
            pendingActivityDetectionEnable = false
        }
    }

    override fun onDestroy() {
        unregisterReceiver(runReceiver)
        mainScope.cancel()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun handleReminderIntent(intent: Intent?) {
        val id = intent?.getStringExtra("reminderId") ?: return
        queuedReminderAction = id to (intent.getStringExtra("reminderAction") ?: "open")
    }

    private fun deliverQueuedReminderAction() {
        val action = queuedReminderAction ?: return
        if (!webReady) return
        queuedReminderAction = null
        val id = org.json.JSONObject.quote(action.first)
        val kind = org.json.JSONObject.quote(action.second)
        webView.evaluateJavascript("window.handleNativeReminderAction && window.handleNativeReminderAction({id:$id,action:$kind})", null)
    }

    private fun handleActivityIntent(intent: Intent?) {
        if (intent?.getStringExtra("activityAction") == "offerStart") queuedActivityOffer = true
    }

    private fun deliverActivityOffer() {
        if (!webReady || !queuedActivityOffer) return
        queuedActivityOffer = false
        webView.evaluateJavascript("window.handleActivityStartOffer && window.handleActivityStartOffer()", null)
    }

    private fun refreshHealthSummary() {
        mainScope.launch {
            healthSummary = try { HealthConnectIntegration.summary(applicationContext).toString() }
            catch (e: Exception) { org.json.JSONObject().put("status", "error").put("message", e.message ?: "Health data could not be read").toString() }
            if (webReady) webView.evaluateJavascript("window.handleHealthConnectUpdate && window.handleHealthConnectUpdate($healthSummary)", null)
        }
    }

    private fun setActivityDetection(enabled: Boolean) {
        ActivityDetection.setEnabled(applicationContext, enabled) { ok, state -> runOnUiThread { notifyActivityDetection(ok, state) } }
    }

    private fun notifyActivityDetection(ok: Boolean, state: String) {
        if (!webReady) return
        val payload = org.json.JSONObject().put("ok", ok).put("state", state).put("enabled", ActivityDetection.isEnabled(this)).toString()
        webView.evaluateJavascript("window.handleActivityDetectionUpdate && window.handleActivityDetectionUpdate($payload)", null)
    }

    private fun notificationStatus(): String {
        val notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val exactAllowed = Build.VERSION.SDK_INT < 31 ||
            (getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        return when {
            notificationsAllowed && exactAllowed -> "ready"
            !notificationsAllowed && !exactAllowed -> "notification and exact alarm permissions needed"
            !notificationsAllowed -> "notification permission needed"
            else -> "exact alarm permission needed"
        }
    }

    private fun requestReminderPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        } else requestExactAlarmAccess()
    }

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT >= 31 && !(getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()) {
            try {
                startActivityForResult(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")),
                    EXACT_ALARM_REQUEST
                )
            } catch (_: Exception) {}
        }
    }

    inner class ReminderBridge {
        @JavascriptInterface
        fun syncReminders(json: String) {
            ReminderScheduler.replaceAll(applicationContext, json)
        }

        @JavascriptInterface
        fun saveBackupSnapshot(json: String) {
            if (json.length > MAX_BACKUP_JSON_CHARS) return
            try {
                org.json.JSONObject(json)
                val directory = File(applicationContext.filesDir, "backups").apply { mkdirs() }
                val temp = File(directory, "latest.json.tmp")
                temp.writeText(json)
                val latest = File(directory, "latest.json")
                if (!temp.renameTo(latest)) {
                    latest.writeText(json)
                    temp.delete()
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun createBackupNow() {
            BackupWorker.createNow(applicationContext)
        }

        @JavascriptInterface
        fun getLatestBackup(): String = BackupWorker.latestBackupText(applicationContext) ?: ""

        @JavascriptInterface
        fun notificationStatus(): String = this@MainActivity.notificationStatus()

        @JavascriptInterface
        fun getIntegrationCapabilities(): String = org.json.JSONObject().apply {
            put("platform", "android")
            put("androidVersion", Build.VERSION.SDK_INT)
            put("androidCalendar", true)
            put("calendarPermission", checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
            put("healthConnect", HealthConnectIntegration.sdkStatus(applicationContext) == HealthConnectClient.SDK_AVAILABLE)
            put("healthStatus", org.json.JSONObject(healthSummary).optString("status", "loading"))
            put("activityRecognition", Build.VERSION.SDK_INT >= 29)
            put("activityPermission", Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
            put("activityEnabled", ActivityDetection.isEnabled(applicationContext))
            put("systemPhotoPicker", Build.VERSION.SDK_INT >= 33)
        }.toString()

        @JavascriptInterface
        fun getHealthConnectSummary(): String = healthSummary

        @JavascriptInterface
        fun connectHealthConnect() {
            runOnUiThread {
                when (HealthConnectIntegration.sdkStatus(applicationContext)) {
                    HealthConnectClient.SDK_AVAILABLE -> healthPermissionLauncher.launch(HealthConnectIntegration.permissions)
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                    else -> android.widget.Toast.makeText(this@MainActivity, "Health Connect is not available on this device.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun refreshHealthConnect() = runOnUiThread { refreshHealthSummary() }

        @JavascriptInterface
        fun setActivityDetectionEnabled(enabled: Boolean) {
            runOnUiThread {
                val missing = mutableListOf<String>()
                if (enabled && Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.ACTIVITY_RECOGNITION
                if (enabled && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.POST_NOTIFICATIONS
                if (missing.isNotEmpty()) {
                    pendingActivityDetectionEnable = true
                    requestPermissions(missing.toTypedArray(), ACTIVITY_RECOGNITION_REQUEST)
                } else setActivityDetection(enabled)
            }
        }

        @JavascriptInterface
        fun writeRunToHealthConnect(runJson: String) {
            if (runJson.length > 50_000) return
            mainScope.launch {
                val ok = try {
                    val run = org.json.JSONObject(runJson)
                    HealthConnectIntegration.writeRun(
                        applicationContext,
                        run.optString("id"),
                        Instant.parse(run.optString("startedAt")),
                        Instant.parse(run.optString("endedAt")),
                        run.optDouble("distanceMeters", 0.0)
                    )
                } catch (_: Exception) { false }
                if (webReady) webView.evaluateJavascript("window.handleHealthRunWrite && window.handleHealthRunWrite($ok)", null)
            }
        }

        @JavascriptInterface
        fun requestPermissions() {
            runOnUiThread { requestReminderPermissions() }
        }

        @JavascriptInterface
        fun startRun() {
            runOnUiThread { requestRunLocationPermission(resume = false) }
        }

        @JavascriptInterface
        fun startRunWithMusic(provider: String, value: String) {
            runOnUiThread {
                pendingMusicProvider = provider.take(20)
                pendingMusicValue = value.take(4000)
                requestRunLocationPermission(resume = false)
            }
        }

        @JavascriptInterface
        fun pauseRun() = sendRunAction(RunTrackingService.ACTION_PAUSE)

        @JavascriptInterface
        fun resumeRun() {
            runOnUiThread { requestRunLocationPermission(resume = true) }
        }

        @JavascriptInterface
        fun isLocationEnabled(): Boolean = this@MainActivity.isLocationEnabled()

        @JavascriptInterface
        fun openLocationSettings() {
            runOnUiThread {
                pendingRunResume = RunTrackingService.loadState(applicationContext).optString("status") == RunTrackingService.STATUS_PAUSED
                pendingRunStart = !pendingRunResume
                openLocationSettingsForRun()
            }
        }

        @JavascriptInterface
        fun finishRun() = sendRunAction(RunTrackingService.ACTION_FINISH)

        @JavascriptInterface
        fun getRunState(): String = RunTrackingService.currentState(applicationContext).toString()

        @JavascriptInterface
        fun pickDeviceRingtone(currentSpec: String) {
            runOnUiThread { openRingtonePicker(currentSpec) }
        }

        @JavascriptInterface
        fun pickRunMusic() {
            runOnUiThread { openRunMusicPicker() }
        }

        @JavascriptInterface
        fun openRunMusic(provider: String, value: String) {
            runOnUiThread { launchRunMusic(provider, value) }
        }

        @JavascriptInterface
        fun shareText(title: String, text: String) {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title.take(100))
                    putExtra(Intent.EXTRA_TEXT, text.take(20_000))
                }
                startActivity(Intent.createChooser(intent, "Share with"))
            }
        }

        @JavascriptInterface
        fun shareImage(dataUrl: String, fileName: String, text: String) {
            shareImageInternal(dataUrl, fileName, text, "")
        }

        @JavascriptInterface
        fun shareImageTo(dataUrl: String, fileName: String, text: String, target: String) {
            shareImageInternal(dataUrl, fileName, text, target)
        }

        @JavascriptInterface
        fun previewDeviceRingtone(spec: String) {
            runOnUiThread {
                try {
                    val uri = ringtoneUri(spec)
                    RingtoneManager.getRingtone(this@MainActivity, uri)?.play()
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun acknowledgeCompletedRun(id: String) {
            RunTrackingService.acknowledgeCompletedRun(applicationContext, id)
        }

        @JavascriptInterface
        fun syncSound(id: String, dataUrl: String): Boolean = saveReminderSound(applicationContext, id, dataUrl)

        @JavascriptInterface
        fun deleteSound(id: String) {
            deleteReminderSound(applicationContext, id)
        }

        @JavascriptInterface
        fun exportGpx(fileName: String, content: String) {
            runOnUiThread { createGpxDocument(fileName, content) }
        }

        private fun sendRunAction(action: String) {
            val intent = Intent(this@MainActivity, RunTrackingService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startService(intent) else startService(intent)
        }
    }

    private fun requestRunLocationPermission(resume: Boolean) {
        pendingRunResume = resume
        pendingRunStart = !resume
        val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineGranted) {
            continuePendingRunAction()
            return
        }
        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            RUN_LOCATION_REQUEST
        )
    }

    private fun continuePendingRunAction() {
        if (!pendingRunStart && !pendingRunResume) return
        if (!isLocationEnabled()) {
            if (webReady) webView.evaluateJavascript("window.handleLocationServicesRequired && window.handleLocationServicesRequired()", null)
            openLocationSettingsForRun()
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                RUN_LOCATION_REQUEST
            )
            return
        }
        if (pendingRunResume) sendRunAction(RunTrackingService.ACTION_RESUME) else {
            startRunService()
            if (pendingMusicProvider.isNotBlank()) launchRunMusic(pendingMusicProvider, pendingMusicValue)
        }
        clearPendingRunAction()
    }

    private fun clearPendingRunAction() {
        pendingRunStart = false
        pendingRunResume = false
        pendingMusicProvider = ""
        pendingMusicValue = ""
    }

    private fun isLocationEnabled(): Boolean {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        return try {
            val masterEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled else true
            masterEnabled && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) { false }
    }

    private fun openLocationSettingsForRun() {
        if (locationSettingsOpened) return
        locationSettingsOpened = true
        try {
            startActivityForResult(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), LOCATION_SETTINGS_REQUEST)
        } catch (_: Exception) {
            locationSettingsOpened = false
            android.widget.Toast.makeText(this, "Turn on Location in device settings before starting a run.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun sendRunAction(action: String) {
        val intent = Intent(this, RunTrackingService::class.java).setAction(action)
        startService(intent)
    }

    private fun startRunService() {
        val intent = Intent(this, RunTrackingService::class.java).setAction(RunTrackingService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        if (webReady) webView.evaluateJavascript("window.handleRunPermissionResult && window.handleRunPermissionResult(true)", null)
    }

    private fun createGpxDocument(fileName: String, content: String) {
        pendingGpx = content
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "run.gpx" }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_TITLE, safeName)
        }
        try {
            startActivityForResult(intent, GPX_EXPORT_REQUEST)
        } catch (_: Exception) {
            pendingGpx = ""
            android.widget.Toast.makeText(this, "No file manager is available for GPX export.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun openRingtonePicker(currentSpec: String) {
        val existing = ringtoneUri(currentSpec)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose task notification sound")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
        }
        try {
            startActivityForResult(intent, RINGTONE_PICKER_REQUEST)
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "This device does not provide a ringtone picker.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun openRunMusicPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, RUN_MUSIC_PICKER_REQUEST) }
        catch (_: Exception) { android.widget.Toast.makeText(this, "No audio picker is available on this device.", android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun shareImageInternal(dataUrl: String, fileName: String, text: String, target: String) {
        if (dataUrl.length > MAX_SHARE_IMAGE_CHARS) return
        runOnUiThread {
            try {
                val encoded = dataUrl.substringAfter("data:image/png;base64,", "")
                if (encoded.isBlank()) return@runOnUiThread
                val directory = File(cacheDir, "share-cards").apply { mkdirs() }
                directory.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
                val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "consistency-card.png" }
                val image = File(directory, safeName).apply { writeBytes(Base64.decode(encoded, Base64.DEFAULT)) }
                val uri = FileProvider.getUriForFile(this, "$packageName.files", image)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, text.take(2000))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = android.content.ClipData.newRawUri("Consistency card", uri)
                }
                val targetPackage = mapOf("whatsapp" to "com.whatsapp", "instagram" to "com.instagram.android", "facebook" to "com.facebook.katana")[target]
                if (targetPackage != null) {
                    intent.setPackage(targetPackage)
                    if (intent.resolveActivity(packageManager) == null) {
                        android.widget.Toast.makeText(this, "That app is not installed. Opening the share menu instead.", android.widget.Toast.LENGTH_SHORT).show()
                        intent.setPackage(null)
                    }
                }
                startActivity(if (intent.`package` == null) Intent.createChooser(intent, "Share progress") else intent)
            } catch (_: Exception) {
                android.widget.Toast.makeText(this, "The share card could not be opened.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchRunMusic(provider: String, value: String) {
        val uri = try { Uri.parse(value) } catch (_: Exception) { null }
        val allowed = when (provider) {
            "spotify" -> uri?.scheme == "https" && uri.host == "open.spotify.com"
            "youtube" -> uri?.scheme == "https" && uri.host in setOf("music.youtube.com", "youtube.com", "www.youtube.com", "youtu.be")
            "local" -> uri?.scheme == "content"
            else -> false
        }
        if (!allowed || uri == null) {
            android.widget.Toast.makeText(this, "Choose a valid music source first.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (provider == "local") {
                setDataAndType(uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else data = uri
        }
        try { startActivity(intent) }
        catch (_: Exception) { android.widget.Toast.makeText(this, "No compatible music app is installed.", android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun ringtoneUri(spec: String): Uri = when {
        spec == "device:default" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        spec.startsWith("device:") -> Uri.parse(spec.removePrefix("device:"))
        else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    private fun packageInstalled(packageId: String): Boolean = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageId, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun saveReminderSound(context: Context, id: String, dataUrl: String): Boolean {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) return false
        val match = Regex("^data:(audio/[A-Za-z0-9.+-]+)(?:;[^,]*)?;base64,([A-Za-z0-9+/=]+)$").matchEntire(dataUrl) ?: return false
        return try {
            val mime = match.groupValues[1].lowercase()
            val extension = when (mime.substringAfter('/')) {
                "mpeg" -> "mp3"
                "mp4", "x-m4a" -> "m4a"
                "x-wav" -> "wav"
                "3gpp" -> "3gp"
                else -> mime.substringAfter('/').substringBefore('+')
            }
            val directory = File(context.filesDir, "reminder-sounds").apply { mkdirs() }
            directory.listFiles()?.filter { it.name.substringBefore('.') == id && it.extension != extension }?.forEach(File::delete)
            File(directory, "$id.$extension").writeBytes(Base64.decode(match.groupValues[2], Base64.DEFAULT))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteReminderSound(context: Context, id: String) {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) return
        File(context.filesDir, "reminder-sounds").listFiles()
            ?.filter { it.name.substringBefore('.') == id }
            ?.forEach(File::delete)
    }

    companion object {
        private const val FILE_CHOOSER_REQUEST = 702
        private const val WEB_MEDIA_REQUEST = 703
        private const val NOTIFICATION_REQUEST = 704
        private const val EXACT_ALARM_REQUEST = 705
        private const val RUN_LOCATION_REQUEST = 706
        private const val GPX_EXPORT_REQUEST = 707
        private const val RINGTONE_PICKER_REQUEST = 708
        private const val LOCATION_SETTINGS_REQUEST = 709
        private const val RUN_MUSIC_PICKER_REQUEST = 710
        private const val ACTIVITY_RECOGNITION_REQUEST = 711
        private const val MAX_BACKUP_JSON_CHARS = 12_000_000
        private const val MAX_SHARE_IMAGE_CHARS = 12_000_000
    }
}

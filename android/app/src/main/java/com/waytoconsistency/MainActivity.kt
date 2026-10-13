package com.waytoconsistency

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var fileCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var webReady = false
    private var queuedReminderAction: Pair<String, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "file" || request.url.path?.startsWith("/android_asset/") != true

            override fun onPageFinished(view: WebView, url: String) {
                webReady = true
                view.evaluateJavascript("window.refreshAndroidPermissions && window.refreshAndroidPermissions()", null)
                deliverQueuedReminderAction()
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
                val localAsset = request.origin.scheme == "file" && request.origin.path?.startsWith("/android_asset/") == true
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
        handleReminderIntent(intent)
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReminderIntent(intent)
        deliverQueuedReminderAction()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized && webReady) {
            webView.evaluateJavascript("window.refreshAndroidPermissions && window.refreshAndroidPermissions()", null)
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
        if (requestCode == EXACT_ALARM_REQUEST) {
            webView.evaluateJavascript("window.refreshAndroidPermissions && window.refreshAndroidPermissions()", null)
        }
    }

    @Deprecated("Permission result is needed for WebView camera/microphone capture")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
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
        }
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
        fun notificationStatus(): String = this@MainActivity.notificationStatus()

        @JavascriptInterface
        fun requestPermissions() {
            runOnUiThread { requestReminderPermissions() }
        }
    }

    companion object {
        private const val FILE_CHOOSER_REQUEST = 702
        private const val WEB_MEDIA_REQUEST = 703
        private const val NOTIFICATION_REQUEST = 704
        private const val EXACT_ALARM_REQUEST = 705
    }
}
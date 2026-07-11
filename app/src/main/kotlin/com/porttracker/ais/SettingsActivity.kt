package com.porttracker.ais

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

class SettingsActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var webContainer: LinearLayout? = null

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // Receiver for USB permission result — auto-starts service when granted
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == USB_PERMISSION_ACTION) {
                val device = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    Log.i(TAG, "USB permission granted for ${device.productName}")
                    Toast.makeText(context, "✅ USB permission granted", Toast.LENGTH_SHORT).show()
                    // Start or restart service with the device
                    val serviceIntent = Intent(context, AisReceiverService::class.java).apply {
                        putExtra("USB_VENDOR_ID", device.vendorId)
                        putExtra("USB_PRODUCT_ID", device.productId)
                    }
                    if (AisReceiverService.isRunning) {
                        // Service is in web-only mode — stop and restart with USB device
                        Log.i(TAG, "Service running, restarting with USB device...")
                        stopService(Intent(context, AisReceiverService::class.java))
                        Handler(Looper.getMainLooper()).postDelayed({
                            startForegroundService(serviceIntent)
                        }, 1500)
                    } else {
                        startForegroundService(serviceIntent)
                    }
                    // Reload web UI to reflect new status
                    webView?.postDelayed({ loadWebUI() }, 3000)
                } else {
                    Toast.makeText(context, "❌ USB permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build layout programmatically
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        // 1. Small native header bar with port config
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        // Port label
        val portLabel = TextView(this).apply {
            text = "Web Port: "
            textSize = 14f
        }
        headerLayout.addView(portLabel)

        // Port input
        val portInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            setText(prefs.getString("pref_local_web_port", "8080"))
            layoutParams = LinearLayout.LayoutParams(200, WRAP_CONTENT)
            textSize = 14f
        }
        headerLayout.addView(portInput)

        // Apply button
        val applyBtn = Button(this).apply {
            text = "Apply"
            textSize = 12f
            setOnClickListener {
                val newPort = portInput.text.toString().trim()
                if (newPort.isNotEmpty()) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                    prefs.edit().putString("pref_local_web_port", newPort).apply()
                    // Reload WebView with new port
                    webView?.loadUrl("http://127.0.0.1:$newPort/")
                }
            }
        }
        headerLayout.addView(applyBtn)

        rootLayout.addView(headerLayout)

        // 2. WebView
        webView = createWebView()
        rootLayout.addView(webView)
        webContainer = rootLayout

        setContentView(rootLayout)

        // Register USB permission receiver
        val filter = IntentFilter(USB_PERMISSION_ACTION)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, filter)
        }

        // Check USB permission at startup
        Handler(Looper.getMainLooper()).postDelayed({
            checkAndRequestUsbPermission()
        }, AUTO_START_DELAY)

        // Always start the service (web server is required for the WebView UI).
        // The auto_start_launch pref controls whether we also try to start the SDR engine.
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val autoStartOnLaunch = prefs.getBoolean("auto_start_launch", true)

        if (!AisReceiverService.isRunning) {
            // Delay 3 seconds to allow USB device enumeration and permission
            Handler(Looper.getMainLooper()).postDelayed({
                if (!AisReceiverService.isRunning) {
                    val deviceInfo = UsbDeviceScanner.scanForDevices(this)

                    if (autoStartOnLaunch && deviceInfo.found && deviceInfo.isUsable) {
                        // Auto-start with SDR device
                        val intent = Intent(this, AisReceiverService::class.java).apply {
                            putExtra("USB_VENDOR_ID", deviceInfo.vendorId)
                            putExtra("USB_PRODUCT_ID", deviceInfo.productId)
                        }
                        startForegroundService(intent)
                    } else {
                        // Start in web-only mode — always needed for the WebView UI
                        val intent = Intent(this, AisReceiverService::class.java).apply {
                            putExtra("USB_VENDOR_ID", 0)
                            putExtra("USB_PRODUCT_ID", 0)
                        }
                        startForegroundService(intent)
                        Toast.makeText(this, "🌐 Starting web server...", Toast.LENGTH_SHORT).show()
                    }
                }
            }, BOOT_CHECK_DELAY)
        }

        // Handle USB device attached intent (from "Always open PortTracker" selection)
        handleUsbIntent(intent)

        // Request battery optimization exemption — critical to prevent Android from
        // killing the service, dropping WiFi, or restricting background activity
        requestBatteryOptimizationExemption()

        // Load web UI after a delay to let the service start via BOOT_CHECK_DELAY
        webView?.postDelayed({ loadWebUI() }, 4000)
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(TAG, "Requesting battery optimization exemption...")
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not request battery optimization exemption", e)
            }
        } else {
            Log.i(TAG, "Already exempt from battery optimization")
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure webviewer_enabled is always true when this activity is active
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit().putBoolean("webviewer_enabled", true).apply()

        // Reload the WebView
        loadWebUI()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun loadWebUI() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val port = prefs.getString("pref_local_web_port", "8080") ?: "8080"
        webView?.loadUrl("http://127.0.0.1:$port/")
    }

    /** Build a fully-configured WebView. Extracted so it can be rebuilt if the
     *  render process is killed (see [recreateWebView]). */
    private fun createWebView(): WebView = WebView(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // Only reload for MAIN-FRAME failures (local server not ready yet).
                // A failed subresource — e.g. an external map tile when internet
                // drops — must NOT trigger a full page reload, or the UI thrashes.
                if (request?.isForMainFrame == true) {
                    view?.postDelayed({ loadWebUI() }, 2000)
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                // Android killed the WebView's render process (memory pressure or
                // a connectivity change while backgrounded). The surface is now a
                // dead black view that can't recover in place and survives even
                // when connectivity returns — so rebuild it. Returning true keeps
                // the app alive instead of letting the system terminate it.
                Log.w(TAG, "WebView render process gone (crash=${detail?.didCrash()}); recreating")
                recreateWebView(view)
                return true
            }
        }
        webChromeClient = WebChromeClient()
        addJavascriptInterface(WebBridge(), "Android")
    }

    /** Replace a dead WebView with a fresh one in the same layout slot. */
    private fun recreateWebView(dead: WebView?) {
        val container = webContainer ?: return
        val idx = dead?.let { container.indexOfChild(it) }?.takeIf { it >= 0 } ?: container.childCount
        if (dead != null) {
            container.removeView(dead)
            try { dead.destroy() } catch (_: Exception) {}
        }
        val fresh = createWebView()
        container.addView(fresh, idx)
        webView = fresh
        loadWebUI()
    }

    /** JavaScript bridge exposed to the WebView as `Android.requestUsbPermission()` */
    inner class WebBridge {
        @android.webkit.JavascriptInterface
        fun requestUsbPermission() {
            runOnUiThread {
                checkAndRequestUsbPermission()
            }
        }
    }

    // =============== USB PERMISSION HANDLING ===============

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleUsbIntent(it) }
    }

    private fun handleUsbIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (device != null) {
                Log.i(TAG, "USB device attached via Intent: ${device.productName} (${device.vendorId}:${device.productId})")

                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

                if (usbManager.hasPermission(device)) {
                    // We have permission - this means user selected "Always open PortTracker"!
                    Log.i(TAG, "USB permission already granted (persistent)")
                    Toast.makeText(this, "✅ SDR connected with persistent permission", Toast.LENGTH_SHORT).show()

                    // Start the service with this device
                    if (!AisReceiverService.isRunning) {
                        val serviceIntent = Intent(this, AisReceiverService::class.java).apply {
                            putExtra("USB_VENDOR_ID", device.vendorId)
                            putExtra("USB_PRODUCT_ID", device.productId)
                        }
                        startForegroundService(serviceIntent)
                    }
                } else {
                    // Request permission - this time the "Always open" checkbox will be available!
                    Log.i(TAG, "Requesting USB permission with 'Always' option available")
                    val pendingIntent = PendingIntent.getBroadcast(
                        this, 0,
                        Intent(USB_PERMISSION_ACTION).apply {
                            putExtra(UsbManager.EXTRA_DEVICE, device)
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    usbManager.requestPermission(device, pendingIntent)
                }
            }
        }
    }

    private fun checkAndRequestUsbPermission() {
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            val deviceInfo = UsbDeviceScanner.scanForDevices(this)

            if (deviceInfo.found && !deviceInfo.isUsable) {
                // SDR found but no permission - request it
                for ((_, device) in usbManager.deviceList) {
                    if (device.vendorId == deviceInfo.vendorId && device.productId == deviceInfo.productId) {
                        val pendingIntent = PendingIntent.getBroadcast(
                            this, 0,
                            Intent(USB_PERMISSION_ACTION),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        usbManager.requestPermission(device, pendingIntent)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore permission check errors
        }
    }

    private companion object {
        const val TAG = "SettingsActivity"
        const val AUTO_START_DELAY = 1000L
        const val BOOT_CHECK_DELAY = 3000L
        const val USB_PERMISSION_ACTION = "com.porttracker.ais.USB_PERMISSION"
    }
}

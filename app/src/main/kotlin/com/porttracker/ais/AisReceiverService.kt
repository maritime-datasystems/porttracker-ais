package com.porttracker.ais

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

import android.content.pm.ServiceInfo
import com.jvdegithub.aiscatcher.AisCatcherJava
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service for running the AIS-catcher receiver in the background.
 */
class AisReceiverService : Service() {

    companion object {
        private const val TAG = "porttracker-service"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ais_receiver_channel"
        
        // Single lock to prevent concurrent native execution across service restarts
        private val serviceLock = java.util.concurrent.locks.ReentrantLock()

        // Callbacks from native code (called via AisCatcherJava bridge).
        // Hot path — runs for every decoded sentence; no logging here.
        @JvmStatic fun onNMEA(nmea: String) {
            for (sentence in nmeaSentences(nmea)) {
                incrementMessageCount()
                lastNmeaTime = System.currentTimeMillis()
                nmeaListeners.forEach { it.onNMEA(sentence) }
            }
        }

        /** Wall-clock time of the last decoded sentence (0 = none yet). */
        @Volatile var lastNmeaTime = 0L
            private set
        
        @JvmStatic fun onStatus(status: String) {
            Log.i(TAG, "Status: $status")
        }
        
        @JvmStatic fun onError(error: String) {
            Log.e(TAG, "Error: $error")
        }
        
        // Listener interfaces
        interface NMEAListener { fun onNMEA(nmea: String) }
        
        // CopyOnWriteArrayList: onNMEA is called from the native receiver
        // thread while add/remove happen on the UI thread — ArrayList is not thread-safe here.
        private val nmeaListeners = java.util.concurrent.CopyOnWriteArrayList<NMEAListener>()

        fun addNMEAListener(l: NMEAListener)    = nmeaListeners.add(l)
        fun removeNMEAListener(l: NMEAListener) = nmeaListeners.remove(l)
        
        // Service state
        @Volatile var isRunning = false
            private set
        
        @Volatile var hasDevice = false
            internal set
        
        // Message counter for UI display
        private val _messageCount = java.util.concurrent.atomic.AtomicInteger(0)
        val messageCount: Int get() = _messageCount.get()
        
        fun incrementMessageCount() {
            _messageCount.incrementAndGet()
        }
        
        fun resetMessageCount() {
            _messageCount.set(0)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var lastNotificationText: String? = null
    private var receiverThread: Thread? = null
    private val shouldStop = AtomicBoolean(false)
    private var foregroundTimedOut = false
    private val restartRequested = AtomicBoolean(false)
    private var fileDescriptor: Int = -1
    private var currentSource: Int = 0
    private var gpsForwarder: GpsForwarder? = null
    private var nmeaOutputServer: NmeaOutputServer? = null
    private var frpTunnelManager: FrpTunnelManager? = null
    private var adminWebServer: AdminWebServer? = null
    private var mqttPublisher: MqttPublisher? = null
    private var mqttNmeaListener: NMEAListener? = null
    private var vesselCacheWriter: VesselCacheWriter? = null
    private val startLock = Object()  // Prevent concurrent starts
    // Dynamic USB receiver for detecting SDR plug/unplug while service is running
    private var usbReceiver: android.content.BroadcastReceiver? = null
    
    private val INTERNAL_WEB_PORT = 8888

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        extractWebAssets()
        // Register for USB attach/detach events
        registerUsbReceiver()
    }
    
    private fun extractWebAssets() {
        val webDir = java.io.File(filesDir, "web")
        val versionFile = java.io.File(webDir, ".version")
        val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName

        if (versionFile.exists() && versionFile.readText() == currentVersion) {
            return  // Assets already extracted for this version
        }

        extractAssetDir("web", webDir)
        versionFile.writeText(currentVersion ?: "")
        Log.i(TAG, "Web assets extracted for version $currentVersion")
    }

    private fun extractAssetDir(assetPath: String, targetDir: java.io.File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val entries = assets.list(assetPath) ?: return
        for (entry in entries) {
            val fullAssetPath = "$assetPath/$entry"
            val targetFile = java.io.File(targetDir, entry)
            val subEntries = assets.list(fullAssetPath)
            if (subEntries != null && subEntries.isNotEmpty()) {
                // It's a directory — recurse
                extractAssetDir(fullAssetPath, targetFile)
            } else {
                // It's a file — copy
                try {
                    assets.open(fullAssetPath).use { inStream ->
                        java.io.FileOutputStream(targetFile).use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract $fullAssetPath", e)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Enter foreground FIRST — after startForegroundService() Android kills
        // the process unless startForeground() is called promptly, and the USB
        // scan below can be slow. Re-posting when already running is harmless.
        if (!startForegroundWithNotification(lastNotificationText ?: "Starting AIS Receiver...")) {
            stopSelf()
            return START_NOT_STICKY
        }

        synchronized(startLock) {
            // Check if service is already running
            if (isRunning && receiverThread?.isAlive == true) {
                // If we're in web-only mode and a USB device is now available, trigger engine restart
                val newVid = intent?.getIntExtra("USB_VENDOR_ID", -1) ?: -1
                val newPid = intent?.getIntExtra("USB_PRODUCT_ID", -1) ?: -1
                if (!hasDevice && newVid > 0 && newPid > 0) {
                    Log.i(TAG, "USB device available while in web-only mode (vid=$newVid, pid=$newPid) — triggering restart")
                    val newFd = openUsbDevice(newVid, newPid)
                    if (newFd >= 0) {
                        fileDescriptor = newFd
                        currentSource = intent?.getIntExtra("SOURCE", currentSource) ?: currentSource
                        restartRequested.set(true)
                        shouldStop.set(true)
                        receiverThread?.interrupt()  // Break out of keep-alive sleep loop
                    } else {
                        Log.w(TAG, "USB device found but could not open (permission issue?)")
                    }
                    return START_STICKY
                }
                Log.w(TAG, "Service already running, ignoring start command")
                return START_STICKY
            }

            Log.i(TAG, "Service starting...")

        // Get configuration from intent or preferences
        val config = loadConfig()
        currentSource = intent?.getIntExtra("SOURCE", config.deviceType.ordinal) ?: config.deviceType.ordinal


        // Open USB device - try intent first, then scan for SDR
        var vendorId = intent?.getIntExtra("USB_VENDOR_ID", -1) ?: -1
        var productId = intent?.getIntExtra("USB_PRODUCT_ID", -1) ?: -1

        // Fallback: scan for SDR device if not specified in intent
        // (also the path taken on a STICKY restart, where intent is null)
        if (vendorId <= 0 || productId <= 0) {
            Log.i(TAG, "No USB IDs in intent, scanning for SDR device...")
            val deviceInfo = UsbDeviceScanner.scanForDevices(this)
            if (deviceInfo.found && deviceInfo.isUsable) {
                vendorId = deviceInfo.vendorId
                productId = deviceInfo.productId
                Log.i(TAG, "Found SDR via scan: vendor=$vendorId, product=$productId")
            }
        }

        fileDescriptor = if (vendorId > 0 && productId > 0) {
            openUsbDevice(vendorId, productId)
        } else {
            -1
        }

        Log.i(TAG, "USB fd=$fileDescriptor (vendor=$vendorId, product=$productId)")

        // Acquire wake lock
        acquireWakeLock()
        
        // Start receiver in background thread
        shouldStop.set(false)
        receiverThread = Thread {
            runServiceLoop(config)
        }.apply {
            name = "AIS-Receiver-Thread"
            start()
        }
        
        // Start GPS forwarding if enabled
        if (config.gpsdEnabled) {
            gpsForwarder = GpsForwarder(this, config.gpsdHost, config.gpsdPort, config.gpsdInterval)
            if (gpsForwarder?.start() == true) {
                Log.i(TAG, "GPS forwarding started to ${config.gpsdHost}:${config.gpsdPort} (every ${config.gpsdInterval}s)")
            } else {
                Log.w(TAG, "GPS forwarding failed to start (check permissions)")
            }
        }

        // Chartplotter network output (NMEA 0183 / NMEA 2000 emulation)
        restartNmeaOutput()
        
        // Manage FRP tunnel — use intent extras if present, otherwise config
        if (intent?.hasExtra("REMOTE_ENABLED") == true) {
            val overrideEnabled = intent.getBooleanExtra("REMOTE_ENABLED", false)
            val overrideName = intent.getStringExtra("STATION_NAME") ?: config.stationName
            Log.i(TAG, "Override config from intent: remote=$overrideEnabled, station='$overrideName'")
            manageFrpTunnel(overrideEnabled, overrideName, config.webViewerPort)
        } else {
            Log.i(TAG, "FRP config: remoteEnabled=${config.remoteAccessEnabled}, stationName='${config.stationName}'")
            manageFrpTunnel(config.remoteAccessEnabled, config.stationName, config.webViewerPort)
        }
        
        // Start MQTT publishing if enabled and configured
        if (config.mqttEnabled && (config.mqttTopicJson.isNotEmpty() || config.mqttTopicRaw.isNotEmpty())) {
            startMqttPublisher()
        }

        // Start internal vessel DB writer if "Write to internal DB" is enabled
        if (config.internalDbEnabled) {
            startVesselCacheWriter()
        }

        isRunning = true
        } // end synchronized(startLock)
        // STICKY: unattended stations must come back after a process kill.
        // The null restart intent is handled by the USB-scan fallback above.
        return START_STICKY
    }
    
    private var usbConnection: android.hardware.usb.UsbDeviceConnection? = null
    
    private fun openUsbDevice(vendorId: Int, productId: Int): Int {
        try {
            // Close previous connection if any
            usbConnection?.close()
            usbConnection = null

            val usbManager = getSystemService(USB_SERVICE) as android.hardware.usb.UsbManager
            for ((_, device) in usbManager.deviceList) {
                if (device.vendorId == vendorId && device.productId == productId) {
                    if (usbManager.hasPermission(device)) {
                        val connection = usbManager.openDevice(device) ?: return -1
                        usbConnection = connection
                        Log.i(TAG, "USB device opened successfully: ${device.productName}")
                        return connection.fileDescriptor
                    } else {
                        Log.w(TAG, "No USB permission for device")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening USB device", e)
        }
        return -1
    }

    private fun manageFrpTunnel(enabled: Boolean, stationName: String, port: Int) {
        if (frpTunnelManager == null) {
            frpTunnelManager = FrpTunnelManager(this)
        }
        if (enabled && stationName.isNotEmpty()) {
            // Fail closed: never expose the web UI over the remote tunnel without
            // credentials. Generate one if the user hasn't set any.
            if (!ensureRemoteAuthConfigured()) {
                Log.e(TAG, "Remote access NOT started — could not configure auth credentials")
                InternalLog.log("Remote access NOT started: failed to configure credentials")
                return
            }
            if (frpTunnelManager?.isRunning() != true) {
                Log.i(TAG, "Starting FRP tunnel for $stationName...")
                if (frpTunnelManager?.startTunnel(stationName, port) == true) {
                    Log.i(TAG, "FRP tunnel started for station: $stationName")
                } else {
                    Log.w(TAG, "FRP tunnel failed to start")
                }
            }
        } else {
            if (frpTunnelManager?.isRunning() == true) {
                Log.i(TAG, "Stopping FRP tunnel")
                frpTunnelManager?.stopTunnel()
            }
        }
    }

    /**
     * Ensure web-auth credentials exist before the remote tunnel is exposed.
     *
     * Remote (tunnelled) requests always require Basic auth, so if the user
     * enabled remote access without setting credentials we auto-generate a
     * password (username defaults to "admin"), enable web auth, and surface the
     * password in the internal log so the operator can retrieve it from the UI.
     *
     * @return true if credentials are present afterwards.
     */
    private fun ensureRemoteAuthConfigured(): Boolean {
        return try {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            val user = prefs.getString("web_auth_username", "") ?: ""
            val pass = prefs.getString("web_auth_password", "") ?: ""

            // The shipped admin/admin gates the LAN, but it is public knowledge
            // and the tunnel puts the station on a hostname anyone can reach.
            // Treat it as unconfigured so the generated password below wins.
            if (!WebAuth.isStillDefault(user, pass)) {
                // Make sure enforcement is on for the non-local path.
                if (!prefs.getBoolean("web_auth_enabled", WebAuth.ENABLED_BY_DEFAULT)) {
                    prefs.edit().putBoolean("web_auth_enabled", true).apply()
                }
                return true
            }

            // Generate a random alphanumeric password.
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
            val rnd = java.security.SecureRandom()
            val generated = (1..14).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
            val newUser = if (user.isNotEmpty()) user else "admin"

            prefs.edit()
                .putBoolean("web_auth_enabled", true)
                .putString("web_auth_username", newUser)
                .putString("web_auth_password", generated)
                .apply()

            Log.w(TAG, "Generated remote-access credentials (user=$newUser)")
            InternalLog.log("Remote access enabled. Login: $newUser / $generated (change this in App settings)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure remote auth credentials", e)
            false
        }
    }

    /**
     * Re-scan USB devices and open the SDR file descriptor.
     * Used when restarting the engine after the SDR was plugged in post-launch.
     */
    private fun rescanAndOpenUsb(): Int {
        val deviceInfo = UsbDeviceScanner.scanForDevices(this)
        if (deviceInfo.found && deviceInfo.isUsable) {
            Log.i(TAG, "rescanAndOpenUsb: Found SDR vendor=${deviceInfo.vendorId}, product=${deviceInfo.productId}")
            val fd = openUsbDevice(deviceInfo.vendorId, deviceInfo.productId)
            fileDescriptor = fd
            return fd
        }
        Log.w(TAG, "rescanAndOpenUsb: No usable SDR device found")
        return -1
    }

    /**
     * Register a dynamic BroadcastReceiver for USB attach/detach events.
     * This allows the service to detect when an SDR device is plugged in
     * after the service has already started in web-only mode.
     */
    private fun registerUsbReceiver() {
        usbReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                when (intent.action) {
                    android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
                        }
                        device?.let {
                            if (SupportedDevices.isSupportedDevice(it.vendorId, it.productId)) {
                                Log.i(TAG, "SDR device attached while service running: ${it.productName} (${it.vendorId}:${it.productId})")
                                if (!hasDevice) {
                                    val usbManager = getSystemService(USB_SERVICE) as android.hardware.usb.UsbManager
                                    if (usbManager.hasPermission(it)) {
                                        Log.i(TAG, "Device has permission, restarting engine...")
                                        val fd = openUsbDevice(it.vendorId, it.productId)
                                        if (fd >= 0) {
                                            fileDescriptor = fd
                                            restartRequested.set(true)
                                            shouldStop.set(true)
                                            receiverThread?.interrupt()
                                        }
                                    } else {
                                        Log.i(TAG, "Device needs permission — requesting...")
                                        val permIntent = android.app.PendingIntent.getBroadcast(
                                            this@AisReceiverService, 0,
                                            android.content.Intent("com.porttracker.ais.USB_PERMISSION").apply {
                                                setPackage(packageName)
                                            },
                                            android.app.PendingIntent.FLAG_MUTABLE
                                        )
                                        usbManager.requestPermission(it, permIntent)
                                    }
                                }
                            }
                        }
                    }
                    android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
                        }
                        device?.let {
                            if (SupportedDevices.isSupportedDevice(it.vendorId, it.productId)) {
                                Log.w(TAG, "SDR device detached: ${it.productName}")
                                hasDevice = false
                            }
                        }
                    }
                    "com.porttracker.ais.USB_PERMISSION" -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
                        }
                        val granted = intent.getBooleanExtra(android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted && device != null) {
                            Log.i(TAG, "USB permission granted in service for ${device.productName}")
                            if (!hasDevice) {
                                val fd = openUsbDevice(device.vendorId, device.productId)
                                if (fd >= 0) {
                                    fileDescriptor = fd
                                    restartRequested.set(true)
                                    shouldStop.set(true)
                                    receiverThread?.interrupt()
                                }
                            }
                        } else {
                            Log.w(TAG, "USB permission denied in service")
                        }
                    }
                }
            }
        }
        
        val filter = android.content.IntentFilter().apply {
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction("com.porttracker.ais.USB_PERMISSION")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, usbReceiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        Log.i(TAG, "USB attach/detach receiver registered")
    }

    fun triggerEngineRestart() {
        Log.i(TAG, "Triggering engine restart via Admin API...")
        restartRequested.set(true)
        // Run on background thread to avoid blocking the HTTP response
        // and give native engine time to wind down before Close() is called
        Thread {
            try {
                Thread.sleep(500) // Let HTTP response flush first
                AisCatcherJava.forceStop()
            } catch (e: Exception) {
                Log.e(TAG, "Error forcing stop for restart", e)
            }
        }.start()
    }

    private fun runServiceLoop(config: ServiceConfig) {
        Log.i(TAG, "Service Loop Started")
        
        try {
            // Only run ONCE per process - no retry loop for native engine
            // If the native engine crashes/disconnects, we restart the whole service
            serviceLock.lock()
            try {
                restartRequested.set(false)
                
                // Manage Admin Web Server
                if (config.webViewerEnabled) {
                     if (adminWebServer == null) {
                         Log.i(TAG, "Starting Admin Web Server on port ${config.webViewerPort}...")
                         adminWebServer = AdminWebServer(this, config.webViewerPort, INTERNAL_WEB_PORT)
                         adminWebServer?.start()
                     }
                } else {
                    adminWebServer?.stop()
                    adminWebServer = null
                }
                
                Log.i(TAG, "Starting Native Engine...")
                isRunning = true
                runNativeEngine(config, currentSource, fileDescriptor)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in Service Loop", e)
            } finally {
                serviceLock.unlock()
            }
            
            // Engine exited - decide what to do
            if (restartRequested.get()) {
                Log.i(TAG, "Engine restart requested — restarting in-process")
                Thread.sleep(2000)
                shouldStop.set(false)
                val newConfig = ServiceConfig.fromPreferences(this)
                restartRequested.set(false)
                // Use existing fd if set by USB receiver, otherwise re-scan
                val newFd = if (fileDescriptor >= 0) fileDescriptor else rescanAndOpenUsb()
                Log.i(TAG, "Re-running engine with updated config... (fd=$newFd)")
                runNativeEngine(newConfig, currentSource, newFd)
            }
            
            if (shouldStop.get()) {
                Log.i(TAG, "Service loop exiting (stopped by user)")
                return
            }
            
            // Unexpected exit (USB disconnect, crash, etc.)
            if (!shouldStop.get() && !restartRequested.get()) {
                Log.w(TAG, "Engine exited unexpectedly. Entering web-only keep-alive...")
                updateNotification("SDR disconnected — Web server running")
                // Stay alive for web server instead of killing the process
                while (!shouldStop.get() && !restartRequested.get()) {
                    try {
                        Thread.sleep(1000)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
                if (restartRequested.get()) {
                    Log.i(TAG, "Restart requested during keep-alive, re-scanning USB and re-running engine...")
                    Thread.sleep(1000)
                    shouldStop.set(false)
                    val newConfig = ServiceConfig.fromPreferences(this)
                    restartRequested.set(false)
                    val newFd = if (fileDescriptor >= 0) fileDescriptor else rescanAndOpenUsb()
                    Log.i(TAG, "Re-running engine with fd=$newFd")
                    runNativeEngine(newConfig, currentSource, newFd)
                }
            }
            
        } finally {
            isRunning = false
            Log.i(TAG, "Service Loop Ended")
        }
    }
    
    private fun runNativeEngine(config: ServiceConfig, source: Int, fd: Int) {
        try {
            if (!AisCatcherJava.isLibraryLoaded) {
                val error = "Native library not loaded - cannot start receiver"
                Log.e(TAG, error)
                updateNotification(error)
                onError(error)
                return
            }

            Log.i(TAG, "Initializing native library...")
            
            // Force close first to ensure clean state on restart
            try {
                AisCatcherJava.Close()
                Thread.sleep(500)  // Allow port to be released
            } catch (e: Exception) {
                Log.d(TAG, "Pre-init close (expected on first start): ${e.message}")
            }
            
            AisCatcherJava.InitNative(INTERNAL_WEB_PORT, "127.0.0.1")
            
            // Initialize statistics class - REQUIRED before Run()
            Log.i(TAG, "Initializing statistics...")
            AisCatcherJava.Statistics.Init()
            
            // Configure UDP outputs (up to 4)
            config.udpOutputs.forEachIndexed { index, udp ->
                if (udp.enabled) {
                    Log.i(TAG, "Creating UDP output ${index + 1}: ${udp.host}:${udp.port} (json=${udp.json})")
                    AisCatcherJava.createUDP(udp.host, udp.port.toString(), udp.json)
                }
            }
            
            if (config.tcpEnabled) {
                Log.i(TAG, "Creating TCP listener on port ${config.tcpPort}")
                AisCatcherJava.createTCPlistener(config.tcpPort.toString())
            }
            
            // Start Community Hub sharing
            if (config.hubEnabled && config.hubKey.isNotEmpty()) {
                Log.i(TAG, "Starting Community Hub sharing with key: ${config.hubKey.take(8)}...")
                AisCatcherJava.createSharing(true, config.hubKey)
            }
            
            // Start web viewer (Internal C++ Server)
            if (config.webViewerEnabled) {
                Log.i(TAG, "Creating Internal WebViewer on port $INTERNAL_WEB_PORT")
                AisCatcherJava.createWebViewer(INTERNAL_WEB_PORT.toString())
                
                // External Admin/Proxy Server is managed in runServiceLoop
            }
            
            // Try to create receiver — guard against invalid fd which causes native SIGABRT
            Log.i(TAG, "Creating receiver (source=$source, fd=$fd)")
            val result = if (fd < 0) {
                Log.w(TAG, "No valid USB file descriptor (fd=$fd), skipping createReceiver")
                -1  // Treat as no device
            } else {
                AisCatcherJava.createReceiver(source, fd, 0, 0, 0)
            }
            
            if (result == 0) {
                hasDevice = true
                val statusMsg = if (config.webViewerEnabled) {
                    "AIS Receiver running (Web: port ${config.webViewerPort})"
                } else {
                    "AIS Receiver running"
                }
                updateNotification(statusMsg)
                Log.i(TAG, "Running receiver...")
                AisCatcherJava.Run() // This blocks until stopped
                Log.i(TAG, "Receiver stopped")
            } else {
                hasDevice = false
                if (config.webViewerEnabled) {
                    Log.w(TAG, "No SDR device available (result=$result), web interface running")
                    updateNotification("No SDR device - Web only (port ${config.webViewerPort})")
                    
                    // Web server was already created by createWebViewer() and runs on its own thread
                    // Do NOT call Run() here - it crashes (SIGSEGV) without a valid SDR device
                    // Just keep the service alive so the web server can continue serving
                    Log.i(TAG, "Starting in web-only mode (keep-alive loop)...")
                    while (!shouldStop.get() && !restartRequested.get()) {
                        try {
                            Thread.sleep(1000)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                    Log.i(TAG, "Web-only mode stopped")
                } else {
                    Log.e(TAG, "No SDR device available and web interface disabled - stopping service")
                    updateNotification("No SDR device - Service stopping")
                    // No SDR and no web - nothing to do
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Receiver fatal error", t)
            updateNotification("Error: ${t.message}")
            onError(t.message ?: "Unknown error")
        } finally {
            if (AisCatcherJava.isLibraryLoaded) {
                try {
                    AisCatcherJava.Close()
                    // Brief delay for port release
                    Thread.sleep(500)
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing receiver", e)
                }
            }
            // isRunning = false  <- MOVED to runServiceLoop
        }
    }

    @android.annotation.TargetApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service time limit reached; stopping until the user reopens the app")
        foregroundTimedOut = true
        shouldStop.set(true)
        receiverThread?.interrupt()
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "Service stopping...")
        
        shouldStop.set(true)
        try {
            AisCatcherJava.forceStop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping receiver", e)
        }
        
        receiverThread?.let {
            it.interrupt()
            try {
                it.join(if (foregroundTimedOut) 500 else 5000)
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted waiting for receiver thread")
            }
        }
        
        // Stop GPS forwarding
        try {
            gpsForwarder?.stop()
            gpsForwarder = null
            nmeaOutputServer?.stop()
            nmeaOutputServer = null
            Log.i(TAG, "GPS forwarder stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping GPS forwarder", e)
        }
        
        // Stop FRP tunnel
        try {
            frpTunnelManager?.stopTunnel()
            frpTunnelManager = null
            Log.i(TAG, "FRP tunnel stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping FRP tunnel", e)
        }
        
        // Stop MQTT publisher
        try {
            mqttNmeaListener?.let { removeNMEAListener(it) }
            mqttNmeaListener = null
            mqttPublisher?.stop()
            mqttPublisher = null
            Log.i(TAG, "MQTT publisher stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MQTT publisher", e)
        }
        
        // Stop vessel DB writer
        try {
            stopVesselCacheWriter()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping vessel cache writer", e)
        }

        // Stop Admin Web Server
        try {
            adminWebServer?.stop()
            adminWebServer = null
            Log.i(TAG, "Admin Web Server stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Admin Web Server", e)
        }
        
        // Close USB connection to release device for re-enumeration
        try {
            usbConnection?.close()
            usbConnection = null
            Log.i(TAG, "USB connection closed")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing USB connection", e)
        }
        
        // Unregister USB receiver
        try {
            usbReceiver?.let { unregisterReceiver(it) }
            usbReceiver = null
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering USB receiver", e)
        }

        releaseWakeLock()
        isRunning = false
        hasDevice = false
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadConfig(): ServiceConfig {
        return ServiceConfig.fromPreferences(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PortTracker AIS",
                NotificationManager.IMPORTANCE_MIN  // Minimum importance - no sound, no popup
            ).apply {
                description = "AIS Receiver service status"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /**
     * Enter the foreground under the service type that matches what we are
     * actually doing right now.
     *
     * This used to always declare dataSync. From Android 15 that type is capped
     * at a few hours of runtime per day, which would quietly kill a station that
     * is supposed to run for months. A station with a receiver plugged in is a
     * connectedDevice service - the case the type exists for - and carries no
     * such cap. Without a dongle there is nothing to connect to, so the web-only
     * mode stays on dataSync; it is not receiving anything either way.
     *
     * The type is re-evaluated on every start, and plugging a dongle in fires
     * UsbDeviceReceiver -> onStartCommand, so a station upgrades itself as soon
     * as it has hardware to justify it.
     *
     * Every step degrades rather than throws: an unmet prerequisite for a type
     * makes startForeground throw SecurityException, and a service that cannot
     * enter the foreground is a station that is simply gone.
     */
    private fun startForegroundWithNotification(text: String): Boolean {
        val notification = createNotification(text)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return true
        }

        var primary = if (hasUsbDeviceAttached()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        if (hasLocationPermission()) {
            primary = primary or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }

        // Fall back through progressively weaker claims rather than die.
        val attempts = listOf(
            primary,
            primary and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION.inv(),
        )
        for (type in attempts) {
            try {
                startForeground(NOTIFICATION_ID, notification, type)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "startForeground rejected service type $type: ${e.message}")
            }
        }
        Log.e(TAG, "Could not enter the foreground under any service type.")
        return false
    }

    /** A supported USB device with permission satisfies the connectedDevice prerequisite. */
    private fun hasUsbDeviceAttached(): Boolean = try {
        val usbManager = getSystemService(USB_SERVICE) as android.hardware.usb.UsbManager
        usbManager.deviceList.values.any { device ->
            SupportedDevices.isSupportedDevice(device.vendorId, device.productId) &&
                usbManager.hasPermission(device)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the USB device list: ${e.message}")
        false
    }

    /** Claiming the location service type without the permission is refused. */
    private fun hasLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun createNotification(text: String): Notification {
        lastNotificationText = text
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PortTracker AIS")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)  // Prevent sound/vibration
            .setPriority(NotificationCompat.PRIORITY_LOW)  // Minimize interruption
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        // CPU wake lock — keep CPU alive indefinitely
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "porttracker-ais:WakeLock")
        wakeLock?.acquire() // No timeout — released explicitly in onDestroy
        
        // WiFi lock — prevent Android from turning off WiFi when screen is off
        // Without this, the phone becomes unreachable over the network
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "porttracker-ais:WifiLock")
        wifiLock?.acquire()
        Log.i(TAG, "Wake lock and WiFi lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        wifiLock?.let {
            if (it.isHeld) it.release()
        }
        wifiLock = null
        Log.i(TAG, "Wake lock and WiFi lock released")
    }
    /**
     * Start the MQTT publisher on a background thread.
     * Reads credentials directly from config (saved via settings web UI).
     */
    private fun startMqttPublisher() {
        Thread({
            try {
                val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                val brokerUrl = prefs.getString("mqtt_broker_url", "") ?: ""
                val username = prefs.getString("mqtt_username", "") ?: ""
                val password = prefs.getString("mqtt_password", "") ?: ""
                var topicRaw = prefs.getString("mqtt_topic_raw", "") ?: ""
                var topicJson = prefs.getString("mqtt_topic_json", "") ?: ""
                val format = prefs.getString("mqtt_format", "aisc-json") ?: "aisc-json"
                val stationName = prefs.getString("pref_station_name", "") ?: ""
                // Presence: published by the station itself, so "reachable"
                // stays distinguishable from "hearing something".
                val stationId = prefs.getInt("sharing_station_id", 0)
                val topicStatus = if (stationId > 0) {
                    "ais/status/SHARE/$stationId"
                } else {
                    ""
                }
                val antennaUuid = prefs.getString(
                    PorttrackerAuth.PREF_ANTENNA_UUID, "") ?: ""
                // String-tolerant: the web config page stores booleans as
                // strings on save.
                val gpsEnabled = prefs.all["mqtt_gps_enabled"] == true ||
                    prefs.all["mqtt_gps_enabled"]?.toString() == "true"
                val displayName =
                    prefs.getString("station_display_name", "") ?: ""
                val stationKind =
                    (prefs.getString("station_kind", "fixed") ?: "fixed")
                        .ifBlank { "fixed" }
                val publicStation = prefs.all["station_public"] == true ||
                    prefs.all["station_public"]?.toString() == "true"

                // Auto-construct topics from userId/antennaId if not explicitly set.
                // This handles devices provisioned before auto-topic construction was added.
                if (topicRaw.isEmpty() && topicJson.isEmpty()) {
                    val userId = prefs.getInt("mqtt_user_id", 0)
                    val antennaId = prefs.getInt("mqtt_antenna_id", 0)
                    if (userId > 0 && antennaId > 0) {
                        topicJson = "ais/aisc-json/$userId/$antennaId"
                        topicRaw = "ais/raw/$userId/$antennaId"
                        Log.i(TAG, "Auto-constructed MQTT topics from userId=$userId, antennaId=$antennaId")
                        // Persist so we don't re-derive every time
                        prefs.edit()
                            .putString("mqtt_topic_json", topicJson)
                            .putString("mqtt_topic_raw", topicRaw)
                            .apply()
                    }
                }

                if (brokerUrl.isEmpty() || (topicRaw.isEmpty() && topicJson.isEmpty())) {
                    Log.e(TAG, "MQTT enabled but broker/topic not configured — skipping")
                    return@Thread
                }

                Log.i(TAG, "Starting MQTT publisher: broker=$brokerUrl, topic=${if (format == "raw") topicRaw else topicJson}")

                val publisher = MqttPublisher(this)
                publisher.start(
                    MqttPublisher.Config(
                        brokerUrl = brokerUrl,
                        username = username,
                        password = password,
                        topicRaw = topicRaw,
                        topicJson = topicJson,
                        format = format,
                        qos = 1,
                        stationName = stationName,
                        topicStatus = topicStatus,
                        stationId = stationId,
                        antennaUuid = antennaUuid,
                        gpsEnabled = gpsEnabled,
                        displayName = displayName,
                        stationKind = stationKind,
                        publicStation = publicStation
                    )
                )

                mqttPublisher = publisher

                // Register NMEA listener to forward messages
                val listener = object : NMEAListener {
                    override fun onNMEA(nmea: String) {
                        publisher.publishNmea(nmea)
                    }
                }
                mqttNmeaListener = listener
                addNMEAListener(listener)

                Log.i(TAG, "MQTT publisher started and registered as NMEA listener")

            } catch (e: Exception) {
                Log.e(TAG, "Error starting MQTT publisher", e)
            }
        }, "MQTT-Setup").start()
    }

    /**
     * Apply configuration changes that are safe to apply live (without native engine restart).
     * Called automatically after config save from the web UI.
     */
    fun applyLiveConfigChanges() {
        Log.i(TAG, "Applying live config changes...")
        val config = ServiceConfig.fromPreferences(this)

        // Restart FRP tunnel with new settings
        manageFrpTunnel(config.remoteAccessEnabled, config.stationName, config.webViewerPort)

        // Restart MQTT publisher with new settings
        if (config.mqttEnabled && (config.mqttTopicJson.isNotEmpty() || config.mqttTopicRaw.isNotEmpty())) {
            stopMqttPublisher()
            startMqttPublisher()
        } else {
            // MQTT disabled — stop if running
            stopMqttPublisher()
        }

        // Start/stop internal vessel DB writer to match the toggle live
        if (config.internalDbEnabled) {
            startVesselCacheWriter()
        } else {
            stopVesselCacheWriter()
        }

        // Restart chartplotter NMEA output with new settings
        restartNmeaOutput()

        Log.i(TAG, "Live config changes applied (FRP=${config.remoteAccessEnabled}, MQTT=${config.mqttEnabled}, DB=${config.internalDbEnabled})")
    }

    /** FRP tunnel state for the doctor. */
    fun frpTunnelRunning(): Boolean = frpTunnelManager?.isRunning() ?: false

    /** (Re)start the chartplotter NMEA network output from current prefs. */
    private fun restartNmeaOutput() {
        nmeaOutputServer?.stop()
        nmeaOutputServer = null
        val nmeaConfig = NmeaOutputServer.Config.fromPrefs(this)
        if (nmeaConfig != null) {
            nmeaOutputServer = NmeaOutputServer(this, nmeaConfig).also { it.start() }
        }
    }

    /** Start the internal vessel DB writer (polls the engine's ship list). */
    private fun startVesselCacheWriter() {
        if (vesselCacheWriter != null) return
        vesselCacheWriter = VesselCacheWriter(this, INTERNAL_WEB_PORT).also { it.start() }
        Log.i(TAG, "Vessel cache writer enabled")
    }

    /** Stop the internal vessel DB writer. */
    private fun stopVesselCacheWriter() {
        vesselCacheWriter?.stop()
        vesselCacheWriter = null
    }

    /**
     * Stop the current MQTT publisher cleanly.
     */
    private fun stopMqttPublisher() {
        mqttNmeaListener?.let { removeNMEAListener(it) }
        mqttNmeaListener = null
        mqttPublisher?.stop()
        mqttPublisher = null
    }

    /**
     * Public method to restart MQTT publisher from API endpoint.
     */
    fun restartMqttPublisher() {
        Log.i(TAG, "Restarting MQTT publisher via API...")
        stopMqttPublisher()
        val config = ServiceConfig.fromPreferences(this)
        if (config.mqttEnabled && (config.mqttTopicJson.isNotEmpty() || config.mqttTopicRaw.isNotEmpty())) {
            startMqttPublisher()
        } else {
            Log.i(TAG, "MQTT disabled or not configured — not starting publisher")
        }
    }

    /**
     * Public method to restart FRP tunnel from API endpoint.
     */
    fun restartFrpTunnel() {
        Log.i(TAG, "Restarting FRP tunnel via API...")
        val config = ServiceConfig.fromPreferences(this)
        // Stop existing tunnel first
        frpTunnelManager?.stopTunnel()
        // Start with new config
        manageFrpTunnel(config.remoteAccessEnabled, config.stationName, config.webViewerPort)
    }
}

/**
 * UDP output configuration
 */
data class UdpOutput(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 10110,
    val json: Boolean = false
)

/**
 * Service configuration
 */
data class ServiceConfig(
    val deviceType: DeviceType = DeviceType.RTLSDR,
    val udpOutputs: List<UdpOutput> = emptyList(),
    val tcpEnabled: Boolean = false,
    val tcpPort: Int = 10111,
    val webViewerEnabled: Boolean = true,
    val webViewerPort: Int = 8080,
    val gpsdEnabled: Boolean = false,
    val gpsdHost: String = "127.0.0.1",
    val gpsdPort: Int = 2947,
    val gpsdInterval: Int = 10,
    val hubEnabled: Boolean = false,
    val hubKey: String = "",
    val remoteAccessEnabled: Boolean = false,
    val stationName: String = "",
    val mqttEnabled: Boolean = false,
    val mqttBrokerUrl: String = "ssl://mqtt.navisense.de:8883",
    val mqttUsername: String = "",
    val mqttPassword: String = "",
    val mqttTopicRaw: String = "",
    val mqttTopicJson: String = "",
    val mqttFormat: String = "aisc-json",
    val mqttStationName: String = "",
    val internalDbEnabled: Boolean = false
) {
    companion object {
        fun fromPreferences(context: Context): ServiceConfig {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            
            // Load UDP outputs
            val udpOutputs = (1..4).map { i ->
                UdpOutput(
                    enabled = prefs.getBoolean("udp${i}_enabled", false),
                    host = prefs.getString("udp${i}_host", "127.0.0.1") ?: "127.0.0.1",
                    port = prefs.getString("udp${i}_port", (10109 + i).toString())?.toIntOrNull() ?: (10109 + i),
                    json = prefs.getBoolean("udp${i}_json", false)
                )
            }
            
            return ServiceConfig(
                deviceType = DeviceType.entries.getOrElse(prefs.getString("device_type", "1")?.toIntOrNull() ?: 1) { DeviceType.RTLSDR },
                udpOutputs = udpOutputs,
                tcpEnabled = prefs.getBoolean("tcp_enabled", false),
                tcpPort = prefs.getString("tcp_port", "10111")?.toIntOrNull() ?: 10111,
                webViewerEnabled = prefs.getBoolean("webviewer_enabled", false),
                webViewerPort = prefs.getString("pref_local_web_port", "8080")?.toIntOrNull() ?: 8080,
                gpsdEnabled = prefs.getBoolean("gpsd_enabled", false),
                gpsdHost = prefs.getString("gpsd_host", "127.0.0.1") ?: "127.0.0.1",
                gpsdPort = prefs.getString("gpsd_port", "2947")?.toIntOrNull() ?: 2947,
                gpsdInterval = prefs.getString("gpsd_interval", "10")?.toIntOrNull() ?: 10,
                hubEnabled = prefs.getBoolean("hub_sharing", false),
                hubKey = prefs.getString("hub_key", "") ?: "",
                remoteAccessEnabled = prefs.getBoolean("pref_enable_remote", false),
                stationName = prefs.getString("pref_station_name", "") ?: "",
                mqttEnabled = prefs.getBoolean("mqtt_enabled", false),
                mqttBrokerUrl = prefs.getString("mqtt_broker_url", "ssl://mqtt.navisense.de:8883") ?: "ssl://mqtt.navisense.de:8883",
                mqttUsername = prefs.getString("mqtt_username", "") ?: "",
                mqttPassword = prefs.getString("mqtt_password", "") ?: "",
                mqttTopicRaw = prefs.getString("mqtt_topic_raw", "") ?: "",
                mqttTopicJson = prefs.getString("mqtt_topic_json", "") ?: "",
                mqttFormat = prefs.getString("mqtt_format", "aisc-json") ?: "aisc-json",
                mqttStationName = prefs.getString("pref_station_name", "") ?: "",
                internalDbEnabled = prefs.getBoolean("internal_db_enabled", false)
            )
        }
    }
}

/**
 * SDR device types
 */
enum class DeviceType {
    RTLTCP,
    RTLSDR,
    AIRSPY,
    AIRSPYHF,
    SPYSERVER
}

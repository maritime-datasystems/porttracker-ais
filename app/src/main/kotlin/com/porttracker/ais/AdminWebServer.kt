package com.porttracker.ais

import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class AdminWebServer(
    private val service: AisReceiverService,
    private val port: Int,
    private val internalApiPort: Int
) : NanoHTTPD(null, port) {

    companion object {
        private const val TAG = "porttracker-service.AdminWeb"
    }
    private val configManager = ConfigurationManager(service)
    private val updateManager = UpdateManager(service)

    override fun start() {
        try {
            super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "Admin Web Server started on port $port")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start Admin Web Server", e)
        }
    }

    override fun stop() {
        super.stop()
        // Detach the NMEA ring-buffer listener — otherwise every server
        // restart (webviewer toggle, service restart) leaks one listener.
        nmeaBufferListener?.let { AisReceiverService.removeNMEAListener(it) }
        nmeaBufferListener = null
        Log.i(TAG, "Admin Web Server stopped")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "Request: $method $uri")

        // Check authentication if enabled (bypass for health endpoint — used by remote dashboard)
        if (uri != "/admin/api/health") {
            val authResponse = checkAuthentication(session)
            if (authResponse != null) {
                return authResponse
            }
        }

        // 1. Admin API Endpoints
        if (uri.startsWith("/admin/")) {
            return handleAdminApi(session)
        }

        // 1.5 NMEA buffer endpoint — returns recent NMEA as JSON array (polling-friendly)
        if (uri == "/api/nmea_buffer" || uri == "/admin/api/nmea_buffer") {
            return handleNmeaBuffer(session)
        }

        // 1.6 Direct NMEA SSE stream — bypasses C++ server, uses native NMEA callbacks
        if (uri == "/api/sse" || uri == "/admin/api/nmea_stream") {
            return handleNmeaSse()
        }

        // 2. Reverse Proxy for Internal C++ API
        // Forward stats, metrics, tile requests to the internal server
        if (shouldProxy(uri)) {
            return proxyToInternalServer(session)
        }

        // 3. Static File Serving (Custom Web Interface)
        // Check filesDir/web first (for overrides)
        val webDir = File(service.filesDir, "web")
        val relativePath = if (uri == "/") "index.html" else uri.substring(1)
        val requestedFile = File(webDir, relativePath)

        // Containment check: a crafted URI ("/../shared_prefs/….xml") must never
        // escape the web root — filesDir holds credentials in shared_prefs.
        val insideWebRoot = try {
            requestedFile.canonicalPath.startsWith(webDir.canonicalPath + File.separator)
        } catch (e: IOException) { false }

        if (insideWebRoot && requestedFile.exists() && requestedFile.isFile) {
            return serveStaticFile(requestedFile)
        }

        // 4. Fallback: Check APK assets/web (".." never allowed in asset paths)
        if (relativePath.split('/').any { it == ".." }) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")
        }
        val assetPath = "web/$relativePath"
        try {
            val assetStream = service.assets.open(assetPath)
            val mimeType = determineMimeType(relativePath)
            Log.d(TAG, "Serving from APK assets: $assetPath")
            return newChunkedResponse(Response.Status.OK, mimeType, assetStream)
        } catch (e: IOException) {
            // Asset not found, continue to proxy
        }

        // 5. Last Resort: Proxy to internal server (e.g. native API endpoints not caught above)
        return proxyToInternalServer(session)
    }

    private fun shouldProxy(uri: String): Boolean {
        return uri.startsWith("/api/") || 
               uri.startsWith("/metrics") || 
               uri == "/stat.json" ||
               uri.startsWith("/tiles/") ||
               uri.startsWith("/cdn/")
    }

    private fun handleAdminApi(session: IHTTPSession): Response {
        if (session.uri == "/admin/status") {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"running\",\"service\":\"active\"}")
        }
        
        if (session.uri == "/admin/api/config") {
            if (session.method == Method.GET) {
                val json = configManager.getAllConfig()
                return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
            } else if (session.method == Method.POST) {
                try {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    // NanoHTTPD puts x-www-form-urlencoded data in session.parms,
                    // and raw JSON body in map["postData"]
                    val jsonString = map["postData"]
                        ?: session.parms?.get("postData")
                        ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing body")
                    
                    if (configManager.updateConfig(jsonString)) {
                        // Settings saved — apply MQTT and FRP changes live (safe, no native engine restart)
                        val response = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"updated\",\"restart_required\":false}")
                        // Apply FRP and MQTT changes on a background thread after response is sent
                        Thread {
                            try {
                                Thread.sleep(200) // Let HTTP response flush
                                service.applyLiveConfigChanges()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error applying live config changes", e)
                            }
                        }.start()
                        return response
                    } else {
                         return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"status\":\"error\",\"message\":\"Failed to update preferences\"}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing POST data", e)
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error processing request")
                }
            }
        }
        
        if (session.uri == "/admin/api/status_ui") {
            return newFixedLengthResponse(Response.Status.OK, "application/json", getUiStatusJson())
        }
        
        // SDR Status endpoint for settings page
        if (session.uri == "/admin/api/status") {
            return newFixedLengthResponse(Response.Status.OK, "application/json", getSdrStatusJson())
        }
        
        // Service control endpoints
        if (session.method == Method.POST && session.uri == "/admin/api/service/start") {
            try {
                val intent = android.content.Intent(service, AisReceiverService::class.java)
                androidx.core.content.ContextCompat.startForegroundService(service, intent)
                return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service", e)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }.toString())
            }
        }
        
        if (session.method == Method.POST && session.uri == "/admin/api/service/stop") {
            try {
                // Only stop the SDR engine, NOT the whole service — web server must stay alive
                Thread {
                    try {
                        AisReceiverService.hasDevice = false
                        com.jvdegithub.aiscatcher.AisCatcherJava.forceStop()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping SDR engine", e)
                    }
                }.start()
                return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service", e)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                    JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }.toString())
            }
        }

        if (session.method == Method.POST && session.uri == "/admin/restart") {
             // Don't restart — native engine crashes on forceStop (SIGSEGV)
             // Just return success, settings are already saved
             return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"restart_required\",\"message\":\"Please restart the app to apply changes\"}")
        }

        // --- TrustedDocks / MQTT Gateway Endpoints ---

        if (session.method == Method.POST && session.uri == "/admin/api/gateway/connect") {
            return handleGatewayConnect(session)
        }

        if (session.method == Method.GET && session.uri == "/admin/api/gateway/status") {
            return handleGatewayStatus()
        }

        // --- Auth0 device login + station auto-provisioning ---

        if (session.method == Method.POST && session.uri == "/admin/api/auth/start") {
            return handleAuthStart()
        }

        if (session.method == Method.GET && session.uri == "/admin/api/auth/status") {
            return handleAuthStatus()
        }

        if (session.method == Method.POST && session.uri == "/admin/api/auth/cancel") {
            PorttrackerAuth.cancel()
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"success":true}""")
        }

        // --- Doctor: self-diagnostics for non-technical users ---
        if (session.method == Method.GET && session.uri == "/admin/api/doctor") {
            return newFixedLengthResponse(
                Response.Status.OK, "application/json", runDoctor())
        }

        // --- Health endpoint for remote dashboard monitoring ---
        if (session.method == Method.GET && session.uri == "/admin/api/health") {
            return handleHealth()
        }

        // Internal app log (tunnel status, generated remote credentials, FRP
        // output). Auth-gated like every /admin endpoint except /health.
        if (session.method == Method.GET && session.uri == "/admin/api/logs") {
            return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", InternalLog.getLogs())
        }

        // USB Permission request — triggers native Android permission dialog via service
        if (session.method == Method.POST && session.uri == "/admin/api/usb/request_permission") {
            return handleRequestUsbPermission()
        }
        
        // Engine restart — re-scans USB and restarts the native AIS engine
        if (session.method == Method.POST && session.uri == "/admin/api/engine/restart") {
            return handleEngineRestart()
        }

        // MQTT restart — reconnect MQTT publisher with current preferences
        if (session.method == Method.POST && session.uri == "/admin/api/mqtt/restart") {
            return handleMqttRestart()
        }

        // FRP restart — restart FRP tunnel with current preferences
        if (session.method == Method.POST && session.uri == "/admin/api/frp/restart") {
            return handleFrpRestart()
        }

        // --- OTA Update Endpoints ---

        if (session.method == Method.GET && session.uri == "/admin/api/update/check") {
            return handleUpdateCheck()
        }

        if (session.method == Method.POST && session.uri == "/admin/api/update/install") {
            return handleUpdateInstall()
        }

        if (session.method == Method.GET && session.uri == "/admin/api/update/status") {
            return newFixedLengthResponse(Response.Status.OK, "application/json", updateManager.getStatusJson().toString())
        }

        // Internal vessel DB — list / count / CSV export for the "DB" page
        if (session.method == Method.GET && session.uri == "/admin/api/vessels") {
            return handleVesselList(session)
        }
        if (session.method == Method.GET && session.uri == "/admin/api/vessels/count") {
            val n = VesselDatabase.getInstance(service).count()
            return newFixedLengthResponse(Response.Status.OK, "application/json", """{"count":$n}""")
        }
        if (session.method == Method.GET && session.uri == "/admin/api/vessel") {
            val mmsi = parseQuery(session.queryParameterString)["mmsi"]?.toLongOrNull()
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"mmsi required"}""")
            val v = VesselDatabase.getInstance(service).queryByMmsi(mmsi)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"not found"}""")
            return newFixedLengthResponse(Response.Status.OK, "application/json", v.toString())
        }
        if (session.method == Method.GET && session.uri == "/admin/api/vessels.csv") {
            return handleVesselCsv()
        }
        if (session.method == Method.GET && session.uri == "/admin/api/positions/stats") {
            val stats = VesselDatabase.getInstance(service).positionStats()
            stats.put("logging_enabled", androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(service).all["position_logging_enabled"]
                .let { it == true || it == "true" })
            stats.put("positions_stored_session", VesselCacheWriter.positionsStored)
            return newFixedLengthResponse(Response.Status.OK, "application/json", stats.toString())
        }
        if (session.method == Method.POST && session.uri == "/admin/api/positions/maintenance") {
            // Manual trigger for the downsampling/retention pass. Optional
            // raw_ms/hourly_ms overrides (used for testing without waiting months).
            val p = parseQuery(session.queryParameterString)
            val rawMs = p["raw_ms"]?.toLongOrNull() ?: (90L * 24 * 3_600_000)
            val hourlyMs = p["hourly_ms"]?.toLongOrNull() ?: (365L * 24 * 3_600_000)
            val stats = VesselDatabase.getInstance(service)
                .runMaintenance(System.currentTimeMillis(), rawMs, hourlyMs)
            return newFixedLengthResponse(Response.Status.OK, "application/json", stats.toString())
        }
        if (session.method == Method.GET && session.uri == "/admin/api/vessel/track") {
            val p = parseQuery(session.queryParameterString)
            val mmsi = p["mmsi"]?.toLongOrNull()
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"mmsi required"}""")
            val track = VesselDatabase.getInstance(service).queryTrack(
                mmsi,
                p["from"]?.toLongOrNull(),
                p["to"]?.toLongOrNull(),
                p["res"]?.toIntOrNull(),
                (p["limit"]?.toIntOrNull() ?: 5000).coerceIn(1, 20000)
            )
            val out = JSONObject().apply { put("mmsi", mmsi); put("points", track) }
            return newFixedLengthResponse(Response.Status.OK, "application/json", out.toString())
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Admin Endpoint Not Found")
    }
    
    private fun getSdrStatusJson(): String {
        val ctx = service.applicationContext
        val deviceInfo = UsbDeviceScanner.scanForDevices(ctx)
        val usbManager = ctx.getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager

        // Use JSONArray/JSONObject so device names with quotes or backslashes can't break the output.
        val usbDevicesArray = org.json.JSONArray()
        for (device in usbManager.deviceList.values) {
            val obj = org.json.JSONObject()
            obj.put("name", device.productName ?: device.deviceName)
            obj.put("vendorId", device.vendorId)
            obj.put("productId", device.productId)
            obj.put("isSDR", UsbDeviceScanner.isSupportedDevice(device.vendorId, device.productId))
            obj.put("hasPermission", usbManager.hasPermission(device))
            usbDevicesArray.put(obj)
        }

        val root = org.json.JSONObject()
        root.put("service_running", AisReceiverService.isRunning)
        root.put("sdr_connected", AisReceiverService.hasDevice)
        root.put("sdr_permission", deviceInfo.found && deviceInfo.isUsable)
        root.put("sdr_device_name", if (deviceInfo.found) deviceInfo.deviceName else "No device")
        root.put("message_count", AisReceiverService.messageCount)
        root.put("usb_devices", usbDevicesArray)
        return root.toString()
    }

    private fun getUiStatusJson(): String {
        val ctx = service.applicationContext
        
        // Network
        val localIp = NetworkUtils.getLocalIpAddress(ctx)
        val isNetAvailable = NetworkUtils.isNetworkAvailable(ctx)
        val netStatus = if (isNetAvailable) "Connected" else "Disconnected"
        
        // Service
        val serviceRunning = AisReceiverService.isRunning
        val serviceStatus = if (serviceRunning) "🟢 Running" else "🔴 Stopped"
        
        // Device
        val deviceInfo = UsbDeviceScanner.scanForDevices(ctx)
        val deviceSummary = when {
            AisReceiverService.hasDevice -> "✅ ${deviceInfo.deviceName} - Active"
            deviceInfo.found && deviceInfo.isUsable -> "🔌 ${deviceInfo.deviceName} - Ready"
            deviceInfo.found && !deviceInfo.isUsable -> "⚠️ ${deviceInfo.deviceName} - No Permission"
            else -> "❌ No SDR device"
        }
        
        // GPS — the phone's own fix (independent of GPSD forwarding, which
        // used to drive this display and made GPS look "Disabled" whenever
        // forwarding was off). Forwarding state is appended as a detail.
        PhoneStatusTracker.poke(ctx)
        val gpsEnabled = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean("gpsd_enabled", false)
        val phoneFix = PhoneStatusTracker.lastLocation
        val fixAgeS = phoneFix?.let { (System.currentTimeMillis() - it.time) / 1000 }
        val forwardingNote = when {
            !gpsEnabled -> "forwarding off"
            GpsForwarder.isForwarding -> "forwarding 🟢"
            else -> "forwarding ⏸️ waiting"
        }
        val gpsStatus = when {
            !PhoneStatusTracker.permissionGranted -> "❌ No location permission ($forwardingNote)"
            !PhoneStatusTracker.providersEnabled -> "❌ Location off in Android ($forwardingNote)"
            phoneFix != null && fixAgeS != null && fixAgeS <= 120 -> "🟢 Fix ($forwardingNote)"
            phoneFix != null -> "🟡 Last fix ${fixAgeS?.let { if (it > 3600) "${it / 3600}h" else "${it / 60}min" }} ago ($forwardingNote)"
            else -> "🔍 Searching... ($forwardingNote)"
        }

        val heading = PhoneStatusTracker.lastHeading
            ?: (if (GpsForwarder.hasHeading) GpsForwarder.lastHeading else null)
        val gpsCoords = if (phoneFix != null) {
            val base = String.format(java.util.Locale.US, "%.6f, %.6f", phoneFix.latitude, phoneFix.longitude)
            if (heading != null) "$base 🧭 ${String.format("%.0f", heading)}°" else base
        } else if (GpsForwarder.hasPosition) {
            String.format(java.util.Locale.US, "%.6f, %.6f", GpsForwarder.lastLatitude, GpsForwarder.lastLongitude)
        } else "📍 Acquiring..."
        
        // Version
        val appVersion = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) { "Unknown" }

        // JSON Building
        return JSONObject().apply {
            put("app_version", appVersion)
            put("local_ip", localIp)
            put("network_status", netStatus)
            put("service_status", serviceStatus)
            put("device_status", deviceSummary)
            put("msg_count", AisReceiverService.messageCount)
            put("gps_status", gpsStatus)
            put("gps_coordinates", gpsCoords)
            put("gps_msg_count", GpsForwarder.messagesLastMinute)
            put("account_status", accountStatus(ctx))
        }.toString()
    }

    /** PortTracker/TrustedDocks sign-in state for the status page. */
    private fun accountStatus(ctx: android.content.Context): String {
        val all = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(ctx).all
        val stationId = (all["sharing_station_id"] as? Int)
            ?: all["sharing_station_id"]?.toString()?.toIntOrNull() ?: 0
        val email = all["sharing_account_email"]?.toString() ?: ""
        val mqttEnabled = all["mqtt_enabled"] == true ||
            all["mqtt_enabled"]?.toString() == "true"
        val apiKey = all["mqtt_api_key"]?.toString() ?: ""
        return when {
            stationId > 0 -> {
                val who = if (email.isNotBlank()) email else "PortTracker/TrustedDocks"
                val link = if (MqttPublisher.isConnected) "🟢" else "⚪"
                "$link Signed in as $who — station #$stationId"
            }
            mqttEnabled && apiKey.isNotBlank() ->
                "🔑 Connected via TrustedDocks API key"
            mqttEnabled -> "🔧 Manual MQTT configuration"
            else -> "⚪ Not signed in — running standalone"
        }
    }

    private fun serveStaticFile(file: File): Response {
        return try {
            val mimeType = determineMimeType(file.name)
            val fis = FileInputStream(file)
            newFixedLengthResponse(Response.Status.OK, mimeType, fis, file.length())
        } catch (e: IOException) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error serving file")
        }
    }

    private fun determineMimeType(name: String): String {
        return when {
            name.endsWith(".html") -> "text/html"
            name.endsWith(".js") -> "application/javascript"
            name.endsWith(".css") -> "text/css"
            name.endsWith(".json") -> "application/json"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".jpg") -> "image/jpeg"
            name.endsWith(".ico") -> "image/x-icon"
            else -> "application/octet-stream"
        }
    }

    private fun proxyToInternalServer(session: IHTTPSession): Response {
        val internalUrl = "http://127.0.0.1:$internalApiPort${session.uri}${if (session.queryParameterString != null) "?" + session.queryParameterString else ""}"
        
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(internalUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = session.method.name
            
            // Disable read timeout for streaming endpoints (SSE)
            if (session.uri.startsWith("/api/sse") || session.uri.startsWith("/api/log")) {
                connection.readTimeout = 0 // Infinite
                Log.d(TAG, "Proxying SSE stream: infinite timeout enabled")
            } else {
                connection.readTimeout = 10000
            }
            
            connection.connectTimeout = 5000
            connection.setRequestProperty("Accept-Encoding", "identity") // Force uncompressed
            
            val responseCode = connection.responseCode
            val rawStream = if (responseCode < 400) connection.inputStream else connection.errorStream
            val contentType = connection.contentType ?: "application/octet-stream"
            
            Log.d(TAG, "Proxying $internalUrl: Code=$responseCode, Type=$contentType")
            
            if (contentType.startsWith("text/event-stream")) {
                // SSE: hand off the raw stream to SseStreamHandler (it owns the connection lifecycle)
                Log.d(TAG, "Starting SSE Heartbeat Proxy")
                val sseStream = SseStreamHandler(rawStream).getInputStream()
                val response = newFixedLengthResponse(
                    Response.Status.lookup(responseCode),
                    contentType,
                    sseStream,
                    -1 // Chunked
                )
                response.addHeader("Cache-Control", "no-cache")
                response.addHeader("X-Accel-Buffering", "no")
                response.addHeader("Connection", "keep-alive")
                return response
            } else {
                // Non-streaming: copy response bytes then disconnect
                val responseBytes = rawStream.readBytes()
                val cacheControl = connection.getHeaderField("Cache-Control")
                connection.disconnect()
                connection = null // prevent double-disconnect in finally
                
                val response = newFixedLengthResponse(
                    Response.Status.lookup(responseCode),
                    contentType,
                    ByteArrayInputStream(responseBytes),
                    responseBytes.size.toLong()
                )
                
                // Forward key headers
                if (cacheControl != null) response.addHeader("Cache-Control", cacheControl)
                
                return response
            }

        } catch (e: Exception) {
            Log.e(TAG, "Proxy Error to $internalUrl", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Proxy Error: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }
    
    // Helper class to inject heartbeats into SSE stream
    private class SseStreamHandler(private val upstream: InputStream) {
        private val pipedOut = java.io.PipedOutputStream()
        private val pipedIn = java.io.PipedInputStream(pipedOut, 65536)
        private val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        @Volatile private var isRunning = true
        
        fun getInputStream(): InputStream {
            startRelay()
            startHeartbeat()
            return pipedIn
        }
        
        private fun startRelay() {
            executor.submit {
                try {
                    val buffer = ByteArray(4096)
                    var read: Int = 0
                    while (isRunning && upstream.read(buffer).also { read = it } != -1) {
                        try {
                            synchronized(pipedOut) {
                                pipedOut.write(buffer, 0, read)
                                pipedOut.flush()
                            }
                        } catch (e: java.io.IOException) {
                            break
                        }
                    }
                } catch (e: Exception) {
                    // Stream ended or error
                } finally {
                    close()
                }
            }
        }
        
        // Pre-allocate constant heartbeat byte array (4KB padding to force flush for Android/WebView)
        private val heartbeat = ": keep-alive ${" ".repeat(4096)}\n\n".toByteArray()
        
        private fun startHeartbeat() {
            executor.submit {
                try {
                    while (isRunning) {
                        Thread.sleep(2000)
                        try {
                            synchronized(pipedOut) {
                                pipedOut.write(heartbeat)
                                pipedOut.flush()
                            }
                        } catch (e: java.io.IOException) {
                            // Pipe broken — client disconnected
                            break
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                } finally {
                    close()
                }
            }
        }
        
        private fun close() {
            isRunning = false
            try { pipedOut.close() } catch (e: Exception) {}
            try { upstream.close() } catch (e: Exception) {}
            executor.shutdownNow()
        }
    }

    // ---- Direct NMEA SSE Stream ----

    /**
     * Serves live NMEA sentences as Server-Sent Events.
     * Uses the native NMEAListener callback system directly,
     * bypassing the C++ web server's SSE endpoint.
     */

    // ---- NMEA Ring Buffer for polling ----
    private val nmeaBuffer = java.util.concurrent.ConcurrentLinkedDeque<Pair<Long, String>>()
    private val nmeaSeq = java.util.concurrent.atomic.AtomicLong(0)
    private var nmeaBufferListener: AisReceiverService.Companion.NMEAListener? = null

    @Synchronized
    private fun ensureNmeaBufferListener() {
        if (nmeaBufferListener != null) return
        val listener = object : AisReceiverService.Companion.NMEAListener {
            override fun onNMEA(nmea: String) {
                val lines = nmea.trim().split("\n").filter { it.isNotBlank() }
                for (line in lines) {
                    val seq = nmeaSeq.incrementAndGet()
                    nmeaBuffer.addLast(Pair(seq, line.trim()))
                    // Keep max 200 entries
                    while (nmeaBuffer.size > 200) {
                        nmeaBuffer.pollFirst()
                    }
                }
            }
        }
        nmeaBufferListener = listener
        AisReceiverService.addNMEAListener(listener)
        Log.i(TAG, "NMEA buffer listener registered")
    }

    /**
     * GET /api/nmea_buffer?since=<seq>
     * Returns JSON: {"seq": <latest_seq>, "lines": ["!AIVDM,...", "!AIVDM,..."]}
     * Client passes since=0 (or nothing) on first call, then since=<last seq>
     * on subsequent calls to receive only new sentences.
     */
    private fun handleNmeaBuffer(session: IHTTPSession): Response {
        ensureNmeaBufferListener()
        val since = parseQuery(session.queryParameterString)["since"]?.toLongOrNull() ?: 0L
        val snapshot = nmeaBuffer.toList()
        val recent = (if (since > 0) snapshot.filter { it.first > since } else snapshot).takeLast(50)
        // Advance the client's cursor even when nothing new arrived.
        val latestSeq = snapshot.lastOrNull()?.first ?: since

        // JSONArray handles escaping — NMEA tag blocks contain backslashes,
        // which naive quote-only escaping turns into invalid JSON.
        val lines = org.json.JSONArray()
        for (p in recent) lines.put(p.second)
        val json = JSONObject().apply { put("seq", latestSeq); put("lines", lines) }

        val response = newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
        response.addHeader("Cache-Control", "no-cache")
        return response
    }

    private fun handleNmeaSse(): Response {
        val pipedOut = java.io.PipedOutputStream()
        val pipedIn = java.io.PipedInputStream(pipedOut, 65536)
        val isOpen = java.util.concurrent.atomic.AtomicBoolean(true)

        // Bounded hand-off: the native NMEA callback must NEVER block on a slow
        // client (it would stall AIS decoding and every other listener, incl.
        // MQTT). offer() drops sentences for THIS client when its pipe backs up.
        val queue = java.util.concurrent.LinkedBlockingQueue<String>(512)

        val listener = object : AisReceiverService.Companion.NMEAListener {
            override fun onNMEA(nmea: String) {
                if (isOpen.get()) queue.offer(nmea)
            }
        }
        AisReceiverService.addNMEAListener(listener)
        Log.i(TAG, "NMEA SSE client connected")

        // Single writer thread per client: drains the queue into the pipe and
        // doubles as heartbeat (poll timeout). Only this thread can block; a
        // broken pipe surfaces here as IOException → cleanup.
        Thread({
            try {
                while (isOpen.get()) {
                    val nmea = queue.poll(3, java.util.concurrent.TimeUnit.SECONDS)
                    val payload = if (nmea == null) {
                        ": heartbeat\n\n"
                    } else {
                        // SSE spec: each line of multiline data prefixed with "data:"
                        nmea.trim().split("\n").joinToString("\n") { "data: ${it.trim()}" } + "\n\n"
                    }
                    pipedOut.write(payload.toByteArray())
                    pipedOut.flush()
                }
            } catch (e: Exception) {
                // Client disconnected or interrupted
            } finally {
                isOpen.set(false)
                AisReceiverService.removeNMEAListener(listener)
                try { pipedOut.close() } catch (_: Exception) {}
                Log.i(TAG, "NMEA SSE client disconnected")
            }
        }, "NmeaSseWriter").start()

        val response = newChunkedResponse(
            Response.Status.OK,
            "text/event-stream",
            pipedIn
        )
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("X-Accel-Buffering", "no")
        response.addHeader("Connection", "keep-alive")
        return response
    }

    // ---- TrustedDocks / MQTT Gateway ----

    /**
     * POST /admin/api/gateway/connect
     * Body: {"api_key": "..."}
     *
     * Validates the API key against TrustedDocks, saves config to SharedPreferences,
     * and returns station information on success.
     */
    private fun handleGatewayConnect(session: IHTTPSession): Response {
        try {
            val map = HashMap<String, String>()
            session.parseBody(map)
            val jsonString = map["postData"]
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    """{"success":false,"error":"Missing request body"}""")

            val reqJson = JSONObject(jsonString)
            val apiKey = reqJson.optString("api_key", "")
            if (apiKey.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    """{"success":false,"error":"api_key is required"}""")
            }

            // Call TrustedDocks API (blocking — NanoHTTPD serves on worker threads)
            val stationConfig = TrustedDocksApi.getStationConfig(apiKey)
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                    """{"success":false,"error":"Invalid API key or network error"}""")

            // Persist to SharedPreferences.
            // Topics are auto-constructed from userId + antennaId.
            // Format: ais/{format}/{userId}/{antennaId}
            // This ensures consistent, deterministic topic assignment —
            // users cannot misconfigure their own topics.
            val topicJson = "ais/aisc-json/${stationConfig.userId}/${stationConfig.antennaId}"
            val topicRaw = "ais/raw/${stationConfig.userId}/${stationConfig.antennaId}"

            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(service)
            prefs.edit()
                .putBoolean("mqtt_enabled", true)
                .putString("mqtt_api_key", apiKey)
                // Canonical keys read by the rest of the app:
                .putString("pref_station_name", stationConfig.portName)
                .putString("mqtt_username", stationConfig.mqttUsername)
                .putString("mqtt_password", stationConfig.mqttPassword)
                .putString("mqtt_broker_url", stationConfig.brokerUrl)
                // Auto-constructed topics (userId/antennaId):
                .putString("mqtt_topic_json", topicJson)
                .putString("mqtt_topic_raw", topicRaw)
                .putString("mqtt_format", "aisc-json")
                // Metadata kept for the gateway/status display:
                .putString("mqtt_station_name", stationConfig.portName)
                .putInt("mqtt_antenna_id", stationConfig.antennaId)
                .putInt("mqtt_port_id", stationConfig.portId)
                .putInt("mqtt_user_id", stationConfig.userId)
                .putString("mqtt_user_name", stationConfig.userName)
                .putString("mqtt_company_name", stationConfig.companyName)
                .apply()

            // Build response
            val result = JSONObject().apply {
                put("success", true)
                put("station_name", stationConfig.portName)
                put("antenna_id", stationConfig.antennaId)
                put("port_id", stationConfig.portId)
                put("broker_url", stationConfig.brokerUrl)
                put("user_name", stationConfig.userName)
                put("company_name", stationConfig.companyName)
            }

            Log.i(TAG, "Gateway connected: station='${stationConfig.portName}'")
            return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())

        } catch (e: Exception) {
            Log.e(TAG, "Error in gateway/connect", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }.toString())
        }
    }

    /**
     * GET /admin/api/gateway/status
     *
     * Returns current MQTT connection status, message count, and saved station info.
     */
    private fun handleGatewayStatus(): Response {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(service)
        val result = JSONObject().apply {
            put("mqtt_enabled", prefs.getBoolean("mqtt_enabled", false))
            put("mqtt_connected", MqttPublisher.isConnected)
            put("mqtt_messages_sent", MqttPublisher.messagesSent)
            put("station_name", prefs.getString("mqtt_station_name", ""))
            put("antenna_id", prefs.getInt("mqtt_antenna_id", 0))
            put("port_id", prefs.getInt("mqtt_port_id", 0))
            put("broker_url", prefs.getString("mqtt_broker_url", ""))
            put("user_name", prefs.getString("mqtt_user_name", ""))
            put("company_name", prefs.getString("mqtt_company_name", ""))
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
    }
    /**
     * GET /admin/api/health
     *
     * Compact health endpoint for remote dashboard monitoring.
     * Returns all key station metrics in a single JSON response.
     */
    private fun handleHealth(): Response {
        val ctx = service.applicationContext
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val deviceInfo = UsbDeviceScanner.scanForDevices(ctx)

        val appVersion = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) { "unknown" }

        // NOTE: this endpoint is intentionally unauthenticated (polled by the
        // remote dashboard through the public tunnel), so it must NOT expose
        // locating or credential data. Sensitive fields deliberately omitted:
        // GPS coordinates (gps_lat/gps_lon), mqtt_username, antenna_uuid.
        val result = JSONObject().apply {
            put("station_name", prefs.getString("pref_station_name", "") ?: "")
            put("service_running", AisReceiverService.isRunning)
            put("sdr_connected", AisReceiverService.hasDevice)
            put("sdr_device", if (deviceInfo.found) deviceInfo.deviceName else "none")
            put("ais_msg_count", AisReceiverService.messageCount)
            put("gps_locked", GpsForwarder.hasPosition)
            put("mqtt_connected", MqttPublisher.isConnected)
            put("mqtt_messages_sent", MqttPublisher.messagesSent)
            put("mqtt_enabled", prefs.getBoolean("mqtt_enabled", false))
            put("web_viewer_port", prefs.getString("pref_local_web_port", "8080") ?: "8080")
            put("remote_access", prefs.getBoolean("pref_enable_remote", false))
            put("uptime_ms", android.os.SystemClock.elapsedRealtime())
            put("version", appVersion)
            put("timestamp", System.currentTimeMillis())
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
    }

    /**
     * POST /admin/api/usb/request_permission
     * Scans for SDR devices and requests USB permission if needed.
     */
    private fun handleRequestUsbPermission(): Response {
        val ctx = service.applicationContext
        val deviceInfo = UsbDeviceScanner.scanForDevices(ctx)
        
        if (!deviceInfo.found) {
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"success":false,"message":"No SDR device connected"}""")
        }
        
        if (deviceInfo.isUsable) {
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"success":true,"message":"Permission already granted","device":"${deviceInfo.deviceName}"}""")
        }
        
        // Device found but no permission — request it
        try {
            val usbManager = ctx.getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
            for ((_, device) in usbManager.deviceList) {
                if (device.vendorId == deviceInfo.vendorId && device.productId == deviceInfo.productId) {
                    val permissionIntent = android.app.PendingIntent.getBroadcast(
                        ctx, 0,
                        android.content.Intent("com.porttracker.ais.USB_PERMISSION").apply {
                            setPackage(ctx.packageName)
                        },
                        android.app.PendingIntent.FLAG_MUTABLE
                    )
                    usbManager.requestPermission(device, permissionIntent)
                    return newFixedLengthResponse(Response.Status.OK, "application/json",
                        """{"success":true,"message":"Permission requested for ${deviceInfo.deviceName}","device":"${deviceInfo.deviceName}"}""")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting USB permission", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown") }.toString())
        }
        
        return newFixedLengthResponse(Response.Status.OK, "application/json",
            """{"success":false,"message":"Could not find device to request permission"}""")
    }
    
    /**
     * POST /admin/api/engine/restart
     * Re-scans USB devices and restarts the AIS engine.
     */
    private fun handleEngineRestart(): Response {
        try {
            service.triggerEngineRestart()
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"success":true,"message":"Engine restart triggered"}""")
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering engine restart", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown") }.toString())
        }
    }

    /**
     * POST /admin/api/auth/start
     *
     * Begin the Auth0 device-code login. Returns the code the user must
     * confirm in a browser; the provisioning continues in the background and
     * is observable via GET /admin/api/auth/status.
     */
    private fun handleAuthStart(): Response {
        val result = PorttrackerAuth.startLogin(service)
        return newFixedLengthResponse(
            Response.Status.OK, "application/json", authStatusJson(result))
    }

    /** GET /admin/api/auth/status - poll the login/provisioning state. */
    private fun handleAuthStatus(): Response {
        val result = PorttrackerAuth.currentStatus()
        // Once provisioning finished, make the new credentials effective.
        if (result.state == PorttrackerAuth.State.PROVISIONED
                && !authRestartDone) {
            authRestartDone = true
            Thread {
                try {
                    service.restartMqttPublisher()
                } catch (e: Exception) {
                    Log.e(TAG, "MQTT restart after login failed", e)
                }
            }.start()
        }
        if (result.state == PorttrackerAuth.State.PENDING) {
            authRestartDone = false
        }
        return newFixedLengthResponse(
            Response.Status.OK, "application/json", authStatusJson(result))
    }

    private var authRestartDone = false

    /**
     * GET /admin/api/doctor — run all self-checks and return them as a JSON
     * array. Each check: {id, label, status: ok|warn|fail|off, detail, fix?}.
     * `fix` names a JS-bridge action the web UI can offer inside the app.
     */
    private fun runDoctor(): String {
        val ctx = service
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val checks = org.json.JSONArray()

        fun add(id: String, label: String, status: String, detail: String, fix: String? = null) {
            checks.put(JSONObject().apply {
                put("id", id); put("label", label)
                put("status", status); put("detail", detail)
                if (fix != null) put("fix", fix)
            })
        }

        // 1. Service
        add("service", "AIS service",
            if (AisReceiverService.isRunning) "ok" else "fail",
            if (AisReceiverService.isRunning) "Running" else "Service is not running")

        // 2. USB SDR
        val usb = try { UsbDeviceScanner.scanForDevices(ctx) } catch (e: Exception) { null }
        when {
            usb == null -> add("usb", "USB SDR receiver", "fail", "USB scan failed")
            usb.found && usb.isUsable ->
                add("usb", "USB SDR receiver", "ok", usb.deviceName ?: "SDR connected")
            usb.found ->
                add("usb", "USB SDR receiver", "fail",
                    "${usb.deviceName ?: "SDR"} found but no USB permission",
                    "usb_permission")
            else ->
                add("usb", "USB SDR receiver", "fail",
                    "No RTL-SDR dongle found — plug it in via the USB-OTG adapter")
        }

        // 3. Radio / AIS reception
        val lastMsgAge = if (AisReceiverService.lastNmeaTime == 0L) -1
            else (System.currentTimeMillis() - AisReceiverService.lastNmeaTime) / 1000
        when {
            !AisReceiverService.hasDevice ->
                add("radio", "AIS reception", "warn",
                    "Receiver engine idle (no SDR) — web-only mode")
            lastMsgAge in 0..300 ->
                add("radio", "AIS reception", "ok",
                    "${AisReceiverService.messageCount} messages, last ${lastMsgAge}s ago")
            lastMsgAge > 300 ->
                add("radio", "AIS reception", "warn",
                    "No AIS message for ${lastMsgAge / 60} min — check antenna/coverage")
            else ->
                add("radio", "AIS reception", "warn",
                    "No AIS messages received yet — check antenna and VHF coverage")
        }

        // 4. Internet — ask Android first (it already runs captive-portal
        // validation on every network); fall back to an HTTPS probe. The
        // probe MUST be https: our network security config only permits
        // cleartext to localhost, so a plain-http probe always throws.
        val internetOk = run {
            val validated = try {
                val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                caps != null &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } catch (e: Exception) { false }
            validated || try {
                val c = java.net.URL("https://connectivitycheck.gstatic.com/generate_204")
                    .openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 3000; c.readTimeout = 3000
                c.requestMethod = "GET"
                val ok = c.responseCode == 204
                c.disconnect()
                ok
            } catch (e: Exception) { false }
        }
        add("internet", "Internet connection",
            if (internetOk) "ok" else "fail",
            if (internetOk) "Online"
            else "No internet — data sharing, sign-in and map tiles will not work",
            if (internetOk) null else "wifi_settings")

        // 5. Location permission
        val hasLocation =
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        add("location_permission", "Location permission",
            if (hasLocation) "ok" else "warn",
            if (hasLocation) "Granted"
            else "Not granted — needed for station position and own-ship GPS output",
            if (hasLocation) null else "location_permission")

        // 6. Location services
        val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE)
            as? android.location.LocationManager
        val locOn = try {
            lm?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true ||
            lm?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true
        } catch (e: Exception) { false }
        add("location_services", "Location services (GPS)",
            if (locOn) "ok" else "warn",
            if (locOn) "Enabled" else "Disabled in Android settings",
            if (locOn) null else "location_settings")

        // 7. Battery optimization
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE)
            as android.os.PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        add("battery", "Battery optimization",
            if (exempt) "ok" else "fail",
            if (exempt) "App is exempt — Android will not kill the station"
            else "Not exempt — Android may kill the receiver in the background",
            if (exempt) null else "battery_settings")

        // 8. Notifications (needed for the foreground service notice, API 33+)
        val notifOk = androidx.core.app.NotificationManagerCompat.from(ctx)
            .areNotificationsEnabled()
        add("notifications", "Notifications",
            if (notifOk) "ok" else "warn",
            if (notifOk) "Enabled"
            else "Disabled — the service status notification is hidden",
            if (notifOk) null else "app_settings")

        // 9. MQTT / data sharing
        val mqttEnabled = prefs.all["mqtt_enabled"] == true ||
            prefs.all["mqtt_enabled"]?.toString() == "true"
        when {
            !mqttEnabled -> add("mqtt", "Cloud data sharing (MQTT)", "off",
                "Not configured — sign in on the Account tab to share your AIS data")
            MqttPublisher.isConnected -> add("mqtt", "Cloud data sharing (MQTT)", "ok",
                "Connected, ${MqttPublisher.messagesSent} messages sent")
            else -> add("mqtt", "Cloud data sharing (MQTT)", "fail",
                "Enabled but not connected — check internet and credentials")
        }

        // 10. Chartplotter NMEA output
        val nmeaOutEnabled = prefs.all["nmea_out_enabled"] == true ||
            prefs.all["nmea_out_enabled"]?.toString() == "true"
        when {
            !nmeaOutEnabled -> add("nmea_out", "Chartplotter output", "off", "Disabled")
            NmeaOutputServer.isActive && NmeaOutputServer.lastError == null ->
                add("nmea_out", "Chartplotter output", "ok",
                    "Active, ${NmeaOutputServer.tcpClients} app(s) connected, " +
                        "${NmeaOutputServer.sentenceCount} sentences sent")
            NmeaOutputServer.isActive ->
                add("nmea_out", "Chartplotter output", "warn",
                    NmeaOutputServer.lastError ?: "Active with warnings")
            else -> add("nmea_out", "Chartplotter output", "fail",
                "Enabled but not running — save settings to restart it")
        }

        // 11. Remote access tunnel
        val remoteEnabled = prefs.all["pref_enable_remote"] == true ||
            prefs.all["pref_enable_remote"]?.toString() == "true"
        when {
            !remoteEnabled -> add("remote", "Remote access tunnel", "off", "Disabled")
            service.frpTunnelRunning() -> add("remote", "Remote access tunnel", "ok", "Tunnel up")
            else -> add("remote", "Remote access tunnel", "fail",
                "Enabled but tunnel is down — check internet")
        }

        return JSONObject().put("checks", checks)
            .put("timestamp", System.currentTimeMillis()).toString()
    }

    private fun authStatusJson(s: PorttrackerAuth.Status): String =
        JSONObject().apply {
            put("state", s.state.name.lowercase())
            put("user_code", s.userCode ?: JSONObject.NULL)
            put("verification_uri", s.verificationUri ?: JSONObject.NULL)
            put("message", s.message ?: JSONObject.NULL)
            put("station_id", s.stationId ?: JSONObject.NULL)
            put("station_hostname", s.stationHostname ?: JSONObject.NULL)
        }.toString()

    /**
     * POST /admin/api/mqtt/restart
     * Stops and restarts the MQTT publisher with current preferences.
     */
    private fun handleMqttRestart(): Response {
        try {
            service.restartMqttPublisher()
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"success":true,"message":"MQTT publisher restarted"}""")
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting MQTT", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown") }.toString())
        }
    }

    /**
     * POST /admin/api/frp/restart
     * Restarts the FRP tunnel with current preferences.
     */
    private fun handleFrpRestart(): Response {
        try {
            service.restartFrpTunnel()
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"success":true,"message":"FRP tunnel restarted"}""")
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting FRP", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown") }.toString())
        }
    }

    // --- OTA Update Handlers ---

    private fun handleUpdateCheck(): Response {
        return try {
            // Run check synchronously (NanoHTTPD handles each request in its own thread)
            val info = updateManager.checkForUpdate()
            val json = JSONObject().apply {
                put("success", true)
                put("update", info.toJson())
                if (updateManager.lastError != null) {
                    put("error", updateManager.lastError)
                }
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                JSONObject().apply { put("success", false); put("error", e.message ?: "Check failed") }.toString())
        }
    }

    private fun handleUpdateInstall(): Response {
        if (updateManager.isDownloading) {
            return newFixedLengthResponse(Response.Status.OK, "application/json",
                """{"success":false,"error":"Download already in progress","progress":${updateManager.downloadProgress}}""")
        }

        val info = updateManager.lastCheckResult
        if (info == null || !info.available) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                """{"success":false,"error":"No update available. Run /admin/api/update/check first."}""")
        }

        // Start download + install on a background thread
        Thread {
            try {
                updateManager.downloadAndInstall()
            } catch (e: Exception) {
                Log.e(TAG, "Update install failed", e)
            }
        }.start()

        return newFixedLengthResponse(Response.Status.OK, "application/json",
            """{"success":true,"message":"Download started. Check /admin/api/update/status for progress."}""")
    }

    /**
     * Decide whether a request arrived via the remote FRP tunnel.
     *
     * The frpc client forwards tunnelled traffic to the local web port, so
     * [IHTTPSession.remoteIpAddress] is 127.0.0.1 for both the in-app WebView
     * AND remote visitors — peer IP alone cannot tell them apart. We instead
     * key off the HTTP layer: the tunnel sets X-Forwarded-For and a public
     * Host header (a DNS name), whereas local/LAN access uses an IP-literal or
     * "localhost" Host. Anything that looks like a public hostname is treated
     * as remote and must authenticate.
     */
    private fun isRemoteRequest(session: IHTTPSession): Boolean {
        // FRP's http proxy injects this for tunnelled requests.
        if (!session.headers["x-forwarded-for"].isNullOrBlank()) return true

        val host = (session.headers["host"] ?: "").substringBefore(":").trim().lowercase()
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return false

        // IP literals (incl. LAN addresses like 192.168.x.x) are local access.
        // A DNS name (the public subdomain) means the request came in remotely.
        val isIpLiteral = host.matches(Regex("^[0-9.]+$")) || host.contains(':')
        return !isIpLiteral
    }

    /** Exact loopback match — used only to short-circuit the in-app WebView. */
    private fun isLoopbackAddress(ip: String): Boolean {
        return ip == "127.0.0.1" ||
            ip == "::1" ||
            ip == "0:0:0:0:0:0:0:1" ||
            ip == "::ffff:127.0.0.1" ||
            ip.equals("localhost", ignoreCase = true)
    }

    /**
     * GET /admin/api/vessels?limit=&offset=&q=
     * Returns cached static vessel rows for the DB page.
     */
    private fun handleVesselList(session: IHTTPSession): Response {
        val params = parseQuery(session.queryParameterString)
        val limit = (params["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 2000)
        val offset = (params["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val q = params["q"]

        val db = VesselDatabase.getInstance(service)
        val result = JSONObject().apply {
            put("count", db.count())
            put("db_writer_running", VesselCacheWriter.isRunning)
            put("last_poll_ms", VesselCacheWriter.lastPollMs)
            put("vessels", db.queryJson(limit, offset, q))
        }
        val response = newFixedLengthResponse(Response.Status.OK, "application/json", result.toString())
        response.addHeader("Cache-Control", "no-cache")
        return response
    }

    /** GET /admin/api/vessels.csv — full table as a CSV download. */
    private fun handleVesselCsv(): Response {
        val csv = VesselDatabase.getInstance(service).exportCsv()
        val response = newFixedLengthResponse(Response.Status.OK, "text/csv", csv)
        response.addHeader("Content-Disposition", "attachment; filename=\"vessels.csv\"")
        return response
    }

    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrEmpty()) return emptyMap()
        return query.split("&").mapNotNull {
            val idx = it.indexOf('=')
            if (idx <= 0) null
            else try {
                java.net.URLDecoder.decode(it.substring(0, idx), "UTF-8") to
                    java.net.URLDecoder.decode(it.substring(idx + 1), "UTF-8")
            } catch (e: Exception) { null }
        }.toMap()
    }

    /**
     * Check HTTP Basic Authentication.
     *
     * Policy ("gate remote only, fail-closed"):
     *  - Remote (tunnelled) requests ALWAYS require credentials — even though
     *    they reach us on 127.0.0.1 — and are denied if none are configured.
     *  - Local/LAN requests are open by default; they only require auth when the
     *    user explicitly enables web_auth_enabled (and never for the loopback
     *    in-app WebView).
     *
     * Returns null if auth passes/not required, or a 401 response if it fails.
     */
    private fun checkAuthentication(session: IHTTPSession): Response? {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(service)

        val isRemote = isRemoteRequest(session)
        val authEnabled = prefs.getBoolean("web_auth_enabled", false)
        val remoteIp = session.remoteIpAddress ?: ""
        Log.d(TAG, "Auth check: remoteIp=$remoteIp isRemote=$isRemote authEnabled=$authEnabled")

        if (!isRemote) {
            // Local or LAN access.
            if (!authEnabled) return null                       // open by default
            if (isLoopbackAddress(remoteIp)) return null         // in-app WebView always allowed
            // authEnabled + LAN: fall through to credential check below.
        }

        val requiredUsername = prefs.getString("web_auth_username", "") ?: ""
        val requiredPassword = prefs.getString("web_auth_password", "") ?: ""

        // No credentials configured: fail closed for remote, allow for local
        // (the local user has no way to authenticate, and the tunnel is gated
        // separately so it should never start without credentials).
        if (requiredUsername.isEmpty() || requiredPassword.isEmpty()) {
            return if (isRemote) createUnauthorizedResponse() else null
        }

        // Get Authorization header
        val authHeader = session.headers["authorization"]
        
        if (authHeader == null || !authHeader.startsWith("Basic ", ignoreCase = true)) {
            return createUnauthorizedResponse()
        }
        
        try {
            // Decode Base64 credentials
            val base64Credentials = authHeader.substring(6)
            val credentials = String(Base64.decode(base64Credentials, Base64.DEFAULT))
            val colonIndex = credentials.indexOf(':')
            
            if (colonIndex == -1) {
                return createUnauthorizedResponse()
            }
            
            val username = credentials.substring(0, colonIndex)
            val password = credentials.substring(colonIndex + 1)
            
            // Validate credentials
            if (username == requiredUsername && password == requiredPassword) {
                return null // Auth passed
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding auth header", e)
        }
        
        return createUnauthorizedResponse()
    }
    
    private fun createUnauthorizedResponse(): Response {
        val response = newFixedLengthResponse(
            Response.Status.UNAUTHORIZED,
            "text/html",
            """
            <!DOCTYPE html>
            <html>
            <head><title>401 Unauthorized</title></head>
            <body>
                <h1>401 Unauthorized</h1>
                <p>Authentication required to access this resource.</p>
            </body>
            </html>
            """.trimIndent()
        )
        response.addHeader("WWW-Authenticate", "Basic realm=\"TrustedDocks AIS\"")
        return response
    }
}

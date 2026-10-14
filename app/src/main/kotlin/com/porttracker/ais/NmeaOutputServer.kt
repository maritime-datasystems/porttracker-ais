package com.porttracker.ais

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Presents this station as an AIS/GPS instrument on the local network so
 * chartplotter apps (OpenCPN, Navionics, Aqua Map, ...) can use it directly:
 *
 *  - NMEA 0183: raw !AIVDM sentences plus own-ship $GPRMC/$GPGGA/$GPHDT
 *  - NMEA 2000 emulation: AIS + GPS re-encoded as PGNs, framed as
 *    Yacht Devices RAW or SeaSmart $PCDIN (see [Nmea2000Encoder])
 *
 * Transports: TCP server (clients connect to us, standard port 10110) and/or
 * UDP to a target address (255.255.255.255 = whole-subnet broadcast).
 *
 * Threading: the native receiver thread only ever enqueues into a bounded
 * queue — a dispatcher thread does conversion + fan-out, and every TCP client
 * has its own bounded queue + writer thread, so one stuck client can never
 * block the radio or the other clients.
 */
class NmeaOutputServer(
    private val context: Context,
    private val config: Config,
) {
    data class Config(
        val format: String,             // "nmea0183" | Nmea2000Encoder.FORMAT_*
        val tcpEnabled: Boolean,
        val tcpPort: Int,
        val udpEnabled: Boolean,
        val udpHost: String,
        val udpPort: Int,
        val includeGps: Boolean,
    ) {
        companion object {
            fun fromPrefs(context: Context): Config? {
                // Web config saves store types inconsistently (string vs
                // native) — read tolerantly via the raw pref map.
                val all = androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(context).all
                fun str(key: String, def: String): String =
                    all[key]?.toString()?.takeIf { it.isNotBlank() } ?: def
                fun bool(key: String, def: Boolean): Boolean =
                    when (val v = all[key]) {
                        is Boolean -> v
                        is String -> v.equals("true", true)
                        null -> def
                        else -> def
                    }
                if (!bool("nmea_out_enabled", false)) return null
                return Config(
                    format = str("nmea_out_format", "nmea0183"),
                    tcpEnabled = bool("nmea_out_tcp_enabled", true),
                    tcpPort = str("nmea_out_tcp_port", "10110").toIntOrNull() ?: 10110,
                    udpEnabled = bool("nmea_out_udp_enabled", false),
                    udpHost = str("nmea_out_udp_host", "255.255.255.255"),
                    udpPort = str("nmea_out_udp_port", "10110").toIntOrNull() ?: 10110,
                    includeGps = bool("nmea_out_gps", true),
                )
            }
        }
    }

    companion object {
        private const val TAG = "porttracker-service.NmeaOut"
        private const val QUEUE_CAPACITY = 2000
        private const val CLIENT_QUEUE_CAPACITY = 500
        private const val MAX_CLIENTS = 8

        // Stats for status/doctor display
        @Volatile var isActive = false
            private set
        @Volatile var tcpClients = 0
            private set
        @Volatile var sentenceCount = 0L
            private set
        @Volatile var lastError: String? = null
            private set
    }

    private val rawQueue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)
    private val clients = CopyOnWriteArrayList<TcpClient>()

    @Volatile private var running = false
    private var dispatcherThread: Thread? = null
    private var acceptThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var udpAddress: InetAddress? = null
    private var gpsTimerThread: Thread? = null
    private var locationManager: LocationManager? = null

    @Volatile private var lastLocation: Location? = null

    private val nmeaListener = object : AisReceiverService.Companion.NMEAListener {
        override fun onNMEA(nmea: String) {
            // Native receiver thread — enqueue only, drop when full.
            rawQueue.offer(nmea)
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    // ------------------------------------------------------------------

    fun start(): Boolean {
        if (running) return true
        running = true
        lastError = null

        if (config.udpEnabled) {
            try {
                udpAddress = InetAddress.getByName(config.udpHost)
                udpSocket = DatagramSocket().apply { broadcast = true }
            } catch (e: Exception) {
                Log.e(TAG, "UDP setup failed", e)
                lastError = "UDP: ${e.message}"
            }
        }

        if (config.tcpEnabled) {
            try {
                serverSocket = ServerSocket(config.tcpPort)
                acceptThread = Thread { acceptLoop() }.apply {
                    name = "NmeaOut-Accept"; isDaemon = true; start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP server on port ${config.tcpPort} failed", e)
                lastError = "TCP port ${config.tcpPort}: ${e.message}"
            }
        }

        dispatcherThread = Thread { dispatchLoop() }.apply {
            name = "NmeaOut-Dispatch"; isDaemon = true; start()
        }

        AisReceiverService.addNMEAListener(nmeaListener)
        if (config.includeGps) startGps()

        isActive = true
        InternalLog.log(
            "NMEA output started (${config.format}" +
                (if (config.tcpEnabled) ", tcp:${config.tcpPort}" else "") +
                (if (config.udpEnabled) ", udp:${config.udpHost}:${config.udpPort}" else "") + ")")
        return true
    }

    fun stop() {
        running = false
        isActive = false
        AisReceiverService.removeNMEAListener(nmeaListener)
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        gpsTimerThread?.interrupt()
        dispatcherThread?.interrupt()
        try { serverSocket?.close() } catch (_: Exception) {}
        clients.forEach { it.close() }
        clients.clear()
        tcpClients = 0
        try { udpSocket?.close() } catch (_: Exception) {}
        Log.i(TAG, "NMEA output stopped")
    }

    // ------------------------------------------------------------------
    // AIS fan-out
    // ------------------------------------------------------------------

    private fun dispatchLoop() {
        while (running) {
            val raw = try {
                rawQueue.poll(1, TimeUnit.SECONDS) ?: continue
            } catch (e: InterruptedException) {
                return
            }
            val lines: List<String> = if (config.format == "nmea0183") {
                // Plotters expect plain sentences without tag blocks
                listOf(AisDecoder.stripTagBlock(raw))
            } else {
                val decoded = AisDecoder.decode(raw) ?: continue
                Nmea2000Encoder.encodeAis(decoded, config.format)
            }
            lines.forEach { send(it) }
        }
    }

    private fun send(line: String) {
        val data = (line + "\r\n").toByteArray()
        sentenceCount++
        udpSocket?.let { socket ->
            try {
                socket.send(DatagramPacket(data, data.size, udpAddress, config.udpPort))
            } catch (e: Exception) {
                // Network hiccup — drop; the next sentence will retry.
            }
        }
        clients.forEach { it.offer(data) }
    }

    // ------------------------------------------------------------------
    // TCP server
    // ------------------------------------------------------------------

    private inner class TcpClient(private val socket: Socket) {
        private val queue = LinkedBlockingQueue<ByteArray>(CLIENT_QUEUE_CAPACITY)
        @Volatile private var open = true
        private val writer = Thread {
            try {
                val out: OutputStream = socket.getOutputStream()
                while (open) {
                    val data = queue.poll(2, TimeUnit.SECONDS) ?: continue
                    out.write(data)
                    out.flush()
                }
            } catch (e: Exception) {
                // Client went away
            } finally {
                close()
            }
        }.apply { name = "NmeaOut-Client"; isDaemon = true }

        fun start() = writer.start()

        fun offer(data: ByteArray) {
            if (!queue.offer(data)) {
                // Client can't keep up — drop oldest so fresh data wins.
                queue.poll()
                queue.offer(data)
            }
        }

        fun close() {
            if (!open) return
            open = false
            try { socket.close() } catch (_: Exception) {}
            clients.remove(this)
            tcpClients = clients.size
            Log.i(TAG, "Client disconnected (${clients.size} left)")
        }
    }

    private fun acceptLoop() {
        val server = serverSocket ?: return
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Accept failed: ${e.message}")
                return
            }
            if (clients.size >= MAX_CLIENTS) {
                try { socket.close() } catch (_: Exception) {}
                continue
            }
            socket.tcpNoDelay = true
            val client = TcpClient(socket)
            clients.add(client)
            tcpClients = clients.size
            client.start()
            Log.i(TAG, "Client connected from ${socket.inetAddress.hostAddress} " +
                "(${clients.size} total)")
        }
    }

    // ------------------------------------------------------------------
    // Own-ship GPS
    // ------------------------------------------------------------------

    private fun startGps() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No location permission — own-ship GPS output disabled")
            lastError = "GPS: location permission missing"
            return
        }
        try {
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    if (locationManager?.isProviderEnabled(provider) == true) {
                        locationManager?.requestLocationUpdates(
                            provider, 1000L, 0f, locationListener, Looper.getMainLooper())
                    }
                } catch (_: Exception) {}
            }
            locationManager?.let { lm ->
                for (provider in listOf("gps", "network", "passive")) {
                    try {
                        lm.getLastKnownLocation(provider)?.let { lastLocation = it }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GPS setup failed", e)
        }
        // Emit own-ship position every 2 s from whatever fix we have
        gpsTimerThread = Thread {
            while (running) {
                try { Thread.sleep(2000) } catch (e: InterruptedException) { return@Thread }
                val loc = lastLocation ?: continue
                if (System.currentTimeMillis() - loc.time > 120_000) continue  // stale
                emitGps(loc)
            }
        }.apply { name = "NmeaOut-GPS"; isDaemon = true; start() }
    }

    private fun emitGps(loc: Location) {
        val heading = if (GpsForwarder.hasHeading) GpsForwarder.lastHeading
            else if (loc.hasBearing()) loc.bearing else null
        if (config.format == "nmea0183") {
            send(nmeaRmc(loc))
            send(nmeaGga(loc))
            heading?.let { send(nmeaHdt(it)) }
        } else {
            Nmea2000Encoder.encodeGps(
                loc.latitude, loc.longitude,
                loc.speed * 1.94384, if (loc.hasBearing()) loc.bearing.toDouble() else -1.0,
                heading, config.format,
            ).forEach { send(it) }
        }
    }

    // ------------------------------------------------------------------
    // NMEA 0183 sentence builders
    // ------------------------------------------------------------------

    private fun checksum(body: String): String {
        var cs = 0
        for (c in body) cs = cs xor c.code
        return String.format(Locale.US, "%02X", cs)
    }

    private fun sentence(body: String): String = "\$$body*${checksum(body)}"

    private fun latNmea(lat: Double): Pair<String, String> {
        val abs = Math.abs(lat)
        val deg = abs.toInt()
        val min = (abs - deg) * 60
        return String.format(Locale.US, "%02d%07.4f", deg, min) to (if (lat >= 0) "N" else "S")
    }

    private fun lonNmea(lon: Double): Pair<String, String> {
        val abs = Math.abs(lon)
        val deg = abs.toInt()
        val min = (abs - deg) * 60
        return String.format(Locale.US, "%03d%07.4f", deg, min) to (if (lon >= 0) "E" else "W")
    }

    private fun utcTime(ms: Long): String {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.timeInMillis = ms
        return String.format(Locale.US, "%02d%02d%02d.%02d",
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
            c.get(java.util.Calendar.SECOND), c.get(java.util.Calendar.MILLISECOND) / 10)
    }

    private fun nmeaRmc(loc: Location): String {
        val (latS, latH) = latNmea(loc.latitude)
        val (lonS, lonH) = lonNmea(loc.longitude)
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.timeInMillis = loc.time
        val date = String.format(Locale.US, "%02d%02d%02d",
            c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.YEAR) % 100)
        val sog = String.format(Locale.US, "%.1f", loc.speed * 1.94384)
        val cog = if (loc.hasBearing())
            String.format(Locale.US, "%.1f", loc.bearing) else ""
        return sentence("GPRMC,${utcTime(loc.time)},A,$latS,$latH,$lonS,$lonH,$sog,$cog,$date,,,A")
    }

    private fun nmeaGga(loc: Location): String {
        val (latS, latH) = latNmea(loc.latitude)
        val (lonS, lonH) = lonNmea(loc.longitude)
        val alt = if (loc.hasAltitude())
            String.format(Locale.US, "%.1f", loc.altitude) else "0.0"
        return sentence("GPGGA,${utcTime(loc.time)},$latS,$latH,$lonS,$lonH,1,08,1.0,$alt,M,0.0,M,,")
    }

    private fun nmeaHdt(heading: Float): String =
        sentence(String.format(Locale.US, "GPHDT,%.1f,T", heading))
}

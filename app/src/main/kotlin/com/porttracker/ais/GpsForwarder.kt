package com.porttracker.ais

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * Forwards mobile phone GPS location and heading via UDP in NMEA format
 * Sentences: GPRMC, GPGGA, HEHDT (heading)
 */
class GpsForwarder(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val intervalSeconds: Int = 10
) : LocationListener, SensorEventListener {
    
    companion object {
        private const val TAG = "porttracker-service.GPS"
        private const val MIN_DISTANCE_M = 0f   // Update on any movement
        
        // Stats for Status display
        @Volatile internal var lastLatitude: Double = 0.0
        @Volatile internal var lastLongitude: Double = 0.0
        @Volatile internal var lastHeading: Float = 0f
        @Volatile internal var hasPosition: Boolean = false
        @Volatile internal var hasHeading: Boolean = false
        @Volatile internal var isForwarding: Boolean = false
        
        // Message count tracking (per minute)
        private val messageTimes = mutableListOf<Long>()
        private val messageTimesLock = Object()
        
        val messagesLastMinute: Int
            get() {
                synchronized(messageTimesLock) {
                    val cutoff = System.currentTimeMillis() - 60000
                    messageTimes.removeAll { it < cutoff }
                    return messageTimes.size
                }
            }
        
        private fun recordMessage() {
            synchronized(messageTimesLock) {
                // Prune on write too — on headless stations nothing may ever
                // read messagesLastMinute, and the list must not grow forever.
                val cutoff = System.currentTimeMillis() - 60000
                messageTimes.removeAll { it < cutoff }
                messageTimes.add(System.currentTimeMillis())
            }
        }
        
        fun reset() {
            lastLatitude = 0.0
            lastLongitude = 0.0
            lastHeading = 0f
            hasPosition = false
            hasHeading = false
            isForwarding = false
            synchronized(messageTimesLock) {
                messageTimes.clear()
            }
        }
    }
    
    private val minTimeMs = intervalSeconds * 1000L
    
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var socket: DatagramSocket? = null
    private var destAddress: InetAddress? = null
    private var executor = Executors.newSingleThreadExecutor()
    private var isRunning = false
    
    // Cached date formatters (accessed only from single-thread executor, so thread-safe)
    private val timeFormat = SimpleDateFormat("HHmmss.SS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val dateFormat = SimpleDateFormat("ddMMyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    
    // Compass sensor data
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null
    private var currentHeading: Float = 0f
    
    fun start(): Boolean {
        if (isRunning) return true
        executor = Executors.newSingleThreadExecutor()
        
        try {
            // Check permissions
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "GPS permission not granted")
                return false
            }
            
            // Setup UDP socket
            socket = DatagramSocket()
            destAddress = InetAddress.getByName(host)
            
            // Setup location manager
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            // Request location updates on main looper
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                minTimeMs,
                MIN_DISTANCE_M,
                this,
                Looper.getMainLooper()
            )
            
            // Setup compass sensors for heading (HDT)
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            
            accelerometer?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            magnetometer?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            
            isRunning = true
            isForwarding = true
            Log.i(TAG, "GPS+HDT forwarding started to $host:$port (interval: ${intervalSeconds}s)")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GPS forwarding", e)
            stop()
            return false
        }
    }
    
    fun stop() {
        isRunning = false
        isForwarding = false
        hasHeading = false
        
        try {
            locationManager?.removeUpdates(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing location updates", e)
        }
        locationManager = null
        
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering sensors", e)
        }
        sensorManager = null
        gravity = null
        geomagnetic = null
        
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket", e)
        }
        socket = null
        destAddress = null
        
        executor.shutdownNow()
        
        Log.i(TAG, "GPS+HDT forwarding stopped")
    }
    
    override fun onLocationChanged(location: Location) {
        if (!isRunning) return
        
        // Update last known position
        lastLatitude = location.latitude
        lastLongitude = location.longitude
        hasPosition = true
        
        executor.execute {
            try {
                // Generate NMEA sentences
                val gprmc = generateGPRMC(location)
                val gpgga = generateGPGGA(location)
                
                // Send via UDP
                sendNmea(gprmc)
                sendNmea(gpgga)
                
                // Send HDT (True Heading) if we have compass data
                if (hasHeading) {
                    val hdt = generateHEHDT(currentHeading)
                    sendNmea(hdt)
                    recordMessage()
                    Log.d(TAG, "Sent GPS: ${location.latitude}, ${location.longitude}, HDG: ${currentHeading}°")
                } else {
                    Log.d(TAG, "Sent GPS: ${location.latitude}, ${location.longitude}")
                }
                
                // Record message for stats (2-3 messages per update)
                recordMessage()
                recordMessage()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending GPS data", e)
            }
        }
    }
    
    // SensorEventListener for compass heading
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> gravity = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD -> geomagnetic = event.values.clone()
        }
        
        // Calculate heading when both sensors have data
        val g = gravity
        val m = geomagnetic
        if (g != null && m != null) {
            val R = FloatArray(9)
            val I = FloatArray(9)
            if (SensorManager.getRotationMatrix(R, I, g, m)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(R, orientation)
                // Convert to degrees and normalize to 0-360
                var heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (heading < 0) heading += 360f
                currentHeading = heading
                lastHeading = heading
                hasHeading = true
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }
    
    private fun sendNmea(nmea: String) {
        // Capture both refs atomically-enough: stop() nulls them, executor runs concurrently.
        val addr = destAddress ?: return
        val sock = socket ?: return
        val data = (nmea + "\r\n").toByteArray()
        val packet = DatagramPacket(data, data.size, addr, port)
        sock.send(packet)
    }
    
    /**
     * Generate GPRMC sentence (Recommended Minimum Navigation Information)
     */
    private fun generateGPRMC(location: Location): String {
        val time = timeFormat
        val date = dateFormat
        val now = Date(location.time)
        
        val lat = formatLatitude(location.latitude)
        val lon = formatLongitude(location.longitude)
        val speed = location.speed * 1.94384  // m/s to knots
        val course = if (location.hasBearing()) location.bearing else 0f
        
        val sentence = "GPRMC,${time.format(now)},A,$lat,$lon,${String.format(Locale.US, "%.1f", speed)},${String.format(Locale.US, "%.1f", course)},${date.format(now)},,"
        return "\$${sentence}*${calculateChecksum(sentence)}"
    }
    
    /**
     * Generate GPGGA sentence (Global Positioning System Fix Data)
     */
    private fun generateGPGGA(location: Location): String {
        val time = timeFormat
        val now = Date(location.time)
        
        val lat = formatLatitude(location.latitude)
        val lon = formatLongitude(location.longitude)
        val altitude = location.altitude
        val satellites = 8  // Not available in Location, use placeholder
        
        val sentence = "GPGGA,${time.format(now)},$lat,$lon,1,$satellites,1.0,${String.format(Locale.US, "%.1f", altitude)},M,0.0,M,,"
        return "\$${sentence}*${calculateChecksum(sentence)}"
    }
    
    /**
     * Generate HEHDT sentence (Heading True)
     * Format: $HEHDT,x.x,T*hh
     * where x.x is heading in degrees, T indicates true heading
     */
    private fun generateHEHDT(heading: Float): String {
        val sentence = "HEHDT,${String.format(Locale.US, "%.1f", heading)},T"
        return "\$${sentence}*${calculateChecksum(sentence)}"
    }
    
    private fun formatLatitude(lat: Double): String {
        val absLat = Math.abs(lat)
        val degrees = absLat.toInt()
        val minutes = (absLat - degrees) * 60
        val direction = if (lat >= 0) "N" else "S"
        return String.format(Locale.US, "%02d%07.4f,%s", degrees, minutes, direction)
    }
    
    private fun formatLongitude(lon: Double): String {
        val absLon = Math.abs(lon)
        val degrees = absLon.toInt()
        val minutes = (absLon - degrees) * 60
        val direction = if (lon >= 0) "E" else "W"
        return String.format(Locale.US, "%03d%07.4f,%s", degrees, minutes, direction)
    }
    
    private fun calculateChecksum(sentence: String): String {
        var checksum = 0
        for (c in sentence) {
            checksum = checksum xor c.code
        }
        return String.format("%02X", checksum)
    }
    
    // LocationListener callbacks
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {
        Log.i(TAG, "GPS provider enabled")
    }
    override fun onProviderDisabled(provider: String) {
        Log.w(TAG, "GPS provider disabled")
    }
}

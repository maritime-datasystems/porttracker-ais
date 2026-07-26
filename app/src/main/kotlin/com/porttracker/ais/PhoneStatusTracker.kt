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
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * On-demand phone GPS + compass state for the status display.
 *
 * The status page used to show the GpsForwarder's state, which only runs
 * when GPSD forwarding is enabled — so GPS/heading always looked "Disabled"
 * on normal stations even with a perfect fix. This tracker is independent:
 * [poke] is called on every status poll and (re)activates location + sensor
 * updates; everything auto-stops [IDLE_TIMEOUT_MS] after the last poll, so
 * an unattended station pays no battery cost.
 */
object PhoneStatusTracker : LocationListener, SensorEventListener {

    private const val TAG = "porttracker-service.PhoneStatus"
    private const val IDLE_TIMEOUT_MS = 60_000L

    @Volatile var lastLocation: Location? = null
        private set
    @Volatile var lastHeading: Float? = null
        private set
    @Volatile var permissionGranted = false
        private set
    @Volatile var providersEnabled = false
        private set

    @Volatile private var active = false
    private var lastPokeAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null

    /** Called from the status endpoint — safe from any thread. */
    fun poke(context: Context) {
        lastPokeAt = System.currentTimeMillis()
        val app = context.applicationContext
        handler.post {
            refreshStaticState(app)
            if (!active) start(app)
        }
    }

    private fun refreshStaticState(context: Context) {
        permissionGranted =
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        providersEnabled = try {
            lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        } catch (e: Exception) { false }
    }

    private fun start(context: Context) {
        if (active || !permissionGranted) return
        active = true
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    if (lm.isProviderEnabled(provider)) {
                        lm.requestLocationUpdates(provider, 2000L, 0f, this, Looper.getMainLooper())
                    }
                } catch (_: Exception) {}
            }
            for (provider in listOf("gps", "network", "passive")) {
                try {
                    lm.getLastKnownLocation(provider)?.let { candidate ->
                        val current = lastLocation
                        if (current == null || candidate.time > current.time) {
                            lastLocation = candidate
                        }
                    }
                } catch (_: Exception) {}
            }
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            scheduleIdleCheck(context)
            Log.d(TAG, "Status tracking started")
        } catch (e: Exception) {
            Log.w(TAG, "Status tracking failed to start", e)
            active = false
        }
    }

    private fun scheduleIdleCheck(context: Context) {
        handler.postDelayed({
            if (System.currentTimeMillis() - lastPokeAt >= IDLE_TIMEOUT_MS) {
                stop(context)
            } else if (active) {
                scheduleIdleCheck(context)
            }
        }, IDLE_TIMEOUT_MS)
    }

    private fun stop(context: Context) {
        if (!active) return
        active = false
        try {
            (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                ?.removeUpdates(this)
        } catch (_: Exception) {}
        try {
            (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
                ?.unregisterListener(this)
        } catch (_: Exception) {}
        Log.d(TAG, "Status tracking stopped (idle)")
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> gravity = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD -> geomagnetic = event.values.clone()
        }
        val g = gravity ?: return
        val m = geomagnetic ?: return
        val rotation = FloatArray(9)
        val inclination = FloatArray(9)
        if (SensorManager.getRotationMatrix(rotation, inclination, g, m)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotation, orientation)
            var heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (heading < 0) heading += 360f
            lastHeading = heading
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

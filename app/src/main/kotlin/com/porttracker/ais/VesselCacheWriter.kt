package com.porttracker.ais

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Polls the native engine's decoded ship list (`/api/ships.json` on the internal
 * web port) and upserts static vessel data into [VesselDatabase].
 *
 * This consumes the engine's already-decoded JSON (name/imo/callsign/dimensions
 * cleaned by AIS-catcher), so no AIS bit-parsing happens here. Active only while
 * the "Write to internal DB" setting (`internal_db_enabled`) is on.
 */
class VesselCacheWriter(
    context: Context,
    private val internalApiPort: Int
) {
    companion object {
        private const val TAG = "porttracker-service.DBWriter"
        private const val POLL_INTERVAL_SEC = 30L

        // Movement-based position-ingestion thresholds. Store a point when the
        // vessel moved far enough, turned enough, or a heartbeat elapsed — so
        // moored vessels don't fill the DB with identical points.
        private const val MOVE_THRESHOLD_M = 50.0
        private const val HEARTBEAT_MS = 300_000L   // 5 min
        private const val TURN_DEG = 15.0

        // Retention: keep raw 3 months, then hourly until 1 year, then daily.
        private const val RAW_RETENTION_MS = 90L * 24 * 3_600_000     // 90 days
        private const val HOURLY_RETENTION_MS = 365L * 24 * 3_600_000 // 1 year
        private const val MAINTENANCE_INTERVAL_MS = 24L * 3_600_000   // daily

        // Status surface for the UI / health endpoint.
        @Volatile var isRunning = false
            private set
        @Volatile var lastPollMs = 0L
            private set
        @Volatile var lastPollVessels = 0
            private set
        @Volatile var totalUpserts = 0L
            private set
        @Volatile var positionsStored = 0L
            private set
    }

    /** Last stored track point per vessel (writer-thread only). */
    private data class LastPoint(val ts: Long, val lat: Double, val lon: Double, val cog: Double?)
    private val lastStored = HashMap<Long, LastPoint>()

    private val appContext = context.applicationContext
    private val db = VesselDatabase.getInstance(appContext)
    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "VesselCacheWriter").apply { isDaemon = true }
        }
        executor?.scheduleWithFixedDelay({ safePoll() }, 5, POLL_INTERVAL_SEC, TimeUnit.SECONDS)
        isRunning = true
        Log.i(TAG, "Vessel cache writer started (poll every ${POLL_INTERVAL_SEC}s)")
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
        isRunning = false
        Log.i(TAG, "Vessel cache writer stopped")
    }

    private fun safePoll() {
        try {
            poll()
        } catch (e: Exception) {
            // Engine web server may not be up yet (web-only mode / restart) — retry next cycle.
            Log.d(TAG, "poll skipped: ${e.message}")
        }
    }

    private fun poll() {
        val body = fetch("http://127.0.0.1:$internalApiPort/api/ships.json")
        val root = JSONObject(body)
        val ships = root.optJSONArray("ships") ?: return

        // Tolerant read: the web config-save path may persist a new flag as a
        // String, so accept both Boolean true and "true".
        val posLogging = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(appContext).all["position_logging_enabled"]
            .let { it == true || it == "true" }
        val now = System.currentTimeMillis()

        val records = ArrayList<VesselRecord>(ships.length())
        val positions = if (posLogging) ArrayList<PositionRecord>() else null

        for (i in 0 until ships.length()) {
            val s = ships.optJSONObject(i) ?: continue
            val mmsi = s.optLong("mmsi", 0L)
            if (mmsi <= 0L) continue
            records.add(
                VesselRecord(
                    mmsi = mmsi,
                    imo = s.optInt("imo", 0).takeIf { it > 0 },
                    name = s.optString("shipname", "").cleaned(),
                    callsign = s.optString("callsign", "").cleaned(),
                    shipType = s.optInt("shiptype", 0).takeIf { it > 0 },
                    toBow = s.optInt("to_bow", -1).takeIf { it >= 0 },
                    toStern = s.optInt("to_stern", -1).takeIf { it >= 0 },
                    toPort = s.optInt("to_port", -1).takeIf { it >= 0 },
                    toStarboard = s.optInt("to_starboard", -1).takeIf { it >= 0 },
                    destination = s.optString("destination", "").cleaned(),
                    draught = s.optDouble("draught", 0.0).takeIf { it > 0.0 },
                    eta = formatEta(s),
                    country = s.optString("country", "").cleaned()
                )
            )
            if (positions != null) maybeAddPosition(positions, s, mmsi, now)
        }

        db.upsertAll(records, now)
        if (positions != null && positions.isNotEmpty()) {
            db.insertPositions(positions)
            positionsStored += positions.size
        }
        // Bound the in-memory dedup map over very long runs. Drop stale
        // entries first — a full clear() would burst duplicate points for
        // every currently-tracked vessel on the next poll.
        if (lastStored.size > 10_000) {
            val cutoff = now - HEARTBEAT_MS
            lastStored.entries.removeAll { it.value.ts < cutoff }
            if (lastStored.size > 10_000) lastStored.clear()
        }

        lastPollMs = now
        lastPollVessels = records.size
        totalUpserts += records.size
        Log.d(TAG, "polled ${records.size} vessels" +
            (if (positions != null) "; +${positions.size} positions" else "") +
            "; static rows=${db.count()}")

        maybeRunMaintenance(now)
    }

    /** Run downsampling/retention at most once a day, off the poll thread. */
    private fun maybeRunMaintenance(now: Long) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
        val last = try { prefs.getLong("last_position_maintenance_ms", 0L) } catch (e: Exception) { 0L }
        if (now - last < MAINTENANCE_INTERVAL_MS) return
        prefs.edit().putLong("last_position_maintenance_ms", now).apply()
        Thread({
            try {
                Log.i(TAG, "Running position-history maintenance...")
                val stats = db.runMaintenance(now, RAW_RETENTION_MS, HOURLY_RETENTION_MS)
                Log.i(TAG, "Maintenance done: $stats")
            } catch (e: Exception) {
                Log.e(TAG, "Maintenance failed", e)
            }
        }, "PositionMaintenance").start()
    }

    /** Apply the movement/heartbeat rule and append a point if it should be stored. */
    private fun maybeAddPosition(out: MutableList<PositionRecord>, s: JSONObject, mmsi: Long, now: Long) {
        val lat = s.optDouble("lat", Double.NaN)
        val lon = s.optDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0) ||
            Math.abs(lat) > 90.0 || Math.abs(lon) > 180.0) return

        val cog = s.optDouble("cog", Double.NaN).let { if (it.isNaN() || it >= 360.0) null else it }
        val last = lastStored[mmsi]
        val store = when {
            last == null -> true
            now - last.ts >= HEARTBEAT_MS -> true
            haversineMeters(last.lat, last.lon, lat, lon) >= MOVE_THRESHOLD_M -> true
            cog != null && last.cog != null && angleDiff(cog, last.cog) >= TURN_DEG -> true
            else -> false
        }
        if (!store) return

        out.add(
            PositionRecord(
                mmsi = mmsi,
                ts = now,
                lat = lat,
                lon = lon,
                sog = s.optDouble("speed", Double.NaN).let { if (it.isNaN()) null else it },
                cog = cog,
                heading = s.optInt("heading", 511).takeIf { it in 0..359 },
                draught = s.optDouble("draught", 0.0).takeIf { it > 0.0 },
                navStatus = s.optInt("status", -1).takeIf { it in 0..15 }
            )
        )
        lastStored[mmsi] = LastPoint(now, lat, lon, cog)
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = Math.abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private fun fetch(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 6000
            setRequestProperty("Accept-Encoding", "identity")
        }
        return try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** AIS eta is broadcast as month/day/hour/minute; compose a compact string. */
    private fun formatEta(s: JSONObject): String? {
        val mo = s.optInt("eta_month", 0)
        val day = s.optInt("eta_day", 0)
        val hr = s.optInt("eta_hour", 24)
        val min = s.optInt("eta_minute", 60)
        if (mo == 0 && day == 0) return null
        return String.format("%02d-%02d %02d:%02d", mo, day, hr % 24, min % 60)
    }

    /** Engine returns "" for unknown strings — normalise to null. */
    private fun String.cleaned(): String? = trim().ifEmpty { null }
}

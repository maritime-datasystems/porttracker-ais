package com.porttracker.ais

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Auth0 device-code login + PortTracker sharing-station auto-provisioning.
 *
 * Flow (all plain HTTP, no Auth0 SDK):
 *  1. POST https://{auth0Domain}/oauth/device/code  -> user_code + verify URL.
 *     The user confirms the code in any browser (their laptop/phone), signing
 *     in with their TrustedDocks/PortTracker account.
 *  2. Poll POST /oauth/token until authorized -> ID token (the PortTracker
 *     API authenticates ID tokens; the native client's ID is one of its
 *     accepted audiences).
 *  3. GET  {apiBase}/v1/sharing/stations  -> reuse THIS device's station if it
 *     already exists (matched by the stable per-device hostname - one account
 *     can run several stations, so only ever touch our own).
 *     Else POST {apiBase}/v1/sharing/stations -> station + mosquitto access.
 *  4. Persist broker/credentials/topics into the same SharedPreferences keys
 *     the manual gateway connect uses; the caller restarts the publisher.
 *
 * State is a simple polled machine surfaced via /admin/api/auth/status so the
 * WebView UI stays a dumb renderer.
 */
object PorttrackerAuth {
    private const val TAG = "PorttrackerAuth"
    private const val CONNECT_TIMEOUT = 10000
    private const val READ_TIMEOUT = 15000

    const val DEFAULT_AUTH0_DOMAIN = "login.trusteddocks.com"
    const val DEFAULT_API_BASE = "https://api-gateway.porttracker.co/api"

    enum class State { IDLE, PENDING, PROVISIONING, PROVISIONED, ERROR }

    data class Status(
        val state: State,
        val userCode: String? = null,
        val verificationUri: String? = null,
        val message: String? = null,
        val stationId: Int? = null,
        val stationHostname: String? = null,
    )

    @Volatile
    private var status = Status(State.IDLE)
    private var pollThread: Thread? = null
    @Volatile
    private var cancelled = false

    fun currentStatus(): Status = status

    @Synchronized
    fun cancel() {
        cancelled = true
        pollThread?.interrupt()
        pollThread = null
        status = Status(State.IDLE)
    }

    /**
     * Kick off the device-code flow. Returns the immediate status (PENDING
     * with the code to display, or ERROR). Polling + provisioning continue on
     * a background thread; observe via currentStatus().
     */
    @Synchronized
    fun startLogin(context: Context): Status {
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
        val domain = prefs.getString("auth0_domain", DEFAULT_AUTH0_DOMAIN)!!
            .ifBlank { DEFAULT_AUTH0_DOMAIN }
        val clientId = prefs.getString("auth0_client_id", "") ?: ""
        if (clientId.isBlank()) {
            status = Status(
                State.ERROR,
                message = "auth0_client_id is not configured. Set it on the "
                    + "Settings page (Auth0 native app client ID).")
            return status
        }

        cancel()  // Drop any previous attempt.
        cancelled = false

        val code = try {
            requestDeviceCode(domain, clientId)
        } catch (e: Exception) {
            Log.e(TAG, "Device code request failed", e)
            status = Status(State.ERROR, message = "Login start failed: ${e.message}")
            return status
        }

        status = Status(
            State.PENDING,
            userCode = code.userCode,
            verificationUri = code.verificationUriComplete,
        )
        InternalLog.log("Auth: device login started, code ${code.userCode}")

        pollThread = Thread {
            runPollLoop(context, domain, clientId, code)
        }.apply {
            name = "Auth0DevicePoll"
            isDaemon = true
            start()
        }
        return status
    }

    // ------------------------------------------------------------------
    // Device flow
    // ------------------------------------------------------------------

    private data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUriComplete: String,
        val intervalS: Int,
        val expiresInS: Int,
    )

    private fun requestDeviceCode(domain: String, clientId: String): DeviceCode {
        val body = form(
            "client_id" to clientId,
            "scope" to "openid profile email",
        )
        val json = postForm("https://$domain/oauth/device/code", body)
        return DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUriComplete = json.optString(
                "verification_uri_complete",
                json.optString("verification_uri", "https://$domain/activate")),
            intervalS = json.optInt("interval", 5),
            expiresInS = json.optInt("expires_in", 900),
        )
    }

    private fun runPollLoop(
        context: Context, domain: String, clientId: String, code: DeviceCode,
    ) {
        val deadline = System.currentTimeMillis() + code.expiresInS * 1000L
        var intervalMs = code.intervalS * 1000L
        while (!cancelled && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                return
            }
            val (idToken, error) = try {
                pollToken(domain, clientId, code.deviceCode)
            } catch (e: Exception) {
                Log.w(TAG, "Token poll failed, retrying", e)
                continue
            }
            when {
                idToken != null -> {
                    provision(context, idToken)
                    return
                }
                error == "authorization_pending" -> continue
                error == "slow_down" -> intervalMs += 5000L
                else -> {
                    // expired_token, access_denied, anything else: terminal.
                    status = Status(State.ERROR, message = "Login failed: $error")
                    InternalLog.log("Auth: device login failed ($error)")
                    return
                }
            }
        }
        if (!cancelled && status.state == State.PENDING) {
            status = Status(State.ERROR, message = "Login code expired - try again.")
        }
    }

    /** @return (idToken, null) on success, (null, oauthErrorCode) otherwise. */
    private fun pollToken(
        domain: String, clientId: String, deviceCode: String,
    ): Pair<String?, String?> {
        val body = form(
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            "device_code" to deviceCode,
            "client_id" to clientId,
        )
        val connection = openJson("https://$domain/oauth/token", "POST")
        connection.setRequestProperty(
            "Content-Type", "application/x-www-form-urlencoded")
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toByteArray()) }
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream
            else connection.errorStream
        val json = JSONObject(readAll(stream))
        return if (responseCode in 200..299) {
            Pair(json.optString("id_token", "").ifBlank { null }, null)
        } else {
            Pair(null, json.optString("error", "http_$responseCode"))
        }
    }

    // ------------------------------------------------------------------
    // Provisioning against /v1/sharing
    // ------------------------------------------------------------------

    private fun provision(context: Context, idToken: String) {
        status = status.copy(state = State.PROVISIONING)
        try {
            val prefs = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(context)
            val apiBase = (prefs.getString("pt_api_base", DEFAULT_API_BASE)
                ?: DEFAULT_API_BASE).trimEnd('/')
            val hostname = stationHostname(context)

            // One account may run several stations - only reuse the station
            // whose hostname matches THIS device; never touch the others.
            var station = findOwnStation(apiBase, idToken, hostname)
            if (station == null) {
                station = createStation(apiBase, idToken, hostname, context)
            }

            persistMosquittoAccess(context, station)
            status = Status(
                State.PROVISIONED,
                stationId = station.stationId,
                stationHostname = station.hostname,
                message = "Station #${station.stationId} ($hostname) connected.",
            )
            InternalLog.log(
                "Auth: provisioned sharing station #${station.stationId} "
                    + "($hostname)")
        } catch (e: Exception) {
            Log.e(TAG, "Provisioning failed", e)
            status = Status(State.ERROR, message = "Provisioning failed: ${e.message}")
            InternalLog.log("Auth: provisioning failed (${e.message})")
        }
    }

    private data class StationAccess(
        val stationId: Int,
        val hostname: String,
        val protocol: String,
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val aisRawTopic: String,
        val aisJsonTopic: String,
    )

    private fun parseStationInfo(info: JSONObject): StationAccess {
        val station = info.getJSONObject("station")
        val access = info.getJSONObject("mosquitto_access")
        return StationAccess(
            stationId = station.getInt("id"),
            hostname = station.getString("hostname"),
            protocol = access.getString("protocol"),
            host = access.getString("host"),
            port = access.getInt("port"),
            username = access.getString("username"),
            password = access.getString("password"),
            aisRawTopic = access.getString("ais_raw_topic"),
            aisJsonTopic = access.getString("ais_json_topic"),
        )
    }

    private fun findOwnStation(
        apiBase: String, idToken: String, hostname: String,
    ): StationAccess? {
        val connection = openJson("$apiBase/v1/sharing/stations", "GET")
        connection.setRequestProperty("Authorization", "Bearer $idToken")
        if (connection.responseCode != 200) {
            throw Exception(
                "Station list failed (HTTP ${connection.responseCode})")
        }
        val stations = JSONArray(readAll(connection.inputStream))
        for (i in 0 until stations.length()) {
            val info = stations.getJSONObject(i)
            if (info.getJSONObject("station").optString("hostname") == hostname) {
                return parseStationInfo(info)
            }
        }
        return null
    }

    private fun createStation(
        apiBase: String, idToken: String, hostname: String, context: Context,
    ): StationAccess {
        val position = lastKnownPosition(context)
        val body = JSONObject().apply {
            put("hostname", hostname)
            put("description",
                "PortTracker AIS-catcher on ${android.os.Build.MODEL}")
            put("position", JSONObject().apply {
                put("lat", position.first)
                put("lon", position.second)
            })
            put("statistics_are_public", false)
        }
        val connection = openJson("$apiBase/v1/sharing/stations", "POST")
        connection.setRequestProperty("Authorization", "Bearer $idToken")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        if (connection.responseCode !in 200..299) {
            val error = connection.errorStream?.let { readAll(it) } ?: ""
            throw Exception(
                "Station create failed (HTTP ${connection.responseCode}) "
                    + error.take(200))
        }
        return parseStationInfo(JSONObject(readAll(connection.inputStream)))
    }

    /**
     * Stable per-device station hostname: the user-chosen station name if
     * set, else a generated one persisted on first use so repeated logins
     * (and reinstalls of the same config) reuse the same station.
     */
    @SuppressLint("HardwareIds")
    fun stationHostname(context: Context): String {
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
        prefs.getString("sharing_station_hostname", "")!!.ifBlank { null }
            ?.let { return it }
        val custom = prefs.getString("pref_station_name", "")!!.trim()
        val hostname = if (custom.isNotBlank()) {
            custom.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-')
        } else {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
            "aiscatcher-$androidId"
        }
        prefs.edit().putString("sharing_station_hostname", hostname).apply()
        return hostname
    }

    private fun lastKnownPosition(context: Context): Pair<Double, Double> {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE)
                as android.location.LocationManager
            for (provider in listOf("gps", "network", "passive")) {
                try {
                    lm.getLastKnownLocation(provider)?.let {
                        return Pair(it.latitude, it.longitude)
                    }
                } catch (_: SecurityException) {
                    // No permission for this provider - try the next.
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No last known position", e)
        }
        // Position is editable on the website later; 0/0 marks "unset".
        return Pair(0.0, 0.0)
    }

    private fun persistMosquittoAccess(context: Context, s: StationAccess) {
        // Paho's URL scheme for TLS is ssl://; the API reports "mqtts".
        val scheme = when (s.protocol.lowercase()) {
            "mqtts", "ssl", "tls" -> "ssl"
            "ws" -> "ws"
            "wss" -> "wss"
            else -> "tcp"
        }
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
        prefs.edit()
            .putBoolean("mqtt_enabled", true)
            .putString("mqtt_broker_url", "$scheme://${s.host}:${s.port}")
            .putString("mqtt_username", s.username)
            .putString("mqtt_password", s.password)
            .putString("mqtt_topic_raw", s.aisRawTopic)
            .putString("mqtt_topic_json", s.aisJsonTopic)
            .putString("mqtt_format", "aisc-json")
            .putString("mqtt_station_name", s.hostname)
            .putString("pref_station_name", s.hostname)
            .putInt("mqtt_antenna_id", s.stationId)
            .putString("sharing_station_hostname", s.hostname)
            .putInt("sharing_station_id", s.stationId)
            .apply()
    }

    // ------------------------------------------------------------------
    // Small HTTP helpers (mirrors TrustedDocksApi's plain-URLConnection style)
    // ------------------------------------------------------------------

    private fun openJson(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Accept", "application/json")
        }

    private fun readAll(stream: java.io.InputStream?): String =
        stream?.let {
            BufferedReader(InputStreamReader(it)).use { r -> r.readText() }
        } ?: ""

    private fun postForm(url: String, body: String): JSONObject {
        val connection = openJson(url, "POST")
        connection.setRequestProperty(
            "Content-Type", "application/x-www-form-urlencoded")
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toByteArray()) }
        val code = connection.responseCode
        val text = readAll(
            if (code in 200..299) connection.inputStream
            else connection.errorStream)
        if (code !in 200..299) {
            throw Exception("HTTP $code from $url: ${text.take(200)}")
        }
        return JSONObject(text)
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
}

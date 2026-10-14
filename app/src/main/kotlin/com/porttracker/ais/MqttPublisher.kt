package com.porttracker.ais

import android.content.Context
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.*
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore

/**
 * Publishes AIS NMEA data to an MQTT broker (e.g. TrustedDocks / Navisense).
 *
 * Supports two payload formats:
 *   - "aisc-json": each NMEA sentence wrapped as {"nmea":["..."],"channel":"A|B"}
 *   - "raw": plain NMEA text
 *
 * Messages are batched for 1 second before flushing to avoid flooding the broker.
 */
class MqttPublisher(private val context: Context) {

    companion object {
        private const val TAG = "MqttPublisher"
        private const val BATCH_INTERVAL_MS = 1000L
        private const val CLIENT_ID_PREFIX = "porttracker-"
        private const val CONNECT_TIMEOUT_SEC = 30
        private const val KEEP_ALIVE_SEC = 60
        // Sentences buffered across broker reconnects (~a few minutes of a busy port)
        private const val PENDING_QUEUE_CAP = 2000
        // Paho default is 10 in-flight QoS-1 publishes — far too low for AIS burst rates
        private const val MAX_INFLIGHT = 100
        // Presence refresh. Long enough to be free on a metered connection,
        // short enough that "last seen" means something.
        private const val STATUS_INTERVAL_MS = 60_000L
        // Retry delay for the FIRST connect. Paho's automatic reconnect only
        // takes over once a connection has succeeded at least once.
        private const val CONNECT_RETRY_MIN_MS = 5_000L
        private const val CONNECT_RETRY_MAX_MS = 300_000L

        // The publisher the service currently runs. Status is read through it
        // so a publisher being torn down cannot overwrite a newer one's state.
        @Volatile
        private var active: MqttPublisher? = null

        val isConnected: Boolean
            get() = active?.connected == true

        @Volatile
        var messagesSent = 0L
            private set

        @Volatile
        var stationInfo: String = ""
            private set

        fun resetCounters() {
            messagesSent = 0L
        }
    }

    data class Config(
        val brokerUrl: String,           // e.g. ssl://mqtt.navisense.de:8883
        val username: String,
        val password: String,
        val topicRaw: String,            // e.g. ais/raw/SHARE/4/1000005
        val topicJson: String,           // e.g. ais/aisc-json/SHARE/4/1000005
        val format: String = "aisc-json", // "aisc-json" or "raw"
        val qos: Int = 1,
        val stationName: String = "",
        // Presence topic (ais/status/SHARE/<station id>). Empty for stations
        // provisioned before presence existed - they simply do not announce.
        val topicStatus: String = "",
        val stationId: Int = 0,
        val antennaUuid: String = "",
        // Report where this station stands. Its coverage - how far it hears
        // and in which direction - can only be worked out from a position, so
        // a station left at 0/0 gets no reach figures at all.
        val gpsEnabled: Boolean = false,
        // How the station presents itself: the real name of whatever carries
        // the antenna, what that is, and whether other users may see it.
        val displayName: String = "",
        val stationKind: String = "fixed",
        val publicStation: Boolean = false,
    ) {
        /** Paho uses ssl:// not mqtts:// */
        val normalizedBrokerUrl: String
            get() = brokerUrl.replace("mqtts://", "ssl://")

        /** The publish topic based on selected format */
        val publishTopic: String
            get() = if (format == "raw") topicRaw else topicJson
    }

    @Volatile private var client: MqttAsyncClient? = null
    @Volatile private var config: Config? = null
    @Volatile private var connected = false
    @Volatile private var stopped = false
    private var connectAttempts = 0
    // A sentence taken from the queue whose publish was refused (not
    // connected / in-flight window full). Sent first on the next flush.
    // Only touched on the batch timer thread.
    private var unsent: String? = null
    // Bounded: survives broker reconnects without growing unbounded if the
    // outage lasts. When full, the oldest sentences are dropped first.
    private val pendingMessages = LinkedBlockingQueue<String>(PENDING_QUEUE_CAP)
    private var batchExecutor: ScheduledExecutorService? = null
    private var statusExecutor: ScheduledExecutorService? = null
    private val lifecycleLock = Object()

    /**
     * Begin batched publishing and connect to the broker in the background.
     *
     * Never blocks on the network. The flush and presence timers run from the
     * start, sentences queue up while there is no connection, and a failed
     * first connect is retried with backoff — before, a station that booted
     * without internet (or hit a broker hiccup at that moment) never
     * published anything until someone restarted it by hand.
     */
    fun start(config: Config) {
        synchronized(lifecycleLock) {
            stop() // clean up any previous session

            this.config = config
            stopped = false
            connectAttempts = 0
            stationInfo = config.stationName
            active = this

            try {
                val clientId = CLIENT_ID_PREFIX + config.normalizedBrokerUrl.hashCode().toString(16).takeLast(6) +
                        "-" + System.currentTimeMillis() % 100000
                val client = MqttAsyncClient(config.normalizedBrokerUrl, clientId, MemoryPersistence())
                this.client = client

                client.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        if (this@MqttPublisher.client !== client) return
                        Log.i(TAG, "MQTT connected (reconnect=$reconnect) to $serverURI")
                        connected = true
                        // After a drop the broker has published our will
                        // ("offline"); put the retained status right again.
                        if (reconnect) statusExecutor?.execute { publishStatus(online = true) }
                    }

                    override fun connectionLost(cause: Throwable?) {
                        if (this@MqttPublisher.client !== client) return
                        Log.w(TAG, "MQTT connection lost: ${cause?.message}")
                        connected = false
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        // Not subscribing to any topics — no-op
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        // Delivery acknowledged
                    }
                })

                // Timers first: they must run whether or not the first
                // connect works.
                batchExecutor = Executors.newSingleThreadScheduledExecutor { r ->
                    Thread(r, "MqttBatchTimer").apply { isDaemon = true }
                }
                batchExecutor?.scheduleWithFixedDelay(
                    { flushBatch() },
                    BATCH_INTERVAL_MS,
                    BATCH_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
                )
                // Refresh presence periodically: it carries live counters
                // (messages, radio age, SDR state) that the account page and
                // the station detail page read, and it proves the session is
                // still alive to anything that only sees retained state.
                statusExecutor = Executors.newSingleThreadScheduledExecutor { r ->
                    Thread(r, "MqttStatusTimer").apply { isDaemon = true }
                }
                statusExecutor?.scheduleWithFixedDelay(
                    { publishStatus(online = true) },
                    STATUS_INTERVAL_MS, STATUS_INTERVAL_MS,
                    TimeUnit.MILLISECONDS)

                connect(client, config)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MQTT publisher", e)
                connected = false
            }
        }
    }

    /**
     * One asynchronous connect attempt; on failure the next one is scheduled
     * with exponential backoff until it works or the publisher is stopped.
     */
    private fun connect(client: MqttAsyncClient, config: Config) {
        if (stopped || this.client !== client) return
        val options = buildConnectOptions(config)
        // Register the will BEFORE connecting: the broker publishes it
        // if we vanish without saying goodbye, which is the whole
        // point - a station that loses power or signal must stop
        // claiming to be online.
        if (config.topicStatus.isNotBlank()) {
            options.setWill(
                config.topicStatus,
                statusPayload(config, online = false).toByteArray(),
                1, true)
        }
        connectAttempts++
        Log.i(TAG, "Connecting to ${config.brokerUrl} as ${config.username} (attempt $connectAttempts)...")
        try {
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    if (stopped || this@MqttPublisher.client !== client) {
                        // Stopped while the connect was in flight: do not
                        // leave a ghost session behind.
                        closeQuietly(client)
                        return
                    }
                    connected = true
                    connectAttempts = 0
                    Log.i(TAG, "MQTT publisher started — topic: ${config.publishTopic}")
                    statusExecutor?.execute { publishStatus(online = true) }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.w(TAG, "MQTT connect failed: ${exception?.message}")
                    scheduleReconnect(client, config)
                }
            })
        } catch (e: MqttException) {
            Log.w(TAG, "MQTT connect could not be started: ${e.message}")
            scheduleReconnect(client, config)
        }
    }

    private fun scheduleReconnect(client: MqttAsyncClient, config: Config) {
        if (stopped || this.client !== client) return
        val delay = (CONNECT_RETRY_MIN_MS shl minOf(connectAttempts - 1, 6).coerceAtLeast(0))
            .coerceAtMost(CONNECT_RETRY_MAX_MS)
        Log.i(TAG, "Retrying MQTT connect in ${delay / 1000}s")
        try {
            batchExecutor?.schedule({ connect(client, config) }, delay, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Publisher stopped in the meantime.
        }
    }

    private fun closeQuietly(client: MqttAsyncClient) {
        try {
            if (client.isConnected) client.disconnectForcibly(0, 1000)
        } catch (_: Exception) {
        }
        try {
            client.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Queue an NMEA sentence for publishing. Thread-safe, never blocks.
     * Data is queued while the broker is unreachable or reconnecting;
     * when the bounded queue fills, the oldest sentence is dropped.
     */
    fun publishNmea(nmea: String) {
        if (client == null) return
        while (!pendingMessages.offer(nmea)) {
            pendingMessages.poll()
        }
    }

    /**
     * Publish queued sentences until the queue is empty or the client stops
     * accepting them. A sentence the client refuses is kept for the next
     * flush instead of being thrown away — the old version drained the whole
     * queue first and lost everything that failed, which is exactly what
     * happens in a burst (in-flight window full) or when the link drops
     * mid-flush.
     */
    private fun flushBatch() {
        if (!connected) return  // keep messages queued through a reconnect
        val cfg = config ?: return
        val topic = cfg.publishTopic

        while (true) {
            val nmea = unsent ?: pendingMessages.poll() ?: return
            unsent = null
            val trimmed = nmea.trim()
            if (trimmed.isEmpty()) continue
            val payload = if (cfg.format == "aisc-json") {
                JSONObject().apply {
                    put("nmea", JSONArray().put(trimmed))
                    put("channel", inferChannel(trimmed))
                }.toString()
            } else {
                trimmed  // Raw format — send each NMEA line as-is
            }
            when (publish(topic, payload, cfg.qos)) {
                PublishResult.SENT -> {}
                PublishResult.RETRY_LATER -> {
                    unsent = nmea
                    return
                }
                PublishResult.DROPPED -> {}
            }
        }
    }

    private enum class PublishResult { SENT, RETRY_LATER, DROPPED }

    /**
     * Publish a single message to the broker.
     */
    private fun publish(topic: String, payload: String, qos: Int): PublishResult {
        val c = client ?: return PublishResult.RETRY_LATER
        return try {
            val mqttMessage = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                this.qos = qos
                isRetained = false
            }
            c.publish(topic, mqttMessage)
            messagesSent++
            PublishResult.SENT
        } catch (e: MqttException) {
            when (e.reasonCode.toShort()) {
                MqttException.REASON_CODE_MAX_INFLIGHT,
                MqttException.REASON_CODE_CLIENT_NOT_CONNECTED,
                MqttException.REASON_CODE_CLIENT_DISCONNECTING,
                MqttException.REASON_CODE_CONNECTION_LOST,
                MqttException.REASON_CODE_WRITE_TIMEOUT -> PublishResult.RETRY_LATER
                else -> {
                    Log.e(TAG, "MQTT publish error on topic=$topic: ${e.message}")
                    PublishResult.DROPPED
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MQTT publish error on topic=$topic: ${e.message}")
            PublishResult.DROPPED
        }
    }

    /**
     * Announce this station's presence, retained, on its status topic.
     *
     * "Online" and "receiving data" are different states and a station is the
     * only thing that can report the first: the backend cannot tell a station
     * with a broken antenna from one that lost power. The payload also carries
     * what the station knows about itself, which is what the detail page shows
     * when reception drops to nothing.
     */
    /**
     * Add the station's own position, when the operator asked us to send it.
     *
     * Prefers a fix the phone already has - a last known location costs
     * nothing - and only asks the tracker to start when there is none, so an
     * unattended station does not hold the GPS on for a coordinate that
     * changes once a year. The age is reported alongside so the backend can
     * judge how much to trust it.
     */
    private fun appendPosition(payload: org.json.JSONObject) {
        val location = PhoneStatusTracker.lastLocation ?: lastKnownLocation()
        if (location == null) {
            // Nothing yet: wake the tracker so the next status has a fix.
            PhoneStatusTracker.poke(context)
            return
        }
        payload.put("lat", location.latitude)
        payload.put("lon", location.longitude)
        if (location.hasAccuracy()) {
            payload.put("position_accuracy_m", location.accuracy.toDouble())
        }
        val ageMs = System.currentTimeMillis() - location.time
        if (location.time > 0) payload.put("position_age_s", ageMs / 1000)
    }

    private fun lastKnownLocation(): android.location.Location? {
        return try {
            val manager = context.getSystemService(Context.LOCATION_SERVICE)
                as android.location.LocationManager
            listOf("gps", "network", "passive")
                .mapNotNull {
                    try {
                        manager.getLastKnownLocation(it)
                    } catch (_: SecurityException) {
                        null
                    }
                }
                .maxByOrNull { it.time }
        } catch (e: Exception) {
            Log.w(TAG, "No last known location: ${e.message}")
            null
        }
    }

    private fun publishStatus(online: Boolean) {
        val cfg = config ?: return
        if (cfg.topicStatus.isBlank()) return
        if (client?.isConnected != true) return
        try {
            val message = MqttMessage(
                statusPayload(cfg, online).toByteArray(Charsets.UTF_8)).apply {
                qos = 1
                isRetained = true
            }
            client?.publish(cfg.topicStatus, message)
        } catch (e: MqttException) {
            Log.w(TAG, "MQTT status publish failed: ${e.message}")
        }
    }

    private fun statusPayload(cfg: Config, online: Boolean): String {
        val payload = org.json.JSONObject()
        payload.put("online", online)
        payload.put("station_id", cfg.stationId)
        if (cfg.antennaUuid.isNotBlank()) {
            payload.put("antenna_uuid", cfg.antennaUuid)
        }
        if (cfg.stationName.isNotBlank()) payload.put("station", cfg.stationName)
        try {
            val info = context.packageManager.getPackageInfo(
                context.packageName, 0)
            payload.put("app_version", info.versionName)
            payload.put(
                "version_code",
                if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
                else @Suppress("DEPRECATION") info.versionCode.toLong())
        } catch (_: Exception) {
            // Version is nice to have, presence is not worth failing over.
        }
        if (!online) return payload.toString()
        if (cfg.displayName.isNotBlank()) {
            payload.put("display_name", cfg.displayName)
        }
        payload.put("station_kind", cfg.stationKind)
        payload.put("public", cfg.publicStation)
        if (cfg.gpsEnabled) appendPosition(payload)
        payload.put("messages_sent", messagesSent)
        payload.put("messages_decoded", AisReceiverService.messageCount)
        payload.put("service_running", AisReceiverService.isRunning)
        // Whether a receiver is even plugged in. Without this, a station with
        // no dongle and one with a broken antenna look identical from the
        // outside - both simply decode nothing.
        try {
            val device = UsbDeviceScanner.scanForDevices(context)
            payload.put("sdr_attached", device.found)
            if (device.found && device.deviceName.isNotBlank()) {
                payload.put("sdr_device", device.deviceName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "SDR scan for status failed: ${e.message}")
        }
        // -1 = nothing ever decoded. A station that is online with a large or
        // missing value here is the "reachable but deaf" case: dead dongle,
        // unplugged antenna, or an SDR that was never attached.
        val lastNmea = AisReceiverService.lastNmeaTime
        payload.put(
            "last_nmea_age_s",
            if (lastNmea > 0) (System.currentTimeMillis() - lastNmea) / 1000
            else -1)
        return payload.toString()
    }

    /**
     * Disconnect and release resources.
     */
    fun stop() {
        synchronized(lifecycleLock) {
            stopped = true
            // Say goodbye properly while the connection is still up; the will
            // only covers the case where we cannot.
            publishStatus(online = false)
            try {
                statusExecutor?.shutdownNow()
                statusExecutor = null
                batchExecutor?.shutdownNow()
                batchExecutor = null
            } catch (e: Exception) {
                Log.w(TAG, "Error shutting down batch executor", e)
            }

            try {
                if (client?.isConnected == true) {
                    client?.disconnect()?.waitForCompletion(5000)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting MQTT client", e)
            }

            try {
                client?.close()
            } catch (e: Exception) {
                // A connect still in flight refuses close(); its success
                // callback sees `stopped` and closes the session then.
                Log.w(TAG, "Error closing MQTT client: ${e.message}")
            }

            client = null
            connected = false
            if (active === this) active = null
            pendingMessages.clear()
            unsent = null
            Log.i(TAG, "MQTT publisher stopped")
        }
    }

    // ---- Helpers ----

    /**
     * Build MQTT connect options with TLS, credentials, and auto-reconnect.
     */
    private fun buildConnectOptions(config: Config): MqttConnectOptions {
        return MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            connectionTimeout = CONNECT_TIMEOUT_SEC
            keepAliveInterval = KEEP_ALIVE_SEC
            maxInflight = MAX_INFLIGHT

            if (config.username.isNotEmpty()) {
                userName = config.username
            }
            if (config.password.isNotEmpty()) {
                password = config.password.toCharArray()
            }

            // Enable TLS if broker URL starts with ssl://
            if (config.normalizedBrokerUrl.startsWith("ssl://")) {
                socketFactory = createSslSocketFactory()
            }
        }
    }

    /**
     * Create an SSLSocketFactory that uses the system default trust store.
     */
    private fun createSslSocketFactory(): SSLSocketFactory {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as KeyStore?) // Use system default trust store

        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(null, trustManagerFactory.trustManagers, null)
        return sslContext.socketFactory
    }

    /**
     * Infer AIS channel (A or B) from NMEA sentence content.
     * AIVDM sentences have the channel letter in the 4th field (0-indexed).
     */
    private fun inferChannel(nmea: String): String {
        // Typical: !AIVDM,1,1,,A,...   → field index 4 = channel
        val parts = nmea.split(",")
        if (parts.size > 4) {
            val ch = parts[4].trim()
            if (ch == "B" || ch == "2") return "B"
        }
        return "A"
    }
}

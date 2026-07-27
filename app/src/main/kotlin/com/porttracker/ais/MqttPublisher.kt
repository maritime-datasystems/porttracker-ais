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

        @Volatile
        var isConnected = false
            private set

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
    ) {
        /** Paho uses ssl:// not mqtts:// */
        val normalizedBrokerUrl: String
            get() = brokerUrl.replace("mqtts://", "ssl://")

        /** The publish topic based on selected format */
        val publishTopic: String
            get() = if (format == "raw") topicRaw else topicJson
    }

    private var client: MqttAsyncClient? = null
    private var config: Config? = null
    // Bounded: survives broker reconnects without growing unbounded if the
    // outage lasts. When full, the oldest sentences are dropped first.
    private val pendingMessages = LinkedBlockingQueue<String>(PENDING_QUEUE_CAP)
    private var batchExecutor: ScheduledExecutorService? = null
    private var statusExecutor: ScheduledExecutorService? = null
    private val lifecycleLock = Object()

    /**
     * Connect to the broker and begin batched publishing.
     */
    fun start(config: Config) {
        synchronized(lifecycleLock) {
            stop() // clean up any previous session

            this.config = config
            stationInfo = config.stationName

            try {
                val clientId = CLIENT_ID_PREFIX + config.normalizedBrokerUrl.hashCode().toString(16).takeLast(6) +
                        "-" + System.currentTimeMillis() % 100000
                val client = MqttAsyncClient(config.normalizedBrokerUrl, clientId, MemoryPersistence())
                this.client = client

                client.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        Log.i(TAG, "MQTT connected (reconnect=$reconnect) to $serverURI")
                        isConnected = true
                    }

                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "MQTT connection lost: ${cause?.message}")
                        isConnected = false
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        // Not subscribing to any topics — no-op
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        // Delivery acknowledged
                    }
                })

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
                Log.i(TAG, "Connecting to ${config.brokerUrl} as ${config.username}...")
                client.connect(options).waitForCompletion(CONNECT_TIMEOUT_SEC * 1000L)
                isConnected = true

                // Start batch flush timer
                batchExecutor = Executors.newSingleThreadScheduledExecutor { r ->
                    Thread(r, "MqttBatchTimer").apply { isDaemon = true }
                }
                batchExecutor?.scheduleAtFixedRate(
                    { flushBatch() },
                    BATCH_INTERVAL_MS,
                    BATCH_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
                )

                publishStatus(online = true)
                // Refresh presence periodically: it carries live counters
                // (messages, radio age, SDR state) that the account page and
                // the station detail page read, and it proves the session is
                // still alive to anything that only sees retained state.
                statusExecutor = Executors.newSingleThreadScheduledExecutor { r ->
                    Thread(r, "MqttStatusTimer").apply { isDaemon = true }
                }
                statusExecutor?.scheduleAtFixedRate(
                    { publishStatus(online = true) },
                    STATUS_INTERVAL_MS, STATUS_INTERVAL_MS,
                    TimeUnit.MILLISECONDS)

                Log.i(TAG, "MQTT publisher started — topic: ${config.publishTopic}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MQTT publisher", e)
                isConnected = false
            }
        }
    }

    /**
     * Queue an NMEA sentence for publishing. Thread-safe, never blocks.
     * Data is queued even while the broker reconnects (Paho auto-reconnect);
     * when the bounded queue fills, the oldest sentence is dropped.
     */
    fun publishNmea(nmea: String) {
        if (client == null) return
        while (!pendingMessages.offer(nmea)) {
            pendingMessages.poll()
        }
    }

    /**
     * Drain the pending queue, format, and publish.
     */
    private fun flushBatch() {
        if (!isConnected) return  // keep messages queued through a reconnect
        val batch = mutableListOf<String>()
        while (pendingMessages.isNotEmpty()) {
            pendingMessages.poll()?.let { batch.add(it) }
        }
        if (batch.isEmpty()) return

        val cfg = config ?: return
        val topic = cfg.publishTopic

        try {
            if (cfg.format == "aisc-json") {
                for (nmea in batch) {
                    val trimmed = nmea.trim()
                    if (trimmed.isEmpty()) continue
                    val json = JSONObject().apply {
                        put("nmea", JSONArray().put(trimmed))
                        put("channel", inferChannel(trimmed))
                    }
                    publish(topic, json.toString(), cfg.qos)
                }
            } else {
                // Raw format — send each NMEA line as-is
                for (nmea in batch) {
                    val trimmed = nmea.trim()
                    if (trimmed.isEmpty()) continue
                    publish(topic, trimmed, cfg.qos)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing MQTT batch", e)
        }
    }

    /**
     * Publish a single message to the broker.
     */
    private fun publish(topic: String, payload: String, qos: Int) {
        try {
            val mqttMessage = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                this.qos = qos
                isRetained = false
            }
            client?.publish(topic, mqttMessage)
            messagesSent++
        } catch (e: MqttException) {
            Log.e(TAG, "MQTT publish error on topic=$topic: ${e.message}")
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
        payload.put("messages_sent", messagesSent)
        payload.put("messages_decoded", AisReceiverService.messageCount)
        payload.put("service_running", AisReceiverService.isRunning)
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
                Log.w(TAG, "Error closing MQTT client", e)
            }

            client = null
            isConnected = false
            pendingMessages.clear()
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
            if (config.brokerUrl.startsWith("ssl://")) {
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

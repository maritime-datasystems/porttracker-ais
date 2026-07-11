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
        val stationName: String = ""
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
     * Disconnect and release resources.
     */
    fun stop() {
        synchronized(lifecycleLock) {
            try {
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

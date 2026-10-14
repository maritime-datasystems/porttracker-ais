package com.porttracker.ais

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the FRP (Fast Reverse Proxy) tunnel for remote access to the web interface.
 */
class FrpTunnelManager(private val context: Context) {
    
    companion object {
        private const val TAG = "porttracker-service.FRP"
        
        // FRP server configuration
        private const val FRP_SERVER_ADDR = "5.75.129.207"
        private const val FRP_SERVER_PORT = 7000
    }
    
    private fun getAuthToken(context: Context): String {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString("frp_auth_token", "porttrackerruleztheworld2026") ?: "porttrackerruleztheworld2026"
    }
    
    private var frpProcess: Process? = null
    private val isRunning = AtomicBoolean(false)
    

    
    /**
     * Get the frpc binary from the native library directory.
     * The binary is packaged as libfrpc.so in jniLibs for the appropriate ABI.
     * Returns the File object for the binary, or null on failure.
     */
    private fun prepareBinary(): File? {
        try {
            // Get the native library directory - binaries here are allowed to execute
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val binaryFile = File(nativeLibDir, "libfrpc.so")
            
            if (!binaryFile.exists()) {
                Log.e(TAG, "Binary not found in native lib dir: ${binaryFile.absolutePath}")
                return null
            }
            
            if (!binaryFile.canExecute()) {
                Log.w(TAG, "Binary not executable, this may fail")
            }
            
            Log.i(TAG, "Binary found: ${binaryFile.absolutePath} (executable=${binaryFile.canExecute()})")
            return binaryFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare binary", e)
            return null
        }
    }
    
    /**
     * Generate the config.toml file for frpc.
     */
    private fun generateConfig(stationName: String, webPort: Int): File? {
        val configFile = File(context.filesDir, "frpc.toml")
        
        // Sanitize station name for use as subdomain (alphanumeric and hyphens only)
        val sanitizedName = stationName.lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(32)
        
        val subdomain = if (sanitizedName.isNotEmpty()) sanitizedName else "station-${System.currentTimeMillis()}"
        
        val configContent = """
            serverAddr = "$FRP_SERVER_ADDR"
            serverPort = $FRP_SERVER_PORT
            auth.token = "${getAuthToken(context)}"
            loginFailExit = false
            
            [[proxies]]
            name = "$subdomain"
            type = "http"
            localPort = $webPort
            subdomain = "$subdomain"
        """.trimIndent()
        
        try {
            configFile.writeText(configContent)
            Log.i(TAG, "Config generated: ${configFile.absolutePath}")
            Log.d(TAG, "FRP config written successfully")
            return configFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate config", e)
            return null
        }
    }
    
    /**
     * Start the FRP tunnel.
     * @param stationName The station name to use for the subdomain.
     * @param webPort The local web interface port to tunnel.
     * @return true if started successfully, false otherwise.
     */
    fun startTunnel(stationName: String, webPort: Int): Boolean {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Tunnel already running")
            return true
        }
        
        val binary = prepareBinary()
        if (binary == null || !binary.exists()) {
            Log.e(TAG, "Failed to prepare frpc binary")
            isRunning.set(false)
            return false
        }
        
        val config = generateConfig(stationName, webPort)
        if (config == null || !config.exists()) {
            Log.e(TAG, "Failed to generate config")
            isRunning.set(false)
            return false
        }
        
        try {
            val processBuilder = ProcessBuilder(
                binary.absolutePath,
                "-c", config.absolutePath
            )
            processBuilder.directory(context.filesDir)
            processBuilder.redirectErrorStream(true)

            // isRunning already set via compareAndSet above

            Thread {
                try {
                    frpProcess = processBuilder.start()
                    Log.i(TAG, "Tunnel started successfully")
                    InternalLog.log("Tunnel started successfully")

                    frpProcess?.inputStream?.bufferedReader()?.use { reader ->
                        reader.forEachLine { line ->
                            Log.d(TAG, "frpc: $line")
                            InternalLog.log("frpc: $line")
                        }
                    }

                    frpProcess?.waitFor()
                    Log.i(TAG, "Tunnel process exited")
                    InternalLog.log("Tunnel process exited")

                } catch (e: Exception) {
                    Log.e(TAG, "Tunnel process error", e)
                    InternalLog.log("Tunnel process error: ${e.message}")
                } finally {
                    isRunning.set(false)
                }
            }.start()

            return true

        } catch (e: Exception) {
            isRunning.set(false)  // Reset if thread launch itself fails
            Log.e(TAG, "Failed to start tunnel", e)
            InternalLog.log("Failed to start tunnel: ${e.message}")
            return false
        }
    }
    
    /**
     * Stop the FRP tunnel.
     */
    fun stopTunnel() {
        if (!isRunning.compareAndSet(true, false)) return
        try {
            frpProcess?.let { process ->
                Log.i(TAG, "Stopping tunnel...")
                InternalLog.log("Stopping tunnel...")
                process.destroy()

                // Force kill if not stopped after 3 seconds
                Thread {
                    try {
                        Thread.sleep(3000)
                        if (android.os.Build.VERSION.SDK_INT >= 26 && process.isAlive) {
                            process.destroyForcibly()
                            Log.w(TAG, "Tunnel force killed")
                            InternalLog.log("Tunnel force killed")
                        }
                    } catch (e: InterruptedException) {
                        // Ignore
                    }
                }.start()
            }
            frpProcess = null
            Log.i(TAG, "Tunnel stopped")
            InternalLog.log("Tunnel stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tunnel", e)
            InternalLog.log("Error stopping tunnel: ${e.message}")
        }
    }
    
    /**
     * Check if the tunnel is currently running.
     */
    fun isRunning(): Boolean = isRunning.get()
}

package com.porttracker.ais

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Starts AIS Receiver Service on device boot if enabled in settings.
 * Enhanced for remote/unattended stations:
 * - Scans for USB devices with cached permissions
 * - Falls back to web-only mode if USB permission not available
 * - Requests battery optimization exemption for reliability
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "porttracker-service.Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed, checking auto-start settings...")
            
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            val autoStart = prefs.getBoolean("auto_start_boot", false)
            
            if (!autoStart) {
                Log.i(TAG, "Auto-start disabled, not starting service")
                return
            }
            
            Log.i(TAG, "Auto-start enabled, scanning for SDR devices...")
            
            // Scan for SDR device with cached permission
            val deviceInfo = UsbDeviceScanner.scanForDevices(context)
            
            // Android 15+ disallows starting a dataSync service from boot.
            if (android.os.Build.VERSION.SDK_INT >= 35 && !deviceInfo.isUsable) {
                Log.i(TAG, "Open the app to start web-only mode or grant USB permission")
                return
            }
            val serviceIntent = Intent(context, AisReceiverService::class.java)
            
            if (deviceInfo.found && deviceInfo.isUsable) {
                // Device found WITH permission - start normally
                Log.i(TAG, "SDR found with permission: ${deviceInfo.deviceName}")
                serviceIntent.putExtra("USB_VENDOR_ID", deviceInfo.vendorId)
                serviceIntent.putExtra("USB_PRODUCT_ID", deviceInfo.productId)
            } else if (deviceInfo.found && !deviceInfo.isUsable) {
                // Device found but NO permission - try to request it
                Log.w(TAG, "SDR found but NO permission: ${deviceInfo.deviceName}")
                
                // Try to request permission (requires user interaction)
                val requested = tryRequestUsbPermission(context, deviceInfo)
                
                if (!requested) {
                    // If we couldn't request permission, start in web-only mode
                    Log.w(TAG, "Could not request USB permission, enabling web-only mode")
                    prefs.edit().putBoolean("webviewer_enabled", true).apply()
                }
            } else {
                // No SDR device found - check if web-only mode is enabled
                val webEnabled = prefs.getBoolean("webviewer_enabled", false)
                if (!webEnabled) {
                    Log.w(TAG, "No SDR device found and web-only mode disabled - skipping service start")
                    return
                }
                Log.i(TAG, "No SDR device, starting in web-only mode")
            }
            
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Boot start is not currently allowed; open the app to start", e)
            }
        }
    }
    
    /**
     * Try to request USB permission. Returns true if request was sent.
     * Note: This will show a dialog to the user - they must have previously
     * granted permission with "Remember this device" for the permission to persist.
     */
    private fun tryRequestUsbPermission(context: Context, deviceInfo: UsbDeviceScanner.SdrDeviceInfo): Boolean {
        val device = deviceInfo.usbDevice ?: return false
        
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent("com.porttracker.ais.USB_PERMISSION_BOOT"),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
            Log.i(TAG, "USB permission request sent")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request USB permission", e)
            return false
        }
    }
}

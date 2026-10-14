package com.porttracker.ais

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Handles USB device attach/detach events and manages permissions.
 * Device support checks are delegated to [SupportedDevices].
 */
class UsbDeviceReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "porttracker-service.USB"
        private const val ACTION_USB_PERMISSION = "com.porttracker.ais.USB_PERMISSION"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                
                device?.let { handleDeviceAttached(context, it) }
            }
            
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (device != null && SupportedDevices.isSupportedDevice(device.vendorId, device.productId)) {
                    Log.i(TAG, "Supported SDR device detached: ${device.productName} (VID=${device.vendorId}, PID=${device.productId})")
                    AisReceiverService.hasDevice = false
                } else {
                    Log.i(TAG, "USB device detached (not a supported SDR)")
                }
            }
            
            ACTION_USB_PERMISSION -> {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                
                if (granted && device != null) {
                    Log.i(TAG, "USB permission granted for ${device.productName}")
                    startServiceWithDevice(context, device)
                } else {
                    Log.w(TAG, "USB permission denied")
                }
            }
        }
    }

    private fun handleDeviceAttached(context: Context, device: UsbDevice) {
        Log.i(TAG, "USB device attached: ${device.productName} (VID=${device.vendorId}, PID=${device.productId})")
        
        if (!SupportedDevices.isSupportedDevice(device.vendorId, device.productId)) {
            Log.d(TAG, "Device not supported")
            return
        }
        
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val autoStartOnUsb = prefs.getBoolean("auto_start_usb", true)
        
        if (!autoStartOnUsb) {
            Log.d(TAG, "Auto-start on USB disabled")
            return
        }
        
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        
        if (usbManager.hasPermission(device)) {
            startServiceWithDevice(context, device)
        } else {
            Log.i(TAG, "Requesting USB permission...")
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(context.packageName)
                },
                PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun startServiceWithDevice(context: Context, device: UsbDevice) {
        val deviceType = SupportedDevices.getDeviceType(device.vendorId, device.productId)
        
        Log.i(TAG, "Starting service with device: type=$deviceType, vid=${device.vendorId}, pid=${device.productId}")
        
        // Pass VID/PID - service will open device itself for proper lifecycle management
        val serviceIntent = Intent(context, AisReceiverService::class.java).apply {
            putExtra("USB_VENDOR_ID", device.vendorId)
            putExtra("USB_PRODUCT_ID", device.productId)
            putExtra("SOURCE", deviceType.sourceId)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}

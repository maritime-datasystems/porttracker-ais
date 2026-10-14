package com.porttracker.ais

/**
 * Single source of truth for all supported USB SDR device VID/PID pairs.
 *
 * Centralises the device lists formerly duplicated in [UsbDeviceReceiver] and
 * [UsbDeviceScanner].
 */
object SupportedDevices {

    /** A supported SDR device identified by USB vendor/product IDs. */
    data class SdrDevice(
        val vendorId: Int,
        val productId: Int,
        val type: DeviceType,
        val label: String
    )

    // ── RTL-SDR ──────────────────────────────────────────────────────────
    val RTL_SDR_DEVICES: List<SdrDevice> = listOf(
        SdrDevice(0x0BDA, 0x2832, DeviceType.RTLSDR, "RTL2832U"),
        SdrDevice(0x0BDA, 0x2838, DeviceType.RTLSDR, "RTL2838UHIDIR"),
        SdrDevice(0x0CCD, 0x00A9, DeviceType.RTLSDR, "RTL-SDR Blog V3"),
        SdrDevice(0x0CCD, 0x00B3, DeviceType.RTLSDR, "RTL-SDR Blog V4"),
        SdrDevice(0x0FCE, 0x6A34, DeviceType.RTLSDR, "Sony"),
        SdrDevice(0x1F4D, 0xB803, DeviceType.RTLSDR, "GTek"),
        SdrDevice(0x1F4D, 0xC803, DeviceType.RTLSDR, "Lifeview"),
        SdrDevice(0x1F4D, 0xD286, DeviceType.RTLSDR, "MyGica"),
        SdrDevice(0x1B80, 0xD3A4, DeviceType.RTLSDR, "Zadig"),
        SdrDevice(0x1D19, 0x1101, DeviceType.RTLSDR, "Dexatek"),
        SdrDevice(0x1D19, 0x1102, DeviceType.RTLSDR, "Dexatek"),
        SdrDevice(0x1D19, 0x1103, DeviceType.RTLSDR, "Dexatek"),
        SdrDevice(0x0458, 0x707F, DeviceType.RTLSDR, "Genius"),
        SdrDevice(0x1B80, 0xD393, DeviceType.RTLSDR, "SVEON"),
    )

    // ── HackRF ───────────────────────────────────────────────────────────
    val HACKRF_DEVICES: List<SdrDevice> = listOf(
        SdrDevice(0x1D50, 0x6089, DeviceType.RTLSDR, "HackRF"),
    )

    /** Every supported device across all families. */
    val ALL_DEVICES: List<SdrDevice> =
        RTL_SDR_DEVICES + HACKRF_DEVICES

    /** Quick look-up: is the VID/PID pair supported? */
    fun isSupportedDevice(vendorId: Int, productId: Int): Boolean =
        ALL_DEVICES.any { it.vendorId == vendorId && it.productId == productId }

    /** Return the [SdrDevice] entry for a VID/PID pair, or `null`. */
    fun findDevice(vendorId: Int, productId: Int): SdrDevice? =
        ALL_DEVICES.firstOrNull { it.vendorId == vendorId && it.productId == productId }

    /** Return the [DeviceType] for a VID/PID pair, defaulting to [DeviceType.RTLSDR]. */
    fun getDeviceType(vendorId: Int, productId: Int): DeviceType =
        findDevice(vendorId, productId)?.type ?: DeviceType.RTLSDR
}

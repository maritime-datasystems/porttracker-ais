package com.porttracker.ais

import org.junit.Assert.*
import org.junit.Test

class SupportedDevicesTest {
    @Test fun removedReceiversAreNotDetected() {
        assertFalse(SupportedDevices.isSupportedDevice(0x1D50, 0x60A1))
        assertFalse(SupportedDevices.isSupportedDevice(0x1D50, 0x60A6))
        assertFalse(SupportedDevices.isSupportedDevice(0x03EB, 0x800C))
        assertTrue(SupportedDevices.isSupportedDevice(0x0BDA, 0x2838))
    }

    @Test fun persistedSourceIdsRemainStable() {
        assertEquals(0, DeviceType.RTLTCP.sourceId)
        assertEquals(1, DeviceType.RTLSDR.sourceId)
        assertEquals(4, DeviceType.SPYSERVER.sourceId)
        assertEquals(DeviceType.SPYSERVER, DeviceType.fromSourceId(4))
        assertEquals(DeviceType.RTLSDR, DeviceType.fromSourceId(2))
        assertEquals(DeviceType.RTLSDR, DeviceType.fromSourceId(3))
    }
}

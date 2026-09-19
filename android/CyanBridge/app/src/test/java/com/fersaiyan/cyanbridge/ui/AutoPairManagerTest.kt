package com.fersaiyan.cyanbridge.ui

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPairManagerTest {
    @Test
    fun vivePlaceholderAddressIsRejectedForReconnect() {
        assertFalse(AutoPairManager.isValidBluetoothAddress("VIVE_EAGLE"))
        assertNull(AutoPairManager.normalizeAddressCandidate("VIVE_EAGLE"))
    }

    @Test
    fun realBluetoothAddressIsAcceptedForReconnect() {
        assertTrue(AutoPairManager.isValidBluetoothAddress("AA:BB:CC:DD:EE:FF"))
        assertEquals("AA:BB:CC:DD:EE:FF", AutoPairManager.normalizeAddressCandidate("AA:BB:CC:DD:EE:FF"))
    }
}

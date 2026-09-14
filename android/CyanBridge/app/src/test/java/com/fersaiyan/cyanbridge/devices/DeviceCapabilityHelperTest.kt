package com.fersaiyan.cyanbridge.devices

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityHelperTest {

    @Test
    fun heyCyan_supportsCapturedPreview() {
        assertTrue(DeviceCapabilityHelper.supportsCapturedPreview(DeviceClass.HEY_CYAN))
    }
}

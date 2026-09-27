package com.fersaiyan.cyanbridge.hil

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fersaiyan.cyanbridge.MainActivity
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.oudmon.ble.base.bluetooth.BleOperateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ModelCaptureHeyCyanHilTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun captureSyncsAndRendersTheNewHeyCyanImage() {
        assumeTrue(
            "Real model-capture HIL is disabled. Set hil_glasses=true on the dedicated HeyCyan device.",
            HilTestSupport.glassesRequired,
        )
        val context = composeRule.activity
        assertEquals(DeviceClass.HEY_CYAN, DeviceProfileStore.selectedClass(context))
        assertTrue(
            "HeyCyan BLE did not reconnect after installing the branch build",
            waitUntil(30_000L) { BleOperateManager.getInstance().isConnected },
        )

        composeRule.onNodeWithText("3D Capture").performClick()
        composeRule.onNodeWithTag("model_capture_start").performClick()
        composeRule.onNodeWithTag("model_capture_status_capturing").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 120_000L) {
            composeRule.onAllNodesWithTag("model_capture_final_image")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("model_capture_final_image").assertIsDisplayed()
        composeRule.onNodeWithTag("model_capture_process").assertIsNotEnabled()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(500)
        }
        return condition()
    }
}

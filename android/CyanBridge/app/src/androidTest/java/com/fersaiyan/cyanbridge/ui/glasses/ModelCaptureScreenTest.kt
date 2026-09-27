package com.fersaiyan.cyanbridge.ui.glasses

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.ModelCaptureAssetId
import com.fersaiyan.cyanbridge.shared.glasses.ModelCaptureDetail
import com.fersaiyan.cyanbridge.shared.glasses.ModelCaptureOperationId
import com.fersaiyan.cyanbridge.shared.glasses.ModelCapturePhase
import com.fersaiyan.cyanbridge.shared.glasses.ModelCaptureUiState
import com.fersaiyan.cyanbridge.shared.ui.glasses.ModelCaptureScreen
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ModelCaptureScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun idleAndFailedPhasesShowStatusAndAllowAnotherCapture() {
        var startCount = 0
        composeRule.setContent {
            CyanBridgeTheme {
                ModelCaptureScreen(
                    glassesState = GlassesDashboardUiState(connectionLabel = "Connected"),
                    state = ModelCaptureUiState(),
                    finalImage = null,
                    onStartCapture = { startCount++ },
                    onCancelCapture = {},
                )
            }
        }

        composeRule.onNodeWithTag("model_capture_status_idle").assertIsDisplayed()
        composeRule.onNodeWithText("Connected").assertIsDisplayed()
        composeRule.onNodeWithTag("model_capture_start").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, startCount) }

        composeRule.setContent {
            CyanBridgeTheme {
                ModelCaptureScreen(
                    glassesState = GlassesDashboardUiState(),
                    state = ModelCaptureUiState(
                        phase = ModelCapturePhase.FAILED,
                        detail = ModelCaptureDetail.FAILED,
                    ),
                    finalImage = null,
                    onStartCapture = { startCount++ },
                    onCancelCapture = {},
                )
            }
        }

        composeRule.onNodeWithTag("model_capture_status_failed").assertIsDisplayed()
        composeRule.onNodeWithText("Status: Unable to capture image").assertIsDisplayed()
        composeRule.onNodeWithTag("model_capture_start").assertIsEnabled()
    }

    @Test
    fun activePhasesShowAnimatedIllustrationAndDispatchCancel() {
        val operationId = ModelCaptureOperationId("capture-1")
        var cancelCount = 0
        composeRule.setContent {
            CyanBridgeTheme {
                ModelCaptureScreen(
                    glassesState = GlassesDashboardUiState(),
                    state = ModelCaptureUiState(
                        phase = ModelCapturePhase.CAPTURING,
                        detail = ModelCaptureDetail.CAPTURING,
                        activeOperationId = operationId,
                    ),
                    finalImage = null,
                    onStartCapture = {},
                    onCancelCapture = { cancelCount++ },
                )
            }
        }

        composeRule.onNodeWithTag("model_capture_status_capturing").assertIsDisplayed()
        composeRule.onNodeWithTag("model_capture_syncing_illustration").assertIsDisplayed()
        composeRule.onNodeWithTag("model_capture_cancel").performClick()
        composeRule.runOnIdle { assertEquals(1, cancelCount) }

        composeRule.setContent {
            CyanBridgeTheme {
                ModelCaptureScreen(
                    glassesState = GlassesDashboardUiState(),
                    state = ModelCaptureUiState(
                        phase = ModelCapturePhase.SYNCING,
                        detail = ModelCaptureDetail.SYNCING,
                        activeOperationId = operationId,
                    ),
                    finalImage = null,
                    onStartCapture = {},
                    onCancelCapture = {},
                )
            }
        }

        composeRule.onNodeWithTag("model_capture_status_syncing").assertIsDisplayed()
        composeRule.onNodeWithTag("model_capture_syncing_illustration").assertIsDisplayed()
    }

    @Test
    fun readyPhaseRendersHostDecodedImageAndDisabledProcessingControl() {
        composeRule.setContent {
            CyanBridgeTheme {
                ModelCaptureScreen(
                    glassesState = GlassesDashboardUiState(),
                    state = ModelCaptureUiState(
                        phase = ModelCapturePhase.READY,
                        detail = ModelCaptureDetail.IMAGE_READY,
                        finalImageAssetId = ModelCaptureAssetId("asset-1"),
                    ),
                    finalImage = ImageBitmap(1, 1),
                    onStartCapture = {},
                    onCancelCapture = {},
                )
            }
        }

        composeRule.onNodeWithTag("model_capture_status_ready").assertIsDisplayed()
        composeRule.onNodeWithTag("model_capture_final_image").assertIsDisplayed()
        composeRule.onNodeWithTag("model_capture_process").assertIsNotEnabled()
        composeRule.onAllNodesWithTag("model_capture_image_unavailable").assertCountEquals(0)
    }

    @Test
    fun readyPhaseShowsFallbackWhenHostCannotDecodeAsset() {
        composeRule.setContent {
            CyanBridgeTheme {
                ModelCaptureScreen(
                    glassesState = GlassesDashboardUiState(),
                    state = ModelCaptureUiState(
                        phase = ModelCapturePhase.READY,
                        detail = ModelCaptureDetail.IMAGE_READY,
                        finalImageAssetId = ModelCaptureAssetId("asset-1"),
                    ),
                    finalImage = null,
                    onStartCapture = {},
                    onCancelCapture = {},
                )
            }
        }

        composeRule.onNodeWithTag("model_capture_image_unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("model_capture_process").assertIsNotEnabled()
    }
}
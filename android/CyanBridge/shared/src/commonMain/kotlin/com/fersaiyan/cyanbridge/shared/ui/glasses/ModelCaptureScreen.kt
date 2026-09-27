package com.fersaiyan.cyanbridge.shared.ui.glasses

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.model_capture_cancel
import com.fersaiyan.cyanbridge.shared.generated.resources.model_capture_detail_asset_unavailable
import com.fersaiyan.cyanbridge.shared.generated.resources.model_capture_detail_cancelled
import com.fersaiyan.cyanbridge.shared.generated.resources.model_capture_detail_capturing
import com.fersaiyan.cyanbridge.shared.generated.resources.model_capture_detail_failed
import com.fersaiyan.cyanbridge.shared.generated.resources.model_capture_detail_ready
import com.fersaiyan.cyanbridge.shared.generated.resources.model_capture_detail_ready_to_capture
import com.fersaiyan.cyanbridge.shared.generated.resources.model_capture_detail_syncing
import com.fersaiyan.cyanbridge.shared.generated.resources.model_capture_detail
import com.fersaiyan.cyanbridge.shared.generated.resources.model_capture_image_unavailable
import com.fersaiyan.cyanbridge.shared.generated.resources.model_capture_process
import com.fersaiyan.cyanbridge.shared.generated.resources.model_capture_start
import com.fersaiyan.cyanbridge.shared.generated.resources.model_capture_title
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.ModelCaptureDetail
import com.fersaiyan.cyanbridge.shared.glasses.ModelCapturePhase
import com.fersaiyan.cyanbridge.shared.glasses.ModelCaptureUiState
import org.jetbrains.compose.resources.stringResource

/**
 * Shared 3D-capture UI. Its host owns operation IDs and resolves an opaque asset ID
 * to [finalImage], so this composable never observes Android paths or file URIs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelCaptureScreen(
    glassesState: GlassesDashboardUiState,
    state: ModelCaptureUiState,
    finalImage: ImageBitmap?,
    onStartCapture: () -> Unit,
    onCancelCapture: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Text(stringResource(Res.string.model_capture_title)) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .testTag("model_capture_screen"),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { GlassesStatusCard(glassesState) }
            item {
                ModelCaptureStatusCard(
                    state = state,
                    finalImage = finalImage,
                )
            }
            item {
                when {
                    state.isInProgress -> OutlinedButton(
                        onClick = onCancelCapture,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("model_capture_cancel"),
                    ) {
                        Text(stringResource(Res.string.model_capture_cancel))
                    }

                    else -> Button(
                        onClick = onStartCapture,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("model_capture_start"),
                    ) {
                        Text(stringResource(Res.string.model_capture_start))
                    }
                }
            }
            if (state.phase == ModelCapturePhase.READY) {
                item {
                    Button(
                        enabled = false,
                        onClick = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("model_capture_process"),
                    ) {
                        Text(stringResource(Res.string.model_capture_process))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCaptureStatusCard(
    state: ModelCaptureUiState,
    finalImage: ImageBitmap?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_capture_status_${state.phase.name.lowercase()}"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.model_capture_detail, localizedModelCaptureDetail(state.detail)),
                style = MaterialTheme.typography.bodyLarge,
            )
            when (state.phase) {
                ModelCapturePhase.CAPTURING,
                ModelCapturePhase.SYNCING,
                -> SyncingIllustration()

                ModelCapturePhase.READY -> if (finalImage != null) {
                    Image(
                        bitmap = finalImage,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .testTag("model_capture_final_image"),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.model_capture_image_unavailable),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("model_capture_image_unavailable"),
                    )
                }

                ModelCapturePhase.IDLE,
                ModelCapturePhase.FAILED,
                -> Unit
            }
        }
    }
}

@Composable
private fun localizedModelCaptureDetail(detail: ModelCaptureDetail): String = when (detail) {
    ModelCaptureDetail.READY_TO_CAPTURE -> stringResource(Res.string.model_capture_detail_ready_to_capture)
    ModelCaptureDetail.CAPTURING -> stringResource(Res.string.model_capture_detail_capturing)
    ModelCaptureDetail.SYNCING -> stringResource(Res.string.model_capture_detail_syncing)
    ModelCaptureDetail.IMAGE_READY -> stringResource(Res.string.model_capture_detail_ready)
    ModelCaptureDetail.CANCELLED -> stringResource(Res.string.model_capture_detail_cancelled)
    ModelCaptureDetail.ASSET_UNAVAILABLE -> stringResource(Res.string.model_capture_detail_asset_unavailable)
    ModelCaptureDetail.FAILED -> stringResource(Res.string.model_capture_detail_failed)
}

@Composable
private fun SyncingIllustration() {
    val transition = rememberInfiniteTransition(label = "model_capture_sync")
    val imageAlpha = transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "model_capture_image_alpha",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .testTag("model_capture_syncing_illustration"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CameraAlt,
            contentDescription = null,
            modifier = Modifier
                .size(76.dp)
                .alpha(imageAlpha.value),
            tint = MaterialTheme.colorScheme.primary,
        )
        Icon(
            imageVector = Icons.Outlined.Image,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .size(44.dp)
                .alpha(imageAlpha.value),
            tint = MaterialTheme.colorScheme.secondary,
        )
        LinearProgressIndicator(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
        )
    }
}
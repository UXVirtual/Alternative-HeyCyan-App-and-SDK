package com.fersaiyan.cyanbridge.shared.glasses

/** Opaque identifier for an app-managed final capture asset. */
@JvmInline
value class ModelCaptureAssetId(val value: String) {
    init {
        require(value.isNotBlank()) { "Model capture asset IDs must not be blank" }
    }
}

/** Opaque identifier that pins platform callbacks to one capture attempt. */
@JvmInline
value class ModelCaptureOperationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Model capture operation IDs must not be blank" }
    }
}

enum class ModelCapturePhase {
    IDLE,
    CAPTURING,
    SYNCING,
    READY,
    FAILED,
}

/** User-facing capture status values; platform diagnostic text must not reach shared UI. */
enum class ModelCaptureDetail {
    READY_TO_CAPTURE,
    CAPTURING,
    SYNCING,
    IMAGE_READY,
    CANCELLED,
    ASSET_UNAVAILABLE,
    FAILED,
}

/**
 * Platform-neutral state for the 3D capture destination. File paths and decoded
 * images deliberately remain in the Android asset adapter.
 */
data class ModelCaptureUiState(
    val phase: ModelCapturePhase = ModelCapturePhase.IDLE,
    val detail: ModelCaptureDetail = ModelCaptureDetail.READY_TO_CAPTURE,
    val activeOperationId: ModelCaptureOperationId? = null,
    val finalImageAssetId: ModelCaptureAssetId? = null,
) {
    init {
        require(phase != ModelCapturePhase.READY || finalImageAssetId != null) {
            "READY requires a verified final image asset"
        }
        require(phase == ModelCapturePhase.READY || finalImageAssetId == null) {
            "Only READY may retain a final image asset"
        }
        require(
            (phase == ModelCapturePhase.CAPTURING || phase == ModelCapturePhase.SYNCING) ==
                (activeOperationId != null),
        ) {
            "Only active phases may retain an operation ID"
        }
    }

    val isInProgress: Boolean
        get() = activeOperationId != null
}

sealed interface ModelCaptureAction {
    data class StartModelCapture(val operationId: ModelCaptureOperationId) : ModelCaptureAction
    data class CancelModelCapture(val operationId: ModelCaptureOperationId) : ModelCaptureAction
    data class CapturePersisted(val operationId: ModelCaptureOperationId) : ModelCaptureAction
    data class SyncStarted(val operationId: ModelCaptureOperationId) : ModelCaptureAction
    data class Complete(
        val operationId: ModelCaptureOperationId,
        val finalImageAssetId: ModelCaptureAssetId,
    ) : ModelCaptureAction

    data class Fail(
        val operationId: ModelCaptureOperationId,
        val detail: String,
    ) : ModelCaptureAction
}

/**
 * Reduces platform events without trusting their delivery order. BLE, P2P, HTTP,
 * speech, and teardown adapters must include their initiating [ModelCaptureOperationId].
 */
fun reduceModelCapture(
    state: ModelCaptureUiState,
    action: ModelCaptureAction,
): ModelCaptureUiState = when (action) {
    is ModelCaptureAction.StartModelCapture -> {
        if (state.isInProgress) {
            state
        } else {
            ModelCaptureUiState(
                phase = ModelCapturePhase.CAPTURING,
                detail = ModelCaptureDetail.CAPTURING,
                activeOperationId = action.operationId,
            )
        }
    }

    is ModelCaptureAction.CancelModelCapture -> {
        if (state.activeOperationId == action.operationId) {
            failedModelCapture(ModelCaptureDetail.CANCELLED)
        } else {
            state
        }
    }

    is ModelCaptureAction.CapturePersisted -> {
        if (state.activeOperationId == action.operationId && state.phase == ModelCapturePhase.CAPTURING) {
            state.copy(
                phase = ModelCapturePhase.SYNCING,
                detail = ModelCaptureDetail.SYNCING,
            )
        } else {
            state
        }
    }

    is ModelCaptureAction.SyncStarted -> {
        if (state.activeOperationId == action.operationId && state.phase == ModelCapturePhase.CAPTURING) {
            state.copy(
                phase = ModelCapturePhase.SYNCING,
                detail = ModelCaptureDetail.SYNCING,
            )
        } else {
            state
        }
    }

    is ModelCaptureAction.Complete -> {
        if (state.activeOperationId == action.operationId && state.phase == ModelCapturePhase.SYNCING) {
            ModelCaptureUiState(
                phase = ModelCapturePhase.READY,
                detail = ModelCaptureDetail.IMAGE_READY,
                finalImageAssetId = action.finalImageAssetId,
            )
        } else {
            state
        }
    }

    is ModelCaptureAction.Fail -> {
        if (state.activeOperationId == action.operationId) {
            failedModelCapture(ModelCaptureDetail.FAILED)
        } else {
            state
        }
    }
}

/** Called only after the platform adapter verifies that the app-managed asset is decodable. */
fun restoreModelCapture(assetId: ModelCaptureAssetId?): ModelCaptureUiState =
    if (assetId == null) {
        ModelCaptureUiState(detail = ModelCaptureDetail.ASSET_UNAVAILABLE)
    } else {
        ModelCaptureUiState(
            phase = ModelCapturePhase.READY,
            detail = ModelCaptureDetail.IMAGE_READY,
            finalImageAssetId = assetId,
        )
    }

private fun failedModelCapture(detail: ModelCaptureDetail): ModelCaptureUiState = ModelCaptureUiState(
    phase = ModelCapturePhase.FAILED,
    detail = detail,
)
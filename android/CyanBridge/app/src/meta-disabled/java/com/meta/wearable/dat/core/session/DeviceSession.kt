package com.meta.wearable.dat.core.session

import android.content.Context
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.display.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DeviceSessionState {
    IDLE,
    STARTING,
    STARTED,
    PAUSED,
    STOPPING,
    STOPPED,
}

class MetaError(val description: String) {
    fun getLocalizedDescription(context: Context): String = description
}

class DeviceSession {
    val state: StateFlow<DeviceSessionState> = MutableStateFlow(DeviceSessionState.IDLE).asStateFlow()
    val errors: StateFlow<MetaError> = MutableStateFlow(MetaError("Meta Wearables support is disabled."))

    fun start(): Result<Unit> = Result.success(Unit)
    fun stop() = Unit
    fun addStream(configuration: StreamConfiguration): Result<Stream> = Result.success(Stream())
    fun addDisplay(): Result<Display> = Result.success(Display())
}

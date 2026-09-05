package com.meta.wearable.dat.camera

import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.VideoFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StreamState {
    STARTING,
    STARTED,
    STREAMING,
    STOPPING,
    STOPPED,
    PAUSED,
    CLOSED,
}

class Stream {
    val state: StateFlow<StreamState> = MutableStateFlow(StreamState.STOPPED).asStateFlow()
    val errorStream: StateFlow<StreamError> = MutableStateFlow(StreamError.STREAM_ERROR)
    val videoStream: StateFlow<VideoFrame> = MutableStateFlow(VideoFrame())

    fun start(): Result<Unit> = Result.success(Unit)
    fun stop() = Unit
    fun capturePhoto(): Result<PhotoData> = Result.success(
        PhotoData.HEIC(byteArrayOf(0x00, 0x01, 0x02, 0x03)),
    )
}

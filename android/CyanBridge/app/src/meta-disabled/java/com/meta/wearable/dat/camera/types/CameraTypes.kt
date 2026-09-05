package com.meta.wearable.dat.camera.types

import android.graphics.Bitmap
import java.nio.ByteBuffer

enum class VideoQuality {
    MEDIUM,
}

data class StreamConfiguration(
    val videoQuality: VideoQuality = VideoQuality.MEDIUM,
    val frameRate: Int = 24,
)

enum class StreamError {
    STREAM_ERROR,
}

data class VideoFrame(
    val width: Int = 0,
    val height: Int = 0,
    val buffer: ByteBuffer = ByteBuffer.allocate(0),
    val isCompressed: Boolean = false,
    val isCodecConfig: Boolean = false,
)

sealed interface PhotoData {
    data class Bitmap(val bitmap: Bitmap) : PhotoData
    data class HEIC(val data: ByteArray) : PhotoData
}

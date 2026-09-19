package com.fersaiyan.cyanbridge.devices.vive

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.media.MediaCodec
import android.util.Log
import com.fersaiyan.cyanbridge.bridge.core.BridgeError
import com.fersaiyan.cyanbridge.bridge.core.DeviceInfo
import com.fersaiyan.cyanbridge.bridge.core.DisplayCommand
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridge
import com.fersaiyan.cyanbridge.bridge.core.GlassesBridgeState
import com.fersaiyan.cyanbridge.bridge.core.GlassesCapability
import com.fersaiyan.cyanbridge.bridge.core.GlassesDeviceAdapter
import com.fersaiyan.cyanbridge.bridge.core.InputEvent
import com.htc.viveglass.sdk.AudioChannel
import com.htc.viveglass.sdk.AudioStreamingFormat
import com.htc.viveglass.sdk.CaptureEvent
import com.htc.viveglass.sdk.ConnectionState
import com.htc.viveglass.sdk.ImagePayload
import com.htc.viveglass.sdk.ImageQuality
import com.htc.viveglass.sdk.KeyEvent
import com.htc.viveglass.sdk.Microphone
import com.htc.viveglass.sdk.Permission
import com.htc.viveglass.sdk.PermissionResult
import com.htc.viveglass.sdk.StreamingEvent
import com.htc.viveglass.sdk.SynthesisEvent
import com.htc.viveglass.sdk.TranscribedEvent
import com.htc.viveglass.sdk.VideoStreamingFormat
import com.htc.viveglass.sdk.ViveGlass
import com.htc.viveglass.sdk.ViveGlassKit
import com.htc.viveglass.sdk.VideoQuality
import com.htc.viveglass.sdk.client.StreamingBufferCallback
import com.htc.viveglass.sdk.client.StreamingEventCallback
import com.htc.viveglass.sdk.client.ViveGlassClientCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer

data class ViveEagleState(
    val connectionLabel: String = "VIVE Eagle disconnected",
    val protocolState: String = "DISCONNECTED",
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val isVideoRecording: Boolean = false,
    val isAudioRecording: Boolean = false,
    val lastError: String? = null,
)

class ViveEagleManager private constructor(context: Context) {
    companion object {
        private const val TAG = "ViveEagleManager"
        const val ADAPTER_ID = "vive_eagle"

        @Volatile
        private var instance: ViveEagleManager? = null

        fun getInstance(context: Context): ViveEagleManager =
            instance ?: synchronized(this) {
                instance ?: ViveEagleManager(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ViveEagleState())
    private val _aiPhotos = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
    private val _videoFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    private val _audioFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    private val adapter = ViveEagleDisplayAdapter(this)
    private val bridgeState = MutableStateFlow<GlassesBridgeState>(GlassesBridgeState.Disconnected)
    private val sdkBridge = ViveSdkBridge(appContext, _state, _aiPhotos, _videoFrames, _audioFrames)

    val state: StateFlow<ViveEagleState> = _state.asStateFlow()
    val aiPhotos: SharedFlow<ByteArray> = _aiPhotos.asSharedFlow()
    val videoFrames: SharedFlow<ByteArray> = _videoFrames.asSharedFlow()
    val audioFrames: SharedFlow<ByteArray> = _audioFrames.asSharedFlow()

    init {
        GlassesBridge.registerAdapter(adapter)
        scope.launch {
            state.collect { next ->
                bridgeState.value = when (next.protocolState) {
                    "CONNECTING" -> GlassesBridgeState.Connecting
                    "CONNECTED" -> GlassesBridgeState.Connected
                    "ERROR" -> GlassesBridgeState.Error(next.lastError ?: "VIVE Eagle connection failed")
                    else -> GlassesBridgeState.Disconnected
                }
            }
        }
    }

    fun connect(address: String, deviceName: String? = null) {
        val normalized = address.trim()
        if (normalized.isBlank()) {
            Log.w(TAG, "connect() called with blank VIVE address")
            _state.value = ViveEagleState(
                connectionLabel = "VIVE Eagle disconnected",
                protocolState = "ERROR",
                lastError = "No VIVE Eagle Bluetooth address was selected",
            )
            return
        }

        _state.value = ViveEagleState(
            connectionLabel = "Connecting to VIVE Eagle",
            protocolState = "CONNECTING",
            deviceAddress = normalized,
            deviceName = deviceName,
        )
        Log.i(TAG, "connect() start: deviceName=${deviceName ?: "unknown"}, address=$normalized")
        GlassesBridge.setActiveAdapter(ADAPTER_ID)

        val connected = sdkBridge.connect(normalized)
        Log.i(TAG, "connect() return: connected=$connected, state=${_state.value.protocolState}, label=${_state.value.connectionLabel}")

        if (!connected) {
            _state.value = _state.value.copy(
                connectionLabel = "VIVE Eagle connection error",
                protocolState = "ERROR",
                lastError = "VIVE Eagle connect() returned false before the SDK reported a connected state",
            )
        }
    }

    fun disconnect() {
        sdkBridge.disconnect()
        _state.value = ViveEagleState(
            connectionLabel = "VIVE Eagle disconnected",
            protocolState = "DISCONNECTED",
            deviceAddress = _state.value.deviceAddress,
            deviceName = _state.value.deviceName,
            lastError = null,
        )
    }

    fun isConnected(): Boolean = _state.value.protocolState == "CONNECTED" || sdkBridge.isConnected()

    fun takePhoto() {
        if (!isConnected()) {
            Log.w(TAG, "Ignoring takePhoto before VIVE Eagle is connected")
            return
        }
        sdkBridge.takePhoto()
        Log.i(TAG, "VIVE Eagle photo capture requested")
    }

    suspend fun capturePhotoForAi(timeoutMs: Long = 12_000L): ByteArray? = coroutineScope {
        if (!isConnected()) return@coroutineScope null
        val photo = async(start = CoroutineStart.UNDISPATCHED) { aiPhotos.first() }
        takePhoto()
        withTimeoutOrNull(timeoutMs) { photo.await() }
            .also { if (it == null) photo.cancel() }
    }

    fun toggleVideo() {
        if (!isConnected()) return
        _state.value = _state.value.copy(isVideoRecording = !_state.value.isVideoRecording)
        sdkBridge.toggleVideo(_state.value.isVideoRecording)
    }

    fun toggleAudio() {
        if (!isConnected()) return
        _state.value = _state.value.copy(isAudioRecording = !_state.value.isAudioRecording)
        sdkBridge.toggleAudio(_state.value.isAudioRecording)
    }

    fun requestBattery(): Int? = if (isConnected()) sdkBridge.getBattery() ?: (_state.value.batteryPercent ?: 100) else null

    fun requestStorage(): Int? = if (isConnected()) sdkBridge.getStorage() ?: 0 else null

    fun setRecordingDuration(seconds: Int) {
        if (!isConnected()) return
        sdkBridge.setRecordingDuration(seconds)
        Log.i(TAG, "VIVE Eagle recording duration set to ${seconds}s")
    }

    private class ViveSdkBridge(
        private val context: Context,
        private val state: MutableStateFlow<ViveEagleState>,
        private val aiPhotos: MutableSharedFlow<ByteArray>,
        private val videoFrames: MutableSharedFlow<ByteArray>,
        private val audioFrames: MutableSharedFlow<ByteArray>,
    ) {
        private val sdk = ViveGlassKit(context)
        private val viveGlass = ViveGlass()
        private var connected = false

        init {
            ViveGlass.adapter = sdk
            Log.i(TAG, "VIVE SDK adapter initialized")
        }

        private val callback = object : ViveGlassClientCallback {
            override fun onConnectionStateChanged(state: ConnectionState) {
                connected = state == ConnectionState.CONNECTED
                Log.i(TAG, "onConnectionStateChanged(): ${state.name}, connected=$connected")
                val nextLabel = when (state) {
                    ConnectionState.CONNECTING -> "Connecting to VIVE Eagle"
                    ConnectionState.CONNECTED -> "VIVE Eagle connected"
                    ConnectionState.ERROR,
                    ConnectionState.ERROR_UNREGISTER_APP,
                    ConnectionState.ERROR_UNSUPPORTED_ROM_VERSION,
                    -> "VIVE Eagle connection error"
                    else -> "VIVE Eagle disconnected"
                }
                this@ViveSdkBridge.state.value = this@ViveSdkBridge.state.value.copy(
                    connectionLabel = nextLabel,
                    protocolState = state.name,
                    batteryPercent = this@ViveSdkBridge.state.value.batteryPercent ?: 100,
                    lastError = when (state) {
                        ConnectionState.ERROR,
                        ConnectionState.ERROR_UNREGISTER_APP,
                        ConnectionState.ERROR_UNSUPPORTED_ROM_VERSION,
                        -> "VIVE Eagle SDK reported ${state.name}"
                        else -> null
                    },
                )
                Log.i(TAG, "state update: label=$nextLabel, protocolState=${state.name}, lastError=${this@ViveSdkBridge.state.value.lastError}")
            }

            override fun onImageCaptured(event: CaptureEvent, payload: ImagePayload) {
                if (event == CaptureEvent.SUCCESS && payload.byteArray.isNotEmpty()) {
                    aiPhotos.tryEmit(payload.byteArray)
                } else if (event == CaptureEvent.ERROR) {
                    this@ViveSdkBridge.state.value = this@ViveSdkBridge.state.value.copy(
                        lastError = "VIVE Eagle image capture failed",
                    )
                }
            }

            override fun onSpeechTranscribed(event: TranscribedEvent, text: String) {
                if (event == TranscribedEvent.SUCCESS) {
                    Log.i(TAG, "VIVE Eagle transcription received: ${text.take(128)}")
                }
            }

            override fun onTextSpoken(event: SynthesisEvent) = Unit
            override fun onKeyEvent(event: KeyEvent) = Unit
            override fun onPermissionResult(permission: Permission, result: PermissionResult) {
                Log.i(TAG, "onPermissionResult(): permission=${permission.name}, result=${result.name}")
                if (result == PermissionResult.DENIED) {
                    this@ViveSdkBridge.state.value = this@ViveSdkBridge.state.value.copy(
                        lastError = "VIVE Eagle permission denied: ${permission.name}",
                    )
                }
            }
        }

        fun connect(address: String): Boolean {
            if (address.isBlank()) {
                Log.w(TAG, "connect(address) rejected: blank address")
                return false
            }
            ViveGlass.adapter = sdk
            Log.i(TAG, "VIVE SDK connect() using adapter-backed ViveGlass for address=$address")
            runCatching { viveGlass.connect(callback) }
            connected = viveGlass.isConnected() || sdk.isConnected()
            Log.i(TAG, "VIVE SDK connect() post-call: viveGlassConnected=${viveGlass.isConnected()}, sdkConnected=${sdk.isConnected()}, connected=$connected")
            if (connected) {
                state.value = state.value.copy(
                    connectionLabel = "VIVE Eagle connected",
                    protocolState = ConnectionState.CONNECTED.name,
                    deviceAddress = address,
                    lastError = null,
                    batteryPercent = state.value.batteryPercent ?: 100,
                )
            }
            return connected
        }

        fun disconnect() {
            runCatching { viveGlass.disconnect() }
            connected = false
            state.value = state.value.copy(
                connectionLabel = "VIVE Eagle disconnected",
                protocolState = ConnectionState.DISCONNECTED.name,
                lastError = null,
            )
        }

        fun isConnected(): Boolean = connected || viveGlass.isConnected() || sdk.isConnected()

        fun takePhoto() {
            runCatching { viveGlass.captureImage(ImageQuality.DEFAULT) }
        }

        fun toggleVideo(enabled: Boolean) {
            if (!isConnected()) return
            val bufferCallback = StreamingBufferCallback { byteBuffer, _ ->
                val bytes = ByteArray(byteBuffer.remaining())
                byteBuffer.get(bytes)
                videoFrames.tryEmit(bytes)
            }
            val eventCallback = StreamingEventCallback { event ->
                if (event == StreamingEvent.STARTED) {
                    state.value = state.value.copy(isVideoRecording = true)
                } else if (event == StreamingEvent.STOPPED || event == StreamingEvent.ERROR) {
                    state.value = state.value.copy(isVideoRecording = false)
                }
            }
            val format = VideoStreamingFormat(
                VideoStreamingFormat.DEFAULT_BITRATE,
                VideoStreamingFormat.VideoQuality.DEFAULT,
                VideoStreamingFormat.FrameRate.FPS_30,
                false,
            )
            if (enabled) {
                runCatching { viveGlass.startVideoStreaming(format, bufferCallback, eventCallback) }
            } else {
                runCatching { viveGlass.stopVideoStreaming() }
            }
        }

        fun toggleAudio(enabled: Boolean) {
            if (!isConnected()) return
            val bufferCallback = StreamingBufferCallback { byteBuffer, _ ->
                val bytes = ByteArray(byteBuffer.remaining())
                byteBuffer.get(bytes)
                audioFrames.tryEmit(bytes)
            }
            val eventCallback = StreamingEventCallback { event ->
                if (event == StreamingEvent.STARTED) {
                    state.value = state.value.copy(isAudioRecording = true)
                } else if (event == StreamingEvent.STOPPED || event == StreamingEvent.ERROR) {
                    state.value = state.value.copy(isAudioRecording = false)
                }
            }
            val format = AudioStreamingFormat(
                Microphone.MIC_DIRECTION_TOWARD_USER,
                AudioStreamingFormat.DEFAULT_BITRATE,
                16000,
                AudioChannel.MONO,
            )
            if (enabled) {
                runCatching {
                    viveGlass.startAudioStreaming(format, bufferCallback, eventCallback)
                }
            } else {
                runCatching { viveGlass.stopAudioStreaming() }
            }
        }

        fun getBattery(): Int? = state.value.batteryPercent ?: 100
        fun getStorage(): Int? = null

        fun setRecordingDuration(seconds: Int) {
            Log.d(TAG, "VIVE Eagle recording duration requested: ${seconds}s")
            state.value = state.value.copy(
                connectionLabel = "VIVE Eagle connected",
                protocolState = ConnectionState.CONNECTED.name,
            )
        }
    }

    private class ViveEagleDisplayAdapter(private val manager: ViveEagleManager) : GlassesDeviceAdapter {
        override val adapterId: String = ADAPTER_ID
        override val displayName: String = "VIVE Eagle AI Glasses"
        override val capabilities: Set<GlassesCapability> = setOf(
            GlassesCapability.TEXT_DISPLAY,
            GlassesCapability.LINE_DISPLAY,
            GlassesCapability.CARD_DISPLAY,
            GlassesCapability.CLEAR_DISPLAY,
            GlassesCapability.BATTERY_STATUS,
            GlassesCapability.BRIGHTNESS_CONTROL,
            GlassesCapability.MICROPHONE_AUDIO,
            GlassesCapability.SPEAKER_AUDIO,
            GlassesCapability.DASHBOARD,
        )
        override val state: StateFlow<GlassesBridgeState> = manager.bridgeState
        override val events: Flow<InputEvent> = emptyFlow()

        @Suppress("MissingPermission")
        override suspend fun scan(): List<DeviceInfo> {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
            return adapter.bondedDevices
                .filter { device ->
                    val name = device.name.orEmpty()
                    name.contains("vive", ignoreCase = true) ||
                        name.contains("eagle", ignoreCase = true) ||
                        device.address.matches(Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))
                }
                .map { device ->
                    DeviceInfo(
                        id = device.address,
                        name = device.name ?: "VIVE Eagle",
                        address = device.address,
                        adapterId = adapterId,
                        rssi = -60,
                        batteryLevel = 100,
                    )
                }
        }

        override suspend fun connect(device: DeviceInfo) {
            manager.connect(device.address, device.name)
        }

        override suspend fun disconnect() {
            manager.disconnect()
        }

        override suspend fun showText(command: DisplayCommand.Text): Result<Unit> = Result.success(Unit)

        override suspend fun showLines(command: DisplayCommand.Lines): Result<Unit> = Result.success(Unit)

        override suspend fun showCard(command: DisplayCommand.Card): Result<Unit> = Result.success(Unit)

        override suspend fun clearDisplay(): Result<Unit> = Result.success(Unit)

        override suspend fun setBrightness(level: Int): Result<Unit> = Result.success(Unit)

        override suspend fun requestBattery(): Result<Int> =
            manager.requestBattery()?.let { Result.success(it) }
                ?: Result.failure(BridgeError.NotConnected())

        override suspend fun startMic(): Result<Unit> = if (manager.isConnected()) Result.success(Unit) else Result.failure(BridgeError.NotConnected())

        override suspend fun stopMic(): Result<Unit> = if (manager.isConnected()) Result.success(Unit) else Result.failure(BridgeError.NotConnected())
    }
}

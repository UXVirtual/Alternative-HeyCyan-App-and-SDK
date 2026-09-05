package com.fersaiyan.cyanbridge.devices.metarayban

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MetaRaybanManager private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: MetaRaybanManager? = null

        fun getInstance(context: Context): MetaRaybanManager {
            return instance ?: synchronized(this) {
                instance ?: MetaRaybanManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    private val _registrationState = MutableStateFlow(RegistrationState.UNAVAILABLE)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()
    private val _availableDeviceCount = MutableStateFlow(0)
    val availableDeviceCount: StateFlow<Int> = _availableDeviceCount.asStateFlow()
    private val _selectedDeviceName = MutableStateFlow<String?>(null)
    val selectedDeviceName: StateFlow<String?> = _selectedDeviceName.asStateFlow()
    private val _selectedDeviceIsDisplayCapable = MutableStateFlow(false)
    val selectedDeviceIsDisplayCapable: StateFlow<Boolean> = _selectedDeviceIsDisplayCapable.asStateFlow()
    private val _deviceSessionState = MutableStateFlow(DeviceSessionState.IDLE)
    val deviceSessionState: StateFlow<DeviceSessionState> = _deviceSessionState.asStateFlow()
    private val _streamState = MutableStateFlow(StreamState.STOPPED)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    private val _metaAccessState = MutableStateFlow(MetaAccessState.UNKNOWN)
    val metaAccessState: StateFlow<MetaAccessState> = _metaAccessState.asStateFlow()
    private val _isDisplayActive = MutableStateFlow(false)
    val isDisplayActive: StateFlow<Boolean> = _isDisplayActive.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    val lastCapturedPhoto: StateFlow<CapturedPhoto?> = MutableStateFlow(null)

    fun initialize() {
        _isInitialized.value = false
        _registrationState.value = RegistrationState.UNAVAILABLE
        _availableDeviceCount.value = 0
        _selectedDeviceName.value = null
        _selectedDeviceIsDisplayCapable.value = false
        _deviceSessionState.value = DeviceSessionState.IDLE
        _streamState.value = StreamState.STOPPED
        _isDisplayActive.value = false
        _lastError.value = "Meta Wearables support is disabled because no GitHub token is configured."
    }

    fun refreshRegistrationState() = Unit
    fun handleRegistrationCallback(intent: android.content.Intent): Boolean = false
    fun startRegistration(activity: Activity) = Unit
    fun startUnregistration(activity: Activity) = Unit
    fun startSession(onSuccess: () -> Unit, onError: (String) -> Unit) = onError("Meta Wearables support is disabled")
    fun stopSession() = Unit
    fun startStreaming(onFrame: (Bitmap) -> Unit, onSuccess: () -> Unit, onError: (String) -> Unit) = onError("Meta Wearables support is disabled")
    fun stopStreaming() = Unit
    fun capturePhoto(onSuccess: (CapturedPhoto) -> Unit, onError: (String) -> Unit) = onError("Meta Wearables support is disabled")
    suspend fun capturePhotoOnce(timeoutMs: Long = 20_000L): CapturedPhoto = throw IllegalStateException("Meta Wearables support is disabled")
    suspend fun savePhotoForProcessing(photo: CapturedPhoto, namePrefix: String): java.io.File = throw IllegalStateException("Meta Wearables support is disabled")
    fun startDisplay(onSuccess: () -> Unit, onError: (String) -> Unit) = onError("Meta Wearables support is disabled")
    fun stopDisplay() = Unit
    fun checkCameraPermission(onGranted: () -> Unit, onRequestNeeded: () -> Unit, onError: (String) -> Unit) = onRequestNeeded()
    fun isRegistered(): Boolean = false
    fun isCameraReady(): Boolean = false
    suspend fun awaitCameraReady(timeoutMs: Long = 10_000L): Boolean = false
    fun installedMetaAiPackageName(): String? = null
    fun isMetaAiInstalled(): Boolean = false
    fun registrationGuidance(): String? = "Meta Wearables support is disabled because no GitHub token is configured."
    fun diagnosticsSnapshot(): String = "Meta Wearables support is disabled because no GitHub token is configured."
    fun reportExternalError(operation: String, message: String): String = message
    fun destroy() = Unit

    data class CapturedPhoto(
        val bytes: ByteArray,
        val mimeType: String,
        val uri: Uri?,
    )

    enum class RegistrationState {
        UNAVAILABLE,
        AVAILABLE,
        REGISTERED,
        REGISTERING,
        UNREGISTERING,
    }

    enum class DeviceSessionState {
        IDLE,
        STARTING,
        STARTED,
        PAUSED,
        STOPPING,
        STOPPED,
    }

    enum class StreamState {
        STOPPED,
        STARTING,
        STARTED,
        STREAMING,
        STOPPING,
        PAUSED,
        CLOSED,
    }
}

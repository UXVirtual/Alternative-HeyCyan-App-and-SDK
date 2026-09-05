package com.meta.wearable.dat.core

import android.app.Activity
import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object Wearables {
    val registrationState: StateFlow<RegistrationState> = MutableStateFlow<RegistrationState>(RegistrationState.UNAVAILABLE).asStateFlow()
    val registrationErrorStream: StateFlow<Throwable> = MutableStateFlow<Throwable>(UnsupportedOperationException("Meta Wearables support is disabled.")).asStateFlow()
    val devices: StateFlow<Set<DeviceIdentifier>> = MutableStateFlow<Set<DeviceIdentifier>>(emptySet()).asStateFlow()
    val devicesMetadata: Map<DeviceIdentifier, StateFlow<Device>> = emptyMap()
    const val isDevMode: Boolean = false

    fun initialize(context: Context): Result<Unit> = Result.success(Unit)
    fun startRegistration(activity: Activity) = Unit
    fun startUnregistration(activity: Activity) = Unit
    fun createSession(selector: Any): Result<DeviceSession> = Result.failure(UnsupportedOperationException("Meta Wearables support is disabled."))
    fun checkPermissionStatus(permission: Permission): Result<PermissionStatus> = Result.success(PermissionStatus.Denied)

    class RequestPermissionContract : ActivityResultContract<Permission, Map<PermissionStatus, PermissionStatus>>() {
        override fun createIntent(context: Context, input: Permission): android.content.Intent = android.content.Intent()
        override fun parseResult(resultCode: Int, intent: android.content.Intent?): Map<PermissionStatus, PermissionStatus> = emptyMap()
    }
}

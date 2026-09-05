package com.meta.wearable.dat.core.types

@JvmInline
value class DeviceIdentifier(val value: String) {
    override fun toString(): String = value
}

enum class DeviceCompatibility {
    DEVICE_UPDATE_REQUIRED,
    SDK_UPDATE_REQUIRED,
    COMPATIBLE,
}

data class Device(
    val name: String = "",
    val compatibility: DeviceCompatibility = DeviceCompatibility.COMPATIBLE,
    val displayCapable: Boolean = false,
) {
    fun isDisplayCapable(): Boolean = displayCapable
}

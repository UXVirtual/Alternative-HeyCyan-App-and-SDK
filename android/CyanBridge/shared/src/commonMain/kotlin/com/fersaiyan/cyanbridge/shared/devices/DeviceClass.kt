package com.fersaiyan.cyanbridge.shared.devices

enum class DeviceClass {
    HEY_CYAN,
    EYEVUE,
    TUNEBUDS,
    VIVE_EAGLE,
    META_RAYBAN,
    MEIZU_MYVU,
    GENERIC_AUDIO,
    UNKNOWN;

    fun displayName(): String = when (this) {
        HEY_CYAN -> "HeyCyan"
        EYEVUE -> "Eyevue"
        TUNEBUDS -> "TuneBuds / AB Mate"
        VIVE_EAGLE -> "VIVE Eagle AI Glasses"
        META_RAYBAN -> "Meta Rayban"
        MEIZU_MYVU -> "Meizu MYVU / Star Air"
        GENERIC_AUDIO -> "Earbuds / Audio-only glasses"
        UNKNOWN -> "Unknown"
    }
}

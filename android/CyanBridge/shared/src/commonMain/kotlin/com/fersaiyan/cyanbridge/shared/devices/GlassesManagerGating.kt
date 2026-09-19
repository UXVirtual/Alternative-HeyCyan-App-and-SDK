package com.fersaiyan.cyanbridge.shared.devices

object GlassesManagerGating {

    enum class Action {
        MEETING_CAPTURE,
        STATUS_BATTERY,
        STATUS_STORAGE,
        HEY_CYAN_EXTRAS,
        META_RAYBAN_CONTROLS,
        META_RAYBAN_REGISTRATION,
        MEIZU_MYVU_CONTROLS,
        EYEVUE_CONTROLS,
        TUNEBUDS_CONTROLS,
        VIVE_EAGLE_CONTROLS,
        CAPTURE_SETTINGS,
        AI_WAKE_WORD_ROUTING,
        MEDIA_SYNC,
        ADVANCED_CONTROLS,
        ADVANCED_LOCAL_AGENT,
        ADVANCED_DEVICE_INFO,
        ADVANCED_DEVICE_VOLUME,
        ADVANCED_IMAGE_QUALITY,
        ADVANCED_DEVELOPER_TOOLS,
        ADVANCED_OTA,
        WIFI_ADB_DEBUG,
    }

    data class UiModel(
        val visibleActions: Set<Action>,
    ) {
        fun isVisible(action: Action): Boolean = visibleActions.contains(action)
    }

    fun uiModel(profile: DeviceProfile?): UiModel = UiModel(visibleActions(profile))

    fun visibleActions(profile: DeviceProfile?): Set<Action> {
        val selected = profile?.selectedClass ?: DeviceClass.UNKNOWN
        val actions = visibleActions(selected).toMutableSet()
        if (profile != null && isUnsupportedMediaSyncModel(profile)) {
            actions.remove(Action.MEDIA_SYNC)
        }
        return actions
    }

    private fun isUnsupportedMediaSyncModel(profile: DeviceProfile): Boolean {
        val advertisedName = profile.advertisedName?.trim().orEmpty()
        val lower = advertisedName.lowercase()
        val explicitUnsupportedId = lower == "anko43700141"
        return profile.selectedClass == DeviceClass.HEY_CYAN &&
            (explicitUnsupportedId || lower.startsWith("q_") || lower.startsWith("o_"))
    }

    fun visibleActions(deviceClass: DeviceClass): Set<Action> {
        val base = linkedSetOf(Action.MEETING_CAPTURE)
        when (deviceClass) {
            DeviceClass.HEY_CYAN -> {
                base.add(Action.HEY_CYAN_EXTRAS)
                base.add(Action.STATUS_BATTERY)
                base.add(Action.STATUS_STORAGE)
                base.add(Action.MEDIA_SYNC)
                base.addAll(heyCyanAdvancedActions)
                base.add(Action.CAPTURE_SETTINGS)
                base.add(Action.AI_WAKE_WORD_ROUTING)
                base.add(Action.WIFI_ADB_DEBUG)
            }
            DeviceClass.META_RAYBAN -> {
                base.add(Action.META_RAYBAN_CONTROLS)
                base.add(Action.META_RAYBAN_REGISTRATION)
            }
            DeviceClass.MEIZU_MYVU -> {
                base.add(Action.MEIZU_MYVU_CONTROLS)
                base.add(Action.STATUS_BATTERY)
            }
            DeviceClass.EYEVUE -> {
                base.add(Action.EYEVUE_CONTROLS)
                base.add(Action.STATUS_BATTERY)
                base.add(Action.STATUS_STORAGE)
                base.addAll(heyCyanAdvancedActions)
                base.add(Action.CAPTURE_SETTINGS)
                base.add(Action.AI_WAKE_WORD_ROUTING)
            }
            DeviceClass.TUNEBUDS -> {
                base.add(Action.TUNEBUDS_CONTROLS)
                base.add(Action.STATUS_BATTERY)
                base.add(Action.STATUS_STORAGE)
                base.add(Action.ADVANCED_CONTROLS)
                base.add(Action.ADVANCED_DEVICE_INFO)
                base.add(Action.ADVANCED_IMAGE_QUALITY)
            }
            DeviceClass.VIVE_EAGLE -> {
                base.add(Action.VIVE_EAGLE_CONTROLS)
                base.add(Action.STATUS_BATTERY)
                base.add(Action.STATUS_STORAGE)
                base.add(Action.ADVANCED_CONTROLS)
                base.add(Action.ADVANCED_DEVICE_INFO)
                base.add(Action.ADVANCED_IMAGE_QUALITY)
                base.add(Action.CAPTURE_SETTINGS)
                base.add(Action.AI_WAKE_WORD_ROUTING)
            }
            else -> {}
        }
        return base
    }

    private val heyCyanAdvancedActions = setOf(
        Action.ADVANCED_CONTROLS,
        Action.ADVANCED_DEVICE_INFO,
        Action.ADVANCED_DEVICE_VOLUME,
        Action.ADVANCED_IMAGE_QUALITY,
        Action.ADVANCED_DEVELOPER_TOOLS,
        Action.ADVANCED_OTA,
    )
}

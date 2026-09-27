package com.fersaiyan.cyanbridge.shared.navigation

import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.icons.AppIcon

val AppDestination.label: String
    get() = when (this) {
        AppDestination.GLASSES -> "Glasses"
        AppDestination.MODEL_CAPTURE -> "3D Capture"
        AppDestination.CHATS -> "Chats"
        AppDestination.MEDIA -> "Media"
        AppDestination.PLUGINS -> "Plugins"
        AppDestination.SETTINGS -> "Settings"
    }

val AppDestination.icon: AppIcon
    get() = when (this) {
        AppDestination.GLASSES -> AppIcon.Glasses
        AppDestination.MODEL_CAPTURE -> AppIcon.Model
        AppDestination.CHATS -> AppIcon.Chat
        AppDestination.MEDIA -> AppIcon.Recordings
        AppDestination.PLUGINS -> AppIcon.Plugins
        AppDestination.SETTINGS -> AppIcon.Settings
    }

fun availableDestinations(deviceClass: DeviceClass): List<AppDestination> =
    if (deviceClass == DeviceClass.HEY_CYAN) {
        AppDestination.entries
    } else {
        AppDestination.entries.filterNot { it == AppDestination.MODEL_CAPTURE }
    }

fun compactPrimaryDestinations(deviceClass: DeviceClass): List<AppDestination> =
    if (deviceClass == DeviceClass.HEY_CYAN) {
        listOf(
            AppDestination.GLASSES,
            AppDestination.MODEL_CAPTURE,
            AppDestination.CHATS,
            AppDestination.MEDIA,
        )
    } else {
        availableDestinations(deviceClass)
    }

fun compactOverflowDestinations(deviceClass: DeviceClass): List<AppDestination> =
    availableDestinations(deviceClass) - compactPrimaryDestinations(deviceClass).toSet()

fun compactNavigationItemCount(deviceClass: DeviceClass): Int =
    compactPrimaryDestinations(deviceClass).size +
        if (compactOverflowDestinations(deviceClass).isNotEmpty()) 1 else 0

fun isDestinationAvailable(destination: AppDestination, deviceClass: DeviceClass): Boolean =
    destination in availableDestinations(deviceClass)

fun destinationFromNameOrDefault(
    requestedName: String?,
    deviceClass: DeviceClass,
): AppDestination {
    val requested = requestedName
        ?.let { name -> AppDestination.entries.firstOrNull { it.name == name } }
        ?: AppDestination.GLASSES
    return requested.takeIf { isDestinationAvailable(it, deviceClass) }
        ?: AppDestination.GLASSES
}

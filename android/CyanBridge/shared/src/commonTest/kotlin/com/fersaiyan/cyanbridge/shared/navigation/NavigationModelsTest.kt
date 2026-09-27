package com.fersaiyan.cyanbridge.shared.navigation

import com.fersaiyan.cyanbridge.shared.icons.AppIcon
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NavigationModelsTest {

    @Test
    fun allDestinationsHaveLabels() {
        AppDestination.entries.forEach { destination ->
            assertNotNull(destination.label)
            assertTrue(destination.label.isNotBlank())
        }
    }

    @Test
    fun allDestinationsHaveIcons() {
        AppDestination.entries.forEach { destination ->
            assertNotNull(destination.icon)
        }
    }

    @Test
    fun labelsMatchExpectedValues() {
        assertEquals("Glasses", AppDestination.GLASSES.label)
        assertEquals("3D Capture", AppDestination.MODEL_CAPTURE.label)
        assertEquals("Chats", AppDestination.CHATS.label)
        assertEquals("Media", AppDestination.MEDIA.label)
        assertEquals("Plugins", AppDestination.PLUGINS.label)
        assertEquals("Settings", AppDestination.SETTINGS.label)
    }

    @Test
    fun iconsMatchExpectedValues() {
        assertEquals(AppIcon.Glasses, AppDestination.GLASSES.icon)
        assertEquals(AppIcon.Model, AppDestination.MODEL_CAPTURE.icon)
        assertEquals(AppIcon.Chat, AppDestination.CHATS.icon)
        assertEquals(AppIcon.Recordings, AppDestination.MEDIA.icon)
        assertEquals(AppIcon.Plugins, AppDestination.PLUGINS.icon)
        assertEquals(AppIcon.Settings, AppDestination.SETTINGS.icon)
    }

    @Test
    fun exactlySixDestinations() {
        assertEquals(6, AppDestination.entries.size)
    }

    @Test
    fun compactHeyCyanNavigationHasFiveItemsWithPluginsAndSettingsInMore() {
        assertEquals(5, compactNavigationItemCount(DeviceClass.HEY_CYAN))
        assertEquals(
            listOf(AppDestination.PLUGINS, AppDestination.SETTINGS),
            compactOverflowDestinations(DeviceClass.HEY_CYAN),
        )
    }

    @Test
    fun nonHeyCyanNavigationRetainsExistingDestinationsWithoutOverflow() {
        assertEquals(
            listOf(
                AppDestination.GLASSES,
                AppDestination.CHATS,
                AppDestination.MEDIA,
                AppDestination.PLUGINS,
                AppDestination.SETTINGS,
            ),
            compactPrimaryDestinations(DeviceClass.META_RAYBAN),
        )
        assertEquals(emptyList(), compactOverflowDestinations(DeviceClass.META_RAYBAN))
    }

    @Test
    fun destinationNameValidationGatesModelCaptureByProfile() {
        assertEquals(
            AppDestination.MODEL_CAPTURE,
            destinationFromNameOrDefault(AppDestination.MODEL_CAPTURE.name, DeviceClass.HEY_CYAN),
        )
        assertEquals(
            AppDestination.GLASSES,
            destinationFromNameOrDefault(AppDestination.MODEL_CAPTURE.name, DeviceClass.META_RAYBAN),
        )
        assertEquals(
            AppDestination.GLASSES,
            destinationFromNameOrDefault("NOT_A_DESTINATION", DeviceClass.HEY_CYAN),
        )
    }

    private fun assertTrue(condition: Boolean) {
        kotlin.test.assertTrue(condition)
    }
}

package com.fersaiyan.cyanbridge.shared.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination

@Composable
fun CyanBridgeNavShell(
    currentDestination: AppDestination,
    deviceClass: DeviceClass,
    onNavigate: (AppDestination) -> Unit,
    content: @Composable (AppDestination) -> Unit,
) {
    CyanBridgeNavigationSuite(
        currentDestination = currentDestination,
        deviceClass = deviceClass,
        onNavigate = onNavigate,
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            content(currentDestination)
        }
    }
}

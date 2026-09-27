package com.fersaiyan.cyanbridge.shared.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.devices.DeviceClass
import com.fersaiyan.cyanbridge.shared.icons.AppIcon
import com.fersaiyan.cyanbridge.shared.icons.imageVector
import com.fersaiyan.cyanbridge.shared.navigation.AppDestination
import com.fersaiyan.cyanbridge.shared.navigation.availableDestinations
import com.fersaiyan.cyanbridge.shared.navigation.compactOverflowDestinations
import com.fersaiyan.cyanbridge.shared.navigation.compactPrimaryDestinations
import com.fersaiyan.cyanbridge.shared.navigation.icon

@Composable
fun CyanBridgeNavigationBar(
    currentDestination: AppDestination,
    deviceClass: DeviceClass,
    onNavigate: (AppDestination) -> Unit,
) {
    var moreExpanded by remember { mutableStateOf(false) }
    val primaryDestinations = compactPrimaryDestinations(deviceClass)
    val moreDestinations = compactOverflowDestinations(deviceClass)

    NavigationBar {
        primaryDestinations.forEach { destination ->
            NavigationBarItem(
                selected = destination == currentDestination,
                onClick = { onNavigate(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon.imageVector(),
                        contentDescription = null,
                    )
                },
                label = { Text(localizedDestinationLabel(destination)) },
            )
        }
        if (moreDestinations.isNotEmpty()) {
            NavigationBarItem(
                selected = currentDestination in moreDestinations,
                onClick = { moreExpanded = true },
                icon = {
                    Icon(
                        imageVector = AppIcon.More.imageVector(),
                        contentDescription = null,
                    )
                    DropdownMenu(
                        expanded = moreExpanded,
                        onDismissRequest = { moreExpanded = false },
                    ) {
                        moreDestinations.forEach { destination ->
                            DropdownMenuItem(
                                text = { Text(localizedDestinationLabel(destination)) },
                                onClick = {
                                    moreExpanded = false
                                    onNavigate(destination)
                                },
                            )
                        }
                    }
                },
                label = { Text("More") },
            )
        }
    }
}

@Composable
fun CyanBridgeNavigationSuite(
    currentDestination: AppDestination,
    deviceClass: DeviceClass,
    onNavigate: (AppDestination) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            Row(modifier = Modifier.fillMaxSize()) {
                CyanBridgeNavigationRail(
                    currentDestination = currentDestination,
                    deviceClass = deviceClass,
                    onNavigate = onNavigate,
                    modifier = Modifier.fillMaxHeight(),
                )
                content(PaddingValues())
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    CyanBridgeNavigationBar(
                        currentDestination = currentDestination,
                        deviceClass = deviceClass,
                        onNavigate = onNavigate,
                    )
                },
            ) { innerPadding ->
                content(innerPadding)
            }
        }
    }
}

@Composable
private fun CyanBridgeNavigationRail(
    currentDestination: AppDestination,
    deviceClass: DeviceClass,
    onNavigate: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(modifier = modifier) {
        availableDestinations(deviceClass).forEach { destination ->
            NavigationRailItem(
                selected = destination == currentDestination,
                onClick = { onNavigate(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon.imageVector(),
                        contentDescription = null,
                    )
                },
                label = { Text(localizedDestinationLabel(destination)) },
            )
        }
    }
}

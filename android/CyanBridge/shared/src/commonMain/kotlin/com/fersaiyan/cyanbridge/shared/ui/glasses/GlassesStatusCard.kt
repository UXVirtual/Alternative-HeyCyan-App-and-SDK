package com.fersaiyan.cyanbridge.shared.ui.glasses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.shared.generated.resources.Res
import com.fersaiyan.cyanbridge.shared.generated.resources.dashboard_battery
import com.fersaiyan.cyanbridge.shared.generated.resources.dashboard_battery_unknown
import com.fersaiyan.cyanbridge.shared.generated.resources.dashboard_class
import com.fersaiyan.cyanbridge.shared.generated.resources.dashboard_glasses_status
import com.fersaiyan.cyanbridge.shared.generated.resources.dashboard_storage
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import org.jetbrains.compose.resources.stringResource

@Composable
fun GlassesStatusCard(state: GlassesDashboardUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(Res.string.dashboard_glasses_status),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.connectionLabel, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = stringResource(Res.string.dashboard_class, state.deviceClassLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.showBattery || state.showStorage) {
                    Column(horizontalAlignment = Alignment.End) {
                        if (state.showBattery) {
                            Text(
                                text = state.batteryPercent?.let { stringResource(Res.string.dashboard_battery, it) }
                                    ?: stringResource(Res.string.dashboard_battery_unknown),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        if (state.showStorage) {
                            Text(
                                text = stringResource(Res.string.dashboard_storage, state.storageLabel),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
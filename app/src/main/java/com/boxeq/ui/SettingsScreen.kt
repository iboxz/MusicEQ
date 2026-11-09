package com.boxeq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxeq.R
import com.boxeq.engine.EffectKind

@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_shizuku_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = state.config.shizukuEnabled, onCheckedChange = vm::setShizukuEnabled)
                }
                Text(stringResource(R.string.settings_shizuku_desc), style = MaterialTheme.typography.bodySmall)
                val message = when (state.shizuku.kind) {
                    ShizukuKind.OFF -> stringResource(R.string.shizuku_off)
                    ShizukuKind.NOT_INSTALLED -> stringResource(R.string.shizuku_not_installed)
                    ShizukuKind.NOT_RUNNING -> stringResource(R.string.shizuku_not_running)
                    ShizukuKind.NEEDS_PERMISSION -> stringResource(R.string.shizuku_needs_permission)
                    ShizukuKind.READY -> stringResource(R.string.shizuku_ready)
                    ShizukuKind.API_FAILED -> stringResource(R.string.shizuku_api_failed, state.shizuku.detail ?: "")
                }
                Text(message, color = MaterialTheme.colorScheme.primary)
                when (state.shizuku.kind) {
                    ShizukuKind.NOT_INSTALLED -> Button(onClick = vm::openShizukuDownload) { Text(stringResource(R.string.btn_get_shizuku)) }
                    ShizukuKind.NOT_RUNNING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::openShizukuApp) { Text(stringResource(R.string.btn_open_shizuku)) }
                        OutlinedButton(onClick = vm::refreshShizuku) { Text(stringResource(R.string.btn_refresh)) }
                    }
                    ShizukuKind.NEEDS_PERMISSION -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::requestShizukuPermission) { Text(stringResource(R.string.btn_grant_permission)) }
                        OutlinedButton(onClick = vm::openShizukuApp) { Text(stringResource(R.string.btn_open_shizuku)) }
                    }
                    else -> Unit
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_engine_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        when (state.runtime.effectKind) {
                            EffectKind.DYNAMICS -> R.string.engine_dynamics
                            EffectKind.LEGACY -> R.string.engine_legacy
                            null -> R.string.engine_none
                        },
                    ),
                )
                Text(stringResource(R.string.engine_sessions, state.runtime.activeEffects), style = MaterialTheme.typography.bodySmall)
            }
        }

        OutlinedButton(onClick = { vm.setOnboardingDone(false) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_show_intro))
        }
    }
}

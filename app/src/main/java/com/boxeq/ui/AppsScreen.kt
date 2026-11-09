@file:OptIn(ExperimentalMaterial3Api::class)

package com.boxeq.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxeq.R
import com.boxeq.data.AppDirectory
import com.boxeq.data.AppProfile
import com.boxeq.data.PresetNames
import com.boxeq.engine.AppStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun statusText(status: AppStatus): String = stringResource(
    when (status) {
        AppStatus.CONNECTED -> R.string.status_connected
        AppStatus.NO_SESSION_EXPOSED -> R.string.status_no_session
        AppStatus.NEEDS_SHIZUKU -> R.string.status_needs_shizuku
        AppStatus.WAITING -> R.string.status_waiting
        AppStatus.DISABLED -> R.string.status_disabled
    },
)

@Composable
private fun statusColor(status: AppStatus): Color = when (status) {
    AppStatus.CONNECTED -> Color(0xFF2E9E5B)
    AppStatus.NEEDS_SHIZUKU -> MaterialTheme.colorScheme.tertiary
    AppStatus.NO_SESSION_EXPOSED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun rememberAppIcon(packageName: String): ImageBitmap? {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            AppDirectory.iconOf(context, packageName)?.toBitmap(96, 96)?.asImageBitmap()
        }
    }
    return icon
}

@Composable
fun AppsScreen(
    state: UiState,
    vm: MainViewModel,
    onEditEq: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAdd by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Button(onClick = { vm.loadInstalledApps(); showAdd = true }, modifier = Modifier.padding(vertical = 8.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.apps_add))
        }
        if (state.config.profiles.isEmpty()) {
            Text(stringResource(R.string.apps_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.config.profiles, key = { it.packageName }) { profile ->
                AppCard(
                    profile = profile,
                    status = state.runtime.statuses[profile.packageName] ?: AppStatus.WAITING,
                    state = state,
                    vm = vm,
                    onEditEq = onEditEq,
                )
            }
            item { Spacer(Modifier.padding(8.dp)) }
        }
    }

    if (showAdd) AddAppDialog(state, vm) { showAdd = false }
}

@Composable
private fun AppCard(
    profile: AppProfile,
    status: AppStatus,
    state: UiState,
    vm: MainViewModel,
    onEditEq: (String) -> Unit,
) {
    val context = LocalContext.current
    val icon = rememberAppIcon(profile.packageName)
    var menu by remember { mutableStateOf(false) }
    val presetName = PresetNames.display(context, state.config.preset(profile.presetId))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(40.dp))
                else Spacer(Modifier.size(40.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(AppDirectory.labelOf(context, profile.packageName), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(statusText(status), color = statusColor(status), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = profile.enabled, onCheckedChange = { vm.setAppEnabled(profile.packageName, it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    TextButton(onClick = { menu = true }) { Text(stringResource(R.string.apps_preset_label, presetName)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        state.config.allPresets().forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(PresetNames.display(context, preset)) },
                                onClick = { vm.setAppPreset(profile.packageName, preset.id); menu = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { onEditEq(profile.packageName) }) { Text(stringResource(R.string.apps_edit_eq)) }
                IconButton(onClick = { vm.removeApp(profile.packageName) }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.apps_remove))
                }
            }
        }
    }
}

@Composable
private fun AddAppDialog(state: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
    val apps by vm.installedApps.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val taken = state.config.profiles.map { it.packageName }.toSet()
    val shown = apps.filter { it.packageName !in taken && it.label.contains(query, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.apps_search)) }, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(shown, key = { it.packageName }) { app ->
                        Text(
                            app.label,
                            Modifier.fillMaxWidth().clickable { vm.addApp(app.packageName); onDismiss() }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

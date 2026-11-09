@file:OptIn(ExperimentalMaterial3Api::class)

package com.boxeq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import com.boxeq.R
import com.boxeq.data.AppDirectory
import com.boxeq.data.BuiltInPresets
import com.boxeq.data.PresetNames
import com.boxeq.dsp.BandMapping
import java.util.Locale

private fun dbLabel(db: Float): String =
    if (db % 1f == 0f) String.format(Locale.US, "%+d", db.toInt()) else String.format(Locale.US, "%+.1f", db)

@Composable
fun EqualizerScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pkg = state.selectedPackage
    if (pkg == null) {
        Text(
            stringResource(R.string.eq_no_apps),
            modifier = modifier.fillMaxWidth().padding(24.dp),
            textAlign = TextAlign.Center,
        )
        return
    }
    var showSave by remember { mutableStateOf(false) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.eq_select_app), style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.config.profiles, key = { it.packageName }) { p ->
                FilterChip(
                    selected = p.packageName == pkg,
                    onClick = { vm.selectApp(p.packageName) },
                    label = { Text(AppDirectory.labelOf(context, p.packageName)) },
                )
            }
        }

        Text(stringResource(R.string.eq_preset), style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.config.allPresets(), key = { it.id }) { preset ->
                FilterChip(
                    selected = preset.id == state.activePreset.id,
                    onClick = { vm.setPreset(preset.id) },
                    label = { Text(PresetNames.display(context, preset)) },
                )
            }
        }

        // Frequency axes read low-to-high from the left in every language, so keep these LTR even in RTL locales.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.eq_curve_title), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    ResponseCurveView(state.gains)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp)) {
                    state.gains.forEachIndexed { i, gain ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(dbLabel(gain), fontSize = 10.sp, maxLines = 1)
                            VerticalBandSlider(
                                value = gain,
                                onValueChange = { vm.onBandChange(i, it) },
                                description = stringResource(R.string.band_description, BandMapping.label(i)),
                                modifier = Modifier.width(32.dp).height(190.dp),
                            )
                            Text(BandMapping.label(i), fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.eq_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.eq_preamp), style = MaterialTheme.typography.labelLarge)
                    Text(dbLabel(state.preampDb) + " dB")
                }
                Slider(
                    value = state.preampDb,
                    onValueChange = { vm.onPreampChange(BandMapping.snapGain(it)) },
                    valueRange = BandMapping.MIN_PREAMP_DB..BandMapping.MAX_PREAMP_DB,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.eq_limiter), Modifier.weight(1f))
                    Switch(checked = state.config.limiterEnabled, onCheckedChange = vm::setLimiter)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::resetBands) { Text(stringResource(R.string.eq_reset)) }
            Button(onClick = { showSave = true }) { Text(stringResource(R.string.eq_save_preset)) }
            if (!BuiltInPresets.isBuiltIn(state.activePreset.id)) {
                TextButton(onClick = vm::deleteActivePreset) { Text(stringResource(R.string.eq_delete_preset)) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (showSave) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text(stringResource(R.string.eq_save_preset)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.eq_preset_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { vm.saveAsPreset(name); showSave = false },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSave = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

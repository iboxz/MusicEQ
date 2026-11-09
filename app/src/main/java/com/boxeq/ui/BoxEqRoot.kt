@file:OptIn(ExperimentalMaterial3Api::class)

package com.boxeq.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boxeq.R
import com.boxeq.service.EqService

@Composable
fun BoxEqRoot(vm: MainViewModel = viewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    val context = LocalContext.current

    // The activity is visible here, so starting the foreground service is always allowed.
    LaunchedEffect(state.runtime.loaded, state.config.onboardingDone, state.config.masterEnabled) {
        if (state.runtime.loaded && state.config.onboardingDone && state.config.masterEnabled) {
            runCatching { EqService.start(context) }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        when {
            !state.runtime.loaded -> Unit // wait for the first DataStore read; avoids flashing onboarding
            !state.config.onboardingDone -> OnboardingScreen(onStart = { vm.setOnboardingDone(true) })
            else -> Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.app_name)) },
                        actions = {
                            Switch(
                                checked = state.config.masterEnabled,
                                onCheckedChange = vm::setMaster,
                                modifier = Modifier.padding(end = 16.dp),
                            )
                        },
                    )
                },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(painterResource(R.drawable.ic_tab_eq), contentDescription = null) },
                            label = { Text(stringResource(R.string.tab_equalizer)) },
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1 },
                            icon = { Icon(painterResource(R.drawable.ic_tab_apps), contentDescription = null) },
                            label = { Text(stringResource(R.string.tab_apps)) },
                        )
                        NavigationBarItem(
                            selected = tab == 2, onClick = { tab = 2 },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text(stringResource(R.string.tab_settings)) },
                        )
                    }
                },
            ) { padding ->
                val content = Modifier.padding(padding)
                when (tab) {
                    0 -> EqualizerScreen(state, vm, content)
                    1 -> AppsScreen(state, vm, onEditEq = { vm.selectApp(it); tab = 0 }, modifier = content)
                    else -> SettingsScreen(state, vm, content)
                }
            }
        }
    }
}

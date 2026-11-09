package com.boxeq.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boxeq.R
import com.boxeq.data.AppDirectory
import com.boxeq.data.AppProfile
import com.boxeq.data.BuiltInPresets
import com.boxeq.data.EqConfig
import com.boxeq.data.EqPreset
import com.boxeq.data.InstalledApp
import com.boxeq.data.PresetNames
import com.boxeq.dsp.BandMapping
import com.boxeq.engine.EngineRuntime
import com.boxeq.graph
import com.boxeq.service.EqService
import com.boxeq.shizuku.MonitorState
import com.boxeq.shizuku.ShizukuAvailability
import com.boxeq.shizuku.ShizukuController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class ShizukuKind { OFF, NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, READY, API_FAILED }

data class ShizukuUi(val kind: ShizukuKind = ShizukuKind.OFF, val detail: String? = null)

data class UiState(
    val config: EqConfig = EqConfig(),
    val runtime: EngineRuntime = EngineRuntime(),
    val shizuku: ShizukuUi = ShizukuUi(),
    val selectedPackage: String? = null,
    val gains: List<Float> = List(BandMapping.BAND_COUNT) { 0f },
    val preampDb: Float = 0f,
    val activePreset: EqPreset = BuiltInPresets.all.first(),
)

/** Unsaved slider edits, shown immediately and persisted after a short debounce. */
private data class Draft(
    val packageName: String,
    val presetId: String,
    val presetName: String,
    val gains: List<Float>,
    val preampDb: Float,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    private val repository = graph.repository
    private val engine = graph.engine

    private val selected = MutableStateFlow<String?>(null)
    private val draft = MutableStateFlow<Draft?>(null)
    private var persistJob: Job? = null

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val shizukuUi: StateFlow<ShizukuUi> = combine(
        engine.config, graph.shizuku.availability, graph.monitor.state,
    ) { cfg, availability, monitor ->
        when {
            !cfg.shizukuEnabled -> ShizukuUi(ShizukuKind.OFF)
            availability == ShizukuAvailability.NOT_INSTALLED -> ShizukuUi(ShizukuKind.NOT_INSTALLED)
            availability == ShizukuAvailability.NOT_RUNNING -> ShizukuUi(ShizukuKind.NOT_RUNNING)
            availability == ShizukuAvailability.NEEDS_PERMISSION -> ShizukuUi(ShizukuKind.NEEDS_PERMISSION)
            monitor is MonitorState.Failed -> ShizukuUi(ShizukuKind.API_FAILED, monitor.reason)
            else -> ShizukuUi(ShizukuKind.READY)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ShizukuUi())

    val uiState: StateFlow<UiState> = combine(
        engine.config, engine.runtime, shizukuUi, selected, draft,
    ) { cfg, runtime, shizuku, sel, d ->
        val pkg = sel?.takeIf { s -> cfg.profiles.any { it.packageName == s } }
            ?: cfg.profiles.firstOrNull()?.packageName
        val profile = pkg?.let { cfg.profile(it) }
        val preset = cfg.preset(profile?.presetId ?: BuiltInPresets.FLAT)
        val activeDraft = d?.takeIf { it.packageName == pkg }
        UiState(
            config = cfg,
            runtime = runtime,
            shizuku = shizuku,
            selectedPackage = pkg,
            gains = activeDraft?.gains ?: BandMapping.sanitize(preset.gains),
            preampDb = activeDraft?.preampDb ?: profile?.preampDb ?: 0f,
            activePreset = activeDraft?.let { EqPreset(it.presetId, it.presetName, it.gains) } ?: preset,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        // Drop the draft once the stored config has caught up with it, so the UI never flickers back.
        viewModelScope.launch {
            combine(engine.config, draft) { cfg, d -> cfg to d }.collect { (cfg, d) ->
                if (d != null) {
                    val profile = cfg.profile(d.packageName)
                    val stored = cfg.userPresets.firstOrNull { it.id == d.presetId }
                    if (profile != null && profile.presetId == d.presetId &&
                        profile.preampDb == d.preampDb && stored?.gains == d.gains
                    ) draft.compareAndSet(d, null)
                }
            }
        }
    }

    // ---- Equalizer editing ----

    fun selectApp(packageName: String) {
        flushDraftNow()
        selected.value = packageName
    }

    fun setPreset(presetId: String) {
        val pkg = uiState.value.selectedPackage ?: return
        cancelDraft()
        updateProfile(pkg) { it.copy(presetId = presetId) }
    }

    fun onBandChange(index: Int, gainDb: Float) = editDraft { it.copy(gains = it.gains.toMutableList().also { g -> g[index] = BandMapping.snapGain(gainDb) }) }

    fun resetBands() = editDraft { it.copy(gains = List(BandMapping.BAND_COUNT) { 0f }) }

    fun onPreampChange(db: Float) = editDraft { it.copy(preampDb = BandMapping.clampPreamp(db)) }

    private fun editDraft(change: (Draft) -> Draft) {
        val state = uiState.value
        val pkg = state.selectedPackage ?: return
        val base = draft.value?.takeIf { it.packageName == pkg } ?: startDraft(state, pkg)
        draft.value = change(base)
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            persistDraft()
        }
    }

    /** Built-in presets are read-only: the first edit forks them into a user preset. */
    private fun startDraft(state: UiState, pkg: String): Draft {
        val preset = state.activePreset
        return if (preset.builtIn || BuiltInPresets.isBuiltIn(preset.id)) {
            val shown = PresetNames.display(getApplication(), preset)
            Draft(pkg, UUID.randomUUID().toString(), getApplication<Application>().getString(R.string.preset_custom_suffix, shown),
                state.gains, state.preampDb)
        } else {
            Draft(pkg, preset.id, preset.name, state.gains, state.preampDb)
        }
    }

    private fun flushDraftNow() {
        persistJob?.cancel()
        if (draft.value != null) viewModelScope.launch { persistDraft() }
    }

    private fun cancelDraft() {
        persistJob?.cancel()
        draft.value = null
    }

    private suspend fun persistDraft() {
        val d = draft.value ?: return
        repository.update { cfg ->
            val exists = cfg.userPresets.any { it.id == d.presetId }
            val presets = if (exists) cfg.userPresets.map { if (it.id == d.presetId) it.copy(gains = d.gains) else it }
            else cfg.userPresets + EqPreset(d.presetId, d.presetName, d.gains)
            cfg.copy(
                userPresets = presets,
                profiles = cfg.profiles.map {
                    if (it.packageName == d.packageName) it.copy(presetId = d.presetId, preampDb = d.preampDb) else it
                },
            )
        }
    }

    fun saveAsPreset(name: String) {
        val state = uiState.value
        val pkg = state.selectedPackage ?: return
        val clean = name.trim().ifEmpty { return }
        cancelDraft()
        val preset = EqPreset(UUID.randomUUID().toString(), clean, state.gains)
        viewModelScope.launch {
            repository.update { cfg ->
                cfg.copy(
                    userPresets = cfg.userPresets + preset,
                    profiles = cfg.profiles.map {
                        if (it.packageName == pkg) it.copy(presetId = preset.id, preampDb = state.preampDb) else it
                    },
                )
            }
        }
    }

    fun deleteActivePreset() {
        val id = uiState.value.activePreset.id
        if (BuiltInPresets.isBuiltIn(id)) return
        cancelDraft()
        viewModelScope.launch {
            repository.update { cfg ->
                cfg.copy(
                    userPresets = cfg.userPresets.filterNot { it.id == id },
                    profiles = cfg.profiles.map { if (it.presetId == id) it.copy(presetId = BuiltInPresets.FLAT) else it },
                )
            }
        }
    }

    // ---- Apps ----

    fun setAppEnabled(packageName: String, enabled: Boolean) = updateProfile(packageName) { it.copy(enabled = enabled) }

    fun setAppPreset(packageName: String, presetId: String) {
        if (draft.value?.packageName == packageName) cancelDraft()
        updateProfile(packageName) { it.copy(presetId = presetId) }
    }

    fun addApp(packageName: String) {
        viewModelScope.launch {
            repository.update { cfg ->
                if (cfg.profile(packageName) != null) cfg else cfg.copy(profiles = cfg.profiles + AppProfile(packageName))
            }
        }
        selected.value = packageName
    }

    fun removeApp(packageName: String) {
        if (draft.value?.packageName == packageName) cancelDraft()
        viewModelScope.launch {
            repository.update { cfg -> cfg.copy(profiles = cfg.profiles.filterNot { it.packageName == packageName }) }
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            _installedApps.value = withContext(Dispatchers.IO) { AppDirectory.launchableApps(getApplication()) }
        }
    }

    private fun updateProfile(packageName: String, change: (AppProfile) -> AppProfile) {
        viewModelScope.launch {
            repository.update { cfg ->
                cfg.copy(profiles = cfg.profiles.map { if (it.packageName == packageName) change(it) else it })
            }
        }
    }

    // ---- Global settings ----

    fun setMaster(enabled: Boolean) {
        viewModelScope.launch { repository.update { it.copy(masterEnabled = enabled) } }
        if (enabled) EqService.start(getApplication())
    }

    fun setLimiter(enabled: Boolean) {
        viewModelScope.launch { repository.update { it.copy(limiterEnabled = enabled) } }
    }

    fun setShizukuEnabled(enabled: Boolean) {
        graph.shizuku.refresh()
        viewModelScope.launch { repository.update { it.copy(shizukuEnabled = enabled) } }
    }

    fun requestShizukuPermission() = graph.shizuku.requestPermission()

    fun openShizukuApp() {
        if (!graph.shizuku.launchShizukuApp()) openShizukuDownload()
    }

    fun openShizukuDownload() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ShizukuController.DOWNLOAD_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(intent) }
    }

    fun refreshShizuku() = graph.shizuku.refresh()

    fun setOnboardingDone(done: Boolean) {
        viewModelScope.launch { repository.update { it.copy(onboardingDone = done) } }
        if (done) EqService.start(getApplication())
    }

    private companion object {
        const val DEBOUNCE_MS = 120L
    }
}

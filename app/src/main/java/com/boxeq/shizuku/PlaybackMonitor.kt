package com.boxeq.shizuku

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import com.boxeq.engine.EqEngine
import com.boxeq.engine.ObservedPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface MonitorState {
    data object Idle : MonitorState
    data object Active : MonitorState
    data class Failed(val reason: String) : MonitorState
}

/**
 * Shizuku-mode session discovery. The public playback callback tells us WHEN something changed (its payload is
 * anonymised for normal apps), then [HiddenAudioApi] reads the real session IDs and UIDs with shell privileges.
 * A slow poll covers missed callbacks.
 */
class PlaybackMonitor(
    private val context: Context,
    private val engine: EqEngine,
    private val controller: ShizukuController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val triggers = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow<MonitorState>(MonitorState.Idle)
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            triggers.trySend(Unit)
        }
    }

    fun start() {
        scope.launch {
            combine(
                engine.config.map { it.shizukuEnabled }.distinctUntilChanged(),
                controller.availability,
            ) { enabled, availability -> enabled && availability == ShizukuAvailability.GRANTED }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (active) {
                        monitor()
                    } else {
                        _state.value = MonitorState.Idle
                        engine.setShizukuActive(false)
                        engine.onShizukuSnapshot(emptyList())
                    }
                }
        }
        // A newly added/removed app profile must be matched against what is already playing.
        scope.launch {
            engine.config.map { cfg -> cfg.profiles.map { it.packageName }.toSet() }
                .distinctUntilChanged()
                .collect { triggers.trySend(Unit) }
        }
    }

    private suspend fun monitor() = coroutineScope {
        audioManager.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
        try {
            while (isActive) {
                poll()
                val triggered = withTimeoutOrNull(POLL_INTERVAL_MS) { triggers.receive() } != null
                if (triggered) {
                    poll()
                    delay(SETTLE_MS) // let session-ID updates that trail the state change land
                }
            }
        } finally {
            audioManager.unregisterAudioPlaybackCallback(callback)
        }
    }

    private suspend fun poll() {
        val result = withContext(Dispatchers.IO) { HiddenAudioApi.readActivePlayback() }
        result.onSuccess { raw ->
            val observed = raw.mapNotNull { r ->
                packageFor(r.clientUid)?.let { ObservedPlayback(it, r.sessionId) }
            }
            _state.value = MonitorState.Active
            engine.setShizukuActive(true)
            engine.onShizukuSnapshot(observed)
        }.onFailure { e ->
            _state.value = MonitorState.Failed(e.message ?: "unknown error")
            engine.setShizukuActive(false)
            engine.onShizukuSnapshot(emptyList())
        }
    }

    private fun packageFor(uid: Int): String? {
        if (uid < 0) return null
        val packages = context.packageManager.getPackagesForUid(uid) ?: return null
        val profiled = engine.config.value.profiles.map { it.packageName }.toSet()
        return packages.firstOrNull { it in profiled } ?: packages.firstOrNull()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 4_000L
        const val SETTLE_MS = 500L
    }
}

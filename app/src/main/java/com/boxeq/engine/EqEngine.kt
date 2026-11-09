package com.boxeq.engine

import com.boxeq.data.EqConfig
import com.boxeq.data.EqSettings
import com.boxeq.data.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

enum class AppStatus { DISABLED, CONNECTED, NO_SESSION_EXPOSED, NEEDS_SHIZUKU, WAITING }

data class EngineRuntime(
    val loaded: Boolean = false,
    val statuses: Map<String, AppStatus> = emptyMap(),
    val activeEffects: Int = 0,
    val effectKind: EffectKind? = null,
)

/** A player seen by Shizuku. [sessionId] <= 0 means the app is playing but exposes no usable session. */
data class ObservedPlayback(val packageName: String, val sessionId: Int)

/**
 * Owns one [EffectChain] per audio session ID. Every mutation runs on a single dedicated thread, so
 * the maps never see concurrent writers; they are still concurrent maps so the UI/service can read
 * them from any thread.
 */
class EqEngine(private val repository: SettingsRepository) {

    private data class KnownSession(
        val sessionId: Int,
        val packageName: String,
        val viaBroadcast: Boolean,
        val viaShizuku: Boolean,
    )

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "boxeq-engine").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Session ID -> live effect. */
    private val effects = ConcurrentHashMap<Int, EffectChain>()
    private val known = ConcurrentHashMap<Int, KnownSession>()
    private val failed = HashSet<Int>()
    /** Last settings pushed to each live effect, so unchanged sessions are not re-applied on every poll. */
    private val applied = HashMap<Int, EqSettings>()
    private var playingWithoutSession: Set<String> = emptySet()
    private var shizukuActive = false
    private var lastKind: EffectKind? = null

    private val _config = MutableStateFlow(EqConfig())
    val config: StateFlow<EqConfig> = _config.asStateFlow()

    private val _runtime = MutableStateFlow(EngineRuntime())
    val runtime: StateFlow<EngineRuntime> = _runtime.asStateFlow()

    private val ready = CompletableDeferred<Unit>()

    init {
        scope.launch {
            repository.config.collect { cfg ->
                _config.value = cfg
                reconcileAll()
                ready.complete(Unit)
            }
        }
    }

    // ---- Discovery inputs (all safe to call from any thread) ----

    fun onSessionOpened(sessionId: Int, packageName: String?): Job = scope.launch {
        ready.await()
        if (sessionId <= 0 || packageName.isNullOrBlank()) return@launch
        known.compute(sessionId) { _, old ->
            old?.copy(packageName = packageName, viaBroadcast = true)
                ?: KnownSession(sessionId, packageName, viaBroadcast = true, viaShizuku = false)
        }
        reconcile(sessionId)
        publish()
    }

    fun onSessionClosed(sessionId: Int): Job = scope.launch {
        ready.await()
        known[sessionId]?.let { dropSource(it, broadcast = true) }
        reconcile(sessionId)
        publish()
    }

    /** Full list of what Shizuku currently sees playing. An empty list clears all Shizuku-sourced sessions. */
    fun onShizukuSnapshot(playback: List<ObservedPlayback>): Job = scope.launch {
        ready.await()
        playingWithoutSession = playback.filter { it.sessionId <= 0 }.map { it.packageName }.toSet()
        val seen = playback.filter { it.sessionId > 0 }.associate { it.sessionId to it.packageName }
        seen.forEach { (id, pkg) ->
            known.compute(id) { _, old ->
                old?.copy(packageName = pkg, viaShizuku = true)
                    ?: KnownSession(id, pkg, viaBroadcast = false, viaShizuku = true)
            }
        }
        known.values.filter { it.viaShizuku && it.sessionId !in seen }.forEach { dropSource(it, broadcast = false) }
        // Brand-new sessions first: that is the audible gap. Sessions that already have an effect come after.
        val all = (known.keys + effects.keys).toSet()
        val (fresh, existing) = all.partition { !effects.containsKey(it) }
        fresh.forEach { reconcile(it) }
        existing.forEach { reconcile(it) }
        publish()
    }

    fun setShizukuActive(active: Boolean): Job = scope.launch {
        shizukuActive = active
        publish()
    }

    // ---- Internals (engine thread only) ----

    private fun dropSource(s: KnownSession, broadcast: Boolean) {
        val updated = if (broadcast) s.copy(viaBroadcast = false) else s.copy(viaShizuku = false)
        if (!updated.viaBroadcast && !updated.viaShizuku) known.remove(s.sessionId)
        else known[s.sessionId] = updated
    }

    private fun reconcileAll() {
        failed.clear()
        (known.keys + effects.keys).toSet().forEach { reconcile(it) }
        publish()
    }

    private fun reconcile(sessionId: Int) {
        val info = known[sessionId]
        if (info == null) {
            release(sessionId)
            failed.remove(sessionId)
            return
        }
        val cfg = _config.value
        val profile = cfg.profile(info.packageName)
        if (!cfg.masterEnabled || profile == null || !profile.enabled) {
            release(sessionId)
            return
        }
        val settings = cfg.settingsFor(profile)
        val existing = effects[sessionId]
        if (existing != null) {
            if (applied[sessionId] == settings) return
            try {
                existing.apply(settings)
                applied[sessionId] = settings
            } catch (e: Exception) {
                // Session died or the effect lost control; drop it and let a later event retry.
                release(sessionId)
                failed += sessionId
            }
            return
        }
        if (sessionId in failed) return
        val created = EffectFactory.create(sessionId, settings)
        if (created == null) {
            failed += sessionId
        } else {
            effects[sessionId] = created
            applied[sessionId] = settings
            lastKind = created.kind
        }
    }

    private fun release(sessionId: Int) {
        applied.remove(sessionId)
        effects.remove(sessionId)?.release()
    }

    private fun publish() {
        val cfg = _config.value
        val byPackage = known.values.groupBy { it.packageName }
        val statuses = cfg.profiles.associate { p ->
            val sessions = byPackage[p.packageName].orEmpty()
            p.packageName to when {
                !cfg.masterEnabled || !p.enabled -> AppStatus.DISABLED
                sessions.any { effects.containsKey(it.sessionId) } -> AppStatus.CONNECTED
                sessions.isNotEmpty() || p.packageName in playingWithoutSession -> AppStatus.NO_SESSION_EXPOSED
                !shizukuActive -> AppStatus.NEEDS_SHIZUKU
                else -> AppStatus.WAITING
            }
        }
        _runtime.value = EngineRuntime(
            loaded = true,
            statuses = statuses,
            activeEffects = effects.size,
            effectKind = effects.values.firstOrNull()?.kind ?: lastKind,
        )
    }
}

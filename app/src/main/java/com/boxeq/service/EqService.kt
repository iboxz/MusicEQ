package com.boxeq.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.boxeq.MainActivity
import com.boxeq.R
import com.boxeq.data.AppDirectory
import com.boxeq.data.PresetNames
import com.boxeq.engine.AppStatus
import com.boxeq.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process alive (foreground, mediaPlayback type) and shows quick actions. The engine itself is a
 * process singleton (see AppGraph) so that broadcast-driven sessions work even when this service is not running.
 */
class EqService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class NotificationModel(val enabled: Boolean, val presetName: String, val connected: String)

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        startInForeground(NotificationModel(graph.engine.config.value.masterEnabled, "", ""))

        val engine = graph.engine
        scope.launch {
            combine(engine.config, engine.runtime) { cfg, rt ->
                val active = cfg.profiles.firstOrNull { it.enabled }
                val connected = cfg.profiles
                    .filter { rt.statuses[it.packageName] == AppStatus.CONNECTED }
                    .joinToString(", ") { AppDirectory.labelOf(this@EqService, it.packageName) }
                NotificationModel(
                    enabled = cfg.masterEnabled,
                    presetName = active?.let { PresetNames.display(this@EqService, cfg.preset(it.presetId)) } ?: "",
                    connected = connected,
                )
            }.distinctUntilChanged().collect { model ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(model))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> scope.launch { graph.repository.update { it.copy(masterEnabled = !it.masterEnabled) } }
            ACTION_NEXT_PRESET -> scope.launch { cyclePreset() }
            ACTION_STOP -> {
                scope.launch {
                    graph.repository.update { it.copy(masterEnabled = false) }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Moves every enabled app to the next preset in the list (built-ins first, then user presets). */
    private suspend fun cyclePreset() {
        graph.repository.update { cfg ->
            val current = cfg.profiles.firstOrNull { it.enabled }?.presetId ?: return@update cfg
            val all = cfg.allPresets()
            val next = all[(all.indexOfFirst { it.id == current } + 1) % all.size].id
            cfg.copy(profiles = cfg.profiles.map { if (it.enabled) it.copy(presetId = next) else it })
        }
    }

    private fun startInForeground(model: NotificationModel) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(model), type)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun action(label: Int, action: String, requestCode: Int): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, requestCode, Intent(this, EqService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, getString(label), pi).build()
    }

    private fun buildNotification(model: NotificationModel): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (model.connected.isEmpty()) getString(R.string.notif_idle)
        else getString(R.string.notif_connected, model.connected)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_eq)
            .setContentTitle(getString(if (model.enabled) R.string.notif_title_on else R.string.notif_title_off))
            .setContentText(text)
            .apply { if (model.presetName.isNotEmpty()) setSubText(getString(R.string.notif_preset, model.presetName)) }
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(action(if (model.enabled) R.string.notif_action_off else R.string.notif_action_on, ACTION_TOGGLE, 1))
            .addAction(action(R.string.notif_action_preset, ACTION_NEXT_PRESET, 2))
            .addAction(action(R.string.notif_action_stop, ACTION_STOP, 3))
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "boxeq_engine"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE = "com.boxeq.action.TOGGLE"
        const val ACTION_NEXT_PRESET = "com.boxeq.action.NEXT_PRESET"
        const val ACTION_STOP = "com.boxeq.action.STOP"

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            if (running) return
            ContextCompat.startForegroundService(context, Intent(context, EqService::class.java))
        }
    }
}

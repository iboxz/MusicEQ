package com.boxeq.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Handler
import android.os.Looper
import com.boxeq.graph
import com.boxeq.service.EqService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receives the standard "audio effect control session" broadcasts that cooperating players send to
 * equalizer apps. Apps that never send them (possibly Telegram/Instagram) are handled by Shizuku mode.
 */
class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val open = when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> true
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> false
            else -> return
        }
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
        val engine = context.graph.engine

        val pending = goAsync()
        val finished = AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) pending.finish() }
        // Safety net: never hold the broadcast open longer than the system allows.
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 8_000)

        val job = if (open) engine.onSessionOpened(sessionId, packageName) else engine.onSessionClosed(sessionId)
        job.invokeOnCompletion { finish() }

        if (open) {
            // Starting a foreground service from the background is usually refused on Android 12+; that is fine,
            // effects still work while the process is alive.
            runCatching { EqService.start(context) }
        }
    }
}

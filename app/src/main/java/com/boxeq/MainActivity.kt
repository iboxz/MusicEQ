package com.boxeq

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.boxeq.ui.BoxEqRoot
import com.boxeq.ui.BoxEqTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleEffectPanelIntent(intent)
        setContent { BoxEqTheme { BoxEqRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleEffectPanelIntent(intent)
    }

    /** Players that use the system "open equalizer" integration also pass their audio session here. */
    private fun handleEffectPanelIntent(intent: Intent?) {
        if (intent?.action != AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL) return
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
        graph.engine.onSessionOpened(session, intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME))
    }
}

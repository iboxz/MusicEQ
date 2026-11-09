package com.boxeq.data

import android.content.Context
import com.boxeq.R

/** Localised display names; user presets keep the name the user typed. */
object PresetNames {
    fun display(context: Context, preset: EqPreset): String = when (preset.id) {
        BuiltInPresets.FLAT -> context.getString(R.string.preset_flat)
        BuiltInPresets.BASS -> context.getString(R.string.preset_bass)
        BuiltInPresets.VOCAL -> context.getString(R.string.preset_vocal)
        BuiltInPresets.TREBLE -> context.getString(R.string.preset_treble)
        BuiltInPresets.VOICE_CLARITY -> context.getString(R.string.preset_voice_clarity)
        BuiltInPresets.DEEP_BASS -> context.getString(R.string.preset_deep_bass)
        else -> preset.name
    }
}

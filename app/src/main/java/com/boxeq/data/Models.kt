package com.boxeq.data

import com.boxeq.dsp.BandMapping
import kotlinx.serialization.Serializable

@Serializable
data class EqPreset(
    val id: String,
    val name: String,
    val gains: List<Float>,
    val builtIn: Boolean = false,
)

@Serializable
data class AppProfile(
    val packageName: String,
    val enabled: Boolean = true,
    val presetId: String = BuiltInPresets.FLAT,
    val preampDb: Float = 0f,
)

object DefaultTargets {
    const val TELEGRAM = "org.telegram.messenger"
    const val INSTAGRAM = "com.instagram.android"
    val profiles = listOf(
        AppProfile(TELEGRAM, presetId = BuiltInPresets.VOICE_CLARITY),
        AppProfile(INSTAGRAM, presetId = BuiltInPresets.FLAT),
    )
}

object BuiltInPresets {
    const val FLAT = "flat"
    const val BASS = "bass_boost"
    const val VOCAL = "vocal"
    const val TREBLE = "treble"
    const val VOICE_CLARITY = "voice_clarity"
    const val DEEP_BASS = "deep_bass"

    // Order: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz
    val all: List<EqPreset> = listOf(
        EqPreset(FLAT, "Flat", List(BandMapping.BAND_COUNT) { 0f }, builtIn = true),
        EqPreset(BASS, "Bass Boost", listOf(6f, 5.5f, 4.5f, 3f, 1.5f, 0f, 0f, 0f, 0f, 0f), builtIn = true),
        EqPreset(VOCAL, "Vocal", listOf(-3f, -3f, -2f, -1f, 1f, 2.5f, 3.5f, 3f, 1f, 0f), builtIn = true),
        EqPreset(TREBLE, "Treble", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2.5f, 4f, 5f, 5.5f), builtIn = true),
        // Speech: cut rumble below ~125 Hz, lift presence 1-4 kHz, tame hiss above 8 kHz.
        EqPreset(
            VOICE_CLARITY, "Voice Message Clarity",
            listOf(-9f, -7f, -4f, -1f, 1f, 3f, 4.5f, 4f, 1.5f, -1f), builtIn = true,
        ),
        // Converted from a parametric EQ (HPQ 10 Hz; LS 85 Hz +12; HS 51.5 Hz -8; PK 120 Hz +8,
        // 250 Hz -3, 1 kHz +2, 2.5 kHz -1.5, 6.5 kHz +3.5, 8 kHz -5). Each value is the combined
        // response averaged over that band's range. The original -15 dB preamp is set per app.
        EqPreset(
            DEEP_BASS, "Deep Bass",
            listOf(8.5f, 5.5f, 3.5f, -6f, -7.5f, -6.5f, -8.5f, -8.5f, -9.5f, -8.5f), builtIn = true,
        ),
    )

    fun isBuiltIn(id: String) = all.any { it.id == id }
}

/** What the DSP actually needs for one app. */
data class EqSettings(
    val gainsDb: List<Float>,
    val preampDb: Float,
    val limiterEnabled: Boolean,
)

data class EqConfig(
    val masterEnabled: Boolean = true,
    val limiterEnabled: Boolean = true,
    val shizukuEnabled: Boolean = false,
    val onboardingDone: Boolean = false,
    val profiles: List<AppProfile> = DefaultTargets.profiles,
    val userPresets: List<EqPreset> = emptyList(),
) {
    fun allPresets(): List<EqPreset> = BuiltInPresets.all + userPresets

    fun preset(id: String): EqPreset =
        allPresets().firstOrNull { it.id == id } ?: BuiltInPresets.all.first()

    fun profile(packageName: String): AppProfile? =
        profiles.firstOrNull { it.packageName == packageName }

    fun settingsFor(profile: AppProfile): EqSettings = EqSettings(
        gainsDb = BandMapping.sanitize(preset(profile.presetId).gains),
        preampDb = BandMapping.clampPreamp(profile.preampDb),
        limiterEnabled = limiterEnabled,
    )
}

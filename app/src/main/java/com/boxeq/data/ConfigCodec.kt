package com.boxeq.data

import com.boxeq.dsp.BandMapping
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Pure-Kotlin (de)serialisation so it can be unit-tested on the JVM. */
object ConfigCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val presetsSerializer = ListSerializer(EqPreset.serializer())
    private val profilesSerializer = ListSerializer(AppProfile.serializer())

    /** Returns null for entries that must not be stored (blank id, or shadowing a built-in preset). */
    fun sanitizePreset(p: EqPreset): EqPreset? {
        val id = p.id.trim()
        if (id.isEmpty() || BuiltInPresets.isBuiltIn(id)) return null
        val name = p.name.trim().ifEmpty { id }
        return EqPreset(id, name, BandMapping.sanitize(p.gains), builtIn = false)
    }

    fun encodePresets(presets: List<EqPreset>): String =
        json.encodeToString(presetsSerializer, presets.mapNotNull(::sanitizePreset))

    fun decodePresets(raw: String?): List<EqPreset> {
        if (raw.isNullOrBlank()) return emptyList()
        val parsed = runCatching { json.decodeFromString(presetsSerializer, raw) }
            .getOrDefault(emptyList())
        return parsed.mapNotNull(::sanitizePreset).distinctBy { it.id }
    }

    private fun sanitizeProfile(p: AppProfile): AppProfile? {
        if (p.packageName.isBlank()) return null
        return p.copy(preampDb = BandMapping.clampPreamp(p.preampDb))
    }

    fun encodeProfiles(profiles: List<AppProfile>): String =
        json.encodeToString(profilesSerializer, profiles.mapNotNull(::sanitizeProfile))

    /** Returns null when nothing was stored (caller falls back to defaults) or the data is corrupt. */
    fun decodeProfiles(raw: String?): List<AppProfile>? {
        if (raw == null) return null
        val parsed = runCatching { json.decodeFromString(profilesSerializer, raw) }
            .getOrNull() ?: return null
        return parsed.mapNotNull(::sanitizeProfile).distinctBy { it.packageName }
    }
}

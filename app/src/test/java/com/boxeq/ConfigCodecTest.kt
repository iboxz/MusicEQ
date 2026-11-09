package com.boxeq

import com.boxeq.data.AppProfile
import com.boxeq.data.BuiltInPresets
import com.boxeq.data.ConfigCodec
import com.boxeq.data.DefaultTargets
import com.boxeq.data.EqConfig
import com.boxeq.data.EqPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCodecTest {
    private val custom = EqPreset("u-1", "My preset", listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f))

    @Test fun presetRoundTrip() {
        val decoded = ConfigCodec.decodePresets(ConfigCodec.encodePresets(listOf(custom)))
        assertEquals(listOf(custom), decoded)
    }

    @Test fun persianNamesSurviveRoundTrip() {
        val fa = custom.copy(id = "u-2", name = "پریست من")
        assertEquals(listOf(fa), ConfigCodec.decodePresets(ConfigCodec.encodePresets(listOf(fa))))
    }

    @Test fun decodeToleratesGarbageAndEmptyInput() {
        assertTrue(ConfigCodec.decodePresets(null).isEmpty())
        assertTrue(ConfigCodec.decodePresets("").isEmpty())
        assertTrue(ConfigCodec.decodePresets("{not json").isEmpty())
        assertNull(ConfigCodec.decodeProfiles(null))
        assertNull(ConfigCodec.decodeProfiles("{not json"))
    }

    @Test fun decodeSanitisesGainsAndIgnoresUnknownKeys() {
        val raw = """[{"id":"x","name":" Loud ","gains":[99,-99],"future":true}]"""
        val p = ConfigCodec.decodePresets(raw).single()
        assertEquals("Loud", p.name)
        assertEquals(10, p.gains.size)
        assertEquals(12f, p.gains[0], 0f)
        assertEquals(-12f, p.gains[1], 0f)
        assertEquals(0f, p.gains[9], 0f)
    }

    @Test fun userPresetsCannotShadowBuiltIns() {
        val shadow = custom.copy(id = BuiltInPresets.FLAT)
        assertTrue(ConfigCodec.decodePresets(ConfigCodec.encodePresets(listOf(shadow, custom))).none { it.id == BuiltInPresets.FLAT })
    }

    @Test fun duplicateIdsAreCollapsed() {
        val twice = ConfigCodec.encodePresets(listOf(custom, custom.copy(name = "Other")))
        assertEquals(1, ConfigCodec.decodePresets(twice).size)
    }

    @Test fun profilesRoundTripAndClampPreamp() {
        val profiles = listOf(
            AppProfile("a.b", enabled = false, presetId = "u-1", preampDb = -3.5f),
            AppProfile("c.d", preampDb = 40f),
        )
        val decoded = ConfigCodec.decodeProfiles(ConfigCodec.encodeProfiles(profiles))!!
        assertEquals(profiles[0], decoded[0])
        assertEquals(12f, decoded[1].preampDb, 0f)
    }

    @Test fun emptyProfileListIsDistinctFromMissing() {
        assertEquals(emptyList<AppProfile>(), ConfigCodec.decodeProfiles("[]"))
    }

    @Test fun builtInPresetsAreWellFormed() {
        assertEquals(
            listOf("flat", "bass_boost", "vocal", "treble", "voice_clarity"),
            BuiltInPresets.all.map { it.id },
        )
        BuiltInPresets.all.forEach { assertEquals(10, it.gains.size); assertTrue(it.gains.all { g -> g in -12f..12f }) }
        assertTrue(BuiltInPresets.all.first().gains.all { it == 0f })
    }

    @Test fun defaultsTargetTelegramAndInstagram() {
        val cfg = EqConfig()
        assertEquals(listOf(DefaultTargets.TELEGRAM, DefaultTargets.INSTAGRAM), cfg.profiles.map { it.packageName })
        assertEquals(BuiltInPresets.VOICE_CLARITY, cfg.profile(DefaultTargets.TELEGRAM)!!.presetId)
    }

    @Test fun settingsResolveUserPresetAndFallBackToFlat() {
        val cfg = EqConfig(userPresets = listOf(custom), limiterEnabled = false)
        val s = cfg.settingsFor(AppProfile("p", presetId = "u-1", preampDb = 2f))
        assertEquals(custom.gains.map { it.coerceAtMost(12f) }, s.gainsDb)
        assertEquals(2f, s.preampDb, 0f)
        assertEquals(false, s.limiterEnabled)
        assertEquals(List(10) { 0f }, cfg.settingsFor(AppProfile("p", presetId = "deleted")).gainsDb)
    }
}

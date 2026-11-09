package com.boxeq.engine

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import com.boxeq.data.EqSettings
import com.boxeq.dsp.BandMapping
import com.boxeq.dsp.ResponseCurve
import kotlin.math.roundToInt

enum class EffectKind { DYNAMICS, LEGACY }

/** One audio effect attached to one audio session. */
interface EffectChain {
    val kind: EffectKind

    /** Pushes the settings to the effect. May throw if the session has gone away. */
    fun apply(settings: EqSettings)

    fun release()
}

object EffectFactory {
    /** DynamicsProcessing first; legacy Equalizer if it cannot be initialised on this device/session. */
    fun create(sessionId: Int, initial: EqSettings): EffectChain? =
        DynamicsChain.create(sessionId, initial) ?: LegacyChain.create(sessionId, initial)
}

internal class DynamicsChain private constructor(
    private val dp: DynamicsProcessing,
) : EffectChain {
    override val kind = EffectKind.DYNAMICS

    override fun apply(settings: EqSettings) {
        dp.setInputGainAllChannelsTo(settings.preampDb)
        dp.setPreEqAllChannelsTo(buildEq(settings.gainsDb))
        dp.setLimiterAllChannelsTo(buildLimiter(settings.limiterEnabled))
    }

    override fun release() {
        runCatching { dp.setEnabled(false) }
        runCatching { dp.release() }
    }

    companion object {
        // The DynamicsProcessing effect is stereo; mono sources are up-mixed by the framework.
        private const val CHANNELS = 2

        private fun buildEq(gains: List<Float>): DynamicsProcessing.Eq {
            val eq = DynamicsProcessing.Eq(true, true, BandMapping.BAND_COUNT)
            for (i in 0 until BandMapping.BAND_COUNT) {
                eq.setBand(i, DynamicsProcessing.EqBand(true, BandMapping.cutoffHz(i), gains[i]))
            }
            return eq
        }

        private fun buildLimiter(enabled: Boolean) = DynamicsProcessing.Limiter(
            /* inUse = */ true,
            /* enabled = */ enabled,
            /* linkGroup = */ 0,
            /* attackTime ms = */ 1f,
            /* releaseTime ms = */ 60f,
            /* ratio = */ 10f,
            /* threshold dB = */ -1.5f,
            /* postGain dB = */ 0f,
        )

        fun create(sessionId: Int, initial: EqSettings): DynamicsChain? {
            var dp: DynamicsProcessing? = null
            return try {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    CHANNELS,
                    /* preEqInUse = */ true, BandMapping.BAND_COUNT,
                    /* mbcInUse = */ false, 0,
                    /* postEqInUse = */ false, 0,
                    /* limiterInUse = */ true,
                ).build()
                val effect = DynamicsProcessing(0, sessionId, config)
                dp = effect
                val chain = DynamicsChain(effect)
                chain.apply(initial)
                check(effect.setEnabled(true) == AudioEffect.SUCCESS) { "setEnabled failed" }
                chain
            } catch (e: Exception) {
                runCatching { dp?.release() }
                null
            }
        }
    }
}

/**
 * Fallback for devices where DynamicsProcessing is unavailable. The device exposes its own (usually 5)
 * bands, so the 10-band curve is sampled at each device band's centre. There is no limiter here;
 * the preamp is applied as an offset to every band.
 */
internal class LegacyChain private constructor(
    private val eq: Equalizer,
) : EffectChain {
    override val kind = EffectKind.LEGACY

    private val minLevelMb = eq.bandLevelRange[0].toInt()
    private val maxLevelMb = eq.bandLevelRange[1].toInt()

    override fun apply(settings: EqSettings) {
        for (band in 0 until eq.numberOfBands.toInt()) {
            val centreHz = eq.getCenterFreq(band.toShort()) / 1000f
            val db = ResponseCurve.gainAt(centreHz, settings.gainsDb) + settings.preampDb
            val mb = (db * 100f).roundToInt().coerceIn(minLevelMb, maxLevelMb)
            eq.setBandLevel(band.toShort(), mb.toShort())
        }
    }

    override fun release() {
        runCatching { eq.setEnabled(false) }
        runCatching { eq.release() }
    }

    companion object {
        fun create(sessionId: Int, initial: EqSettings): LegacyChain? {
            var eq: Equalizer? = null
            return try {
                val effect = Equalizer(0, sessionId)
                eq = effect
                val chain = LegacyChain(effect)
                chain.apply(initial)
                check(effect.setEnabled(true) == AudioEffect.SUCCESS) { "setEnabled failed" }
                chain
            } catch (e: Exception) {
                runCatching { eq?.release() }
                null
            }
        }
    }
}

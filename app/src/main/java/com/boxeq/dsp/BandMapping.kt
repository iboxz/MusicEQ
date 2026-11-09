package com.boxeq.dsp

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Fixed 10-band layout and the maths that maps bands to DSP parameters. */
object BandMapping {
    const val BAND_COUNT = 10
    const val MIN_GAIN_DB = -12f
    const val MAX_GAIN_DB = 12f
    const val GAIN_STEP_DB = 0.5f
    const val MIN_PREAMP_DB = -12f
    const val MAX_PREAMP_DB = 12f
    const val MIN_FREQ_HZ = 20f
    const val MAX_FREQ_HZ = 20_000f

    val CENTER_FREQS_HZ: List<Float> =
        listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    fun clampGain(db: Float): Float =
        if (db.isNaN()) 0f else db.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

    fun clampPreamp(db: Float): Float =
        if (db.isNaN()) 0f else db.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB)

    /** Rounds to the slider step and clamps to the allowed range. */
    fun snapGain(db: Float): Float =
        clampGain((db / GAIN_STEP_DB).roundToInt() * GAIN_STEP_DB)

    /** Pads/truncates to [BAND_COUNT] entries and clamps every value. */
    fun sanitize(gains: List<Float>): List<Float> =
        List(BAND_COUNT) { i -> clampGain(gains.getOrNull(i) ?: 0f) }

    /**
     * DynamicsProcessing EQ bands are defined by their UPPER cutoff frequency (a band spans from the
     * previous band's cutoff up to its own). To make band "i" act around its centre frequency the
     * cutoff is placed at the geometric midpoint between neighbouring centres.
     */
    fun cutoffHz(index: Int): Float {
        require(index in 0 until BAND_COUNT) { "band index out of range: $index" }
        return if (index == BAND_COUNT - 1) MAX_FREQ_HZ
        else sqrt(CENTER_FREQS_HZ[index] * CENTER_FREQS_HZ[index + 1])
    }

    fun label(index: Int): String {
        val hz = CENTER_FREQS_HZ[index]
        return if (hz >= 1000f) "${(hz / 1000f).toInt()}k" else hz.toInt().toString()
    }
}

/** Smooth display/legacy-mapping model of the EQ curve (log-frequency, smoothstep between bands). */
object ResponseCurve {
    fun gainAt(freqHz: Float, gains: List<Float>): Float {
        val g = BandMapping.sanitize(gains)
        val c = BandMapping.CENTER_FREQS_HZ
        if (freqHz <= c.first()) return g.first()
        if (freqHz >= c.last()) return g.last()
        val i = c.indexOfLast { it <= freqHz }
        val x0 = ln(c[i])
        val x1 = ln(c[i + 1])
        val t = (ln(freqHz) - x0) / (x1 - x0)
        val s = t * t * (3f - 2f * t)
        return g[i] + (g[i + 1] - g[i]) * s
    }

    /** Position of a frequency on the 20 Hz..20 kHz log axis, 0..1. */
    fun logPosition(freqHz: Float): Float {
        val lo = ln(BandMapping.MIN_FREQ_HZ)
        val hi = ln(BandMapping.MAX_FREQ_HZ)
        val f = freqHz.coerceIn(BandMapping.MIN_FREQ_HZ, BandMapping.MAX_FREQ_HZ)
        return (ln(f) - lo) / (hi - lo)
    }

    /** [points] gains sampled on a log-spaced grid across the audible range. */
    fun sample(gains: List<Float>, points: Int): List<Float> {
        require(points >= 2)
        val lo = ln(BandMapping.MIN_FREQ_HZ)
        val hi = ln(BandMapping.MAX_FREQ_HZ)
        return List(points) { k ->
            gainAt(exp(lo + (hi - lo) * k / (points - 1)), gains)
        }
    }
}

package com.boxeq

import com.boxeq.dsp.BandMapping
import com.boxeq.dsp.ResponseCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class BandMappingTest {
    @Test fun tenBandsWithExpectedCentres() {
        assertEquals(10, BandMapping.BAND_COUNT)
        assertEquals(listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f), BandMapping.CENTER_FREQS_HZ)
    }

    @Test fun gainIsClampedToPlusMinusTwelve() {
        assertEquals(12f, BandMapping.clampGain(20f), 0f)
        assertEquals(-12f, BandMapping.clampGain(-99f), 0f)
        assertEquals(3.5f, BandMapping.clampGain(3.5f), 0f)
        assertEquals(0f, BandMapping.clampGain(Float.NaN), 0f)
    }

    @Test fun snapRoundsToHalfDecibelSteps() {
        assertEquals(1.5f, BandMapping.snapGain(1.4f), 0f)
        assertEquals(1.0f, BandMapping.snapGain(1.2f), 0f)
        assertEquals(-2.5f, BandMapping.snapGain(-2.6f), 0f)
        assertEquals(12f, BandMapping.snapGain(12.4f), 0f)
    }

    @Test fun sanitizePadsTruncatesAndClamps() {
        assertEquals(List(10) { 0f }, BandMapping.sanitize(emptyList()))
        val long = BandMapping.sanitize(List(14) { 30f })
        assertEquals(10, long.size)
        assertTrue(long.all { it == 12f })
        assertEquals(listOf(1f, -12f) + List(8) { 0f }, BandMapping.sanitize(listOf(1f, -50f)))
    }

    @Test fun cutoffsAreGeometricMidpointsAndIncreasing() {
        assertEquals(sqrt(31f * 62f), BandMapping.cutoffHz(0), 0.001f)
        assertEquals(sqrt(8000f * 16000f), BandMapping.cutoffHz(8), 0.01f)
        assertEquals(20_000f, BandMapping.cutoffHz(9), 0f)
        val cutoffs = (0 until 10).map { BandMapping.cutoffHz(it) }
        assertEquals(cutoffs.sorted(), cutoffs)
        // every centre lies inside its own band: previous cutoff < centre <= own cutoff
        for (i in 0 until 10) {
            val lower = if (i == 0) 0f else cutoffs[i - 1]
            assertTrue(BandMapping.CENTER_FREQS_HZ[i] > lower)
            assertTrue(BandMapping.CENTER_FREQS_HZ[i] <= cutoffs[i])
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun cutoffRejectsBadIndex() { BandMapping.cutoffHz(10) }

    @Test fun labels() {
        assertEquals("31", BandMapping.label(0))
        assertEquals("500", BandMapping.label(4))
        assertEquals("1k", BandMapping.label(5))
        assertEquals("16k", BandMapping.label(9))
    }

    @Test fun curveHitsBandGainAtEachCentre() {
        val gains = listOf(-12f, -6f, 0f, 3f, 6f, 9f, 12f, 4f, -4f, -8f)
        BandMapping.CENTER_FREQS_HZ.forEachIndexed { i, hz ->
            assertEquals(gains[i], ResponseCurve.gainAt(hz, gains), 0.001f)
        }
    }

    @Test fun curveStaysBetweenNeighbouringGainsAndClampsOutsideRange() {
        val gains = listOf(0f, 12f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, -12f)
        val mid = ResponseCurve.gainAt(sqrt(31f * 62f), gains)
        assertEquals(6f, mid, 0.01f) // smoothstep midpoint
        assertEquals(0f, ResponseCurve.gainAt(20f, gains), 0f)
        assertEquals(-12f, ResponseCurve.gainAt(20_000f, gains), 0f)
        ResponseCurve.sample(gains, 200).forEach { assertTrue(it in -12f..12f) }
    }

    @Test fun logPositionSpansZeroToOne() {
        assertEquals(0f, ResponseCurve.logPosition(20f), 0.0001f)
        assertEquals(1f, ResponseCurve.logPosition(20_000f), 0.0001f)
        assertEquals(0.5f, ResponseCurve.logPosition(sqrt(20f * 20_000f)), 0.0001f)
    }
}

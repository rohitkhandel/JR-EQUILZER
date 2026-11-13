package com.jr.equalizer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiquadFilterEngineTest {
    @Test
    fun zeroDbPeakingFilterIsUnityTransfer() {
        val c = BiquadCoefficients.rbj(FilterType.PEAKING, 48000.0, 1000.0, 0.8, 0.0)
        assertEquals(1.0, c.b0, 1.0e-12)
        assertEquals(c.a1, c.b1, 1.0e-12)
        assertEquals(c.a2, c.b2, 1.0e-12)
    }

    @Test
    fun allRbjFilterKindsHaveFiniteCoefficients() {
        FilterType.values().forEach { type ->
            val c = BiquadCoefficients.rbj(type, 48000.0, 1200.0, 0.707, 3.0)
            listOf(c.b0, c.b1, c.b2, c.a1, c.a2).forEach {
                assertTrue("Coefficient must be finite", it.isFinite())
            }
        }
    }

    @Test
    fun flatSettingsPreserveQuietPcmSamples() {
        val input = FloatArray(2048) { (kotlin.math.sin(it * 0.03) * 0.1).toFloat() }
        val expected = input.copyOf()
        BiquadFilterEngine(48000, 2, DspSettings()).process(input)
        input.indices.forEach { index ->
            assertEquals(expected[index].toDouble(), input[index].toDouble(), 1.0e-4)
        }
    }

    @Test
    fun settingsJsonRoundTripPreservesBandValues() {
        val original = DspSettings(
            preampDb = -3.5,
            bassDb = 4.0,
            bands = DspSettings.logarithmicBands(15).mapIndexed { i, b -> b.copy(gainDb = i.toDouble() / 2.0 - 2.0) }
        )
        val restored = DspSettings.fromJsonString(original.toJsonString())
        assertEquals(original.preampDb, restored.preampDb, 1.0e-9)
        assertEquals(original.bassDb, restored.bassDb, 1.0e-9)
        assertEquals(15, restored.bands.size)
        assertEquals(original.bands[6].gainDb, restored.bands[6].gainDb, 1.0e-9)
    }
}

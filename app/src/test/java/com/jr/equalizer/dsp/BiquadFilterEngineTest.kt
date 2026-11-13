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
            assertEquals(expected[index].toDouble(), input[index].toDouble(), 2.0e-3)
        }
    }

    @Test
    fun thirtyTwoBandModeUsesTheCanonicalGraphicFrequencyGrid() {
        val bands = DspSettings.logarithmicBands(32)
        assertEquals(32, bands.size)
        assertEquals(GRAPHIC_EQ_FREQUENCIES.toList(), bands.map { it.frequencyHz })
        assertEquals(20.0, bands.first().frequencyHz, 0.0)
        assertEquals(20_000.0, bands.last().frequencyHz, 0.0)
    }

    @Test
    fun tenAndSixteenBandModesUseStandardCentres() {
        assertEquals(10, DspSettings.logarithmicBands(10).size)
        assertEquals(31.5, DspSettings.logarithmicBands(10).first().frequencyHz, 0.0)
        assertEquals(16, DspSettings.logarithmicBands(16).size)
        assertEquals(25.0, DspSettings.logarithmicBands(16).first().frequencyHz, 0.0)
    }

    @Test
    fun dvcAndAutoPreampProduceHeadroomOnlyWhenNeeded() {
        val flat = DspSettings()
        assertEquals(0.0, flat.effectiveInputGainDb(), 0.001)

        val boosted = flat.copy(
            bands = DspSettings.logarithmicBands(32).mapIndexed { index, band ->
                if (index == 8) band.copy(gainDb = 6.0) else band
            }
        )
        assertEquals(-7.0, boosted.effectiveInputGainDb(), 0.001)
        assertEquals(-8.0, boosted.copy(dvcEnabled = true).effectiveInputGainDb(), 0.001)
        assertEquals(0.0, boosted.copy(autoPreampEnabled = false).effectiveInputGainDb(), 0.001)
    }

    @Test
    fun noiseGateStillRunsWhenCompressorIsDisabled() {
        val input = FloatArray(4096) { 0.0001f }
        val originalPeak = input.maxOf { kotlin.math.abs(it) }

        val settings = DspSettings(
            autoPreampEnabled = false,
            compressor = CompressorSettings(
                enabled = false,
                noiseGateEnabled = true,
                noiseGateThresholdDb = -40.0
            )
        )
        BiquadFilterEngine(48000, 2, settings).process(input)

        val processedPeak = input.maxOf { kotlin.math.abs(it) }
        assertTrue("Noise gate must attenuate low-level audio independently", processedPeak < originalPeak * 0.5f)
    }
}

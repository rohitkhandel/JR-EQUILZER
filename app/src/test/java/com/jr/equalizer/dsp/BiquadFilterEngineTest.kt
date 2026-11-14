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
    fun newSettingsDefaultToFullThirtyTwoBandGrid() {
        assertEquals(32, DspSettings().bands.size)
        assertEquals(GRAPHIC_EQ_FREQUENCIES.toList(), DspSettings().bands.map { it.frequencyHz })
    }

    @Test
    fun graphicGridInterpolationReturnsAllThirtyTwoTargetGains() {
        val source = DspSettings.logarithmicBands(10).mapIndexed { index, band ->
            band.copy(gainDb = index.toDouble() - 4.5)
        }
        val mapped = DspSettings.gainsForGraphicGrid(source)
        assertEquals(32, mapped.size)
        assertEquals(source.first().gainDb.toFloat(), mapped.first(), 0.001f)
        assertEquals(source.last().gainDb.toFloat(), mapped.last(), 0.001f)
        assertTrue(mapped.all { it.isFinite() && it in -15f..15f })
    }

    @Test
    fun bassTrebleAndDvcDoNotApplyAutomaticNegativeInputGain() {
        val settings = DspSettings(
            bassDb = 18.0,
            trebleDb = 16.0,
            compressor = CompressorSettings(makeupDb = 3.0),
            dvcEnabled = true
        )
        assertEquals(1.5, settings.effectiveInputGainDb(), 0.001)
        assertEquals(0.0, settings.copy(dvcEnabled = false).effectiveInputGainDb(), 0.001)
    }

    @Test
    fun turboGainIsPositiveAndBoundedToOnePointFiveDb() {
        assertEquals(0.0, DspSettings().effectiveInputGainDb(), 0.001)
        assertEquals(0.75, DspSettings(turboPercent = 50.0).effectiveInputGainDb(), 0.001)
        assertEquals(1.5, DspSettings(turboPercent = 100.0).effectiveInputGainDb(), 0.001)
        assertEquals(1_500, MonsterToneCurve.turboGainMilliBel(DspSettings(turboPercent = 100.0)))
    }

    @Test
    fun matrixZonesInterpolateAcrossAllThirtyTwoPreEqBands() {
        val settings = AcousticMatrix.withZones(
            DspSettings(),
            listOf(9.0, 7.0, 3.0, -2.0, 1.0, 4.0, 6.0, 8.0)
        )
        assertEquals(8, settings.matrixGainsDb.size)
        assertEquals(32, settings.effectiveBandGains().size)
        assertTrue(settings.effectiveBandGains().all { it.isFinite() && it in -15f..15f })
        assertEquals(GRAPHIC_EQ_FREQUENCIES.size, settings.bands.size)
    }

    @Test
    fun toneCurveUsesRequestedBassAndTrebleAnchorWeights() {
        val settings = DspSettings(bassDb = 10.0, trebleDb = 10.0, bassFrequencyHz = 20.0)
        assertEquals(10.5, MonsterToneCurve.bassGainDb(settings, 20.0), 0.001)
        assertEquals(12.6, MonsterToneCurve.bassGainDb(settings, 45.0), 0.1)
        assertEquals(3.5, MonsterToneCurve.trebleGainDb(settings, 4_000.0), 0.001)
        assertEquals(12.5, MonsterToneCurve.trebleGainDb(settings, 20_000.0), 0.001)
    }

    @Test
    fun v4SettingsRoundTripPreservesMatrixAndTurboValues() {
        val original = AcousticMatrix.withZones(
            DspSettings(bassDb = 15.0, trebleDb = 12.0, turboPercent = 75.0),
            listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0)
        )
        val restored = DspSettings.fromJsonString(original.toJsonString())
        assertEquals(original.bassDb, restored.bassDb, 0.001)
        assertEquals(original.trebleDb, restored.trebleDb, 0.001)
        assertEquals(original.turboPercent, restored.turboPercent, 0.001)
        assertEquals(original.matrixGainsDb, restored.matrixGainsDb)
    }

    @Test
    fun sixBandCompressorReducesLoudProgramMaterial() {
        val input = FloatArray(48_000 * 2) { index ->
            (0.8 * kotlin.math.sin(index * 2.0 * Math.PI * 440.0 / 48_000.0)).toFloat()
        }
        val beforePeak = input.maxOf { kotlin.math.abs(it) }
        BiquadFilterEngine(
            48_000,
            2,
            DspSettings(
                autoPreampEnabled = false,
                compressor = CompressorSettings(
                    enabled = true,
                    thresholdDb = -18.0,
                    ratio = 4.0,
                    attackMs = 5.0,
                    releaseMs = 100.0
                )
            )
        ).process(input)
        val afterPeak = input.maxOf { kotlin.math.abs(it) }
        assertTrue("The six-band dynamics stage must reduce peaks above threshold", afterPeak < beforePeak)
    }


    @Test
    fun autoPreampFlagNeverSubtractsGainFromToneBoosts() {
        val boosted = DspSettings(
            autoPreampEnabled = true,
            bands = DspSettings.logarithmicBands(32).mapIndexed { index, band ->
                if (index == 8) band.copy(gainDb = 6.0) else band
            }
        )
        assertEquals(0.0, boosted.effectiveInputGainDb(), 0.001)
        assertEquals(1.5, boosted.copy(dvcEnabled = true).effectiveInputGainDb(), 0.001)
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

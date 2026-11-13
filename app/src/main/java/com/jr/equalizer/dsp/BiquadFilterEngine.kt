package com.jr.equalizer.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

data class BiquadCoefficients(
    val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double
) {
    companion object {
        fun rbj(
            type: FilterType,
            sampleRate: Double,
            frequencyHz: Double,
            q: Double = 1.0,
            gainDb: Double = 0.0,
            shelfSlope: Double = 1.0
        ): BiquadCoefficients {
            require(sampleRate.isFinite() && sampleRate > 0.0)
            val f = frequencyHz.coerceIn(10.0, sampleRate * 0.49)
            val quality = q.coerceIn(0.1, 20.0)
            val slope = shelfSlope.coerceIn(0.1, 2.0)
            val omega = 2.0 * PI * f / sampleRate
            val c = cos(omega)
            val s = sin(omega)
            val a = 10.0.pow(gainDb / 40.0)
            val alpha = s / (2.0 * quality)
            var b0: Double
            var b1: Double
            var b2: Double
            var a0: Double
            var a1: Double
            var a2: Double
            when (type) {
                FilterType.PEAKING -> {
                    b0 = 1.0 + alpha * a
                    b1 = -2.0 * c
                    b2 = 1.0 - alpha * a
                    a0 = 1.0 + alpha / a
                    a1 = -2.0 * c
                    a2 = 1.0 - alpha / a
                }
                FilterType.LOW_SHELF, FilterType.HIGH_SHELF -> {
                    val shelfAlpha = s / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
                    val beta = 2.0 * sqrt(a) * shelfAlpha
                    if (type == FilterType.LOW_SHELF) {
                        b0 = a * ((a + 1.0) - (a - 1.0) * c + beta)
                        b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * c)
                        b2 = a * ((a + 1.0) - (a - 1.0) * c - beta)
                        a0 = (a + 1.0) + (a - 1.0) * c + beta
                        a1 = -2.0 * ((a - 1.0) + (a + 1.0) * c)
                        a2 = (a + 1.0) + (a - 1.0) * c - beta
                    } else {
                        b0 = a * ((a + 1.0) + (a - 1.0) * c + beta)
                        b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * c)
                        b2 = a * ((a + 1.0) + (a - 1.0) * c - beta)
                        a0 = (a + 1.0) - (a - 1.0) * c + beta
                        a1 = 2.0 * ((a - 1.0) - (a + 1.0) * c)
                        a2 = (a + 1.0) - (a - 1.0) * c - beta
                    }
                }
                FilterType.BAND_PASS -> {
                    b0 = s / 2.0; b1 = 0.0; b2 = -s / 2.0
                    a0 = 1.0 + alpha; a1 = -2.0 * c; a2 = 1.0 - alpha
                }
                FilterType.LOW_PASS -> {
                    b0 = (1.0 - c) / 2.0; b1 = 1.0 - c; b2 = (1.0 - c) / 2.0
                    a0 = 1.0 + alpha; a1 = -2.0 * c; a2 = 1.0 - alpha
                }
                FilterType.HIGH_PASS -> {
                    b0 = (1.0 + c) / 2.0; b1 = -(1.0 + c); b2 = (1.0 + c) / 2.0
                    a0 = 1.0 + alpha; a1 = -2.0 * c; a2 = 1.0 - alpha
                }
            }
            require(abs(a0) > 1.0e-15)
            return BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}

/**
 * Offline float-PCM DSP. It shares the graphic-band frequency grid and headroom
 * calculation with the live DynamicsProcessing engine. Android's vendor DSP
 * remains responsible for the live MBC implementation.
 */
class BiquadFilterEngine(
    private val sampleRate: Int,
    private val channels: Int,
    private val settings: DspSettings
) {
    private data class State(var z1: Double = 0.0, var z2: Double = 0.0)
    private data class Filter(val coeff: BiquadCoefficients, val state: Array<State>)

    private val filters: List<Filter>
    private val preamp = 10.0.pow(settings.effectiveInputGainDb() / 20.0)
    private val attack = exp(-1.0 / (sampleRate * settings.compressor.attackMs.coerceAtLeast(1.0) / 1000.0))
    private val release = exp(-1.0 / (sampleRate * settings.compressor.releaseMs.coerceAtLeast(10.0) / 1000.0))
    private val mbcEnvelopes = Array(channels) { DoubleArray(6) }
    private val mbcGains = Array(channels) { DoubleArray(6) { 1.0 } }
    // A program detector after the six-band recombination provides a final
    // wideband gain-control pass. This also makes the software export robust
    // when narrow crossover bands do not individually cross the threshold.
    private val programEnvelopes = DoubleArray(channels)
    private val programGains = DoubleArray(channels) { 1.0 }
    private val crossoverHz = doubleArrayOf(80.0, 200.0, 500.0, 1_500.0, 6_000.0)
    private val crossoverAlpha = DoubleArray(crossoverHz.size) { index ->
        exp(-2.0 * PI * crossoverHz[index] / sampleRate)
    }
    private val crossoverStates = Array(channels) { DoubleArray(crossoverHz.size) }
    private val bandScratch = Array(channels) { DoubleArray(6) }
    private val lowBandExpansionEnabled = settings.bassDb > 0.5
    private val lowBandExpansionThreshold = 10.0.pow(-32.0 / 20.0)
    private val threshold = 10.0.pow(settings.compressor.thresholdDb / 20.0)
    private val gateThreshold = 10.0.pow(settings.compressor.noiseGateThresholdDb / 20.0)
    private val makeup = 10.0.pow(settings.compressor.makeupDb / 20.0)
    private val ceiling = 10.0.pow(settings.compressor.limiterCeilingDb / 20.0)

    init {
        require(sampleRate > 0 && channels in 1..8)
        filters = buildList {
            add(
                Filter(
                    BiquadCoefficients.rbj(
                        FilterType.LOW_SHELF, sampleRate.toDouble(),
                        settings.bassFrequencyHz, 0.707, settings.bassDb, settings.bassQFactor
                    ),
                    Array(channels) { State() }
                )
            )
            GRAPHIC_EQ_FREQUENCIES.forEach { frequency ->
                val gain = DspSettings.gainAtFrequency(settings.bands, frequency)
                add(
                    Filter(
                        BiquadCoefficients.rbj(FilterType.PEAKING, sampleRate.toDouble(), frequency, 1.0, gain),
                        Array(channels) { State() }
                    )
                )
            }
            add(
                Filter(
                    BiquadCoefficients.rbj(
                        FilterType.HIGH_SHELF, sampleRate.toDouble(),
                        settings.trebleFrequencyHz, 0.707, settings.trebleDb, settings.trebleQFactor
                    ),
                    Array(channels) { State() }
                )
            )
        }
    }

    fun process(samples: FloatArray) {
        if (!settings.enabled || samples.isEmpty()) return
        val frameCount = samples.size / channels
        for (frame in 0 until frameCount) {
            val base = frame * channels
            for (ch in 0 until channels) {
                var value = samples[base + ch].toDouble() * preamp
                filters.forEach { filter ->
                    val z = filter.state[ch]
                    val c = filter.coeff
                    val y = c.b0 * value + z.z1
                    z.z1 = c.b1 * value - c.a1 * y + z.z2
                    z.z2 = c.b2 * value - c.a2 * y
                    value = y
                }
                if (ch < 2 && channels >= 2) {
                    val pan = if (ch == 0) settings.balance.coerceAtLeast(0.0) else (-settings.balance).coerceAtLeast(0.0)
                    value *= (1.0 - pan).coerceAtLeast(0.0)
                }
                if (
                    settings.compressor.enabled ||
                    settings.compressor.noiseGateEnabled ||
                    lowBandExpansionEnabled
                ) {
                    // Six complementary low-pass splits create six frequency
                    // bands whose sum is the original signal. This keeps the
                    // offline exporter from applying one broadband detector to
                    // the whole track while live playback uses Android MBC.
                    val states = crossoverStates[ch]
                    val splitBands = bandScratch[ch]
                    var previousLow = 0.0
                    for (split in crossoverHz.indices) {
                        val alpha = crossoverAlpha[split]
                        val low = alpha * states[split] + (1.0 - alpha) * value
                        states[split] = low
                        splitBands[split] = low - previousLow
                        previousLow = low
                    }
                    splitBands[5] = value - previousLow

                    var recombined = 0.0
                    for (band in 0 until 6) {
                        val bandSample = splitBands[band]
                        val magnitude = abs(bandSample)
                        val envelopes = mbcEnvelopes[ch]
                        val coefficient = if (magnitude > envelopes[band]) attack else release
                        envelopes[band] = coefficient * envelopes[band] + (1.0 - coefficient) * magnitude
                        val level = envelopes[band].coerceAtLeast(1.0e-9)
                        val compressed = if (settings.compressor.enabled && level > threshold) {
                            (threshold * (level / threshold).pow(1.0 / settings.compressor.ratio) / level)
                                .coerceAtMost(1.0)
                        } else 1.0
                        val gate = if (settings.compressor.noiseGateEnabled && level < gateThreshold) {
                            (level / gateThreshold).coerceAtLeast(0.0).pow(2.0)
                        } else 1.0
                        val bassExpansion = if (
                            band == 0 && lowBandExpansionEnabled && level < lowBandExpansionThreshold
                        ) {
                            (level / lowBandExpansionThreshold).coerceAtLeast(0.0).pow(1.25)
                        } else 1.0
                        val desired = compressed * gate * bassExpansion
                        val currentGain = mbcGains[ch][band]
                        val smoothing = if (desired < currentGain) attack else release
                        val nextGain = smoothing * currentGain + (1.0 - smoothing) * desired
                        mbcGains[ch][band] = nextGain
                        recombined += bandSample * nextGain *
                            if (settings.compressor.enabled) makeup else 1.0
                    }
                    // Keep a program-level detector after MBC. Android's
                    // live engine owns its native six-band dynamics; the offline
                    // RBJ renderer uses this final guard to give threshold/ratio
                    // controls a predictable result across all program material.
                    if (settings.compressor.enabled || settings.compressor.noiseGateEnabled) {
                        val magnitude = abs(recombined)
                        val envelopeCoefficient = if (magnitude > programEnvelopes[ch]) attack else release
                        programEnvelopes[ch] =
                            envelopeCoefficient * programEnvelopes[ch] +
                                (1.0 - envelopeCoefficient) * magnitude
                        val programLevel = programEnvelopes[ch].coerceAtLeast(1.0e-9)
                        val compressionGain =
                            if (settings.compressor.enabled && programLevel > threshold) {
                                (threshold * (programLevel / threshold)
                                    .pow(1.0 / settings.compressor.ratio) / programLevel)
                                    .coerceAtMost(1.0)
                            } else 1.0
                        val gateGain =
                            if (settings.compressor.noiseGateEnabled && programLevel < gateThreshold) {
                                (programLevel / gateThreshold).coerceIn(0.0, 1.0).pow(2.0)
                            } else 1.0
                        val desiredProgramGain = compressionGain * gateGain
                        val currentProgramGain = programGains[ch]
                        val gainCoefficient =
                            if (desiredProgramGain < currentProgramGain) attack else release
                        val nextProgramGain =
                            gainCoefficient * currentProgramGain +
                                (1.0 - gainCoefficient) * desiredProgramGain
                        programGains[ch] = nextProgramGain
                        recombined *= nextProgramGain
                    }
                    value = recombined
                }
                samples[base + ch] = value.toFloat()
            }

            if (channels >= 2 && settings.stereoWidthPercent != 100.0) {
                val left = samples[base].toDouble()
                val right = samples[base + 1].toDouble()
                val mid = (left + right) * 0.5
                val side = (left - right) * 0.5 * settings.stereoWidthPercent.coerceIn(0.0, 200.0) / 100.0
                samples[base] = (mid + side).toFloat()
                samples[base + 1] = (mid - side).toFloat()
            }

            for (ch in 0 until channels) {
                var value = samples[base + ch].toDouble()
                if (settings.compressor.limiterEnabled) {
                    value = if (settings.hardLimiter) {
                        value.coerceIn(-ceiling, ceiling)
                    } else {
                        tanh(value / ceiling.coerceAtLeast(1.0e-6)) * ceiling
                    }
                }
                samples[base + ch] = value.coerceIn(-1.0, 1.0).toFloat()
            }
        }
    }
}

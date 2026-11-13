package com.jr.equalizer.service

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import androidx.annotation.RequiresApi
import com.jr.equalizer.dsp.DspSettings
import com.jr.equalizer.dsp.GRAPHIC_EQ_FREQUENCIES
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ln

/**
 * Creates one fixed-shape DynamicsProcessing chain for each audio session.
 * Live changes are pushed into that existing chain; create()/release() are
 * reserved for session open/close events.
 */
object AudioSessionEffects {
    private const val PRE_EQ_BANDS = 32
    private const val MBC_BANDS = 6
    private const val POST_EQ_BANDS = 6

    private val postEqFrequencies = floatArrayOf(45f, 110f, 300f, 1_000f, 8_000f, 14_000f)
    private val mbcCutoffs = floatArrayOf(80f, 200f, 500f, 1_500f, 6_000f, 20_000f)

    private data class EngineState(
        var appliedPreEq: FloatArray,
        var inputGainDb: Float,
        var lastSettings: DspSettings
    )

    private data class OptionalEffects(
        var bassBoost: BassBoost? = null,
        var virtualizer: Virtualizer? = null,
        var loudnessEnhancer: LoudnessEnhancer? = null,
        var lastBassStrength: Int = Int.MIN_VALUE,
        var lastWidthStrength: Int = Int.MIN_VALUE,
        var lastLoudnessGainMb: Int = Int.MIN_VALUE,
        var lastEnabled: Boolean? = null
    )

    private val states = Collections.synchronizedMap(WeakHashMap<DynamicsProcessing, EngineState>())
    private val optionalEffects = Collections.synchronizedMap(WeakHashMap<DynamicsProcessing, OptionalEffects>())

    @RequiresApi(Build.VERSION_CODES.P)
    fun create(sessionId: Int, settings: DspSettings): DynamicsProcessing {
        val targetGains = targetPreEqGains(settings)
        val postEq = buildPostEq(settings)
        val mbc = buildMbc(settings)
        val limiter = buildLimiter(settings)

        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            true,
            PRE_EQ_BANDS,
            true,
            MBC_BANDS,
            true,
            POST_EQ_BANDS,
            true
        )
        val channel = DynamicsProcessing.Channel(
            settings.effectiveInputGainDb().toFloat(),
            true,
            PRE_EQ_BANDS,
            true,
            MBC_BANDS,
            true,
            POST_EQ_BANDS,
            true
        )
        val preEq = DynamicsProcessing.Eq(true, true, PRE_EQ_BANDS)
        GRAPHIC_EQ_FREQUENCIES.forEachIndexed { index, frequency ->
            preEq.setBand(
                index,
                DynamicsProcessing.EqBand(true, frequency.toFloat(), targetGains[index])
            )
        }
        channel.setPreEq(preEq)
        channel.setPostEq(postEq)
        channel.setMbc(mbc)
        channel.setLimiter(limiter)
        config.setAllChannelsTo(channel)

        val effect = DynamicsProcessing(0, sessionId, config.build()).apply {
            enabled = settings.enabled
        }
        states[effect] = EngineState(
            appliedPreEq = targetGains.copyOf(),
            inputGainDb = settings.effectiveInputGainDb().toFloat(),
            lastSettings = settings
        )
        optionalEffects[effect] = createOptionalEffects(sessionId, settings)
        return effect
    }

    /**
     * Updates the existing effect without releasing or reconstructing it.
     *
     * Returns true while an EQ/input-gain interpolation frame is still needed.
     * The caller runs this on its dedicated DSP HandlerThread at 16 ms cadence.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun update(effect: DynamicsProcessing, settings: DspSettings): Boolean {
        val state = states[effect] ?: EngineState(
            FloatArray(PRE_EQ_BANDS) { index ->
                runCatching { effect.getPreEqBandByChannelIndex(0, index).gain }.getOrDefault(0f)
            },
            runCatching { effect.getInputGainByChannelIndex(0) }.getOrDefault(0f),
            settings
        ).also { states[effect] = it }

        var interpolationPending = false
        val targetGains = targetPreEqGains(settings)
        targetGains.forEachIndexed { index, target ->
            val current = state.appliedPreEq[index]
            val next = approach(current, target, 1.0f)
            if (abs(next - current) >= 0.01f) {
                effect.setPreEqBandAllChannelsTo(
                    index,
                    DynamicsProcessing.EqBand(true, GRAPHIC_EQ_FREQUENCIES[index].toFloat(), next)
                )
                state.appliedPreEq[index] = next
            }
            if (abs(target - state.appliedPreEq[index]) > 0.01f) interpolationPending = true
        }

        val targetInputGain = settings.effectiveInputGainDb().toFloat()
        val nextInputGain = approach(state.inputGainDb, targetInputGain, 1.0f)
        if (abs(nextInputGain - state.inputGainDb) >= 0.01f) {
            effect.setInputGainAllChannelsTo(nextInputGain)
            state.inputGainDb = nextInputGain
        }
        if (abs(targetInputGain - state.inputGainDb) > 0.01f) interpolationPending = true

        val previous = state.lastSettings
        if (
            previous.bassDb != settings.bassDb ||
            previous.trebleDb != settings.trebleDb ||
            previous.bassFrequencyHz != settings.bassFrequencyHz ||
            previous.bassQFactor != settings.bassQFactor ||
            previous.trebleFrequencyHz != settings.trebleFrequencyHz ||
            previous.trebleQFactor != settings.trebleQFactor
        ) {
            val postEq = buildPostEq(settings)
            repeat(POST_EQ_BANDS) { index ->
                effect.setPostEqBandAllChannelsTo(index, postEq.getBand(index))
            }
        }

        if (previous.compressor != settings.compressor || previous.bassDb != settings.bassDb) {
            val mbc = buildMbc(settings)
            repeat(MBC_BANDS) { index ->
                effect.setMbcBandAllChannelsTo(index, mbc.getBand(index))
            }
        }

        if (previous.compressor != settings.compressor || previous.hardLimiter != settings.hardLimiter) {
            effect.setLimiterAllChannelsTo(buildLimiter(settings))
        }

        if (previous.enabled != settings.enabled) effect.enabled = settings.enabled
        val optionalControlsChanged =
            previous.enabled != settings.enabled ||
            previous.bassDb != settings.bassDb ||
            previous.stereoWidthPercent != settings.stereoWidthPercent ||
            previous.compressor.makeupDb != settings.compressor.makeupDb
        if (optionalControlsChanged) updateOptionalEffects(optionalEffects[effect], settings)
        state.lastSettings = settings
        return interpolationPending
    }

    fun release(effect: DynamicsProcessing?, sessionId: Int = -1) {
        if (effect == null) return
        optionalEffects.remove(effect)?.let { extras ->
            runCatching { extras.bassBoost?.release() }
            runCatching { extras.virtualizer?.release() }
            runCatching { extras.loudnessEnhancer?.release() }
        }
        states.remove(effect)
        runCatching { effect.release() }
    }

    private fun targetPreEqGains(settings: DspSettings): FloatArray =
        DspSettings.gainsForGraphicGrid(settings.bands).also { gains ->
            for (index in gains.indices) gains[index] = gains[index].coerceIn(-15f, 15f)
        }

    private fun buildPostEq(settings: DspSettings): DynamicsProcessing.Eq {
        val eq = DynamicsProcessing.Eq(true, true, POST_EQ_BANDS)
        val bass = settings.bassDb.coerceIn(0.0, 15.0).toFloat()
        val treble = settings.trebleDb.coerceIn(0.0, 12.0).toFloat()
        val bassFrequency = settings.bassFrequencyHz.toFloat().coerceIn(20f, 150f)
        // Keep all six cutoff frequencies strictly increasing. The bass
        // customization range reaches 150 Hz, so a fixed 110 Hz second
        // cutoff would otherwise produce an invalid native DynamicsProcessing
        // configuration on devices enforcing Android's ordered-band rule.
        val punchFrequency = (bassFrequency + 65f).coerceIn(80f, 250f)
        val customTreble = settings.trebleFrequencyHz.toFloat().coerceIn(4_000f, 13_900f)
        val bassWidth = ((3.0 - settings.bassQFactor.coerceIn(0.3, 3.0)) / 2.7)
            .toFloat().coerceIn(0f, 1f)
        val trebleWidth = ((3.0 - settings.trebleQFactor.coerceIn(0.3, 3.0)) / 2.7)
            .toFloat().coerceIn(0f, 1f)
        val frequencies = floatArrayOf(
            bassFrequency,
            punchFrequency,
            300f,
            1_000f,
            customTreble,
            14_000f
        )
        val gains = floatArrayOf(
            bass,
            (bass * (0.15f + 0.52f * bassWidth)).coerceIn(0f, 10f),
            (bass * 0.04f * bassWidth).coerceIn(0f, 1.0f),
            0f,
            (treble * (0.35f + 0.40f * trebleWidth)).coerceIn(0f, 9f),
            (treble * (0.65f + 0.35f * trebleWidth)).coerceIn(0f, 12f)
        )
        frequencies.forEachIndexed { index, frequency ->
            eq.setBand(index, DynamicsProcessing.EqBand(true, frequency, gains[index]))
        }
        return eq
    }

    private fun buildMbc(settings: DspSettings): DynamicsProcessing.Mbc {
        val compressor = settings.compressor
        val mbc = DynamicsProcessing.Mbc(true, true, MBC_BANDS)
        mbcCutoffs.forEachIndexed { index, cutoff ->
            val gateEnabled = compressor.noiseGateEnabled
            val bassExpansionActive = index == 0 && settings.bassDb > 0.5
            val makeupActive = abs(compressor.makeupDb) > 0.01
            val expander = when {
                gateEnabled -> 2f
                bassExpansionActive -> 1.25f
                else -> 1f
            }
            val expansionThreshold = when {
                gateEnabled -> compressor.noiseGateThresholdDb.toFloat().coerceIn(-100f, -30f)
                bassExpansionActive -> -32f
                else -> -120f
            }
            mbc.setBand(
                index,
                DynamicsProcessing.MbcBand(
                    compressor.enabled || gateEnabled || bassExpansionActive || makeupActive,
                    cutoff,
                    compressor.attackMs.toFloat().coerceIn(1f, 200f),
                    compressor.releaseMs.toFloat().coerceIn(10f, 1_000f),
                    if (compressor.enabled) compressor.ratio.toFloat().coerceIn(1f, 20f) else 1f,
                    if (compressor.enabled) compressor.thresholdDb.toFloat().coerceIn(-40f, 0f) else 0f,
                    if (compressor.enabled) 6f else 0f,
                    expansionThreshold,
                    expander,
                    0f,
                    compressor.makeupDb.toFloat().coerceIn(-12f, 6f)
                )
            )
        }
        return mbc
    }

    private fun buildLimiter(settings: DspSettings): DynamicsProcessing.Limiter {
        val compressor = settings.compressor
        return DynamicsProcessing.Limiter(
            compressor.limiterEnabled,
            true,
            0,
            compressor.attackMs.toFloat().coerceIn(1f, 200f),
            compressor.releaseMs.toFloat().coerceIn(10f, 1_000f),
            if (settings.hardLimiter) 20f else 4f,
            compressor.limiterCeilingDb.toFloat().coerceIn(-12f, 0f),
            0f
        )
    }

    private fun createOptionalEffects(sessionId: Int, settings: DspSettings): OptionalEffects {
        val result = OptionalEffects()
        runCatching {
            BassBoost(0, sessionId).also { effect ->
                if (effect.hasControl()) result.bassBoost = effect else effect.release()
            }
        }
        runCatching {
            Virtualizer(0, sessionId).also { effect ->
                if (effect.hasControl()) result.virtualizer = effect else effect.release()
            }
        }
        runCatching {
            LoudnessEnhancer(sessionId).also { effect ->
                if (effect.hasControl()) result.loudnessEnhancer = effect else effect.release()
            }
        }
        updateOptionalEffects(result, settings)
        return result
    }

    private fun updateOptionalEffects(effects: OptionalEffects?, settings: DspSettings) {
        if (effects == null) return
        val active = settings.enabled
        // PostEq already provides the full bass shelf. Keep BassBoost as a
        // modest hardware fill rather than stacking a second full-scale boost.
        val bassStrength = (settings.bassDb.coerceIn(0.0, 15.0) / 15.0 * 150.0).toInt()
        val widthStrength = ((settings.stereoWidthPercent - 100.0).coerceIn(0.0, 100.0) * 10.0).toInt()
        // Makeup is applied once inside the MBC post-gain, before the limiter.
        // A downstream LoudnessEnhancer could undo limiter/headroom protection.
        val makeupGainMb = 0

        if (effects.lastEnabled != active || effects.lastBassStrength != bassStrength) {
            runCatching {
                effects.bassBoost?.let { bass ->
                    bass.setStrength(bassStrength.toShort())
                    bass.enabled = active && bassStrength > 0
                }
            }
            effects.lastBassStrength = bassStrength
        }
        if (effects.lastEnabled != active || effects.lastWidthStrength != widthStrength) {
            runCatching {
                effects.virtualizer?.let { virtualizer ->
                    virtualizer.setStrength(widthStrength.toShort())
                    virtualizer.enabled = active && widthStrength > 0
                }
            }
            effects.lastWidthStrength = widthStrength
        }
        if (effects.lastEnabled != active || effects.lastLoudnessGainMb != makeupGainMb) {
            runCatching {
                effects.loudnessEnhancer?.let { enhancer ->
                    enhancer.setTargetGain(0)
                    enhancer.enabled = false
                }
            }
            effects.lastLoudnessGainMb = makeupGainMb
        }
        effects.lastEnabled = active
    }

    private fun approach(current: Float, target: Float, maximumStep: Float): Float =
        when {
            target > current -> minOf(current + maximumStep, target)
            target < current -> maxOf(current - maximumStep, target)
            else -> current
        }

    /**
     * Legacy output-mix fallback is retained for devices rejecting session-0
     * DynamicsProcessing. Existing fallback objects are updated in place too.
     */
    fun createLegacyOutputFallback(sessionId: Int, settings: DspSettings): List<AudioEffect> {
        val result = ArrayList<AudioEffect>(4)
        runCatching {
            Equalizer(Int.MAX_VALUE, sessionId).also { equalizer ->
                check(equalizer.hasControl()) { "Output Equalizer control was not granted." }
                equalizer.enabled = settings.enabled
            }
        }.onSuccess(result::add)

        runCatching {
            BassBoost(Int.MAX_VALUE, sessionId).also { bass ->
                check(bass.hasControl()) { "Output BassBoost control was not granted." }
                bass.enabled = settings.enabled
            }
        }.onSuccess(result::add)

        runCatching {
            Virtualizer(Int.MAX_VALUE, sessionId).also { virtualizer ->
                check(virtualizer.hasControl()) { "Output Virtualizer control was not granted." }
                virtualizer.enabled = settings.enabled
            }
        }.onSuccess(result::add)

        runCatching {
            LoudnessEnhancer(sessionId).also { enhancer ->
                enhancer.enabled = settings.enabled
            }
        }.onSuccess(result::add)

        if (result.none { it is Equalizer }) {
            result.forEach { runCatching { it.release() } }
            throw IllegalStateException("No legacy output equalizer accepted control.")
        }
        updateLegacyOutputFallback(result, settings)
        return result
    }

    fun updateLegacyOutputFallback(effects: List<AudioEffect>, settings: DspSettings) {
        effects.forEach { effect ->
            runCatching {
                when (effect) {
                    is Equalizer -> {
                        val range = effect.bandLevelRange
                        for (band in 0 until effect.numberOfBands.toInt()) {
                            val hzRange = effect.getBandFreqRange(band.toShort())
                            val centerHz = ((hzRange[0].toLong() + hzRange[1].toLong()) / 2_000.0)
                                .coerceAtLeast(20.0)
                            val gain = DspSettings.gainAtFrequency(settings.bands, centerHz) +
                                if (centerHz < 160.0) settings.bassDb else if (centerHz > 7_000.0) settings.trebleDb else 0.0
                            effect.setBandLevel(
                                band.toShort(),
                                gain.times(100.0).coerceIn(range[0].toDouble(), range[1].toDouble()).toInt().toShort()
                            )
                        }
                        effect.enabled = settings.enabled
                    }
                    is BassBoost -> {
                        val strength = (settings.bassDb.coerceIn(0.0, 15.0) / 15.0 * 220.0).toInt()
                        effect.setStrength(strength.toShort())
                        effect.enabled = settings.enabled && strength > 0
                    }
                    is Virtualizer -> {
                        val strength = ((settings.stereoWidthPercent - 100.0).coerceIn(0.0, 100.0) * 4.0).toInt()
                        effect.setStrength(strength.toShort())
                        effect.enabled = settings.enabled && strength > 0
                    }
                    is LoudnessEnhancer -> {
                        val gain = (settings.compressor.makeupDb.coerceIn(0.0, 2.0) * 100.0).toInt()
                        effect.setTargetGain(gain)
                        effect.enabled = settings.enabled && gain > 0
                    }
                }
            }
        }
    }
}

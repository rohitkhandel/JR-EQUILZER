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
import kotlin.math.abs
import kotlin.math.ln

object AudioSessionEffects {
    @RequiresApi(Build.VERSION_CODES.P)
    fun create(sessionId: Int, settings: DspSettings): DynamicsProcessing {
        val bandCount = settings.bands.size.coerceIn(5, 32)
        val mbcCount = 4
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            true,
            bandCount,
            true,
            mbcCount,
            true,
            bandCount,
            true
        )
        val channel = DynamicsProcessing.Channel(
            0.0f,
            true,
            bandCount,
            true,
            mbcCount,
            true,
            bandCount,
            true
        )
        val preEq = DynamicsProcessing.Eq(true, true, bandCount)
        val postEq = DynamicsProcessing.Eq(true, true, bandCount)

        settings.bands.forEachIndexed { index, band ->
            val cutoff = band.frequencyHz.toFloat().coerceIn(20f, 20_000f)
            preEq.setBand(index, DynamicsProcessing.EqBand(true, cutoff, 0f))
            postEq.setBand(index, DynamicsProcessing.EqBand(true, cutoff, band.gainDb.toFloat()))
        }
        channel.setInputGain(settings.preampDb.toFloat())
        channel.setPreEq(preEq)
        channel.setPostEq(postEq)

        val mbc = DynamicsProcessing.Mbc(true, true, mbcCount)
        val cutoffs = floatArrayOf(120f, 500f, 4_000f, 20_000f)
        settings.compressor.let { compressor ->
            for (index in 0 until mbcCount) {
                mbc.setBand(
                    index,
                    DynamicsProcessing.MbcBand(
                        compressor.enabled,
                        cutoffs[index],
                        compressor.attackMs.toFloat(),
                        compressor.releaseMs.toFloat(),
                        compressor.ratio.toFloat(),
                        compressor.thresholdDb.toFloat(),
                        6f,
                        -90f,
                        1f,
                        0f,
                        compressor.makeupDb.toFloat()
                    )
                )
            }
        }
        channel.setMbc(mbc)
        channel.setLimiter(
            DynamicsProcessing.Limiter(
                settings.compressor.limiterEnabled,
                settings.compressor.limiterEnabled,
                0,
                settings.compressor.attackMs.toFloat(),
                settings.compressor.releaseMs.toFloat(),
                settings.compressor.ratio.toFloat(),
                settings.compressor.limiterCeilingDb.toFloat(),
                0f
            )
        )
        config.setAllChannelsTo(channel)
        return DynamicsProcessing(0, sessionId, config.build()).apply {
            enabled = settings.enabled
        }
    }

    /**
     * Deprecated on Android for output-mix use. This is only attempted when the
     * device rejects DynamicsProcessing on session 0. Each effect is isolated so
     * one unsupported vendor implementation does not prevent other fallback stages.
     */
    fun createLegacyOutputFallback(sessionId: Int, settings: DspSettings): List<AudioEffect> {
        val result = ArrayList<AudioEffect>(4)

        runCatching {
            Equalizer(Int.MAX_VALUE, sessionId).also { equalizer ->
                val bandCount = equalizer.numberOfBands.toInt()
                val levelRange = equalizer.bandLevelRange
                for (band in 0 until bandCount) {
                    val range = equalizer.getBandFreqRange(band.toShort())
                    val centerHz = ((range[0].toLong() + range[1].toLong()) / 2L / 1000L)
                        .toDouble().coerceAtLeast(20.0)
                    val nearest = settings.bands.minByOrNull {
                        abs(ln(it.frequencyHz.coerceAtLeast(20.0)) - ln(centerHz))
                    }
                    val shelf = when {
                        centerHz < 160.0 -> settings.bassDb
                        centerHz > 7_000.0 -> settings.trebleDb
                        else -> 0.0
                    }
                    val gain = ((nearest?.gainDb ?: 0.0) + shelf) * 100.0
                    val safe = gain.coerceIn(levelRange[0].toDouble(), levelRange[1].toDouble())
                    equalizer.setBandLevel(band.toShort(), safe.toInt().toShort())
                }
                equalizer.enabled = settings.enabled
                check(equalizer.hasControl()) { "Output Equalizer control was not granted." }
                equalizer
            }.also(result::add)
        }

        runCatching {
            BassBoost(Int.MAX_VALUE, sessionId).also { bass ->
                bass.setStrength((settings.bassDb.coerceIn(0.0, 12.0) / 12.0 * 1000.0).toInt().toShort())
                bass.enabled = settings.enabled && settings.bassDb > 0.05
                check(bass.hasControl()) { "Output BassBoost control was not granted." }
            }
        }.onSuccess(result::add)

        runCatching {
            Virtualizer(Int.MAX_VALUE, sessionId).also { virtualizer ->
                val strength = ((settings.stereoWidthPercent - 100.0).coerceIn(0.0, 100.0) * 10.0)
                virtualizer.setStrength(strength.toInt().toShort())
                virtualizer.enabled = settings.enabled && strength > 0.0
                check(virtualizer.hasControl()) { "Output Virtualizer control was not granted." }
            }
        }.onSuccess(result::add)

        runCatching {
            LoudnessEnhancer(sessionId).also { enhancer ->
                enhancer.setTargetGain((settings.preampDb.coerceIn(0.0, 12.0) * 100.0).toInt())
                enhancer.enabled = settings.enabled && settings.preampDb > 0.05
            }
        }.onSuccess(result::add)

        if (result.none { it is Equalizer }) {
            result.forEach { runCatching { it.release() } }
            throw IllegalStateException("No legacy output equalizer accepted control.")
        }
        return result
    }
}

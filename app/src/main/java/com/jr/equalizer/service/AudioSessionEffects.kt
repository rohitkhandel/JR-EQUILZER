package com.jr.equalizer.service

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import androidx.annotation.RequiresApi
import com.jr.equalizer.dsp.DspSettings

object AudioSessionEffects {
    @RequiresApi(Build.VERSION_CODES.P)
    fun create(sessionId: Int, settings: DspSettings): DynamicsProcessing {
        val count = settings.bands.size.coerceIn(5, 32)
        val mbcCount = 4
        val builder = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2, true, count, true, mbcCount, true, count, true
        )
        val channel = DynamicsProcessing.Channel(0.0f, true, count, true, mbcCount, true, count, true)
        val preEq = DynamicsProcessing.Eq(true, true, count)
        val postEq = DynamicsProcessing.Eq(true, true, count)
        settings.bands.forEachIndexed { index, band ->
            val cutoff = band.frequencyHz.toFloat().coerceAtMost(20_000.0f)
            preEq.setBand(index, DynamicsProcessing.EqBand(true, cutoff, 0.0f))
            postEq.setBand(index, DynamicsProcessing.EqBand(true, cutoff, band.gainDb.toFloat()))
        }
        channel.setInputGain(settings.preampDb.toFloat())
        channel.setPreEq(preEq)
        channel.setPostEq(postEq)
        val mbc = DynamicsProcessing.Mbc(true, true, mbcCount)
        val cutoffs = floatArrayOf(120.0f, 500.0f, 4000.0f, 20000.0f)
        for (i in 0 until mbcCount) {
            mbc.setBand(
                i,
                DynamicsProcessing.MbcBand(
                    settings.compressor.enabled,
                    cutoffs[i],
                    settings.compressor.attackMs.toFloat(),
                    settings.compressor.releaseMs.toFloat(),
                    settings.compressor.ratio.toFloat(),
                    settings.compressor.thresholdDb.toFloat(),
                    6.0f, -90.0f, 1.0f, 0.0f,
                    settings.compressor.makeupDb.toFloat()
                )
            )
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
                0.0f
            )
        )
        builder.setAllChannelsTo(channel)
        builder.setInputGainAllChannelsTo(settings.preampDb.toFloat())
        return DynamicsProcessing(0, sessionId, builder.build()).apply {
            enabled = settings.enabled
        }
    }
}

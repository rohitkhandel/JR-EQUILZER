package com.jr.equalizer.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class FftAnalyzer(private val sampleRate: Int, private val size: Int = 1024, private val bands: Int = 24) {
    private val samples = FloatArray(size)
    private var position = 0

    fun push(input: FloatArray, channels: Int, callback: (FloatArray) -> Unit) {
        if (channels <= 0) return
        var i = 0
        while (i + channels <= input.size) {
            var mono = 0.0
            for (c in 0 until channels) mono += input[i + c]
            samples[position++] = (mono / channels).toFloat()
            i += channels
            if (position == size) {
                callback(analyze())
                position = 0
            }
        }
    }

    private fun analyze(): FloatArray {
        val real = DoubleArray(size)
        val imaginary = DoubleArray(size)
        for (i in 0 until size) {
            val window = 0.5 - 0.5 * cos(2.0 * PI * i / (size - 1))
            real[i] = samples[i] * window
        }
        fft(real, imaginary)
        val output = FloatArray(bands)
        val low = 30.0
        val high = minOf(18000.0, sampleRate / 2.0)
        for (b in 0 until bands) {
            val f0 = low * exp(ln(high / low) * b / bands)
            val f1 = low * exp(ln(high / low) * (b + 1.0) / bands)
            val first = (f0 * size / sampleRate).toInt().coerceIn(1, size / 2 - 1)
            val last = (f1 * size / sampleRate).toInt().coerceIn(first + 1, size / 2)
            var magnitude = 0.0
            for (bin in first until last) magnitude += sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
            val db = 20.0 * log10(magnitude / (last - first) + 1.0e-8)
            output[b] = ((db + 75.0) / 75.0).coerceIn(0.0, 1.0).toFloat()
        }
        return output
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val r = re[i]; re[i] = re[j]; re[j] = r
                val v = im[i]; im[i] = im[j]; im[j] = v
            }
        }
        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            val wr0 = cos(angle)
            val wi0 = sin(angle)
            var start = 0
            while (start < size) {
                var wr = 1.0
                var wi = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val tr = re[odd] * wr - im[odd] * wi
                    val ti = re[odd] * wi + im[odd] * wr
                    re[odd] = re[even] - tr; im[odd] = im[even] - ti
                    re[even] += tr; im[even] += ti
                    val nr = wr * wr0 - wi * wi0
                    wi = wr * wi0 + wi * wr0
                    wr = nr
                }
                start += length
            }
            length *= 2
        }
    }
}

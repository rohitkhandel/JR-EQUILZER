package com.jr.equalizer.dsp

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln

enum class FilterType { PEAKING, LOW_SHELF, HIGH_SHELF, BAND_PASS, LOW_PASS, HIGH_PASS }

val GRAPHIC_EQ_FREQUENCIES = doubleArrayOf(
    20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0,
    125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
    800.0, 1_000.0, 1_250.0, 1_600.0, 2_000.0, 2_500.0,
    3_150.0, 4_000.0, 5_000.0, 6_300.0, 8_000.0, 10_000.0,
    12_500.0, 16_000.0, 18_000.0, 20_000.0
)

@Immutable
data class EqBand(
    val frequencyHz: Double,
    val gainDb: Double,
    val q: Double = 1.0,
    val type: FilterType = FilterType.PEAKING
)

@Immutable
data class CompressorSettings(
    val enabled: Boolean = true,
    val thresholdDb: Double = -12.0,
    val ratio: Double = 3.0,
    val attackMs: Double = 12.0,
    val releaseMs: Double = 120.0,
    val makeupDb: Double = 0.0,
    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Double = -1.0,
    val noiseGateEnabled: Boolean = false,
    val noiseGateThresholdDb: Double = -75.0
)

@Immutable
data class DspSettings(
    val enabled: Boolean = true,
    val preampDb: Double = 0.0,
    val bassDb: Double = 0.0,
    val trebleDb: Double = 0.0,
    val stereoWidthPercent: Double = 100.0,
    val balance: Double = 0.0,
    val bands: List<EqBand> = logarithmicBands(10),
    val compressor: CompressorSettings = CompressorSettings(),
    val dvcEnabled: Boolean = false,
    val autoPreampEnabled: Boolean = true,
    val hardLimiter: Boolean = true,
    val bassFrequencyHz: Double = 45.0,
    val bassQFactor: Double = 0.7,
    val trebleFrequencyHz: Double = 8_000.0,
    val trebleQFactor: Double = 0.7
) {
    fun effectiveInputGainDb(): Double {
        if (!dvcEnabled && !autoPreampEnabled) return preampDb.coerceIn(-24.0, 12.0)
        val peakBandBoost = bands.maxOfOrNull { it.gainDb.coerceAtLeast(0.0) } ?: 0.0
        val toneAndMakeup = bassDb.coerceAtLeast(0.0) +
            trebleDb.coerceAtLeast(0.0) +
            compressor.makeupDb.coerceAtLeast(0.0)
        val requiredHeadroom = peakBandBoost + toneAndMakeup
        val automaticGain = if (autoPreampEnabled && requiredHeadroom > 0.01) {
            -(requiredHeadroom + 1.0)
        } else {
            preampDb
        }
        // Software DVC headroom is deliberately conservative: it lowers the
        // DSP input gain further, but does not claim to bypass Android's mixer.
        val dvcGain = if (dvcEnabled) -(requiredHeadroom + 2.0) else 12.0
        return minOf(preampDb, automaticGain, dvcGain).coerceIn(-36.0, 12.0)
    }

    fun toJsonString(): String {
        val root = JSONObject()
        root.put("schema", 2)
        root.put("enabled", enabled)
        root.put("preampDb", preampDb)
        root.put("bassDb", bassDb)
        root.put("trebleDb", trebleDb)
        root.put("stereoWidthPercent", stereoWidthPercent)
        root.put("balance", balance)
        root.put("dvcEnabled", dvcEnabled)
        root.put("autoPreampEnabled", autoPreampEnabled)
        root.put("hardLimiter", hardLimiter)
        root.put("bassFrequencyHz", bassFrequencyHz)
        root.put("bassQFactor", bassQFactor)
        root.put("trebleFrequencyHz", trebleFrequencyHz)
        root.put("trebleQFactor", trebleQFactor)

        val items = JSONArray()
        bands.forEach { band ->
            items.put(JSONObject().apply {
                put("frequencyHz", band.frequencyHz)
                put("gainDb", band.gainDb)
                put("q", band.q)
                put("type", band.type.name)
            })
        }
        root.put("bands", items)
        root.put("compressor", JSONObject().apply {
            put("enabled", compressor.enabled)
            put("thresholdDb", compressor.thresholdDb)
            put("ratio", compressor.ratio)
            put("attackMs", compressor.attackMs)
            put("releaseMs", compressor.releaseMs)
            put("makeupDb", compressor.makeupDb)
            put("limiterEnabled", compressor.limiterEnabled)
            put("limiterCeilingDb", compressor.limiterCeilingDb)
            put("noiseGateEnabled", compressor.noiseGateEnabled)
            put("noiseGateThresholdDb", compressor.noiseGateThresholdDb)
        })
        return root.toString(2)
    }

    companion object {
        private val tenBand = doubleArrayOf(
            31.5, 63.0, 125.0, 250.0, 500.0,
            1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0
        )
        private val sixteenBand = doubleArrayOf(
            25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0,
            1_000.0, 1_600.0, 2_500.0, 4_000.0, 6_300.0, 10_000.0, 16_000.0, 20_000.0
        )

        fun logarithmicBands(count: Int): List<EqBand> {
            val n = count.coerceIn(5, 32)
            val frequencies = when (n) {
                10 -> tenBand
                16 -> sixteenBand
                32 -> GRAPHIC_EQ_FREQUENCIES
                else -> {
                    val low = ln(20.0)
                    val high = ln(20_000.0)
                    DoubleArray(n) { index ->
                        val fraction = index.toDouble() / (n - 1)
                        exp(low + (high - low) * fraction)
                    }
                }
            }
            return frequencies.map { EqBand(it, 0.0) }
        }

        fun gainAtFrequency(bands: List<EqBand>, frequencyHz: Double): Double {
            if (bands.isEmpty()) return 0.0
            val sorted = bands.sortedBy { it.frequencyHz }
            val frequency = frequencyHz.coerceAtLeast(1.0)
            if (frequency <= sorted.first().frequencyHz) return sorted.first().gainDb
            if (frequency >= sorted.last().frequencyHz) return sorted.last().gainDb
            val upperIndex = sorted.indexOfFirst { it.frequencyHz >= frequency }.coerceAtLeast(1)
            val lower = sorted[upperIndex - 1]
            val upper = sorted[upperIndex]
            val range = (ln(upper.frequencyHz) - ln(lower.frequencyHz)).coerceAtLeast(1.0e-9)
            val fraction = ((ln(frequency) - ln(lower.frequencyHz)) / range).coerceIn(0.0, 1.0)
            return lower.gainDb + (upper.gainDb - lower.gainDb) * fraction
        }

        fun fromJsonString(json: String): DspSettings {
            val root = JSONObject(json)
            val sourceBands = root.optJSONArray("bands") ?: JSONArray()
            val parsed = ArrayList<EqBand>()
            for (i in 0 until sourceBands.length()) {
                val item = sourceBands.optJSONObject(i) ?: continue
                val kind = runCatching {
                    FilterType.valueOf(item.optString("type", "PEAKING"))
                }.getOrDefault(FilterType.PEAKING)
                parsed += EqBand(
                    item.optDouble("frequencyHz", 1_000.0).coerceIn(20.0, 20_000.0),
                    item.optDouble("gainDb", 0.0).coerceIn(-15.0, 15.0),
                    item.optDouble("q", 1.0).coerceIn(0.1, 12.0),
                    kind
                )
            }
            val c = root.optJSONObject("compressor") ?: JSONObject()
            return DspSettings(
                enabled = root.optBoolean("enabled", true),
                preampDb = root.optDouble("preampDb", 0.0).coerceIn(-24.0, 12.0),
                bassDb = root.optDouble("bassDb", 0.0).coerceIn(0.0, 15.0),
                trebleDb = root.optDouble("trebleDb", 0.0).coerceIn(0.0, 12.0),
                stereoWidthPercent = root.optDouble("stereoWidthPercent", 100.0).coerceIn(0.0, 200.0),
                balance = root.optDouble("balance", 0.0).coerceIn(-1.0, 1.0),
                bands = parsed.ifEmpty { logarithmicBands(10) },
                compressor = CompressorSettings(
                    enabled = c.optBoolean("enabled", true),
                    thresholdDb = c.optDouble("thresholdDb", -12.0).coerceIn(-40.0, 0.0),
                    ratio = c.optDouble("ratio", 3.0).coerceIn(1.0, 20.0),
                    attackMs = c.optDouble("attackMs", 12.0).coerceIn(1.0, 200.0),
                    releaseMs = c.optDouble("releaseMs", 120.0).coerceIn(10.0, 1_000.0),
                    makeupDb = c.optDouble("makeupDb", 0.0).coerceIn(-12.0, 6.0),
                    limiterEnabled = c.optBoolean("limiterEnabled", true),
                    limiterCeilingDb = c.optDouble("limiterCeilingDb", -1.0).coerceIn(-12.0, 0.0),
                    noiseGateEnabled = c.optBoolean("noiseGateEnabled", false),
                    noiseGateThresholdDb = c.optDouble("noiseGateThresholdDb", -75.0).coerceIn(-100.0, -30.0)
                ),
                dvcEnabled = root.optBoolean("dvcEnabled", false),
                autoPreampEnabled = root.optBoolean("autoPreampEnabled", true),
                hardLimiter = root.optBoolean("hardLimiter", true),
                bassFrequencyHz = root.optDouble("bassFrequencyHz", 45.0).coerceIn(20.0, 150.0),
                bassQFactor = root.optDouble("bassQFactor", 0.7).coerceIn(0.3, 3.0),
                trebleFrequencyHz = root.optDouble("trebleFrequencyHz", 8_000.0).coerceIn(4_000.0, 16_000.0),
                trebleQFactor = root.optDouble("trebleQFactor", 0.7).coerceIn(0.3, 3.0)
            )
        }
    }
}

@Immutable
data class EqPreset(val name: String, val settings: DspSettings)

object BuiltInPresets {
    private fun bands(values: List<Double>) =
        DspSettings.logarithmicBands(values.size).mapIndexed { index, band ->
            band.copy(gainDb = values[index].coerceIn(-15.0, 15.0))
        }

    private fun studio(
        name: String,
        bass: Double = 0.0,
        treble: Double = 0.0,
        gains: List<Double> = List(10) { 0.0 }
    ) = EqPreset(name, DspSettings(bassDb = bass, trebleDb = treble, bands = bands(gains)))

    val all = listOf(
        studio("Studio Flat"),
        studio("Bass Lift", 5.0, 0.0, listOf(4.0, 3.0, 2.0, 1.0, 0.0, -1.0, -1.0, 0.0, 1.0, 1.0)),
        studio("Deep Sub", 8.0, 0.0, listOf(7.0, 5.0, 2.0, 0.0, -1.0, -2.0, -1.0, 0.0, 0.0, -1.0)),
        studio("Vocal Boost", 0.0, 1.0, listOf(-2.0, -1.0, 0.0, 1.5, 2.0, 2.5, 2.0, 1.0, 0.0, -1.0)),
        studio("Podcast Voice", 0.0, 0.5, listOf(-3.0, -2.0, -1.0, 1.0, 3.0, 3.0, 2.0, 0.0, -1.0, -2.0)),
        studio("Acoustic", 1.0, 1.5, listOf(2.0, 1.0, 0.0, 1.0, 2.0, 2.0, 1.5, 2.0, 2.5, 2.0)),
        studio("Jazz", 1.5, 1.5, listOf(3.0, 2.0, 0.5, 1.0, -1.0, -1.0, 0.5, 1.5, 2.0, 2.5)),
        studio("Rock", 2.5, 2.0, listOf(4.0, 3.0, -1.0, -2.0, 0.0, 1.5, 3.0, 3.5, 4.0, 3.0)),
        studio("Metal", 3.0, 2.0, listOf(4.0, 3.0, 1.0, -2.0, -1.0, 2.0, 3.0, 2.0, 3.0, 3.0)),
        studio("Pop", 2.0, 2.0, listOf(2.0, 3.0, 1.0, 0.0, -1.0, 0.0, 1.0, 2.0, 2.5, 2.0)),
        studio("Hip-Hop", 5.0, 1.0, listOf(5.0, 4.0, 2.0, 0.0, -1.0, 0.0, 1.0, 1.0, 2.0, 1.0)),
        studio("EDM", 4.5, 2.5, listOf(5.0, 4.0, 1.0, -1.0, -2.0, 1.0, 2.0, 3.0, 3.5, 3.0))
    )
}

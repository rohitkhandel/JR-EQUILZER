package com.jr.equalizer.dsp

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln

enum class FilterType { PEAKING, LOW_SHELF, HIGH_SHELF, BAND_PASS, LOW_PASS, HIGH_PASS }

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
    val limiterCeilingDb: Double = -1.0
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
    val compressor: CompressorSettings = CompressorSettings()
) {
    fun toJsonString(): String {
        val root = JSONObject()
        root.put("schema", 1)
        root.put("enabled", enabled)
        root.put("preampDb", preampDb)
        root.put("bassDb", bassDb)
        root.put("trebleDb", trebleDb)
        root.put("stereoWidthPercent", stereoWidthPercent)
        root.put("balance", balance)
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
        })
        return root.toString(2)
    }

    companion object {
        fun logarithmicBands(count: Int): List<EqBand> {
            val n = count.coerceIn(5, 32)
            val low = ln(20.0)
            val high = ln(20_000.0)
            return List(n) { index ->
                val f = if (n == 1) 0.0 else index.toDouble() / (n - 1)
                EqBand(exp(low + (high - low) * f), 0.0)
            }
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
                    item.optDouble("frequencyHz", 1000.0).coerceIn(20.0, 20_000.0),
                    item.optDouble("gainDb", 0.0).coerceIn(-24.0, 24.0),
                    item.optDouble("q", 1.0).coerceIn(0.1, 12.0),
                    kind
                )
            }
            val c = root.optJSONObject("compressor") ?: JSONObject()
            return DspSettings(
                enabled = root.optBoolean("enabled", true),
                preampDb = root.optDouble("preampDb", 0.0).coerceIn(-24.0, 12.0),
                bassDb = root.optDouble("bassDb", 0.0).coerceIn(-18.0, 18.0),
                trebleDb = root.optDouble("trebleDb", 0.0).coerceIn(-18.0, 18.0),
                stereoWidthPercent = root.optDouble("stereoWidthPercent", 100.0).coerceIn(0.0, 200.0),
                balance = root.optDouble("balance", 0.0).coerceIn(-1.0, 1.0),
                bands = parsed.ifEmpty { logarithmicBands(10) },
                compressor = CompressorSettings(
                    enabled = c.optBoolean("enabled", true),
                    thresholdDb = c.optDouble("thresholdDb", -12.0).coerceIn(-60.0, 0.0),
                    ratio = c.optDouble("ratio", 3.0).coerceIn(1.0, 20.0),
                    attackMs = c.optDouble("attackMs", 12.0).coerceIn(0.1, 200.0),
                    releaseMs = c.optDouble("releaseMs", 120.0).coerceIn(10.0, 2000.0),
                    makeupDb = c.optDouble("makeupDb", 0.0).coerceIn(-12.0, 18.0),
                    limiterEnabled = c.optBoolean("limiterEnabled", true),
                    limiterCeilingDb = c.optDouble("limiterCeilingDb", -1.0).coerceIn(-12.0, 0.0)
                )
            )
        }
    }
}

@Immutable
data class EqPreset(val name: String, val settings: DspSettings)

object BuiltInPresets {
    private fun bands(values: List<Double>) =
        DspSettings.logarithmicBands(values.size).mapIndexed { index, band ->
            band.copy(gainDb = values[index])
        }

    private fun studio(
        name: String,
        bass: Double = 0.0,
        treble: Double = 0.0,
        gains: List<Double> = List(10) { 0.0 }
    ) = EqPreset(name, DspSettings(bassDb = bass, trebleDb = treble, bands = bands(gains)))

    val all = listOf(
        studio("Studio Flat"),
        studio("Poweramp Bass Extreme", 10.0, 1.0, listOf(8.0, 7.0, 5.0, 2.0, 0.0, -1.0, 0.0, 1.0, 2.0, 2.0)),
        studio("Bass Lift", 5.0, 0.0, listOf(4.0, 3.0, 2.0, 1.0, 0.0, -1.0, -1.0, 0.0, 1.0, 1.0)),
        studio("Deep Sub", 8.0, -1.0, listOf(7.0, 5.0, 2.0, 0.0, -1.0, -2.0, -1.0, 0.0, 0.0, -1.0)),
        studio("Dolby Atmos Style", 3.0, 2.0, listOf(3.0, 1.0, -1.0, 0.0, 1.0, 1.5, 2.0, 2.5, 3.0, 2.0)),
        studio("Harman Target (approx.)", 2.5, 1.0, listOf(3.0, 2.0, 0.5, -0.5, -1.0, 0.0, 1.0, 1.5, 2.0, 1.0)),
        studio("Vocal Boost", 0.0, 1.0, listOf(-2.0, -1.0, 0.0, 1.5, 2.0, 2.5, 2.0, 1.0, 0.0, -1.0)),
        studio("Podcast Voice", -1.0, 0.0, listOf(-3.0, -2.0, -1.0, 1.0, 3.0, 3.0, 2.0, 0.0, -1.0, -2.0)),
        studio("Acoustic", 1.0, 1.5, listOf(2.0, 1.0, 0.0, 1.0, 2.0, 2.0, 1.5, 2.0, 2.5, 2.0)),
        studio("Jazz", 1.5, 1.5, listOf(3.0, 2.0, 0.5, 1.0, -1.0, -1.0, 0.5, 1.5, 2.0, 2.5)),
        studio("Rock", 2.5, 2.0, listOf(4.0, 3.0, -1.0, -2.0, 0.0, 1.5, 3.0, 3.5, 4.0, 3.0)),
        studio("Metal", 3.0, 2.0, listOf(4.0, 3.0, 1.0, -2.0, -1.0, 2.0, 3.0, 2.0, 3.0, 3.0)),
        studio("Pop", 2.0, 2.0, listOf(2.0, 3.0, 1.0, 0.0, -1.0, 0.0, 1.0, 2.0, 2.5, 2.0)),
        studio("Latin Urban", 4.0, 2.0, listOf(5.0, 4.0, 1.0, -1.0, -1.0, 0.5, 1.5, 2.0, 2.5, 2.0)),
        studio("Hip-Hop", 5.0, 1.0, listOf(5.0, 4.0, 2.0, 0.0, -1.0, 0.0, 1.0, 1.0, 2.0, 1.0)),
        studio("EDM", 4.5, 2.5, listOf(5.0, 4.0, 1.0, -1.0, -2.0, 1.0, 2.0, 3.0, 3.5, 3.0)),
        studio("Electronic", 4.0, 2.0, listOf(4.5, 3.5, 1.5, 0.0, -1.0, 1.0, 0.5, 2.0, 3.0, 2.0))
    )
}

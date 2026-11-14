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
    val limiterCeilingDb: Double = -0.2,
    val noiseGateEnabled: Boolean = false,
    val noiseGateThresholdDb: Double = -75.0
)

@Immutable
data class DspSettings(
    val enabled: Boolean = true,
    val preampDb: Double = 0.0,
    val bassDb: Double = 0.0,
    val trebleDb: Double = 0.0,
    val vocalPresenceDb: Double = 0.0,
    val turboPercent: Double = 0.0,
    val stereoWidthPercent: Double = 100.0,
    val balance: Double = 0.0,
    val bands: List<EqBand> = logarithmicBands(32),
    val matrixGainsDb: List<Double> = emptyList(),
    val compressor: CompressorSettings = CompressorSettings(),
    val dvcEnabled: Boolean = false,
    val autoPreampEnabled: Boolean = false,
    val hardLimiter: Boolean = true,
    val bassFrequencyHz: Double = 45.0,
    val bassQFactor: Double = 0.7,
    val trebleFrequencyHz: Double = 8_000.0,
    val trebleQFactor: Double = 0.7
) {
    /**
     * No automatic negative preamp is applied. Turbo is a small, explicit
     * positive input trim; the fixed downstream limiter catches reconstructed peaks.
     */
    fun effectiveInputGainDb(): Double {
        val manualGain = preampDb.coerceIn(-24.0, 12.0)
        val turbo = maxOf(turboPercent.coerceIn(0.0, 100.0), if (dvcEnabled) 100.0 else 0.0)
        return (manualGain + turbo / 100.0 * 1.5).coerceIn(-24.0, 13.5)
    }

    fun effectiveBandGains(): FloatArray = AcousticMatrix.gainsForGraphicGrid(this)

    fun effectiveGainAtFrequency(frequencyHz: Double): Double =
        if (matrixGainsDb.size == AcousticMatrix.zones.size) {
            AcousticMatrix.gainAt(matrixGainsDb, frequencyHz)
        } else {
            gainAtFrequency(bands, frequencyHz)
        }

    fun zoneGainValues(): List<Double> = AcousticMatrix.zoneValues(this)

    fun toJsonString(): String {
        val root = JSONObject()
        root.put("schema", 3)
        root.put("enabled", enabled)
        root.put("preampDb", preampDb)
        root.put("bassDb", bassDb)
        root.put("trebleDb", trebleDb)
        root.put("vocalPresenceDb", vocalPresenceDb)
        root.put("turboPercent", turboPercent)
        root.put("stereoWidthPercent", stereoWidthPercent)
        root.put("balance", balance)
        root.put("dvcEnabled", dvcEnabled)
        root.put("autoPreampEnabled", autoPreampEnabled)
        root.put("hardLimiter", true)
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
        val zones = JSONArray()
        matrixGainsDb.forEach { zones.put(it) }
        root.put("matrixGainsDb", zones)
        root.put("compressor", JSONObject().apply {
            put("enabled", compressor.enabled)
            put("thresholdDb", compressor.thresholdDb)
            put("ratio", compressor.ratio)
            put("attackMs", compressor.attackMs)
            put("releaseMs", compressor.releaseMs)
            put("makeupDb", compressor.makeupDb)
            put("limiterEnabled", true)
            put("limiterCeilingDb", -0.2)
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

        fun gainsForGraphicGrid(bands: List<EqBand>): FloatArray {
            if (bands.isEmpty()) return FloatArray(GRAPHIC_EQ_FREQUENCIES.size)
            if (bands.size == GRAPHIC_EQ_FREQUENCIES.size &&
                bands.indices.all { bands[it].frequencyHz == GRAPHIC_EQ_FREQUENCIES[it] }
            ) {
                return FloatArray(GRAPHIC_EQ_FREQUENCIES.size) { index ->
                    bands[index].gainDb.toFloat().coerceIn(-15f, 15f)
                }
            }
            val sorted = bands.sortedBy { it.frequencyHz }
            return FloatArray(GRAPHIC_EQ_FREQUENCIES.size) { index ->
                gainAtSortedFrequency(sorted, GRAPHIC_EQ_FREQUENCIES[index]).toFloat()
            }
        }

        fun gainAtFrequency(bands: List<EqBand>, frequencyHz: Double): Double =
            gainAtSortedFrequency(bands.sortedBy { it.frequencyHz }, frequencyHz)

        private fun gainAtSortedFrequency(sorted: List<EqBand>, frequencyHz: Double): Double {
            if (sorted.isEmpty()) return 0.0
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
            val sourceZones = root.optJSONArray("matrixGainsDb") ?: JSONArray()
            val zones = ArrayList<Double>()
            for (i in 0 until sourceZones.length()) {
                zones += sourceZones.optDouble(i, 0.0).coerceIn(-12.0, 15.0)
            }
            val parsedBands = parsed.ifEmpty { logarithmicBands(32) }
            val c = root.optJSONObject("compressor") ?: JSONObject()
            return DspSettings(
                enabled = root.optBoolean("enabled", true),
                preampDb = root.optDouble("preampDb", 0.0).coerceIn(-24.0, 12.0),
                bassDb = root.optDouble("bassDb", 0.0).coerceIn(0.0, 18.0),
                trebleDb = root.optDouble("trebleDb", 0.0).coerceIn(0.0, 16.0),
                vocalPresenceDb = root.optDouble("vocalPresenceDb", 0.0).coerceIn(0.0, 12.0),
                turboPercent = root.optDouble("turboPercent", if (root.optBoolean("dvcEnabled", false)) 100.0 else 0.0).coerceIn(0.0, 100.0),
                stereoWidthPercent = root.optDouble("stereoWidthPercent", 100.0).coerceIn(100.0, 200.0),
                balance = root.optDouble("balance", 0.0).coerceIn(-1.0, 1.0),
                bands = parsedBands,
                matrixGainsDb = zones.takeIf { it.size == AcousticMatrix.zones.size } ?: emptyList(),
                compressor = CompressorSettings(
                    enabled = c.optBoolean("enabled", true),
                    thresholdDb = c.optDouble("thresholdDb", -12.0).coerceIn(-40.0, 0.0),
                    ratio = c.optDouble("ratio", 3.0).coerceIn(1.0, 20.0),
                    attackMs = c.optDouble("attackMs", 12.0).coerceIn(1.0, 200.0),
                    releaseMs = c.optDouble("releaseMs", 120.0).coerceIn(10.0, 1_000.0),
                    makeupDb = c.optDouble("makeupDb", 0.0).coerceIn(-12.0, 6.0),
                    limiterEnabled = true,
                    limiterCeilingDb = -0.2,
                    noiseGateEnabled = c.optBoolean("noiseGateEnabled", false),
                    noiseGateThresholdDb = c.optDouble("noiseGateThresholdDb", -75.0).coerceIn(-100.0, -30.0)
                ),
                dvcEnabled = root.optBoolean("dvcEnabled", false),
                autoPreampEnabled = false,
                hardLimiter = true,
                bassFrequencyHz = root.optDouble("bassFrequencyHz", 45.0).coerceIn(20.0, 90.0),
                bassQFactor = root.optDouble("bassQFactor", 0.7).coerceIn(0.3, 3.0),
                trebleFrequencyHz = root.optDouble("trebleFrequencyHz", 8_000.0).coerceIn(4_000.0, 20_000.0),
                trebleQFactor = root.optDouble("trebleQFactor", 0.7).coerceIn(0.3, 3.0)
            )
        }
    }
}

@Immutable
data class AcousticZone(
    val name: String,
    val rangeLabel: String,
    val lowHz: Double,
    val highHz: Double,
    val centerHz: Double = kotlin.math.sqrt(lowHz * highHz)
)

object AcousticMatrix {
    val zones = listOf(
        AcousticZone("Sub-Bass", "20–40 Hz", 20.0, 40.0),
        AcousticZone("Deep Bass", "50–80 Hz", 50.0, 80.0),
        AcousticZone("Upper Bass", "100–160 Hz", 100.0, 160.0),
        AcousticZone("Low-Mids", "250–400 Hz", 250.0, 400.0),
        AcousticZone("Center Vocals", "500 Hz–1.2 kHz", 500.0, 1_200.0),
        AcousticZone("Presence", "2–4 kHz", 2_000.0, 4_000.0),
        AcousticZone("Brilliance", "6–10 kHz", 6_000.0, 10_000.0),
        AcousticZone("Ultra-Air", "12–20 kHz", 12_000.0, 20_000.0)
    )

    fun zoneValues(settings: DspSettings): List<Double> =
        if (settings.matrixGainsDb.size == zones.size) {
            settings.matrixGainsDb.map { it.coerceIn(-12.0, 15.0) }
        } else {
            zones.map { DspSettings.gainAtFrequency(settings.bands, it.centerHz).coerceIn(-12.0, 15.0) }
        }

    fun gainAt(gains: List<Double>, frequencyHz: Double): Double {
        if (gains.isEmpty()) return 0.0
        val values = zones.indices.map { index ->
            gains.getOrElse(index) { 0.0 }.coerceIn(-12.0, 15.0)
        }
        val frequency = frequencyHz.coerceIn(20.0, 20_000.0)
        if (frequency <= zones.first().centerHz) return values.first()
        if (frequency >= zones.last().centerHz) return values.last()
        val upper = zones.indexOfFirst { it.centerHz >= frequency }.coerceAtLeast(1)
        val lowZone = zones[upper - 1]
        val highZone = zones[upper]
        val fraction = ((ln(frequency) - ln(lowZone.centerHz)) /
            (ln(highZone.centerHz) - ln(lowZone.centerHz))).coerceIn(0.0, 1.0)
        return values[upper - 1] + (values[upper] - values[upper - 1]) * fraction
    }

    fun gainsForGraphicGrid(settings: DspSettings): FloatArray {
        if (settings.matrixGainsDb.size != zones.size) {
            return DspSettings.gainsForGraphicGrid(settings.bands)
        }
        return FloatArray(GRAPHIC_EQ_FREQUENCIES.size) { index ->
            gainAt(settings.matrixGainsDb, GRAPHIC_EQ_FREQUENCIES[index]).toFloat().coerceIn(-15f, 15f)
        }
    }

    fun withZoneGain(settings: DspSettings, index: Int, gainDb: Double): DspSettings {
        if (index !in zones.indices) return settings
        val next = zoneValues(settings).toMutableList()
        next[index] = gainDb.coerceIn(-12.0, 15.0)
        val mapped = FloatArray(GRAPHIC_EQ_FREQUENCIES.size) { bandIndex ->
            gainAt(next, GRAPHIC_EQ_FREQUENCIES[bandIndex]).toFloat().coerceIn(-12f, 15f)
        }
        val bands = GRAPHIC_EQ_FREQUENCIES.mapIndexed { bandIndex, frequency ->
            EqBand(frequency, mapped[bandIndex].toDouble())
        }
        return settings.copy(matrixGainsDb = next, bands = bands)
    }

    fun flat(settings: DspSettings): DspSettings = withZones(settings, List(zones.size) { 0.0 })

    fun withZones(settings: DspSettings, values: List<Double>): DspSettings {
        val normalized = zones.indices.map { values.getOrElse(it) { 0.0 }.coerceIn(-12.0, 15.0) }
        val mapped = FloatArray(GRAPHIC_EQ_FREQUENCIES.size) { index ->
            gainAt(normalized, GRAPHIC_EQ_FREQUENCIES[index]).toFloat().coerceIn(-12f, 15f)
        }
        val bands = GRAPHIC_EQ_FREQUENCIES.mapIndexed { index, frequency ->
            EqBand(frequency, mapped[index].toDouble())
        }
        return settings.copy(matrixGainsDb = normalized, bands = bands)
    }
}

object MonsterToneCurve {
    val postEqCutoffsHz = floatArrayOf(
        20f, 31.5f, 45f, 63f, 80f, 100f, 125f, 250f,
        1_000f, 4_000f, 6_300f, 8_000f, 10_000f, 12_500f, 16_000f, 20_000f
    )

    private val bassPoints = listOf(
        20.0 to 1.00, 31.5 to 1.15, 45.0 to 1.25, 63.0 to 1.15,
        80.0 to 0.90, 100.0 to 0.55, 125.0 to 0.25, 250.0 to 0.0
    )
    private val treblePoints = listOf(
        2_000.0 to 0.0, 4_000.0 to 0.35, 6_300.0 to 0.65,
        8_000.0 to 0.90, 10_000.0 to 1.10, 12_500.0 to 1.20,
        16_000.0 to 1.25, 20_000.0 to 1.25
    )

    private fun weightAt(points: List<Pair<Double, Double>>, frequencyHz: Double): Double {
        val frequency = frequencyHz.coerceAtLeast(1.0)
        if (frequency <= points.first().first) return points.first().second
        if (frequency >= points.last().first) return points.last().second
        val upper = points.indexOfFirst { it.first >= frequency }.coerceAtLeast(1)
        val low = points[upper - 1]
        val high = points[upper]
        val fraction = ((ln(frequency) - ln(low.first)) / (ln(high.first) - ln(low.first))).coerceIn(0.0, 1.0)
        return low.second + (high.second - low.second) * fraction
    }

    fun bassGainDb(settings: DspSettings, frequencyHz: Double): Double {
        val base = settings.bassDb.coerceIn(0.0, 18.0) * weightAt(bassPoints, frequencyHz)
        val punchCenter = settings.bassFrequencyHz.coerceIn(20.0, 90.0)
        val distance = ln(frequencyHz.coerceAtLeast(1.0) / punchCenter) / 0.42
        val selectedPunch = settings.bassDb.coerceIn(0.0, 18.0) * 0.05 * exp(-0.5 * distance * distance)
        return base + selectedPunch
    }

    fun trebleGainDb(settings: DspSettings, frequencyHz: Double): Double =
        settings.trebleDb.coerceIn(0.0, 16.0) * weightAt(treblePoints, frequencyHz)

    fun vocalPresenceGainDb(settings: DspSettings, frequencyHz: Double): Double {
        val distance = ln(frequencyHz.coerceAtLeast(1.0) / 1_100.0) / 0.72
        return settings.vocalPresenceDb.coerceIn(0.0, 12.0) * exp(-0.5 * distance * distance)
    }

    fun postEqGainDb(settings: DspSettings, frequencyHz: Double): Double =
        bassGainDb(settings, frequencyHz) + trebleGainDb(settings, frequencyHz)

    fun mbcPunchPostGainDb(settings: DspSettings, bandIndex: Int): Double {
        val bassPunch = settings.bassDb.coerceIn(0.0, 18.0) / 18.0
        val lowBandGain = when (bandIndex) {
            0 -> 6.0 * bassPunch
            1 -> 2.0 * bassPunch
            else -> 0.0
        }
        return (settings.compressor.makeupDb + lowBandGain).coerceIn(-12.0, 12.0)
    }

    fun turboGainMilliBel(settings: DspSettings): Int {
        val percent = maxOf(settings.turboPercent.coerceIn(0.0, 100.0), if (settings.dvcEnabled) 100.0 else 0.0)
        return (percent * 15.0).toInt().coerceIn(0, 1_500)
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

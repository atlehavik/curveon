package com.curveon.data.obd

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One registered fuel level: how many litres the rider knew were in the tank, and what every
 * signal the bike gave said at that moment (see [FuelSignal] for the keys).
 */
data class FuelPoint(val liters: Float, val signals: Map<String, Int>, val at: Long)

/**
 * The fuel gauge's set-up (Settings → Engine → Fuel gauge): the tank size, the levels registered so
 * far (the tank table) and which signal of the bike follows the fuel level. Kept between starts;
 * the levels can be registered over several refuellings.
 */
data class FuelCalibration(
    val tankLiters: Float? = null,
    val points: List<FuelPoint> = emptyList(),
    val source: String? = null
) {
    /** Levels that differ enough to tell apart, which is what finding the signal needs. */
    val distinctLevels get() = points.map { it.liters }.sorted().fold(emptyList<Float>()) { out, l ->
        if (out.isEmpty() || l - out.last() >= MIN_LEVEL_GAP) out + l else out
    }.size

    /** The litres the bike's own [raw] value stands for, from the table; null while no signal is chosen. */
    fun liters(raw: Int): Float? {
        val src = source ?: return null
        val table = points.mapNotNull { p -> p.signals[src]?.let { it to p.liters } }
            .groupBy({ it.first }, { it.second }).map { (r, ls) -> r to ls.average().toFloat() }.sortedBy { it.first }
        if (table.size < 2) return null
        val lo = table.first(); val hi = table.last()
        if (raw <= lo.first) return lo.second.coerceAtLeast(0f)
        if (raw >= hi.first) return hi.second
        val i = table.indexOfLast { it.first <= raw }
        val a = table[i]; val b = table[i + 1]
        return a.second + (b.second - a.second) * (raw - a.first) / (b.first - a.first)
    }

    companion object {
        const val MIN_LEVEL_GAP = 0.3f
        /** Levels needed before the signal can be told from the many that change for other reasons. */
        const val LEVELS_NEEDED = 3
    }
}

/** A signal from the bike that seems to follow the fuel level, with the raw value at each registered level. */
data class FuelCandidate(val signal: String, val readings: List<Pair<Float, Int>>, val score: Float)

object FuelAnalysis {

    /**
     * The signals that follow the registered levels: present at every level, steadily rising or
     * falling with the litres, the best fit first. Needs [FuelCalibration.LEVELS_NEEDED] levels:
     * with two, anything that changed would fit. Engine data known to be something else (revs,
     * speed, temperatures, run time) is left out.
     */
    fun candidates(points: List<FuelPoint>): List<FuelCandidate> {
        val levels = points.sortedBy { it.liters }
        if (FuelCalibration(points = points).distinctLevels < FuelCalibration.LEVELS_NEEDED) return emptyList()
        val keys = levels.map { it.signals.keys }.reduce { a, b -> a intersect b }
        return keys.filter { !isKnownOther(it) }.mapNotNull { key ->
            val readings = levels.map { it.liters to it.signals.getValue(key) }
            if (readings.map { it.second }.distinct().size < 3 || !steady(readings)) return@mapNotNull null
            val r = abs(correlation(readings.map { it.first.toDouble() }, readings.map { it.second.toDouble() }))
            if (r < MIN_FIT) null else FuelCandidate(key, readings, r.toFloat())
        }.sortedWith(compareByDescending<FuelCandidate> { it.score }.thenBy { FuelSignal.sortKey(it.signal) })
    }

    /** Never goes back the other way as the litres grow (equal neighbours at about the same litres are fine). */
    private fun steady(readings: List<Pair<Float, Int>>): Boolean {
        val up = readings.zipWithNext().all { (a, b) -> b.second >= a.second - SLACK }
        val down = readings.zipWithNext().all { (a, b) -> b.second <= a.second + SLACK }
        return up || down
    }

    private fun correlation(x: List<Double>, y: List<Double>): Double {
        val mx = x.average(); val my = y.average()
        val sxy = x.indices.sumOf { (x[it] - mx) * (y[it] - my) }
        val sxx = x.sumOf { (it - mx) * (it - mx) }
        val syy = y.sumOf { (it - my) * (it - my) }
        return if (sxx == 0.0 || syy == 0.0) 0.0 else sxy / sqrt(sxx * syy)
    }

    private fun isKnownOther(signal: String): Boolean {
        val s = FuelSignal.parse(signal) ?: return true
        return s.isPid && s.id in NOT_FUEL
    }

    private const val MIN_FIT = 0.9
    private const val SLACK = 1

    /** Standard engine data that is not the fuel level but moves with the engine or the clock. */
    private val NOT_FUEL = setOf(
        "0104", "0105", "0106", "0107", "0108", "0109", "010A", "010B", "010C", "010D", "010E", "010F",
        "0110", "0111", "011F", "0121", "0123", "0131", "0133", "0142", "0143", "0144", "0145", "0146",
        "0147", "0149", "014A", "014C", "015C"
    )
}

/**
 * A signal's key: where on the bike it comes from and which bytes. "pid:0151:0" is the first data
 * byte of the standard engine data 0151, "can:3F0:2-3" bytes 3 and 4 (as one number) of the bus
 * message 3F0.
 */
data class FuelSignal(val isPid: Boolean, val id: String, val from: Int, val to: Int) {
    val key get() = (if (isPid) "pid:" else "can:") + id + ":" + if (from == to) "$from" else "$from-$to"

    /** The value in [bytes] (null when they are too short). */
    fun value(bytes: List<Int>): Int? {
        if (to >= bytes.size) return null
        return if (from == to) bytes[from] else bytes[from] * 256 + bytes[to]
    }

    companion object {
        fun parse(key: String): FuelSignal? {
            val p = key.split(':')
            if (p.size != 3 || (p[0] != "pid" && p[0] != "can")) return null
            val r = p[2].split('-').mapNotNull { it.toIntOrNull() }
            if (r.isEmpty() || r.size > 2) return null
            return FuelSignal(p[0] == "pid", p[1], r.first(), r.last())
        }

        /** Engine data first, then bus messages, one byte before two: the likelier on top when the fit is equal. */
        fun sortKey(key: String): String {
            val s = parse(key) ?: return "z$key"
            return (if (s.isPid) "a" else "b") + (if (s.from == s.to) "a" else "b") + key
        }
    }
}

/** Keeps the [FuelCalibration] in the app's settings. */
class FuelCalibrationStore(private val prefs: SharedPreferences) {

    fun load(): FuelCalibration = runCatching {
        val o = JSONObject(prefs.getString(KEY, null) ?: return FuelCalibration())
        FuelCalibration(
            tankLiters = if (o.has("tank")) o.getDouble("tank").toFloat() else null,
            source = o.optString("source").ifEmpty { null },
            points = (0 until o.optJSONArray("points").let { it?.length() ?: 0 }).map { i ->
                val p = o.getJSONArray("points").getJSONObject(i)
                val s = p.getJSONObject("s")
                FuelPoint(p.getDouble("l").toFloat(), s.keys().asSequence().associateWith { s.getInt(it) }, p.optLong("at"))
            }
        )
    }.getOrDefault(FuelCalibration())

    fun save(c: FuelCalibration) {
        val o = JSONObject()
        c.tankLiters?.let { o.put("tank", it.toDouble()) }
        c.source?.let { o.put("source", it) }
        o.put("points", JSONArray().apply {
            c.points.forEach { p ->
                put(JSONObject().put("l", p.liters.toDouble()).put("at", p.at).put("s", JSONObject().apply { p.signals.forEach { (k, v) -> put(k, v) } }))
            }
        })
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    private companion object { const val KEY = "fuelCalibration" }
}

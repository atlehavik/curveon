package com.curveon.data.obd

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One registered fuel level: how many litres the rider knew were in the tank, and what every
 * signal the bike gave said at that moment (see [FuelSignal] for the keys). The two levels of one
 * refuelling share a [session] (the time of the "before" reading); 0 is a level registered alone.
 */
data class FuelPoint(val liters: Float, val signals: Map<String, Int>, val at: Long, val session: Long = 0L)

/** What the bike said just before the rider started to fill up, waiting for the litres from the pump. */
data class PendingFill(val signals: Map<String, Int>, val at: Long)

/**
 * The fuel gauge's set-up (Settings → Engine → Fuel gauge): the tank size, the levels registered so
 * far (the tank table) and which signal of the bike follows the fuel level. Kept between starts;
 * the levels can be registered over several refuellings.
 */
data class FuelCalibration(
    val tankLiters: Float? = null,
    val points: List<FuelPoint> = emptyList(),
    val source: String? = null,
    val before: PendingFill? = null
) {
    /** Refuellings registered: each is a before and an after reading of litres known from the pump. */
    val sessions get() = points.map { it.session }.filter { it != 0L }.distinct().size

    /**
     * Without the level registered at [at], and the other level of the same refuelling (alone it tells
     * nothing). The chosen reading goes too when what is left no longer singles it out, so numbers
     * from a mistaken entry are never kept in use.
     */
    fun without(at: Long): FuelCalibration {
        val session = points.firstOrNull { it.at == at }?.session ?: 0L
        val left = points.filter { it.at != at && (session == 0L || it.session != session) }
        return copy(points = left, source = source?.takeIf { s -> FuelAnalysis.candidates(left).any { it.signal == s } })
    }

    /**
     * The refuelling is done: [pumped] litres went in and the tank was filled to the brim, so the
     * tank now holds [tankLiters] and held that minus [pumped] before. Null when there is nothing to
     * finish, no tank size, or the numbers can't be right.
     */
    fun withRefuel(after: Map<String, Int>, pumped: Float, now: Long): FuelCalibration? {
        val start = before ?: return null
        val tank = tankLiters ?: return null
        if (pumped < MIN_FILL || pumped > tank || now - start.at > MAX_FILL_GAP_MS) return null
        return copy(
            before = null,
            points = points + FuelPoint(tank - pumped, start.signals, start.at, session = start.at) +
                FuelPoint(tank, after, now, session = start.at)
        )
    }

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
        /** Refuellings needed: one shows what changes with the fuel, the next that it does so every time. */
        const val SESSIONS_NEEDED = 2
        /** Less than this from the pump is too little to tell from noise. */
        const val MIN_FILL = 1f
        /** The tank must still be as it was left when the pump is done: riding in between would spoil it. */
        const val MAX_FILL_GAP_MS = 30 * 60_000L
    }
}

/** A signal from the bike that seems to follow the fuel level, with the raw value at each registered level. */
data class FuelCandidate(val signal: String, val readings: List<Pair<Float, Int>>, val score: Float)

object FuelAnalysis {

    /**
     * What stayed the same in two readings of the same moment, a little apart: whatever moved in
     * between (counters, clocks, anything fast) is not the fuel level. The value kept is the middle one.
     */
    fun stable(first: Map<String, Int>, second: Map<String, Int>): Map<String, Int> =
        first.mapNotNull { (k, a) -> second[k]?.takeIf { abs(it - a) <= STEADY }?.let { k to (a + it) / 2 } }.toMap()

    /**
     * The signals that follow the fuel, the best fit first. A signal must
     *  - be present at every registered level, and steadily rise or fall with the litres (r ≥ [MIN_FIT]);
     *  - read about the same every time the tank was full, which weeds out whatever drifts with time
     *    (odometer, run time, temperatures that follow the season);
     *  - move the same way, by a similar amount per litre, in every refuelling: what the fuel does in
     *    minutes at the pump is the one thing nothing else on the bike does.
     * Needs [FuelCalibration.SESSIONS_NEEDED] refuellings and [FuelCalibration.LEVELS_NEEDED] levels:
     * with fewer, anything that changed would fit. Engine data known to be something else (revs,
     * speed, temperatures, run time) is left out.
     */
    fun candidates(points: List<FuelPoint>): List<FuelCandidate> {
        val levels = points.sortedBy { it.liters }
        val cal = FuelCalibration(points = points)
        if (cal.distinctLevels < FuelCalibration.LEVELS_NEEDED || cal.sessions < FuelCalibration.SESSIONS_NEEDED) return emptyList()
        val keys = levels.map { it.signals.keys }.reduce { a, b -> a intersect b }
        return keys.filter { !isKnownOther(it) }.mapNotNull { key ->
            val readings = levels.map { it.liters to it.signals.getValue(key) }
            if (readings.map { it.second }.distinct().size < 3 || !steady(readings)) return@mapNotNull null
            val r = correlation(readings.map { it.first.toDouble() }, readings.map { it.second.toDouble() })
            if (abs(r) < MIN_FIT) return@mapNotNull null
            if (!sameWhenSameLevel(levels, key) || !movesWithEveryFill(points, key, r > 0)) return@mapNotNull null
            FuelCandidate(key, readings, abs(r).toFloat())
        }.sortedWith(compareByDescending<FuelCandidate> { it.score }.thenBy { FuelSignal.sortKey(it.signal) })
    }

    /** Levels with the same litres (the full tank, each refuelling) must read about the same, whenever they were taken. */
    private fun sameWhenSameLevel(levels: List<FuelPoint>, key: String): Boolean {
        val values = levels.map { it.signals.getValue(key) }
        val allowed = maxOf(STEADY, ((values.max() - values.min()) * SAME_LEVEL_SPREAD).toInt())
        return levels.groupBy { Math.round(it.liters / FuelCalibration.MIN_LEVEL_GAP) }.values.all { group ->
            val v = group.map { it.signals.getValue(key) }
            v.max() - v.min() <= allowed
        }
    }

    /** In each refuelling the reading changes in the direction of the fit, and by a similar amount per litre. */
    private fun movesWithEveryFill(points: List<FuelPoint>, key: String, rising: Boolean): Boolean {
        val perLitre = points.filter { it.session != 0L }.groupBy { it.session }.values.mapNotNull { pair ->
            val lo = pair.minBy { it.liters }; val hi = pair.maxBy { it.liters }
            val litres = hi.liters - lo.liters
            if (litres < FuelCalibration.MIN_FILL) return@mapNotNull null
            val change = (hi.signals.getValue(key) - lo.signals.getValue(key)).toDouble() * if (rising) 1 else -1
            if (change < MIN_JUMP) return false
            change / litres
        }
        return perLitre.size >= FuelCalibration.SESSIONS_NEEDED && perLitre.max() / perLitre.min() <= MAX_SLOPE_RATIO
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

    private const val MIN_FIT = 0.95
    private const val SLACK = 1
    /** Two readings of the same moment may differ this much (the float sloshes a little). */
    const val STEADY = 4
    /** The same level may read this share of the whole range apart on different days. */
    private const val SAME_LEVEL_SPREAD = 0.1
    /** A refuelling must move the reading at least this much, or it is noise. */
    private const val MIN_JUMP = 2
    /** The reading per litre may differ this much between refuellings (gauges are not quite linear). */
    private const val MAX_SLOPE_RATIO = 3.0

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
                FuelPoint(p.getDouble("l").toFloat(), s.keys().asSequence().associateWith { s.getInt(it) }, p.optLong("at"), p.optLong("session"))
            },
            before = o.optJSONObject("before")?.let { b ->
                val s = b.getJSONObject("s")
                PendingFill(s.keys().asSequence().associateWith { s.getInt(it) }, b.getLong("at"))
            }
        )
    }.getOrDefault(FuelCalibration())

    fun save(c: FuelCalibration) {
        val o = JSONObject()
        c.tankLiters?.let { o.put("tank", it.toDouble()) }
        c.source?.let { o.put("source", it) }
        o.put("points", JSONArray().apply {
            c.points.forEach { p ->
                put(JSONObject().put("l", p.liters.toDouble()).put("at", p.at).put("session", p.session).put("s", JSONObject().apply { p.signals.forEach { (k, v) -> put(k, v) } }))
            }
        })
        c.before?.let { b -> o.put("before", JSONObject().put("at", b.at).put("s", JSONObject().apply { b.signals.forEach { (k, v) -> put(k, v) } })) }
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    private companion object { const val KEY = "fuelCalibration" }
}

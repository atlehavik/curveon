package com.curveon.pmtiles

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/** A region as closed (lon, lat) rings, filled even-odd (so holes work). */
class Region(val rings: List<List<DoubleArray>>) {
    val minLon = rings.flatten().minOf { it[0] }
    val maxLon = rings.flatten().maxOf { it[0] }
    val minLat = rings.flatten().minOf { it[1] }
    val maxLat = rings.flatten().maxOf { it[1] }

    companion object {
        /** Reads a GeoJSON Polygon/MultiPolygon (Feature or bare geometry) without a JSON library. */
        fun fromGeoJson(json: String): Region {
            val coords = json.substringAfter("\"coordinates\"").substringAfter(':')
            val numbers = Regex("-?\\d+(\\.\\d+)?([eE]-?\\d+)?").findAll(coords).map { it.value.toDouble() }.toList()
            // Depth tells rings apart: a ring closes when its last point equals its first.
            val rings = mutableListOf<MutableList<DoubleArray>>()
            var current = mutableListOf<DoubleArray>()
            var i = 0
            while (i + 1 < numbers.size) {
                val p = doubleArrayOf(numbers[i], numbers[i + 1])
                current += p
                if (current.size > 3 && p[0] == current[0][0] && p[1] == current[0][1]) {
                    rings += current; current = mutableListOf()
                }
                i += 2
            }
            if (current.size > 2) rings += current
            return Region(rings)
        }
    }
}

data class ExtractProgress(val phase: String, val doneBytes: Long, val totalBytes: Long) {
    val fraction get() = if (totalBytes > 0) (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
}

/**
 * Cuts [region] (zoom 0..[maxZoom]) out of a remote PMTiles archive using HTTP range requests,
 * the same job as `pmtiles extract`. Tiles are fetched in large merged chunks to keep the number
 * of requests low; the result is written as a new, clustered PMTiles file.
 */
class RegionExtractor(private val http: OkHttpClient = OkHttpClient()) {

    fun extract(
        url: String,
        region: Region,
        maxZoom: Int,
        out: File,
        onProgress: (ExtractProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ) {
        onProgress(ExtractProgress("Reading map index", 0, 0))
        val head = get(url, 0, 16_384)
        val h = Pmtiles.readHeader(head)
        val zMax = min(maxZoom, h.maxZoom)

        // 1. Which tiles do we want? (sorted ids; ~6 M for Norway at zoom 14, kept as a LongArray)
        val wanted = coverTiles(region, h.minZoom, zMax)

        // 2. Walk the directory tree. Matches go into primitive arrays as runs of consecutive ids
        //    sharing one tile – millions of tiles must not become objects on a phone-sized heap.
        val fId = LongList(); val fOff = LongList(); val fLen = IntList(); val fRun = IntList()
        var pendingOff = LongList(); var pendingLen = LongList()

        fun process(d: Pmtiles.Dir) {
            for (i in 0 until d.size) {
                val id = d.ids[i]
                if (d.runs[i] > 0) {
                    val end = id + d.runs[i]
                    var k = lowerBound(wanted, id)
                    while (k < wanted.size && wanted[k] < end) {
                        val first = wanted[k]
                        var run = 1
                        while (k + run < wanted.size && wanted[k + run] == first + run && wanted[k + run] < end) run++
                        fId.add(first); fOff.add(d.offsets[i]); fLen.add(d.lengths[i]); fRun.add(run)
                        k += run
                    }
                } else {
                    val end = if (i + 1 < d.size) d.ids[i + 1] else Long.MAX_VALUE
                    val k = lowerBound(wanted, id)
                    if (k < wanted.size && wanted[k] < end) { pendingOff.add(h.leafOffset + d.offsets[i]); pendingLen.add(d.lengths[i].toLong()) }
                }
            }
        }

        val rootBytes = if (h.rootOffset + h.rootLength <= head.size) head.copyOfRange(h.rootOffset.toInt(), (h.rootOffset + h.rootLength).toInt())
        else get(url, h.rootOffset, h.rootLength)
        process(Pmtiles.parseDirectory(Pmtiles.decompress(rootBytes, h.internalCompression)))
        var depth = 0
        while (pendingOff.size > 0) {
            check(depth++ < 8) { "Directory too deep" }
            val offs = pendingOff; val lens = pendingLen
            pendingOff = LongList(); pendingLen = LongList()
            var done = 0
            // Leaves are contiguous on the server: fetch neighbours together, parse one at a time.
            forEachMergedRange(url, offs, lens, LEAF_GAP) { start, bytes, members ->
                check(!isCancelled()) { "Cancelled" }
                for (m in members) {
                    val o = (offs[m] - start).toInt()
                    process(Pmtiles.parseDirectory(Pmtiles.decompress(bytes.copyOfRange(o, o + lens[m].toInt()), h.internalCompression)))
                }
                done += members.size
                onProgress(ExtractProgress("Reading map index ($done of ${offs.size} parts)", 0, 0))
            }
        }
        val n = fId.size
        val addressed = (0 until n).sumOf { fRun[it].toLong() }

        // 3. Unique tile contents in source order, and each one's offset in the new file.
        //    Sorting packs (offset, index) into one long: offsets < 2^37, indexes < 2^26.
        check(n < (1 shl 26)) { "Region too large" }
        val byOffset = LongArray(n) { (fOff[it] shl 26) or it.toLong() }
        byOffset.sort()
        val newOff = LongArray(n)
        val uniqOff = LongList(); val uniqLen = IntList()
        var cursor = 0L
        for (key in byOffset) {
            val idx = (key and ((1L shl 26) - 1)).toInt()
            if (uniqOff.size == 0 || uniqOff[uniqOff.size - 1] != fOff[idx]) {
                uniqOff.add(fOff[idx]); uniqLen.add(fLen[idx]); cursor += fLen[idx]
            }
            newOff[idx] = cursor - fLen[idx]
        }
        val total = cursor

        // 4. New directory in tile-id order, merging runs that now point at the same tile.
        //    Sorting packs (tileId, index): ids < 2^37 up to zoom 18, indexes < 2^26.
        val byId = LongArray(n) { (fId[it] shl 26) or it.toLong() }
        byId.sort()
        val eId = LongList(); val eOff = LongList(); val eLen = IntList(); val eRun = IntList()
        for (key in byId) {
            val idx = (key and ((1L shl 26) - 1)).toInt()
            val last = eId.size - 1
            if (last >= 0 && eOff[last] == newOff[idx] && eId[last] + eRun[last] == fId[idx]) eRun[last] = eRun[last] + fRun[idx]
            else { eId.add(fId[idx]); eOff.add(newOff[idx]); eLen.add(fLen[idx]); eRun.add(fRun[idx]) }
        }
        val (root, leafBytes) = buildDirectories(eId.toArray(), eOff.toArray(), eLen.toArray(), eRun.toArray())
        val metadata = if (h.metadataLength > 0) get(url, h.metadataOffset, h.metadataLength) else Pmtiles.gzip("{}".toByteArray())

        val rootOffset = Pmtiles.HEADER_SIZE.toLong()
        val metaOffset = rootOffset + root.size
        val leafOffset = metaOffset + metadata.size
        val dataOffset = leafOffset + leafBytes.size
        val header = h.copy(
            rootOffset = rootOffset, rootLength = root.size.toLong(),
            metadataOffset = metaOffset, metadataLength = metadata.size.toLong(),
            leafOffset = leafOffset, leafLength = leafBytes.size.toLong(),
            dataOffset = dataOffset, dataLength = total,
            addressedTiles = addressed, tileEntries = eId.size.toLong(), tileContents = uniqOff.size.toLong(),
            clustered = true, internalCompression = Pmtiles.COMPRESSION_GZIP,
            minZoom = h.minZoom, maxZoom = zMax,
            minLonE7 = (region.minLon * 1e7).toInt(), minLatE7 = (region.minLat * 1e7).toInt(),
            maxLonE7 = (region.maxLon * 1e7).toInt(), maxLatE7 = (region.maxLat * 1e7).toInt(),
            centerZoom = min(zMax, 6),
            centerLonE7 = (((region.minLon + region.maxLon) / 2) * 1e7).toInt(),
            centerLatE7 = (((region.minLat + region.maxLat) / 2) * 1e7).toInt()
        )

        // 5. Write header, directories and metadata, then stream the tiles in merged chunks.
        val tmpOut = File(out.parentFile, out.name + ".part")
        var written = 0L
        tmpOut.outputStream().buffered(1 shl 20).use { o ->
            o.write(Pmtiles.writeHeader(header)); o.write(root); o.write(metadata); o.write(leafBytes)
            var i = 0
            while (i < uniqOff.size) {
                check(!isCancelled()) { "Cancelled" }
                var j = i
                while (j + 1 < uniqOff.size) {
                    val gap = uniqOff[j + 1] - (uniqOff[j] + uniqLen[j])
                    val size = uniqOff[j + 1] + uniqLen[j + 1] - uniqOff[i]
                    if (gap > DATA_GAP || size > CHUNK) break
                    j++
                }
                val start = uniqOff[i]
                val chunk = get(url, h.dataOffset + start, uniqOff[j] + uniqLen[j] - start)
                for (k in i..j) {
                    o.write(chunk, (uniqOff[k] - start).toInt(), uniqLen[k])
                    written += uniqLen[k]
                }
                onProgress(ExtractProgress("Downloading map", written, total))
                i = j + 1
            }
        }
        check(written == total) { "Downloaded $written of $total bytes" }
        if (!tmpOut.renameTo(out)) {
            out.delete()
            check(tmpOut.renameTo(out)) { "Could not move the new map into place" }
        }
        onProgress(ExtractProgress("Done", written, total))
    }

    /** Root directory that fits in the first 16 kB, with leaf directories when needed. */
    private fun buildDirectories(ids: LongArray, offs: LongArray, lens: IntArray, runs: IntArray): Pair<ByteArray, ByteArray> {
        val single = Pmtiles.gzip(Pmtiles.serializeDirectory(ids, offs, lens, runs, 0, ids.size))
        if (single.size <= Pmtiles.ROOT_MAX) return single to ByteArray(0)
        var leafSize = 4096
        while (true) {
            val leaves = java.io.ByteArrayOutputStream()
            val count = (ids.size + leafSize - 1) / leafSize
            val rIds = LongArray(count); val rOffs = LongArray(count); val rLens = IntArray(count); val rRuns = IntArray(count)
            for (c in 0 until count) {
                val from = c * leafSize; val to = min(ids.size, from + leafSize)
                val bytes = Pmtiles.gzip(Pmtiles.serializeDirectory(ids, offs, lens, runs, from, to))
                rIds[c] = ids[from]; rOffs[c] = leaves.size().toLong(); rLens[c] = bytes.size; rRuns[c] = 0
                leaves.write(bytes)
            }
            val root = Pmtiles.gzip(Pmtiles.serializeDirectory(rIds, rOffs, rLens, rRuns, 0, count))
            if (root.size <= Pmtiles.ROOT_MAX) return root to leaves.toByteArray()
            leafSize *= 2
        }
    }

    /** Groups ranges that are close together into single requests; calls [block] per request. */
    private fun forEachMergedRange(url: String, offs: LongList, lens: LongList, maxGap: Long, block: (Long, ByteArray, List<Int>) -> Unit) {
        val order = (0 until offs.size).sortedBy { offs[it] }
        var i = 0
        while (i < order.size) {
            var j = i
            var end = offs[order[i]] + lens[order[i]]
            while (j + 1 < order.size && offs[order[j + 1]] - end <= maxGap && offs[order[j + 1]] + lens[order[j + 1]] - offs[order[i]] <= CHUNK) {
                j++
                end = max(end, offs[order[j]] + lens[order[j]])
            }
            val start = offs[order[i]]
            block(start, get(url = url, offset = start, length = end - start), order.subList(i, j + 1))
            i = j + 1
        }
    }

    private fun get(url: String, offset: Long, length: Long): ByteArray {
        var lastError: IOException? = null
        repeat(4) { attempt ->
            try {
                val req = Request.Builder().url(url).header("Range", "bytes=$offset-${offset + length - 1}").build()
                http.newCall(req).execute().use { r ->
                    if (r.code != 206 && r.code != 200) throw IOException("HTTP ${r.code}")
                    val b = r.body!!.bytes()
                    if (r.code == 200 && offset > 0) throw IOException("Server ignored the range request")
                    if (b.size.toLong() < length && r.code == 206) throw IOException("Short read")
                    return if (b.size.toLong() > length) b.copyOf(length.toInt()) else b
                }
            } catch (e: IOException) {
                lastError = e
                Thread.sleep(1000L * (attempt + 1))
            }
        }
        throw lastError!!
    }

    private fun lowerBound(a: LongArray, v: Long): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < v) lo = m + 1 else hi = m }
        return lo
    }

    companion object {
        private const val LEAF_GAP = 256L * 1024
        private const val DATA_GAP = 64L * 1024
        private const val CHUNK = 16L * 1024 * 1024

        /** Sorted ids of all tiles at zoom [minZ]..[maxZ] that touch the region. */
        fun coverTiles(region: Region, minZ: Int, maxZ: Int): LongArray {
            val ids = LongList()
            for (z in minZ..maxZ) {
                val n = 1 shl z
                val x0 = lonToX(region.minLon, n); val x1 = lonToX(region.maxLon, n)
                val y0 = latToY(region.maxLat, n); val y1 = latToY(region.minLat, n)
                for (y in y0..y1) {
                    val top = yToLat(y, n); val bottom = yToLat(y + 1, n)
                    for ((a, b) in rowIntervals(region, bottom, top)) {
                        for (x in max(x0, lonToX(a, n))..min(x1, lonToX(b, n))) ids.add(Pmtiles.tileId(z, x, y))
                    }
                }
            }
            return ids.toArray().also { it.sort() }.distinctSorted()
        }

        private fun LongArray.distinctSorted(): LongArray {
            if (isEmpty()) return this
            var w = 1
            for (i in 1 until size) if (this[i] != this[w - 1]) this[w++] = this[i]
            return copyOf(w)
        }

        /** Longitude spans of the region within the latitude band [south, north]. */
        internal fun rowIntervals(region: Region, south: Double, north: Double): List<Pair<Double, Double>> {
            val spans = ArrayList<Pair<Double, Double>>()
            val mid = (south + north) / 2
            for (ring in region.rings) {
                // Edges passing through the band (clipped) are covered directly…
                for (i in 0 until ring.size - 1) {
                    val p = ring[i]; val q = ring[i + 1]
                    val lo = min(p[1], q[1]); val hi = max(p[1], q[1])
                    if (hi < south || lo > north) continue
                    if (p[1] == q[1]) { spans += min(p[0], q[0]) to max(p[0], q[0]); continue } // horizontal edge: all of it
                    val a = clipLon(p, q, max(lo, south)); val b = clipLon(p, q, min(hi, north))
                    spans += min(a, b) to max(a, b)
                }
                // …and the interior at the band's middle latitude (even-odd fill).
                val xs = ArrayList<Double>()
                for (i in 0 until ring.size - 1) {
                    val p = ring[i]; val q = ring[i + 1]
                    if ((p[1] <= mid && q[1] > mid) || (q[1] <= mid && p[1] > mid)) xs += clipLon(p, q, mid)
                }
                xs.sort()
                for (k in 0 until xs.size - 1 step 2) spans += xs[k] to xs[k + 1]
            }
            return spans
        }

        private fun clipLon(p: DoubleArray, q: DoubleArray, lat: Double): Double =
            if (q[1] == p[1]) p[0] else p[0] + (q[0] - p[0]) * (lat - p[1]) / (q[1] - p[1])

        private fun lonToX(lon: Double, n: Int) = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        private fun latToY(lat: Double, n: Int): Int {
            val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
            return floor((1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
        }
        private fun yToLat(y: Int, n: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / n))))
    }
}

/** Growable list of primitive longs; boxed lists of millions of tile ids do not fit on Android. */
internal class LongList(capacity: Int = 1024) {
    private var a = LongArray(capacity)
    var size = 0; private set
    fun add(v: Long) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    operator fun set(i: Int, v: Long) { a[i] = v }
    fun toArray() = a.copyOf(size)
}

internal class IntList(capacity: Int = 1024) {
    private var a = IntArray(capacity)
    var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    operator fun set(i: Int, v: Int) { a[i] = v }
    fun toArray() = a.copyOf(size)
}

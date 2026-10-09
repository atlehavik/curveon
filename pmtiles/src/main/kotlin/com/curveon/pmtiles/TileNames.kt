package com.curveon.pmtiles

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sinh

/**
 * Names for offline search, read from a downloaded map: places (towns down to farms), useful
 * POIs and named roads, from the zoom-14 tiles of a Protomaps PMTiles archive. Used for countries
 * that have no full search index (see the curveon-data repo); it has no house numbers or speed
 * cameras, and fewer POIs, since most of those only appear at zoom 15.
 */
class TileNames(private val file: File) {

    /** One searchable thing; [area] is the nearest town for anything that isn't a town itself. */
    data class Entry(val name: String, val kind: String, val area: String, val rank: Int, val lat: Double, val lon: Double)

    private class Point(val name: String, val kind: String, val rank: Int, val lat: Double, val lon: Double)

    /** Reads every zoom-14 tile; [onProgress] gets (tiles done, tiles total). */
    fun read(onProgress: (Int, Int) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false }): List<Entry> {
        val places = ArrayList<Point>()
        val pois = ArrayList<Point>()
        // Roads come in pieces, one per tile they cross: gather them per name and ~5 km cell.
        val streets = HashMap<String, DoubleArray>() // "name|cell" -> [sumLat, sumLon, count]
        val seenPlace = HashSet<String>()
        PmtilesFile(file).forEachTile(ZOOM, onProgress, isCancelled) { x, y, tile ->
            VectorTile.read(tile, LAYERS) { layer, props, px, py, extent ->
                val name = props["name"] as? String ?: return@read
                if (name.isBlank()) return@read
                val lon = (x + px.toDouble() / extent) / (1 shl ZOOM) * 360.0 - 180.0
                val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * (y + py.toDouble() / extent) / (1 shl ZOOM)))))
                when (layer) {
                    "places" -> {
                        val detail = props["kind_detail"] as? String ?: props["kind"] as? String ?: return@read
                        val rank = PLACE_RANK[detail] ?: return@read
                        // Labels repeat in neighbouring tiles: keep one per name and ~1 km.
                        if (seenPlace.add("$name|${(lat * 100).toInt()}|${(lon * 50).toInt()}")) places += Point(name, detail, rank, lat, lon)
                    }
                    "pois" -> {
                        val (kind, rank) = POI_KINDS[props["kind"] as? String] ?: return@read
                        pois += Point(name, kind, rank, lat, lon)
                    }
                    "roads" -> {
                        if (props["kind"] == "path") return@read
                        val key = "$name|${(lat * 20).toInt()}|${(lon * 10).toInt()}"
                        val s = streets.getOrPut(key) { DoubleArray(3) }
                        s[0] += lat; s[1] += lon; s[2] += 1.0
                    }
                }
            }
        }

        val near = NearestPlace(places)
        val out = ArrayList<Entry>(places.size + pois.size + streets.size)
        for (p in places) out += Entry(p.name, p.kind, if (p.rank >= 90) "" else near.find(p.lat, p.lon).takeIf { it != p.name } ?: "", p.rank, p.lat, p.lon)
        val poiSeen = HashSet<String>()
        for (p in pois) if (poiSeen.add("${p.name}|${(p.lat * 1000).toInt()}|${(p.lon * 500).toInt()}")) out += Entry(p.name, p.kind, near.find(p.lat, p.lon), p.rank, p.lat, p.lon)
        // One street per name and town.
        val merged = LinkedHashMap<String, DoubleArray>()
        val names = HashMap<String, Pair<String, String>>()
        for ((key, s) in streets) {
            val name = key.substringBefore('|')
            val lat = s[0] / s[2]; val lon = s[1] / s[2]
            val town = near.find(lat, lon)
            val k = "$name|$town"
            names[k] = name to town
            val m = merged.getOrPut(k) { DoubleArray(3) }
            m[0] += lat; m[1] += lon; m[2] += 1.0
        }
        for ((k, m) in merged) { val (name, town) = names[k]!!; out += Entry(name, "street", town, 10, m[0] / m[2], m[1] / m[2]) }
        return out
    }

    /** Grid lookup of the nearest town/village (same idea as the PC index builder). */
    private class NearestPlace(places: List<Point>) {
        private val grid = HashMap<Long, MutableList<Point>>()

        init { for (p in places) if (p.rank >= 45) grid.getOrPut(cell((p.lat * 10).toInt(), (p.lon * 5).toInt())) { ArrayList() } += p }

        private fun cell(y: Int, x: Int) = (y.toLong() shl 32) or (x.toLong() and 0xffffffffL)

        fun find(lat: Double, lon: Double): String {
            var best = ""; var bestScore = Double.MAX_VALUE
            val gy = (lat * 10).toInt(); val gx = (lon * 5).toInt()
            val k = cos(Math.toRadians(lat))
            for (dy in -1..1) for (dx in -1..1) for (p in grid[cell(gy + dy, gx + dx)].orEmpty()) {
                val d = (p.lat - lat) * (p.lat - lat) + (p.lon - lon) * k * (p.lon - lon) * k
                val score = d / (1 + p.rank / 50.0) // prefer real towns over hamlets at similar distance
                if (score < bestScore) { best = p.name; bestScore = score }
            }
            return best
        }
    }

    companion object {
        const val ZOOM = 14
        private val LAYERS = setOf("places", "pois", "roads")

        // Same kinds and ranks as tools/search/build_search_index.py, so results mix well.
        private val PLACE_RANK = mapOf(
            "city" to 100, "town" to 90, "village" to 70, "suburb" to 60, "borough" to 60, "quarter" to 50,
            "hamlet" to 45, "neighbourhood" to 40, "island" to 40, "locality" to 30, "isolated_dwelling" to 20, "farm" to 20
        )
        private val POI_KINDS = mapOf(
            "fuel" to ("fuel" to 55), "charging_station" to ("charging" to 30), "cafe" to ("cafe" to 45),
            "restaurant" to ("restaurant" to 40), "fast_food" to ("fast_food" to 35), "pub" to ("pub" to 30),
            "ferry_terminal" to ("ferry" to 60), "parking" to ("parking" to 15), "viewpoint" to ("viewpoint" to 60),
            "camp_site" to ("camp_site" to 50), "hotel" to ("hotel" to 45), "motel" to ("hotel" to 45),
            "guest_house" to ("hotel" to 40), "hostel" to ("hotel" to 40), "alpine_hut" to ("hut" to 40),
            "wilderness_hut" to ("hut" to 30), "attraction" to ("attraction" to 55), "museum" to ("museum" to 45),
            "picnic_site" to ("picnic" to 25), "motorcycle" to ("motorcycle_shop" to 50), "supermarket" to ("supermarket" to 35),
            "convenience" to ("shop" to 25), "peak" to ("peak" to 35)
        )
    }
}

/**
 * Answers "does this archive have tile z/x/y?" from its directories alone, keeping the ones it has
 * read; used to tell where a downloaded map ends.
 */
class PmtilesIndex(private val file: File) {
    private val header = RandomAccessFile(file, "r").use { f -> Pmtiles.readHeader(ByteArray(Pmtiles.HEADER_SIZE).also { f.readFully(it) }) }
    private val dirs = object : LinkedHashMap<Long, Pmtiles.Dir>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pmtiles.Dir>) = size > 64
    }

    val maxZoom: Int get() = header.maxZoom

    @Synchronized
    fun has(z: Int, x: Int, y: Int): Boolean {
        val id = Pmtiles.tileId(z, x, y)
        var d = dir(header.rootOffset, header.rootLength)
        repeat(4) { // the spec allows at most 3 levels of leaves
            var lo = 0; var hi = d.size - 1; var i = -1
            while (lo <= hi) { val mid = (lo + hi) ushr 1; if (d.ids[mid] <= id) { i = mid; lo = mid + 1 } else hi = mid - 1 }
            if (i < 0) return false
            if (d.runs[i] > 0) return id < d.ids[i] + d.runs[i]
            d = dir(header.leafOffset + d.offsets[i], d.lengths[i].toLong())
        }
        return false
    }

    private fun dir(offset: Long, length: Long): Pmtiles.Dir = dirs.getOrPut(offset) {
        val b = ByteArray(length.toInt())
        RandomAccessFile(file, "r").use { f -> f.seek(offset); f.readFully(b) }
        Pmtiles.parseDirectory(Pmtiles.decompress(b, header.internalCompression))
    }
}

/** Reads tiles of a local PMTiles archive, each distinct tile content once, in file order. */
class PmtilesFile(private val file: File) {

    fun forEachTile(zoom: Int, onProgress: (Int, Int) -> Unit, isCancelled: () -> Boolean, block: (Int, Int, ByteArray) -> Unit) {
        RandomAccessFile(file, "r").use { f ->
            val head = ByteArray(Pmtiles.HEADER_SIZE).also { f.readFully(it) }
            val h = Pmtiles.readHeader(head)
            fun dir(offset: Long, length: Long): Pmtiles.Dir {
                val b = ByteArray(length.toInt()); f.seek(offset); f.readFully(b)
                return Pmtiles.parseDirectory(Pmtiles.decompress(b, h.internalCompression))
            }
            val from = Pmtiles.zoomBase(zoom); val to = Pmtiles.zoomBase(zoom + 1)
            // Collect (offset, length, tile id) of the zoom's tiles; runs share one content.
            val offs = LongList(); val lens = IntList(); val ids = LongList()
            fun walk(d: Pmtiles.Dir) {
                for (i in 0 until d.size) {
                    val id = d.ids[i]
                    if (d.runs[i] == 0) {
                        val end = if (i + 1 < d.size) d.ids[i + 1] else Long.MAX_VALUE
                        if (end > from && id < to) walk(dir(h.leafOffset + d.offsets[i], d.lengths[i].toLong()))
                    } else if (id + d.runs[i] > from && id < to) {
                        offs.add(d.offsets[i]); lens.add(d.lengths[i]); ids.add(maxOf(id, from))
                    }
                }
            }
            walk(dir(h.rootOffset, h.rootLength))
            val order = (0 until offs.size).sortedBy { offs[it] }
            val inflater = Inflater(true)
            var lastOff = -1L
            var done = 0
            for (i in order) {
                if (isCancelled()) error("Cancelled")
                if (offs[i] == lastOff) { done++; continue } // same content as the previous tile (open sea, forest)
                lastOff = offs[i]
                val raw = ByteArray(lens[i]); f.seek(h.dataOffset + offs[i]); f.readFully(raw)
                val tile = if (h.tileCompression == Pmtiles.COMPRESSION_GZIP) gunzip(raw, inflater) else raw
                val (x, y) = Pmtiles.tileXY(zoom, ids[i] - from)
                block(x, y, tile)
                if (++done % 5000 == 0) onProgress(done, offs.size)
            }
            onProgress(offs.size, offs.size)
        }
    }

    /** Gzip without the stream overhead: skip the header and inflate the raw deflate data. */
    private fun gunzip(b: ByteArray, inflater: Inflater): ByteArray {
        var pos = 10
        val flags = b[3].toInt()
        if (flags and 4 != 0) pos += 2 + ((b[pos].toInt() and 0xff) or ((b[pos + 1].toInt() and 0xff) shl 8))
        if (flags and 8 != 0) { while (b[pos].toInt() != 0) pos++; pos++ }
        if (flags and 16 != 0) { while (b[pos].toInt() != 0) pos++; pos++ }
        if (flags and 2 != 0) pos += 2
        inflater.reset()
        inflater.setInput(b, pos, b.size - pos)
        val out = java.io.ByteArrayOutputStream(b.size * 4)
        val buf = ByteArray(65_536)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

/** Just enough of the Mapbox Vector Tile format to read names and the first point of features. */
object VectorTile {

    /** Calls [onFeature] with (layer, properties, x, y, extent) for features of the given layers. */
    fun read(b: ByteArray, layers: Set<String>, onFeature: (String, Map<String, Any?>, Int, Int, Int) -> Unit) {
        val r = Proto(b, 0, b.size)
        while (r.more()) {
            val (field, wire) = r.tag()
            if (field == 3 && wire == 2) { val len = r.varint().toInt(); layer(Proto(b, r.pos, r.pos + len), layers, onFeature); r.pos += len }
            else r.skip(wire)
        }
    }

    private fun layer(r: Proto, layers: Set<String>, onFeature: (String, Map<String, Any?>, Int, Int, Int) -> Unit) {
        var name = ""
        val keys = ArrayList<String>(); val values = ArrayList<Any?>()
        val features = ArrayList<IntArray>() // [start, end]
        var extent = 4096
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 2 -> { name = r.string(); if (name !in layers) return }
                field == 2 && wire == 2 -> { val len = r.varint().toInt(); features += intArrayOf(r.pos, r.pos + len); r.pos += len }
                field == 3 && wire == 2 -> keys += r.string()
                field == 4 && wire == 2 -> { val len = r.varint().toInt(); values += value(Proto(r.b, r.pos, r.pos + len)); r.pos += len }
                field == 5 && wire == 0 -> extent = r.varint().toInt()
                else -> r.skip(wire)
            }
        }
        if (name !in layers) return
        for ((start, end) in features) {
            val f = Proto(r.b, start, end)
            val props = HashMap<String, Any?>()
            var px = 0; var py = 0
            while (f.more()) {
                val (field, wire) = f.tag()
                when {
                    field == 2 && wire == 2 -> {
                        val len = f.varint().toInt(); val e = f.pos + len
                        while (f.pos < e) { val k = f.varint().toInt(); val v = f.varint().toInt(); if (k < keys.size && v < values.size) props[keys[k]] = values[v] }
                    }
                    field == 4 && wire == 2 -> {
                        val len = f.varint().toInt(); val e = f.pos + len
                        if (f.pos < e) { // first command is MoveTo: the feature's first point
                            f.varint()
                            px = zigzag(f.varint()); py = zigzag(f.varint())
                        }
                        f.pos = e
                    }
                    else -> f.skip(wire)
                }
            }
            onFeature(name, props, px, py, extent)
        }
    }

    private fun value(r: Proto): Any? {
        var v: Any? = null
        while (r.more()) {
            val (field, wire) = r.tag()
            v = when (field) {
                1 -> r.string()
                2 -> java.lang.Float.intBitsToFloat(r.fixed32())
                3 -> java.lang.Double.longBitsToDouble(r.fixed64())
                4, 5 -> r.varint()
                6 -> zigzag(r.varint()).toLong()
                7 -> r.varint() != 0L
                else -> { r.skip(wire); v }
            }
        }
        return v
    }

    private fun zigzag(n: Long) = ((n ushr 1) xor -(n and 1)).toInt()

    private class Proto(val b: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun tag(): Pair<Int, Int> { val t = varint().toInt(); return (t ushr 3) to (t and 7) }
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                val v = b[pos++].toInt() and 0xff
                result = result or ((v and 0x7f).toLong() shl shift)
                if (v and 0x80 == 0) return result
                shift += 7
            }
        }
        fun fixed32(): Int { var v = 0; for (i in 0 until 4) v = v or ((b[pos++].toInt() and 0xff) shl (8 * i)); return v }
        fun fixed64(): Long { var v = 0L; for (i in 0 until 8) v = v or ((b[pos++].toLong() and 0xff) shl (8 * i)); return v }
        fun string(): String { val len = varint().toInt(); val s = String(b, pos, len, Charsets.UTF_8); pos += len; return s }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> { val len = varint().toInt(); pos += len }
                5 -> pos += 4
                else -> error("Bad wire type $wire")
            }
        }
    }
}

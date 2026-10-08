package com.curveon.pmtiles

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sinh

/**
 * Step 2 of tools/map/build-countries.sh: builds the country catalog the tablet uses to download
 * maps (app/src/main/assets/map/countries.json). For every area in areas.tsv (from countries.py):
 *  - the zoom-10 tiles it covers, plus one ring of sea tiles around it so islands off the coast
 *    are included (land borders aren't widened),
 *  - the size of its map, summed from the real directory of the current Protomaps world build,
 *  - the BRouter routing tiles (5x5 degrees) it needs and their sizes.
 *
 * Run: ./gradlew :pmtiles:test --tests '*CountryCatalogTool*' -Dcurveon.catalog=true
 */
class CountryCatalogTool {

    private class Area(
        val id: String, val name: String, val iso: String, val continent: String, val parent: String,
        val region: Region
    ) {
        lateinit var tiles: LongArray // sorted Hilbert indexes at zoom 10
        var mapBytes = 0L
        var routing = listOf<String>()
    }

    @Test
    fun `build country catalog`() {
        assumeTrue(System.getProperty("curveon.catalog") == "true")
        val data = File(System.getProperty("curveon.data"), "countries")
        val out = File(System.getProperty("curveon.root"), "app/src/main/assets/map/countries.json")

        val areas = File(data, "areas.tsv").readLines().filter { it.isNotBlank() }.map { line ->
            val f = line.split('\t')
            Area(f[0], f[1], f[2], f[3], f[4], Region.fromGeoJson(f[5]))
        }
        println("${areas.size} areas")

        // Zoom-10 cover, then one ring of sea tiles (tiles no country touches).
        val n = 1 shl Z
        val covers = areas.map { RegionExtractor.coverTiles(it.region, Z, Z) }
        val land = HashSet<Long>()
        areas.forEachIndexed { i, a -> if (a.parent.isEmpty()) covers[i].forEach { land += it } }
        val base = Pmtiles.zoomBase(Z)
        areas.forEachIndexed { i, a ->
            val set = HashSet<Long>()
            for (id in covers[i]) {
                set += id - base
                val (x, y) = Pmtiles.tileXY(Z, id - base)
                for (dx in -1..1) for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= n) continue
                    val nid = Pmtiles.tileId(Z, Math.floorMod(x + dx, n), ny)
                    if (nid !in land) set += nid - base
                }
            }
            a.tiles = set.toLongArray().also { it.sort() }
        }

        // Routing tiles each area needs, and their sizes on brouter.de.
        val segments = Regex("""href="([EW]\d+_[NS]\d+)\.rd5">.*?\s(\d+)\s*$""", RegexOption.MULTILINE)
            .findAll(URL(SEGMENTS_URL).readText()).associate { it.groupValues[1] to it.groupValues[2].toLong() }
        println("${segments.size} routing tiles listed")
        for (a in areas) {
            val cells = sortedSetOf<String>()
            for (d in a.tiles) {
                val (x, y) = Pmtiles.tileXY(Z, d)
                val w = x * 360.0 / n - 180; val e = (x + 1) * 360.0 / n - 180
                val north = lat(y, n); val south = lat(y + 1, n)
                for (lon in cell(w)..cell(e - 1e-9) step 5) for (la in cell(south)..cell(north - 1e-9) step 5) {
                    val name = (if (lon < 0) "W${-lon}" else "E$lon") + "_" + (if (la < 0) "S${-la}" else "N$la")
                    if (name in segments) cells += name
                }
            }
            a.routing = cells.toList()
        }

        // Map sizes from the world build's directory.
        val build = URL(BUILDS_URL).readText().let { Regex("\"key\":\"(\\d{8}\\.pmtiles)\"").findAll(it).map { m -> m.groupValues[1] }.max() }
        val url = "https://build.protomaps.com/$build"
        println("sizing against $build")
        val owners = HashMap<Long, IntArray>() // (zoom shl 40 or index) -> areas
        fun own(key: Long, i: Int) { owners[key] = owners[key]?.plus(i) ?: intArrayOf(i) }
        areas.forEachIndexed { i, a ->
            val seen = HashSet<Long>()
            for (d in a.tiles) {
                own((Z.toLong() shl 40) or d, i)
                for (z in 0 until Z) { val k = (z.toLong() shl 40) or (d shr (2 * (Z - z))); if (seen.add(k)) own(k, i) }
            }
        }
        val head = range(url, 0, 16_384)
        val h = Pmtiles.readHeader(head)
        val leaves = File(data, "leaves-$build")
        if (!leaves.exists() || leaves.length() != h.leafLength) {
            println("downloading ${h.leafLength / 1_000_000} MB of leaf directories")
            RandomAccessFile(File(data, "leaves.part"), "rw").use { f ->
                var off = 0L
                while (off < h.leafLength) {
                    val len = minOf(32L shl 20, h.leafLength - off)
                    f.seek(off); f.write(range(url, h.leafOffset + off, len)); off += len
                }
            }
            File(data, "leaves.part").renameTo(leaves)
        }
        val sizes = LongArray(areas.size)
        // The archive is clustered: tile contents appear in tile-id order, and a tile that reuses
        // earlier content (open sea, plain forest) points backwards. Count each content once.
        var dataEnd = 0L
        fun count(dir: Pmtiles.Dir, leafFile: RandomAccessFile) {
            for (i in 0 until dir.size) {
                if (dir.runs[i] == 0) {
                    val b = ByteArray(dir.lengths[i]); leafFile.seek(dir.offsets[i]); leafFile.readFully(b)
                    count(Pmtiles.parseDirectory(Pmtiles.decompress(b, h.internalCompression)), leafFile)
                    continue
                }
                if (dir.offsets[i] < dataEnd) continue
                dataEnd = dir.offsets[i] + dir.lengths[i]
                val id = dir.ids[i]
                var z = 0
                while (Pmtiles.zoomBase(z + 1) <= id) z++
                if (z > MAX_ZOOM) continue
                val d = id - Pmtiles.zoomBase(z)
                val key = if (z >= Z) (Z.toLong() shl 40) or (d shr (2 * (z - Z))) else (z.toLong() shl 40) or d
                owners[key]?.forEach { sizes[it] += dir.lengths[i] }
            }
        }
        val root = Pmtiles.parseDirectory(Pmtiles.decompress(head.copyOfRange(h.rootOffset.toInt(), (h.rootOffset + h.rootLength).toInt()), h.internalCompression))
        RandomAccessFile(leaves, "r").use { count(root, it) }
        areas.forEachIndexed { i, a -> a.mapBytes = sizes[i] }

        // Output: masks as (gap from previous end, length) pairs to stay small.
        val sb = StringBuilder()
        sb.append("{\"build\":\"").append(build.removeSuffix(".pmtiles")).append("\",\"routingSizes\":{")
        sb.append(areas.flatMap { it.routing }.toSortedSet().joinToString(",") { "\"$it\":${segments[it]}" })
        sb.append("},\"areas\":[\n")
        sb.append(areas.sortedWith(compareBy({ it.continent }, { it.parent }, { it.name })).joinToString(",\n") { a ->
            val mask = ArrayList<Long>()
            var prevEnd = 0L
            var i = 0
            while (i < a.tiles.size) {
                var j = i
                while (j + 1 < a.tiles.size && a.tiles[j + 1] == a.tiles[j] + 1) j++
                mask += a.tiles[i] - prevEnd; mask += (j - i + 1).toLong()
                prevEnd = a.tiles[j] + 1
                i = j + 1
            }
            val r = a.region
            "{\"id\":\"${a.id}\",\"name\":${json(a.name)},\"iso\":\"${a.iso}\",\"continent\":\"${a.continent}\",\"parent\":\"${a.parent}\"," +
                "\"bbox\":[${"%.3f,%.3f,%.3f,%.3f".format(java.util.Locale.ROOT, r.minLon, r.minLat, r.maxLon, r.maxLat)}],\"mapBytes\":${a.mapBytes}," +
                "\"routing\":[${a.routing.joinToString(",") { "\"$it\"" }}],\"mask\":[${mask.joinToString(",")}]}"
        })
        sb.append("\n]}\n")
        out.writeText(sb.toString())
        println("wrote ${out.length() / 1000} kB to $out")
        for (a in areas.sortedByDescending { it.mapBytes }.take(40)) {
            println("%-45s %6.2f GB map  %6.2f GB routing".format(a.id, a.mapBytes / 1e9, a.routing.sumOf { segments[it]!! } / 1e9))
        }
        println("Europe:")
        for (a in areas.filter { it.continent == "Europe" }.sortedBy { it.id }) {
            println("%-45s %6.2f GB map  %6.2f GB routing  %s".format(a.id, a.mapBytes / 1e9, a.routing.sumOf { segments[it]!! } / 1e9, a.routing.size))
        }
    }

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun cell(deg: Double) = (floor(deg / 5) * 5).toInt()

    private fun lat(y: Int, n: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / n))))

    private fun range(url: String, offset: Long, length: Long): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
        return c.inputStream.use { it.readBytes() }.also { check(it.size.toLong() == length) { "short read" } }
    }

    companion object {
        const val Z = TileMask.MASK_ZOOM
        /** Same as the app's download (MapDownloads.MAX_ZOOM). */
        const val MAX_ZOOM = 14
        const val SEGMENTS_URL = "https://brouter.de/brouter/segments4/"
        const val BUILDS_URL = "https://build-metadata.protomaps.dev/builds.json"
    }
}

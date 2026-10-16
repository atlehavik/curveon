package com.curveon.routing

import btools.codec.DataBuffers
import btools.codec.NoisyDiffCoder
import btools.codec.StatCoderContext
import btools.codec.TagValueCoder
import btools.expressions.BExpressionContextWay
import btools.expressions.BExpressionMetaData
import btools.mapaccess.OsmFile
import btools.mapaccess.PhysicalFile
import java.io.File
import java.nio.ByteBuffer

/** What a road is made of, as far as the rider cares: the map draws these, tarmac stays as it is. */
enum class SurfaceKind {
    /** Gravel, compacted, fine gravel, or a solid (grade 1–2) track. */
    GRAVEL,
    /** Anything softer, rougher or unknown: dirt, ground, grass, sand, "unpaved", tracks of grade 3–5 or no grade. */
    SOFT
}

/**
 * One unpaved stretch of road between two junctions.
 *
 * @param rank how big the road is (2 = primary/secondary/tertiary, 1 = unclassified/residential, 0 = service/track)
 * @param coords lon, lat, lon, lat, ...
 */
class SurfaceWay(val kind: SurfaceKind, val rank: Int, val coords: DoubleArray)

/**
 * Reads the unpaved roads in an area straight out of BRouter's segment files, which carry each
 * way's OSM surface and track grade (the vector map doesn't). Mirrors BRouter's own decoder
 * (MicroCache2) but keeps only the geometry and tags of ways the map should mark.
 *
 * Segment files are cut into cells of 1/32°; decoded cells are kept, so panning stays cheap.
 */
class SurfaceReader(private val segmentDir: File, lookupsFile: File) {
    private val meta = BExpressionMetaData()
    private val ctx = BExpressionContextWay(meta).also { meta.readMetaData(lookupsFile) }
    private val buffers = DataBuffers()
    private val physical = HashMap<String, PhysicalFile>()
    private val osmFiles = HashMap<Long, OsmFile>()
    /** Tags (as BRouter's packed bitmap) → kind and rank, or null for roads not to mark. */
    private val classes = HashMap<ByteBuffer, Pair<SurfaceKind, Int>?>()
    private val cells = object : LinkedHashMap<Long, List<SurfaceWay>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<SurfaceWay>>?) = size > MAX_CELLS
    }

    /** The marked ways in (or crossing into) the area. Cells that can't be read are skipped. */
    @Synchronized
    fun ways(south: Double, west: Double, north: Double, east: Double): List<SurfaceWay> {
        val cell = 1_000_000 / DIVISOR
        val lon0 = toILon(west) / cell
        val lon1 = toILon(east) / cell
        val lat0 = toILat(south) / cell
        val lat1 = toILat(north) / cell
        if ((lon1 - lon0 + 1).toLong() * (lat1 - lat0 + 1) > MAX_CELLS) return emptyList()
        val out = ArrayList<SurfaceWay>()
        for (latIdx in lat0..lat1) for (lonIdx in lon0..lon1) {
            val key = lonIdx.toLong() shl 32 or latIdx.toLong()
            // Not remembered without a segment file: it may be downloaded later.
            out += cells[key] ?: (runCatching { decodeCell(lonIdx, latIdx) }.getOrDefault(emptyList()) ?: continue).also { cells[key] = it }
        }
        return out
    }

    /** The marked ways starting in one 1/32° cell, read afresh (not kept); null without a segment file. */
    @Synchronized
    internal fun cellWays(lonIdx: Int, latIdx: Int): List<SurfaceWay>? = runCatching { decodeCell(lonIdx, latIdx) }.getOrDefault(emptyList())

    private fun osmFile(lonDegree: Int, latDegree: Int): OsmFile? {
        val key = lonDegree.toLong() shl 32 or latDegree.toLong()
        osmFiles[key]?.let { return it }
        val lon = lonDegree - 180 - lonDegree % 5
        val lat = latDegree - 90 - latDegree % 5
        val name = (if (lon < 0) "W${-lon}" else "E$lon") + "_" + (if (lat < 0) "S${-lat}" else "N$lat")
        val pf = physical[name] ?: File(segmentDir, "$name.rd5").takeIf { it.exists() }
            ?.let { PhysicalFile(it, buffers, meta.lookupVersion.toInt(), meta.lookupMinorVersion.toInt()) }
            ?.also { physical[name] = it } ?: return null
        if (pf.divisor != DIVISOR) return null
        return OsmFile(pf, lonDegree, latDegree, buffers).also { osmFiles[key] = it }
    }

    /** Same order of reads as BRouter's MicroCache2 constructor; anything else would lose sync. */
    /** Null when there's no segment file for the cell. */
    private fun decodeCell(lonIdx: Int, latIdx: Int): List<SurfaceWay>? {
        val file = osmFile(lonIdx / DIVISOR, latIdx / DIVISOR) ?: return null
        val subIdx = (latIdx - DIVISOR * file.latDegree) * DIVISOR + (lonIdx - DIVISOR * file.lonDegree)
        var ab = buffers.iobuffer
        var size = file.getDataInputForSubIdx(subIdx, ab)
        if (size == 0) return emptyList()
        if (size > ab.size) { ab = ByteArray(size); size = file.getDataInputForSubIdx(subIdx, ab) }

        val bc = StatCoderContext(ab)
        val wayTagCoder = TagValueCoder(bc, buffers, null)
        val nodeTagCoder = TagValueCoder(bc, buffers, null)
        val nodeIdxDiff = NoisyDiffCoder(bc)
        val nodeEleDiff = NoisyDiffCoder(bc)
        val extLonDiff = NoisyDiffCoder(bc)
        val extLatDiff = NoisyDiffCoder(bc)
        val transEleDiff = NoisyDiffCoder(bc)

        val n = bc.decodeNoisyNumber(5)
        val ids = IntArray(n)
        bc.decodeSortedArray(ids, 0, n, 29, 0)
        val cell = 1_000_000 / DIVISOR
        val alon = IntArray(n)
        val alat = IntArray(n)
        for (i in 0 until n) {
            var id = ids[i]; var dlon = 0; var dlat = 0; var bm = 1
            while (bm < 0x8000) {
                if (id and 1 != 0) dlon = dlon or bm
                if (id and 2 != 0) dlat = dlat or bm
                id = id shr 2; bm = bm shl 1
            }
            alon[i] = lonIdx * cell + dlon
            alat[i] = latIdx * cell + dlat
        }
        bc.decodeNoisyNumber(10) // size of BRouter's own output buffer

        val out = ArrayList<SurfaceWay>()
        for (i in 0 until n) {
            val ilon = alon[i]
            val ilat = alat[i]
            var featureId = bc.decodeVarBits()
            if (featureId == 13) continue // empty node (delta files only)
            while (featureId != 0) {
                val bits = bc.decodeNoisyNumber(5)
                when (featureId) {
                    2 -> bc.decodeBounded(1023)
                    1 -> { bc.decodeBit(); repeat(4) { bc.decodeNoisyDiff(10) } }
                    else -> repeat(bits) { bc.decodeBit() }
                }
                featureId = bc.decodeVarBits()
            }
            nodeEleDiff.decodeSignedValue()
            nodeTagCoder.decodeTagValueSet()

            val links = bc.decodeNoisyNumber(1)
            repeat(links) {
                val target = i + nodeIdxDiff.decodeSignedValue()
                var isReverse = false
                val dlonAll: Int
                val dlatAll: Int
                if (target != i) {
                    dlonAll = alon[target] - ilon
                    dlatAll = alat[target] - ilat
                } else {
                    isReverse = bc.decodeBit()
                    dlonAll = extLonDiff.decodeSignedValue()
                    dlatAll = extLatDiff.decodeSignedValue()
                }
                val tags = wayTagCoder.decodeTagValueSet()
                // A reverse link's geometry lives with its forward twin (in this cell or the next).
                if (!isReverse) {
                    val cls = tags?.data?.let { classify(it) }
                    val transfers = bc.decodeVarBits()
                    val coords = if (cls != null) DoubleArray((transfers + 2) * 2) else null
                    coords?.let { it[0] = lonOf(ilon); it[1] = latOf(ilat) }
                    var dlonRem = dlonAll
                    var dlatRem = dlatAll
                    var count = transfers + 1
                    for (t in 0 until transfers) {
                        val dlon = bc.decodePredictedValue(dlonRem / count)
                        val dlat = bc.decodePredictedValue(dlatRem / count)
                        dlonRem -= dlon; dlatRem -= dlat; count--
                        transEleDiff.decodeSignedValue()
                        coords?.let {
                            it[2 + t * 2] = lonOf(ilon + dlonAll - dlonRem)
                            it[3 + t * 2] = latOf(ilat + dlatAll - dlatRem)
                        }
                    }
                    if (coords != null && cls != null) {
                        coords[coords.size - 2] = lonOf(ilon + dlonAll)
                        coords[coords.size - 1] = latOf(ilat + dlatAll)
                        out += SurfaceWay(cls.first, cls.second, coords)
                    }
                }
            }
        }
        // Every byte but the CRC read: anything else means this decoder fell out of step with BRouter's.
        if ((bc.readingBitPosition + 7) shr 3 != size - 4) { syncErrors++; return emptyList() }
        return out
    }

    /** Cells whose data didn't decode to exactly its length (and were dropped). Should stay 0. */
    var syncErrors = 0
        private set

    private fun classify(bitmap: ByteArray): Pair<SurfaceKind, Int>? = classes.getOrPut(ByteBuffer.wrap(bitmap.copyOf())) {
        val tags = ctx.getKeyValueDescription(false, bitmap).split(' ').mapNotNull {
            val eq = it.indexOf('='); if (eq > 0) it.substring(0, eq) to it.substring(eq + 1) else null
        }.toMap()
        classify(tags["highway"], tags["surface"], tags["tracktype"])
    }

    companion object {
        /** Cells per degree in current (lookup version 11) segment files. */
        private const val DIVISOR = 32
        /** About a screenful at zoom 10 on the X7; beyond that the map shows no surfaces anyway. */
        private const val MAX_CELLS = 400

        private val NOT_FOR_MOTORBIKES = setOf(
            "footway", "path", "cycleway", "bridleway", "steps", "pedestrian", "platform", "construction",
            "proposed", "abandoned", "corridor", "elevator", "via_ferrata", "raceway", "bus_stop", "busway"
        )
        private val PAVED = setOf(
            "asphalt", "paved", "concrete", "paving_stones", "cobblestone", "sett", "metal", "wood", "grass_paver", "stone"
        )
        private val GRAVEL = setOf("gravel", "compacted", "fine_gravel")

        internal fun classify(highway: String?, surface: String?, tracktype: String?): Pair<SurfaceKind, Int>? {
            if (highway == null || highway in NOT_FOR_MOTORBIKES) return null
            val rank = when (highway) {
                "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
                "secondary", "secondary_link", "tertiary", "tertiary_link" -> 2
                "unclassified", "residential", "living_street", "road" -> 1
                else -> 0
            }
            val kind = when {
                surface != null -> when (surface) {
                    in PAVED -> null
                    in GRAVEL -> SurfaceKind.GRAVEL
                    else -> SurfaceKind.SOFT
                }
                // No surface: only tracks are taken to be unpaved (as the router does).
                highway == "track" -> if (tracktype == "grade1" || tracktype == "grade2") SurfaceKind.GRAVEL else SurfaceKind.SOFT
                else -> null
            } ?: return null
            return kind to rank
        }

        private fun toILon(lon: Double) = ((lon + 180.0) * 1_000_000).toInt()
        private fun toILat(lat: Double) = ((lat + 90.0) * 1_000_000).toInt()
        private fun lonOf(ilon: Int) = ilon / 1_000_000.0 - 180.0
        private fun latOf(ilat: Int) = ilat / 1_000_000.0 - 90.0
    }
}

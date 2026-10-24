package com.curveon.webplanner

import java.io.File
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Places for the map's layers and the fuel along a route, all of Europe in memory: fuel, viewpoints, mountain passes,
 * campsites, cafés and food, ferries, motorcycle shops. Read from the TSV files `tools/webplanner_pois.py` takes out of
 * the app's search indexes (kind, lat, lon, name per line), so the web shows the places the app shows. Kept small: a
 * million places are a few tens of MB (coordinates as whole micro-degrees, names in one UTF-8 block), found by a grid.
 */
class Pois private constructor(
  private val kinds: ByteArray, private val lats: IntArray, private val lons: IntArray,
  private val nameAt: IntArray, private val names: ByteArray, private val cells: Map<Long, IntArray>,
) {
  class Poi(val kind: String, val lat: Double, val lon: Double, val name: String)

  val size get() = kinds.size

  /** Places of [want] kinds inside the box, at most [limit]. */
  fun inBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, want: Set<String>, limit: Int): List<Poi> {
    val ks = want.mapNotNull { KINDS.indexOf(it).takeIf { i -> i >= 0 }?.toByte() }.toSet()
    val out = ArrayList<Poi>()
    val a = (minLat * 1e6).roundToInt(); val b = (maxLat * 1e6).roundToInt()
    val c = (minLon * 1e6).roundToInt(); val d = (maxLon * 1e6).roundToInt()
    for (cy in cell(minLat)..cell(maxLat)) for (cx in cell(minLon)..cell(maxLon)) {
      for (i in cells[key(cy, cx)] ?: continue) {
        if (kinds[i] !in ks || lats[i] !in a..b || lons[i] !in c..d) continue
        out += Poi(KINDS[kinds[i].toInt()], lats[i] / 1e6, lons[i] / 1e6, String(names, nameAt[i], nameAt[i + 1] - nameAt[i], Charsets.UTF_8))
        if (out.size >= limit) return out
      }
    }
    return out
  }

  companion object {
    /** The kinds kept, by the names the app's search index gives them (MapLayer in the app). */
    val KINDS = listOf("fuel", "viewpoint", "pass", "camp_site", "cafe", "restaurant", "fast_food", "ferry", "motorcycle_shop", "workshop")
    private const val CELL_DEG = 0.25

    private fun cell(deg: Double) = floor(deg / CELL_DEG).toInt()
    private fun key(cy: Int, cx: Int) = (cy.toLong() shl 32) xor (cx.toLong() and 0xffffffffL)

    /** Every `*.tsv` in [dir]; a place in two countries' files (near a border) is kept once. */
    fun load(dir: File): Pois {
      val kinds = ByteBuilder(); val lats = IntBuilder(); val lons = IntBuilder(); val nameAt = IntBuilder(); val names = ByteBuilder()
      // A short fingerprint, not the text: a million of them at start-up.
      val seen = HashSet<Long>()
      for (f in dir.listFiles { x -> x.name.endsWith(".tsv") }.orEmpty().sortedBy { it.name }) f.forEachLine { line ->
        val p = line.split('\t')
        if (p.size < 4) return@forEachLine
        val k = KINDS.indexOf(p[0]); if (k < 0) return@forEachLine
        val lat = p[1].toDoubleOrNull() ?: return@forEachLine; val lon = p[2].toDoubleOrNull() ?: return@forEachLine
        if (!seen.add((p[3].hashCode().toLong() shl 32) xor ((lat * 2000).roundToInt() * 31L + (lon * 1000).roundToInt()) * 16 + k)) return@forEachLine
        kinds.add(k.toByte()); lats.add((lat * 1e6).roundToInt()); lons.add((lon * 1e6).roundToInt())
        nameAt.add(names.size); names.addAll(p[3].take(80).toByteArray(Charsets.UTF_8))
      }
      nameAt.add(names.size)
      val la = lats.toArray(); val lo = lons.toArray()
      val byCell = HashMap<Long, IntBuilder>()
      for (i in la.indices) byCell.getOrPut(key(cell(la[i] / 1e6), cell(lo[i] / 1e6))) { IntBuilder() }.add(i)
      return Pois(kinds.toArray(), la, lo, nameAt.toArray(), names.toArray(), byCell.mapValues { it.value.toArray() })
    }

    /** For tests: these places. */
    fun of(list: List<Poi>): Pois {
      val dir = kotlin.io.path.createTempDirectory("pois").toFile()
      File(dir, "a.tsv").writeText(list.joinToString("") { "${it.kind}\t${it.lat}\t${it.lon}\t${it.name}\n" })
      return load(dir).also { dir.deleteRecursively() }
    }
  }

  private class IntBuilder {
    private var a = IntArray(1024); var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    fun toArray() = a.copyOf(size)
  }

  private class ByteBuilder {
    private var a = ByteArray(1024); var size = 0; private set
    fun add(v: Byte) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    fun addAll(v: ByteArray) { while (size + v.size > a.size) a = a.copyOf(a.size * 2); v.copyInto(a, size); size += v.size }
    fun toArray() = a.copyOf(size)
  }
}

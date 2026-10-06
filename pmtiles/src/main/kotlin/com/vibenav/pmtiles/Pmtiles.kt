package com.vibenav.pmtiles

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** PMTiles v3 format pieces: header, directories, tile ids. See github.com/protomaps/PMTiles/blob/main/spec/v3. */
object Pmtiles {
    const val HEADER_SIZE = 127
    const val ROOT_MAX = 16_384 - HEADER_SIZE

    const val COMPRESSION_NONE = 1
    const val COMPRESSION_GZIP = 2

    data class Header(
        val rootOffset: Long, val rootLength: Long,
        val metadataOffset: Long, val metadataLength: Long,
        val leafOffset: Long, val leafLength: Long,
        val dataOffset: Long, val dataLength: Long,
        val addressedTiles: Long, val tileEntries: Long, val tileContents: Long,
        val clustered: Boolean, val internalCompression: Int, val tileCompression: Int, val tileType: Int,
        val minZoom: Int, val maxZoom: Int,
        val minLonE7: Int, val minLatE7: Int, val maxLonE7: Int, val maxLatE7: Int,
        val centerZoom: Int, val centerLonE7: Int, val centerLatE7: Int
    )

    data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    fun readHeader(b: ByteArray): Header {
        require(String(b, 0, 7, Charsets.US_ASCII) == "PMTiles" && b[7].toInt() == 3) { "Not a PMTiles v3 archive" }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return Header(
            bb.getLong(8), bb.getLong(16), bb.getLong(24), bb.getLong(32), bb.getLong(40), bb.getLong(48),
            bb.getLong(56), bb.getLong(64), bb.getLong(72), bb.getLong(80), bb.getLong(88),
            b[96].toInt() == 1, b[97].toInt(), b[98].toInt(), b[99].toInt(), b[100].toInt() and 0xff, b[101].toInt() and 0xff,
            bb.getInt(102), bb.getInt(106), bb.getInt(110), bb.getInt(114), b[118].toInt() and 0xff, bb.getInt(119), bb.getInt(123)
        )
    }

    fun writeHeader(h: Header): ByteArray {
        val bb = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("PMTiles".toByteArray(Charsets.US_ASCII)); bb.put(3)
        listOf(h.rootOffset, h.rootLength, h.metadataOffset, h.metadataLength, h.leafOffset, h.leafLength,
            h.dataOffset, h.dataLength, h.addressedTiles, h.tileEntries, h.tileContents).forEach { bb.putLong(it) }
        bb.put(if (h.clustered) 1 else 0); bb.put(h.internalCompression.toByte()); bb.put(h.tileCompression.toByte())
        bb.put(h.tileType.toByte()); bb.put(h.minZoom.toByte()); bb.put(h.maxZoom.toByte())
        bb.putInt(h.minLonE7); bb.putInt(h.minLatE7); bb.putInt(h.maxLonE7); bb.putInt(h.maxLatE7)
        bb.put(h.centerZoom.toByte()); bb.putInt(h.centerLonE7); bb.putInt(h.centerLatE7)
        return bb.array()
    }

    fun decompress(b: ByteArray, compression: Int): ByteArray = when (compression) {
        COMPRESSION_NONE, 0 -> b
        COMPRESSION_GZIP -> GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
        else -> error("Unsupported compression $compression")
    }

    fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    /** A directory as parallel primitive arrays (millions of entries must not become objects). */
    class Dir(val ids: LongArray, val offsets: LongArray, val lengths: IntArray, val runs: IntArray) {
        val size get() = ids.size
    }

    fun deserializeDirectory(b: ByteArray): List<Entry> {
        val d = parseDirectory(b)
        return (0 until d.size).map { Entry(d.ids[it], d.offsets[it], d.lengths[it], d.runs[it]) }
    }

    fun parseDirectory(b: ByteArray): Dir {
        var pos = 0
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                val v = b[pos++].toInt() and 0xff
                result = result or ((v and 0x7f).toLong() shl shift)
                if (v and 0x80 == 0) return result
                shift += 7
            }
        }
        val n = varint().toInt()
        val ids = LongArray(n); val runs = IntArray(n); val lens = IntArray(n); val offs = LongArray(n)
        var last = 0L
        for (i in 0 until n) { last += varint(); ids[i] = last }
        for (i in 0 until n) runs[i] = varint().toInt()
        for (i in 0 until n) lens[i] = varint().toInt()
        for (i in 0 until n) {
            val v = varint()
            offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
        }
        return Dir(ids, offs, lens, runs)
    }

    fun serializeDirectory(entries: List<Entry>): ByteArray = serializeDirectory(
        LongArray(entries.size) { entries[it].tileId }, LongArray(entries.size) { entries[it].offset },
        IntArray(entries.size) { entries[it].length }, IntArray(entries.size) { entries[it].runLength }, 0, entries.size
    )

    /** Serializes entries [from, to) of the given arrays. */
    fun serializeDirectory(ids: LongArray, offs: LongArray, lens: IntArray, runs: IntArray, from: Int, to: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) {
            var x = v
            while (x >= 0x80) { out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
            out.write(x.toInt())
        }
        varint((to - from).toLong())
        var last = 0L
        for (i in from until to) { varint(ids[i] - last); last = ids[i] }
        for (i in from until to) varint(runs[i].toLong())
        for (i in from until to) varint(lens[i].toLong())
        for (i in from until to) {
            if (i > from && offs[i] == offs[i - 1] + lens[i - 1]) varint(0) else varint(offs[i] + 1)
        }
        return out.toByteArray()
    }

    /** Tile id on the Hilbert curve, as the spec defines it. */
    fun tileId(z: Int, x: Int, y: Int): Long {
        val acc = ((1L shl (2 * z)) - 1) / 3
        val n = 1L shl z
        var tx = x.toLong(); var ty = y.toLong()
        var d = 0L
        var s = n / 2
        while (s > 0) {
            val rx = if (tx and s > 0) 1L else 0L
            val ry = if (ty and s > 0) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) { tx = s - 1 - tx; ty = s - 1 - ty }
                val t = tx; tx = ty; ty = t
            }
            s /= 2
        }
        return acc + d
    }
}

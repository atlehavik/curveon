package com.curveon.protocol

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Length-prefixed UTF-8 JSON frames: 4-byte big-endian length, then the payload. */
class MessageWriter(out: OutputStream) {
    private val out = DataOutputStream(out.buffered())

    @Synchronized
    fun write(message: Message) {
        val bytes = message.toJson().toString().toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }
}

class MessageReader(input: InputStream) {
    private val input = DataInputStream(input.buffered())

    /**
     * Blocks for the next message; returns null on a clean end of stream. Unknown types are skipped, and so is a frame
     * that is not valid JSON or lacks a field: the length still says where the next one starts, so the link stays up.
     */
    fun read(): Message? {
        while (true) {
            val size = try { input.readInt() } catch (e: EOFException) { return null }
            if (size !in 0..MAX_FRAME) throw IOException("Bad frame size $size")
            val bytes = readBytes(size)
            try {
                Message.fromJson(JSONObject(String(bytes, Charsets.UTF_8)))?.let { return it }
            } catch (_: JSONException) {
            }
        }
    }

    /** Grows as the bytes arrive, so a broken length does not take [MAX_FRAME] of memory before the stream ends. */
    private fun readBytes(size: Int): ByteArray {
        if (size <= CHUNK) return ByteArray(size).also(input::readFully)
        val out = ByteArrayOutputStream(CHUNK)
        val buf = ByteArray(CHUNK)
        var left = size
        while (left > 0) {
            val n = minOf(left, CHUNK)
            input.readFully(buf, 0, n)
            out.write(buf, 0, n)
            left -= n
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_FRAME = 4 * 1024 * 1024
        private const val CHUNK = 64 * 1024
    }
}

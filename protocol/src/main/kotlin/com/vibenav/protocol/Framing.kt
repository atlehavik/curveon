package com.vibenav.protocol

import org.json.JSONObject
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

    /** Blocks for the next message; returns null on a clean end of stream. Unknown types are skipped. */
    fun read(): Message? {
        while (true) {
            val size = try { input.readInt() } catch (e: EOFException) { return null }
            if (size !in 0..MAX_FRAME) throw IOException("Bad frame size $size")
            val bytes = ByteArray(size)
            input.readFully(bytes)
            Message.fromJson(JSONObject(String(bytes, Charsets.UTF_8)))?.let { return it }
        }
    }

    companion object {
        const val MAX_FRAME = 4 * 1024 * 1024
    }
}

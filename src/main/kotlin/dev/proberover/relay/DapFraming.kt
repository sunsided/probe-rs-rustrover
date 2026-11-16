package dev.proberover.relay

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Reads and writes Debug Adapter Protocol messages (`Content-Length` framed JSON). */
class DapReader(private val input: InputStream) {

    /** Returns the next message body, or null on a clean end of stream. */
    fun read(): String? {
        var contentLength = -1
        while (true) {
            val line = readHeaderLine() ?: return null
            if (line.isEmpty()) {
                if (contentLength >= 0) break
                continue // tolerate stray blank lines between messages
            }
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toInt()
            }
        }
        val body = ByteArray(contentLength)
        var off = 0
        while (off < contentLength) {
            val n = input.read(body, off, contentLength - off)
            if (n < 0) throw EOFException("Stream ended inside a $contentLength-byte DAP message")
            off += n
        }
        return String(body, Charsets.UTF_8)
    }

    private fun readHeaderLine(): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else throw EOFException("Stream ended inside a DAP header")
            if (b == '\n'.code) break
            buf.write(b)
        }
        return buf.toString(Charsets.US_ASCII).removeSuffix("\r")
    }
}

class DapWriter(private val output: OutputStream) {
    @Synchronized
    fun write(json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }
}

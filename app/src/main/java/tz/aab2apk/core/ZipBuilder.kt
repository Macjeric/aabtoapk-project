package tz.aab2apk.core

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Minimal streaming ZIP writer with zipalign-style alignment for stored entries. */
class ZipBuilder(private val out: OutputStream) {
    private class Rec(
        val name: ByteArray, val method: Int, val flags: Int,
        val crc: Long, val csize: Long, val usize: Long, val offset: Long
    )

    private val recs = ArrayList<Rec>()
    private val names = HashSet<String>()
    private var pos = 0L

    fun has(name: String) = names.contains(name)

    private fun w8(v: Int) { out.write(v); pos += 1 }
    private fun w16(v: Int) { out.write(v and 0xff); out.write((v ushr 8) and 0xff); pos += 2 }
    private fun w32(v: Long) {
        out.write((v and 0xff).toInt()); out.write(((v ushr 8) and 0xff).toInt())
        out.write(((v ushr 16) and 0xff).toInt()); out.write(((v ushr 24) and 0xff).toInt()); pos += 4
    }
    private fun wb(b: ByteArray, off: Int = 0, len: Int = b.size) { out.write(b, off, len); pos += len }

    private fun localHeader(name: ByteArray, method: Int, flags: Int, crc: Long, csize: Long, usize: Long, align: Int) {
        var pad = 0
        if (align > 1) {
            val base = pos + 30 + name.size
            pad = ((align - (base % align)) % align).toInt()
            if (pad in 1..3) pad += align
        }
        w32(0x04034b50L); w16(20); w16(flags or 0x0800); w16(method)
        w16(0); w16(0x21)
        w32(crc); w32(csize); w32(usize)
        w16(name.size); w16(pad)
        wb(name)
        if (pad > 0) { w16(0xD935); w16(pad - 4); wb(ByteArray(pad - 4)) }
    }

    fun addStored(name: String, data: ByteArray, align: Int = 4) {
        val crc = CRC32().also { it.update(data) }.value
        addStoredStream(name, crc, data.size.toLong(), align, data.inputStream())
    }

    fun addStoredStream(name: String, crc: Long, size: Long, align: Int, input: InputStream) {
        if (!names.add(name)) { input.close(); return }
        val nb = name.toByteArray(Charsets.UTF_8)
        val offset = pos
        localHeader(nb, 0, 0, crc, size, size, align)
        val buf = ByteArray(65536)
        var total = 0L
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                wb(buf, 0, n); total += n
            }
        }
        check(total == size) { "Size mismatch while copying $name ($total != $size)" }
        recs.add(Rec(nb, 0, 0, crc, size, size, offset))
    }

    fun addDeflated(name: String, data: ByteArray) = addDeflatedStream(name, data.inputStream())

    fun addDeflatedStream(name: String, input: InputStream) {
        if (!names.add(name)) { input.close(); return }
        val nb = name.toByteArray(Charsets.UTF_8)
        val offset = pos
        localHeader(nb, 8, 0x08, 0, 0, 0, 1)
        val def = Deflater(6, true)
        val crc = CRC32()
        val buf = ByteArray(65536)
        val dbuf = ByteArray(65536)
        var usize = 0L
        var csize = 0L
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n); usize += n
                def.setInput(buf, 0, n)
                while (!def.needsInput()) {
                    val c = def.deflate(dbuf)
                    if (c > 0) { wb(dbuf, 0, c); csize += c }
                }
            }
        }
        def.finish()
        while (!def.finished()) {
            val c = def.deflate(dbuf)
            if (c > 0) { wb(dbuf, 0, c); csize += c }
        }
        def.end()
        w32(0x08074b50L); w32(crc.value); w32(csize); w32(usize)
        recs.add(Rec(nb, 8, 0x08, crc.value, csize, usize, offset))
    }

    fun finish() {
        val cdStart = pos
        for (r in recs) {
            w32(0x02014b50L); w16(20); w16(20); w16(r.flags or 0x0800); w16(r.method)
            w16(0); w16(0x21)
            w32(r.crc); w32(r.csize); w32(r.usize)
            w16(r.name.size); w16(0); w16(0); w16(0); w16(0); w32(0); w32(r.offset)
            wb(r.name)
        }
        val cdSize = pos - cdStart
        w32(0x06054b50L); w16(0); w16(0); w16(recs.size); w16(recs.size)
        w32(cdSize); w32(cdStart); w16(0)
        out.flush()
    }
}

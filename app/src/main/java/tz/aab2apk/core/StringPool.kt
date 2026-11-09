package tz.aab2apk.core

class PEntry(val str: String, val spans: List<SpanRef>?) {
    var index = -1
}

class SpanRef(val tag: PEntry, val first: Int, val last: Int)

/**
 * Builds an Android ResStringPool. Strings that carry style spans are always
 * placed first (the runtime maps style index == string index).
 */
class StringPoolBuilder(private val utf8: Boolean) {
    private val plainMap = HashMap<String, PEntry>()
    private val styledMap = HashMap<String, PEntry>()
    private val plainOrder = ArrayList<PEntry>()
    private val styledOrder = ArrayList<PEntry>()
    private var finished = false

    fun plain(s: String): PEntry {
        plainMap[s]?.let { return it }
        val e = PEntry(s, null)
        plainMap[s] = e
        plainOrder.add(e)
        return e
    }

    /** A plain entry that is never de-duplicated (keeps positional meaning). */
    fun unique(s: String): PEntry {
        val e = PEntry(s, null)
        plainOrder.add(e)
        return e
    }

    fun styled(s: String, spans: List<Span>): PEntry {
        if (spans.isEmpty()) return plain(s)
        val key = buildString {
            append(s); append('\u0000')
            for (sp in spans) { append(sp.tag); append('@'); append(sp.first); append('-'); append(sp.last); append(';') }
        }
        styledMap[key]?.let { return it }
        val refs = spans.map { SpanRef(plain(it.tag), it.first, it.last) }
        val e = PEntry(s, refs)
        styledMap[key] = e
        styledOrder.add(e)
        return e
    }

    fun finish() {
        if (finished) return
        finished = true
        var i = 0
        for (e in styledOrder) e.index = i++
        for (e in plainOrder) e.index = i++
    }

    val count: Int get() = styledOrder.size + plainOrder.size

    private fun len8(out: ByteBuf, n: Int) {
        if (n > 0x7f) { out.u8((n ushr 8) or 0x80); out.u8(n and 0xff) } else out.u8(n)
    }

    private fun encode(out: ByteBuf, s: String) {
        if (utf8) {
            val raw = s.toByteArray(Charsets.UTF_8)
            len8(out, s.length)
            len8(out, raw.size)
            out.bytes(raw)
            out.u8(0)
        } else {
            val n = s.length
            if (n > 0x7fff) { out.u16((n ushr 16) or 0x8000); out.u16(n and 0xffff) } else out.u16(n)
            for (ch in s) out.u16(ch.code)
            out.u16(0)
        }
    }

    fun write(out: ByteBuf) {
        finish()
        val all = ArrayList<PEntry>(count).apply { addAll(styledOrder); addAll(plainOrder) }
        val start = out.size
        out.u16(1); out.u16(28); out.u32(0)
        out.u32(all.size); out.u32(styledOrder.size); out.u32(if (utf8) 0x100 else 0)
        out.u32(0); out.u32(0)
        val offPos = out.size
        out.zeros(4 * (all.size + styledOrder.size))
        val strStart = out.size
        out.put32(start + 20, strStart - start)
        for ((i, e) in all.withIndex()) {
            out.put32(offPos + 4 * i, out.size - strStart)
            encode(out, e.str)
        }
        out.align4()
        if (styledOrder.isNotEmpty()) {
            val stStart = out.size
            out.put32(start + 24, stStart - start)
            for ((i, e) in styledOrder.withIndex()) {
                out.put32(offPos + 4 * (all.size + i), out.size - stStart)
                for (sp in e.spans!!) { out.u32(sp.tag.index); out.u32(sp.first); out.u32(sp.last) }
                out.u32(-1)
            }
            out.u32(-1); out.u32(-1)
        }
        out.put32(start + 4, out.size - start)
    }
}

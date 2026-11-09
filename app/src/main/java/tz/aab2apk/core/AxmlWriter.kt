package tz.aab2apk.core

/** Serialises a parsed proto XML document into Android binary XML (AXML). */
object AxmlWriter {

    private class FlatAttr(
        val ns: PEntry?, val name: PEntry, val raw: PEntry?,
        val type: Int, val data: Int, val str: PEntry?, val sortId: Int, val sortNs: String, val sortName: String
    )

    private sealed class Op
    private class StartNs(val prefix: PEntry, val uri: PEntry, val line: Int) : Op()
    private class EndNs(val prefix: PEntry, val uri: PEntry, val line: Int) : Op()
    private class StartEl(val ns: PEntry?, val name: PEntry, val attrs: List<FlatAttr>, val idIdx: Int, val classIdx: Int, val styleIdx: Int, val line: Int) : Op()
    private class EndEl(val ns: PEntry?, val name: PEntry, val line: Int) : Op()
    private class Text(val text: PEntry, val line: Int) : Op()

    private fun collectIds(e: XmlElem, out: MutableSet<Pair<Long, String>>) {
        for (a in e.attrs) if (a.resId != 0) out.add(Pair(a.resId.toLong() and 0xffffffffL, a.name))
        for (c in e.children) if (c is XmlElem) collectIds(c, out)
    }

    /**
     * Mirrors aapt2's handling of attribute values that carry no compiled item:
     * backslash escapes are resolved (whitespace is preserved as written).
     */
    internal fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                i++
                when (val n = s[i]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'u', 'U' -> {
                        val hex = if (i + 4 < s.length + 0 && i + 5 <= s.length) s.substring(i + 1, i + 5) else ""
                        val cp = if (hex.length == 4) hex.toIntOrNull(16) else null
                        if (cp != null) { sb.append(cp.toChar()); i += 4 } else sb.append(n)
                    }
                    else -> sb.append(n)
                }
            } else sb.append(c)
            i++
        }
        return sb.toString()
    }

    fun write(root: XmlElem): ByteArray {
        val pool = StringPoolBuilder(false)
        val ids = HashSet<Pair<Long, String>>()
        collectIds(root, ids)
        val sortedIds = ids.sortedWith(compareBy({ it.first }, { it.second }))
        val named = HashMap<Pair<Long, String>, PEntry>()
        for (p in sortedIds) named[p] = pool.unique(p.second)

        val ops = ArrayList<Op>()

        fun flatAttr(a: XmlAttr): FlatAttr {
            val ns = if (a.ns.isEmpty()) null else pool.plain(a.ns)
            val name = if (a.resId != 0) named[Pair(a.resId.toLong() and 0xffffffffL, a.name)]!! else pool.plain(a.name)
            val item = a.item
            var raw: PEntry? = null; var type: Int; var data = 0; var str: PEntry? = null
            when (item) {
                null -> { str = pool.plain(unescape(a.value)); raw = str; type = 0x03 }
                is StrItem -> { str = pool.plain(item.value); raw = str; type = 0x03 }
                is StyledItem -> { str = pool.plain(item.value); raw = str; type = 0x03 }
                is FileItem -> { str = pool.plain(item.path); raw = str; type = 0x03 }
                is RefItem -> { type = if (item.dynamic) (if (item.attr) 0x08 else 0x07) else (if (item.attr) 0x02 else 0x01); data = item.id }
                is IdItem -> { type = 0x12; data = 0 }
                is PrimItem -> { type = item.type; data = item.data }
            }
            return FlatAttr(ns, name, raw, type, data, str, a.resId, a.ns, a.name)
        }

        fun emit(e: XmlElem) {
            for (d in e.nsDecls) ops.add(StartNs(pool.plain(d.prefix), pool.plain(d.uri), d.line))
            val flat = e.attrs.map { flatAttr(it) }.sortedWith(Comparator { x, y ->
                val xi = x.sortId != 0; val yi = y.sortId != 0
                when {
                    xi && yi -> (x.sortId.toLong() and 0xffffffffL).compareTo(y.sortId.toLong() and 0xffffffffL)
                    xi -> -1
                    yi -> 1
                    else -> { val c = x.sortNs.compareTo(y.sortNs); if (c != 0) c else x.sortName.compareTo(y.sortName) }
                }
            })
            var idIdx = 0; var classIdx = 0; var styleIdx = 0
            for ((i, a) in flat.withIndex()) {
                if (a.sortNs.isEmpty()) when (a.sortName) { "id" -> idIdx = i + 1; "class" -> classIdx = i + 1; "style" -> styleIdx = i + 1 }
            }
            val ens = if (e.ns.isEmpty()) null else pool.plain(e.ns)
            val ename = pool.plain(e.name)
            ops.add(StartEl(ens, ename, flat, idIdx, classIdx, styleIdx, e.line))
            for (c in e.children) {
                when (c) {
                    is XmlElem -> emit(c)
                    is XmlText -> if (c.text.isNotBlank()) ops.add(Text(pool.plain(c.text), c.line))
                }
            }
            ops.add(EndEl(ens, ename, e.line))
            for (d in e.nsDecls.reversed()) ops.add(EndNs(pool.plain(d.prefix), pool.plain(d.uri), d.line))
        }
        emit(root)
        pool.finish()

        val out = ByteBuf(1 shl 14)
        out.u16(3); out.u16(8); out.u32(0)
        pool.write(out)
        // resource id map
        out.u16(0x180); out.u16(8); out.u32(8 + 4 * sortedIds.size)
        for (p in sortedIds) out.u32(p.first.toInt())

        fun idx(e: PEntry?): Int = e?.index ?: -1
        fun node(type: Int, size: Int, line: Int) {
            out.u16(type); out.u16(16); out.u32(size); out.u32(line); out.u32(-1)
        }
        for (op in ops) when (op) {
            is StartNs -> { node(0x100, 24, op.line); out.u32(idx(op.prefix)); out.u32(idx(op.uri)) }
            is EndNs -> { node(0x101, 24, op.line); out.u32(idx(op.prefix)); out.u32(idx(op.uri)) }
            is StartEl -> {
                node(0x102, 36 + 20 * op.attrs.size, op.line)
                out.u32(idx(op.ns)); out.u32(idx(op.name))
                out.u16(20); out.u16(20); out.u16(op.attrs.size)
                out.u16(op.idIdx); out.u16(op.classIdx); out.u16(op.styleIdx)
                for (a in op.attrs) {
                    out.u32(idx(a.ns)); out.u32(idx(a.name)); out.u32(idx(a.raw))
                    out.u16(8); out.u8(0); out.u8(a.type)
                    out.u32(if (a.str != null) a.str.index else a.data)
                }
            }
            is EndEl -> { node(0x103, 24, op.line); out.u32(idx(op.ns)); out.u32(idx(op.name)) }
            is Text -> { node(0x104, 28, op.line); out.u32(idx(op.text)); out.u16(8); out.u8(0); out.u8(0); out.u32(0) }
        }
        out.put32(4, out.size)
        return out.toByteArray()
    }
}

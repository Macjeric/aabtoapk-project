package tz.aab2apk.core

import java.util.TreeMap

/** Serialises a [ResTable] into the binary resources.arsc format (like `aapt2 convert`). */
object ArscWriter {

    private class ResVal(val type: Int, val data: Int, val str: PEntry?)

    private class FlatEntry(
        val id: Int, val flags: Int, val key: PEntry, val parent: Int,
        val simple: ResVal?, val map: List<Pair<Int, ResVal>>?
    )

    private class FlatType(val id: Int, val name: String) {
        val configs = LinkedHashMap<String, Pair<ByteArray, TreeMap<Int, FlatEntry>>>()
        val specFlags = TreeMap<Int, Int>()
        var maxEntry = -1
    }

    private class FlatPackage(val id: Int, val name: String, val types: List<FlatType>, val typePool: StringPoolBuilder, val keyPool: StringPoolBuilder)

    private const val ATTR_TYPE = 0x01000000
    private const val ATTR_MIN = 0x01000001
    private const val ATTR_MAX = 0x01000002
    private const val ATTR_OTHER = 0x01000004
    private val ATTR_PLURAL = intArrayOf(0x01000005, 0x01000006, 0x01000007, 0x01000008, 0x01000009, ATTR_OTHER) // zero..other

    private fun flatItem(item: Item, pool: StringPoolBuilder): ResVal = when (item) {
        is RefItem -> ResVal(if (item.dynamic) (if (item.attr) 0x08 else 0x07) else (if (item.attr) 0x02 else 0x01), item.id, null)
        is StrItem -> ResVal(0x03, 0, pool.plain(item.value))
        is StyledItem -> ResVal(0x03, 0, pool.styled(item.value, item.spans))
        is FileItem -> ResVal(0x03, 0, pool.plain(item.path))
        is IdItem -> ResVal(0x12, 0, null)
        is PrimItem -> ResVal(item.type, item.data, null)
    }

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) { sb.append(Character.forDigit((x.toInt() shr 4) and 0xf, 16)); sb.append(Character.forDigit(x.toInt() and 0xf, 16)) }
        return sb.toString()
    }

    private fun flattenPackage(p: ResPackage, global: StringPoolBuilder): FlatPackage {
        val keyPool = StringPoolBuilder(true)
        val typePool = StringPoolBuilder(false)
        val flatTypes = ArrayList<FlatType>()
        var maxTypeId = 0
        for (t in p.types) if (t.id > maxTypeId) maxTypeId = t.id
        val names = Array(maxTypeId) { "" }
        for (t in p.types) if (t.id in 1..maxTypeId) names[t.id - 1] = t.name
        for (n in names) typePool.unique(n)

        for (t in p.types.sortedBy { it.id }) {
            val ft = FlatType(t.id, t.name)
            for (e in t.entries.sortedBy { it.id }) {
                val key = keyPool.plain(e.name)
                var any = false
                for (cv in e.values) {
                    val v = cv.value
                    var flags = 0
                    if (e.isPublic) flags = flags or 0x2
                    if (v.weak) flags = flags or 0x4
                    val fe: FlatEntry? = when {
                        v.item != null -> FlatEntry(e.id, flags, key, 0, flatItem(v.item, global), null)
                        v.compound != null -> {
                            val c = v.compound
                            when (c) {
                                is AttrC -> {
                                    val m = ArrayList<Pair<Int, ResVal>>()
                                    m.add(Pair(ATTR_TYPE, ResVal(0x10, c.format, null)))
                                    if (c.min != Int.MIN_VALUE) m.add(Pair(ATTR_MIN, ResVal(0x10, c.min, null)))
                                    if (c.max != Int.MAX_VALUE) m.add(Pair(ATTR_MAX, ResVal(0x10, c.max, null)))
                                    for (s in c.symbols) m.add(Pair(s.nameId, ResVal(s.type, s.value, null)))
                                    FlatEntry(e.id, flags or 0x1, key, 0, null, m)
                                }
                                is StyleC -> {
                                    val sorted = c.entries.sortedBy { it.first.toLong() and 0xffffffffL }
                                    FlatEntry(e.id, flags or 0x1, key, c.parent, null, sorted.map { Pair(it.first, flatItem(it.second, global)) })
                                }
                                is ArrayC -> FlatEntry(e.id, flags or 0x1, key, 0, null, c.items.map { Pair(0, flatItem(it, global)) })
                                is PluralC -> {
                                    val sorted = c.items.sortedBy { it.first }
                                    FlatEntry(e.id, flags or 0x1, key, 0, null, sorted.map { Pair(ATTR_PLURAL[it.first.coerceIn(0, 5)], flatItem(it.second, global)) })
                                }
                                is SkippedC -> null
                            }
                        }
                        else -> null
                    }
                    if (fe == null) continue
                    any = true
                    val ck = hex(cv.config)
                    val slot = ft.configs.getOrPut(ck) { Pair(cv.config, TreeMap()) }
                    slot.second[e.id] = fe
                    val prev = ft.specFlags[e.id] ?: 0
                    ft.specFlags[e.id] = prev or ConfigCodec.changeFlags(cv.config)
                }
                if (any) {
                    if (e.id > ft.maxEntry) ft.maxEntry = e.id
                    if (e.isPublic) ft.specFlags[e.id] = (ft.specFlags[e.id] ?: 0) or 0x40000000
                }
            }
            if (ft.maxEntry >= 0) flatTypes.add(ft)
        }
        return FlatPackage(p.id, p.name, flatTypes, typePool, keyPool)
    }

    private fun writeValue(out: ByteBuf, v: ResVal) {
        out.u16(8); out.u8(0); out.u8(v.type)
        out.u32(if (v.str != null) v.str.index else v.data)
    }

    private fun writePackage(fp: FlatPackage): ByteBuf {
        val out = ByteBuf(1 shl 16)
        out.u16(0x200); out.u16(288); out.u32(0)
        out.u32(fp.id)
        val nameChars = fp.name.take(127)
        for (c in nameChars) out.u16(c.code)
        out.zeros(256 - nameChars.length * 2)
        out.u32(288)              // typeStrings
        out.u32(0)                // lastPublicType
        val keyOffPos = out.size
        out.u32(0)                // keyStrings (patched)
        out.u32(0)                // lastPublicKey
        out.u32(0)                // typeIdOffset
        fp.typePool.write(out)
        out.put32(keyOffPos, out.size)
        fp.keyPool.write(out)

        for (t in fp.types) {
            val count = t.maxEntry + 1
            // typeSpec
            out.u16(0x202); out.u16(16); out.u32(16 + 4 * count)
            out.u8(t.id); out.u8(0); out.u16(0); out.u32(count)
            for (i in 0 until count) out.u32(t.specFlags[i] ?: 0)

            for ((_, slot) in t.configs) {
                val (config, entries) = slot
                val start = out.size
                out.u16(0x201); out.u16(84); out.u32(0)
                out.u8(t.id); out.u8(0); out.u16(0)
                out.u32(count)
                val entriesStart = 84 + 4 * count
                out.u32(entriesStart)
                out.bytes(config)
                val offPos = out.size
                out.zeros(4 * count)
                for (i in 0 until count) out.put32(offPos + 4 * i, -1)
                val dataStart = out.size
                for ((id, e) in entries) {
                    out.put32(offPos + 4 * id, out.size - dataStart)
                    if (e.map == null) {
                        out.u16(8); out.u16(e.flags); out.u32(e.key.index)
                        writeValue(out, e.simple!!)
                    } else {
                        out.u16(16); out.u16(e.flags); out.u32(e.key.index)
                        out.u32(e.parent); out.u32(e.map.size)
                        for ((name, v) in e.map) { out.u32(name); writeValue(out, v) }
                    }
                }
                out.align4()
                out.put32(start + 4, out.size - start)
            }
        }
        out.put32(4, out.size)
        return out
    }

    fun write(table: ResTable): ByteArray {
        val global = StringPoolBuilder(true)
        val flat = table.packages.map { flattenPackage(it, global) }
        global.finish()
        flat.forEach { it.typePool.finish(); it.keyPool.finish() }
        val out = ByteBuf(1 shl 20)
        out.u16(2); out.u16(12); out.u32(0); out.u32(flat.size)
        global.write(out)
        for (fp in flat) out.append(writePackage(fp))
        out.put32(4, out.size)
        return out.toByteArray()
    }
}

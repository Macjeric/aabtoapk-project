package tz.aab2apk.core

/** Parses aapt2 "proto format" resource tables and XML documents. */
object ResProto {

    // ---------------- Items ----------------
    private fun parseRef(r: PbReader): RefItem {
        var attr = false; var id = 0; var name = ""; var dyn = false
        r.fields { f, w ->
            when (f) {
                1 -> attr = r.int32() == 1
                2 -> id = r.int32()
                3 -> name = r.string()
                5 -> { val s = r.sub(); s.fields { ff, ww -> if (ff == 1) dyn = s.bool() else s.skip(ww) } }
                else -> r.skip(w)
            }
        }
        return RefItem(attr, id, name, dyn)
    }

    private fun parseStr(r: PbReader): String {
        var v = ""
        r.fields { f, w -> if (f == 1) v = r.string() else r.skip(w) }
        return v
    }

    private fun parseStyled(r: PbReader): StyledItem {
        var v = ""
        val spans = ArrayList<Span>()
        r.fields { f, w ->
            when (f) {
                1 -> v = r.string()
                2 -> {
                    val s = r.sub()
                    var tag = ""; var a = 0; var b = 0
                    s.fields { ff, ww ->
                        when (ff) { 1 -> tag = s.string(); 2 -> a = s.int32(); 3 -> b = s.int32(); else -> s.skip(ww) }
                    }
                    spans.add(Span(tag, a, b))
                }
                else -> r.skip(w)
            }
        }
        return StyledItem(v, spans)
    }

    private fun parseFile(r: PbReader): FileItem {
        var path = ""; var type = 0
        r.fields { f, w -> when (f) { 1 -> path = r.string(); 2 -> type = r.int32(); else -> r.skip(w) } }
        return FileItem(path, type)
    }

    private fun parsePrim(r: PbReader): PrimItem {
        var type = 0x01; var data = 0
        r.fields { f, w ->
            when (f) {
                1 -> { r.skip(w); type = 0x01; data = 0 }              // @null
                2 -> { r.skip(w); type = 0x00; data = 1 }              // @empty
                3, 4, 5 -> { type = if (f == 3) 0x04 else if (f == 4) 0x05 else 0x06; data = r.fixed32() }
                6 -> { type = 0x10; data = r.int32() }
                7 -> { type = 0x11; data = r.int32() }
                8 -> { type = 0x12; data = if (r.bool()) -1 else 0 }
                9 -> { type = 0x1c; data = r.int32() }
                10 -> { type = 0x1d; data = r.int32() }
                11 -> { type = 0x1e; data = r.int32() }
                12 -> { type = 0x1f; data = r.int32() }
                13 -> { type = 0x05; data = r.int32() }
                14 -> { type = 0x06; data = r.int32() }
                else -> r.skip(w)
            }
        }
        return PrimItem(type, data)
    }

    fun parseItem(r: PbReader): Item? {
        var out: Item? = null
        r.fields { f, w ->
            when (f) {
                1 -> out = parseRef(r.sub())
                2 -> out = StrItem(parseStr(r.sub()))
                3 -> out = StrItem(parseStr(r.sub()))
                4 -> out = parseStyled(r.sub())
                5 -> out = parseFile(r.sub())
                6 -> { r.skip(w); out = IdItem }
                7 -> out = parsePrim(r.sub())
                else -> r.skip(w)
            }
        }
        return out
    }

    // ---------------- Compound ----------------
    private fun parseAttribute(r: PbReader): AttrC {
        var fmt = 0; var min = 0; var max = 0
        val syms = ArrayList<Sym>()
        r.fields { f, w ->
            when (f) {
                1 -> fmt = r.int32()
                2 -> min = r.int32()
                3 -> max = r.int32()
                4 -> {
                    val s = r.sub()
                    var nameId = 0; var value = 0; var type = 0
                    s.fields { ff, ww ->
                        when (ff) {
                            3 -> nameId = parseRef(s.sub()).id
                            4 -> value = s.int32()
                            5 -> type = s.int32()
                            else -> s.skip(ww)
                        }
                    }
                    syms.add(Sym(nameId, type, value))
                }
                else -> r.skip(w)
            }
        }
        return AttrC(fmt, min, max, syms)
    }

    private fun parseStyle(r: PbReader): StyleC {
        var parent = 0
        val entries = ArrayList<Pair<Int, Item>>()
        r.fields { f, w ->
            when (f) {
                1 -> parent = parseRef(r.sub()).id
                3 -> {
                    val s = r.sub()
                    var key = 0; var item: Item? = null
                    s.fields { ff, ww ->
                        when (ff) { 3 -> key = parseRef(s.sub()).id; 4 -> item = parseItem(s.sub()); else -> s.skip(ww) }
                    }
                    item?.let { entries.add(Pair(key, it)) }
                }
                else -> r.skip(w)
            }
        }
        return StyleC(parent, entries)
    }

    private fun parseArray(r: PbReader): ArrayC {
        val items = ArrayList<Item>()
        r.fields { f, w ->
            if (f == 1) {
                val s = r.sub()
                s.fields { ff, ww -> if (ff == 3) parseItem(s.sub())?.let { items.add(it) } else s.skip(ww) }
            } else r.skip(w)
        }
        return ArrayC(items)
    }

    private fun parsePlural(r: PbReader): PluralC {
        val items = ArrayList<Pair<Int, Item>>()
        r.fields { f, w ->
            if (f == 1) {
                val s = r.sub()
                var arity = 0; var item: Item? = null
                s.fields { ff, ww ->
                    when (ff) { 3 -> arity = s.int32(); 4 -> item = parseItem(s.sub()); else -> s.skip(ww) }
                }
                item?.let { items.add(Pair(arity, it)) }
            } else r.skip(w)
        }
        return PluralC(items)
    }

    private fun parseCompound(r: PbReader): Compound {
        var out: Compound = SkippedC
        r.fields { f, w ->
            when (f) {
                1 -> out = parseAttribute(r.sub())
                2 -> out = parseStyle(r.sub())
                4 -> out = parseArray(r.sub())
                5 -> out = parsePlural(r.sub())
                else -> r.skip(w)      // 3 = styleable, 6 = macro
            }
        }
        return out
    }

    private fun parseValue(r: PbReader): ResValue {
        var weak = false; var item: Item? = null; var comp: Compound? = null
        r.fields { f, w ->
            when (f) {
                3 -> weak = r.bool()
                4 -> item = parseItem(r.sub())
                5 -> comp = parseCompound(r.sub())
                else -> r.skip(w)
            }
        }
        return ResValue(weak, item, comp)
    }

    // ---------------- Table ----------------
    private fun parseEntry(r: PbReader): ResEntry {
        var id = 0; var name = ""; var pub = false
        val values = ArrayList<CfgValue>()
        r.fields { f, w ->
            when (f) {
                1 -> { val s = r.sub(); s.fields { ff, ww -> if (ff == 1) id = s.int32() else s.skip(ww) } }
                2 -> name = r.string()
                3 -> { val s = r.sub(); s.fields { ff, ww -> if (ff == 1) pub = s.int32() == 2 else s.skip(ww) } }
                6 -> {
                    val s = r.sub()
                    var cfg = ConfigCodec.empty(); var v: ResValue? = null
                    s.fields { ff, ww ->
                        when (ff) { 1 -> cfg = ConfigCodec.parse(s.sub()); 2 -> v = parseValue(s.sub()); else -> s.skip(ww) }
                    }
                    v?.let { values.add(CfgValue(cfg, it)) }
                }
                else -> r.skip(w)
            }
        }
        return ResEntry(id, name, pub, values)
    }

    private fun parseType(r: PbReader): ResType {
        var id = 0; var name = ""
        val entries = ArrayList<ResEntry>()
        r.fields { f, w ->
            when (f) {
                1 -> { val s = r.sub(); s.fields { ff, ww -> if (ff == 1) id = s.int32() else s.skip(ww) } }
                2 -> name = r.string()
                3 -> entries.add(parseEntry(r.sub()))
                else -> r.skip(w)
            }
        }
        return ResType(id, name, entries)
    }

    private fun parsePackage(r: PbReader): ResPackage {
        var id = 0; var name = ""
        val types = ArrayList<ResType>()
        r.fields { f, w ->
            when (f) {
                1 -> { val s = r.sub(); s.fields { ff, ww -> if (ff == 1) id = s.int32() else s.skip(ww) } }
                2 -> name = r.string()
                3 -> types.add(parseType(r.sub()))
                else -> r.skip(w)
            }
        }
        return ResPackage(id, name, types)
    }

    fun parseTable(data: ByteArray): ResTable {
        val r = PbReader(data)
        val pkgs = ArrayList<ResPackage>()
        r.fields { f, w -> if (f == 2) pkgs.add(parsePackage(r.sub())) else r.skip(w) }
        return ResTable(pkgs)
    }

    // ---------------- XML ----------------
    private fun line(r: PbReader): Int {
        var l = 0
        r.fields { f, w -> if (f == 1) l = r.int32() else r.skip(w) }
        return l
    }

    private fun parseAttr(r: PbReader): XmlAttr {
        var ns = ""; var name = ""; var value = ""; var id = 0; var item: Item? = null
        r.fields { f, w ->
            when (f) {
                1 -> ns = r.string()
                2 -> name = r.string()
                3 -> value = r.string()
                5 -> id = r.int32()
                6 -> item = parseItem(r.sub())
                else -> r.skip(w)
            }
        }
        return XmlAttr(ns, name, value, id, item)
    }

    private fun parseElem(r: PbReader, lineNo: Int): XmlElem {
        val decls = ArrayList<XmlNs>()
        var ns = ""; var name = ""
        val attrs = ArrayList<XmlAttr>()
        val kids = ArrayList<XmlNode>()
        r.fields { f, w ->
            when (f) {
                1 -> {
                    val s = r.sub()
                    var prefix = ""; var uri = ""; var l = 0
                    s.fields { ff, ww ->
                        when (ff) { 1 -> prefix = s.string(); 2 -> uri = s.string(); 3 -> l = line(s.sub()); else -> s.skip(ww) }
                    }
                    decls.add(XmlNs(prefix, uri, l))
                }
                2 -> ns = r.string()
                3 -> name = r.string()
                4 -> attrs.add(parseAttr(r.sub()))
                5 -> parseNode(r.sub())?.let { kids.add(it) }
                else -> r.skip(w)
            }
        }
        return XmlElem(decls, ns, name, attrs, kids, lineNo)
    }

    private fun parseNode(r: PbReader): XmlNode? {
        var elemReader: PbReader? = null
        var text: String? = null
        var l = 0
        r.fields { f, w ->
            when (f) {
                1 -> elemReader = r.sub()
                2 -> text = r.string()
                3 -> l = line(r.sub())
                else -> r.skip(w)
            }
        }
        elemReader?.let { return parseElem(it, l) }
        text?.let { return XmlText(it, l) }
        return null
    }

    fun parseXml(data: ByteArray): XmlElem {
        val node = parseNode(PbReader(data)) ?: throw IllegalStateException("Empty XML document")
        return node as? XmlElem ?: throw IllegalStateException("XML root is not an element")
    }
}

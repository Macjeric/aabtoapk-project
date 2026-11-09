package tz.aab2apk.core

// ---- Items (values that can appear inline) ----
sealed class Item
class RefItem(val attr: Boolean, val id: Int, val name: String, val dynamic: Boolean) : Item()
class StrItem(val value: String) : Item()
class StyledItem(val value: String, val spans: List<Span>) : Item()
class FileItem(val path: String, val type: Int) : Item()
object IdItem : Item()
class PrimItem(val type: Int, val data: Int) : Item()
class Span(val tag: String, val first: Int, val last: Int)

// ---- Compound values ----
sealed class Compound
class AttrC(val format: Int, val min: Int, val max: Int, val symbols: List<Sym>) : Compound()
class Sym(val nameId: Int, val type: Int, val value: Int)
class StyleC(val parent: Int, val entries: List<Pair<Int, Item>>) : Compound()
class ArrayC(val items: List<Item>) : Compound()
class PluralC(val items: List<Pair<Int, Item>>) : Compound()
object SkippedC : Compound()   // styleable / macro: not part of the binary table

// ---- Table ----
class ResValue(val weak: Boolean, val item: Item?, val compound: Compound?)
class CfgValue(val config: ByteArray, val value: ResValue)
class ResEntry(val id: Int, val name: String, val isPublic: Boolean, val values: List<CfgValue>)
class ResType(val id: Int, val name: String, val entries: List<ResEntry>)
class ResPackage(val id: Int, val name: String, val types: List<ResType>)
class ResTable(val packages: List<ResPackage>) {
    /** Paths of files that are stored as proto XML and need binary conversion. */
    fun protoXmlPaths(): Set<String> {
        val out = HashSet<String>()
        fun see(i: Item?) { if (i is FileItem && i.type == 3) out.add(i.path) }
        for (p in packages) for (t in p.types) for (e in t.entries) for (cv in e.values) {
            see(cv.value.item)
            when (val c = cv.value.compound) {
                is StyleC -> c.entries.forEach { see(it.second) }
                is ArrayC -> c.items.forEach { see(it) }
                is PluralC -> c.items.forEach { see(it.second) }
                else -> {}
            }
        }
        return out
    }
}

// ---- XML ----
class XmlNs(val prefix: String, val uri: String, val line: Int)
class XmlAttr(val ns: String, val name: String, val value: String, val resId: Int, val item: Item?)
sealed class XmlNode
class XmlElem(
    val nsDecls: List<XmlNs>,
    val ns: String,
    val name: String,
    val attrs: MutableList<XmlAttr>,
    val children: MutableList<XmlNode>,
    val line: Int
) : XmlNode()
class XmlText(val text: String, val line: Int) : XmlNode()

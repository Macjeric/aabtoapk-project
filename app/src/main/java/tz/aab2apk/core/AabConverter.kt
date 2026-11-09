package tz.aab2apk.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ConvertOptions(
    /** If non-null, only native libraries for these ABIs are kept. */
    val abiFilter: Set<String>? = null
)

class ConvertResult(val entries: Int, val warnings: List<String>, val packageName: String?)

/** Converts the base module of an Android App Bundle into a single signed universal APK. */
object AabConverter {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val ID_IS_SPLIT_REQUIRED = 0x01010591

    private val REMOVE_META = setOf("com.android.vending.splits.required", "com.android.vending.splits")
    private val REMOVE_ATTRS = setOf("isSplitRequired", "requiredSplitTypes", "splitTypes")

    private fun cleanManifest(e: XmlElem) {
        e.attrs.removeAll { a -> a.ns == ANDROID_NS && (a.name in REMOVE_ATTRS || a.resId == ID_IS_SPLIT_REQUIRED) }
        e.children.removeAll { c ->
            c is XmlElem && c.name == "meta-data" &&
                c.attrs.any { it.ns == ANDROID_NS && it.name == "name" && (it.value in REMOVE_META || (it.item as? StrItem)?.value in REMOVE_META) }
        }
        for (c in e.children) if (c is XmlElem) cleanManifest(c)
    }

    private fun packageOf(m: XmlElem): String? = m.attrs.firstOrNull { it.ns.isEmpty() && it.name == "package" }?.value

    fun convert(
        aab: File, outApk: File, workDir: File, keys: SigningKeys,
        opts: ConvertOptions = ConvertOptions(), log: (String) -> Unit = {}
    ): ConvertResult {
        workDir.mkdirs()
        val warnings = ArrayList<String>()
        val unsigned = File(workDir, "unsigned.apk")
        var pkg: String? = null
        var count = 0

        ZipFile(aab).use { zip ->
            val manifestEntry = zip.getEntry("base/manifest/AndroidManifest.xml")
                ?: throw IllegalArgumentException("This does not look like an .aab: base/manifest/AndroidManifest.xml is missing")

            val otherModules = zip.entries().asSequence()
                .map { it.name }.filter { it.endsWith("/manifest/AndroidManifest.xml") && !it.startsWith("base/") }
                .map { it.substringBefore('/') }.toList()
            if (otherModules.isNotEmpty())
                warnings.add("Skipped feature modules: ${otherModules.joinToString()} (only the base module is converted)")

            unsigned.outputStream().buffered(1 shl 16).use { fos ->
                val zb = ZipBuilder(fos)

                // 1. Manifest
                log("Converting AndroidManifest.xml")
                val manifest = ResProto.parseXml(zip.getInputStream(manifestEntry).readBytes())
                pkg = packageOf(manifest)
                cleanManifest(manifest)
                zb.addDeflated("AndroidManifest.xml", AxmlWriter.write(manifest)); count++

                // 2. Dex files
                val dex = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("base/dex/") && it.name.endsWith(".dex") }
                    .sortedBy { it.name }.toList()
                for (d in dex) {
                    log("Copying ${d.name.removePrefix("base/dex/")}")
                    zb.addDeflatedStream(d.name.removePrefix("base/dex/"), zip.getInputStream(d)); count++
                }

                // 3. Resources table
                var xmlPaths: Set<String> = emptySet()
                val tableEntry = zip.getEntry("base/resources.pb")
                if (tableEntry != null) {
                    log("Converting resources.pb → resources.arsc")
                    val table = ResProto.parseTable(zip.getInputStream(tableEntry).readBytes())
                    xmlPaths = table.protoXmlPaths()
                    zb.addStored("resources.arsc", ArscWriter.write(table), 4); count++
                }

                // 4. res/ files
                var xmlConverted = 0
                for (e in zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("base/res/") }) {
                    val name = e.name.removePrefix("base/")
                    if (name in xmlPaths) {
                        try {
                            val root = ResProto.parseXml(zip.getInputStream(e).readBytes())
                            zb.addDeflated(name, AxmlWriter.write(root)); xmlConverted++
                        } catch (ex: Exception) {
                            warnings.add("Could not convert $name: ${ex.message}")
                            copyEntry(zb, zip, e, name)
                        }
                    } else copyEntry(zb, zip, e, name)
                    count++
                }
                log("Converted $xmlConverted XML resources")

                // 5. assets, lib, root
                fun copyDir(prefix: String, target: String, filter: ((String) -> Boolean)? = null) {
                    for (e in zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(prefix) }) {
                        val rel = e.name.removePrefix(prefix)
                        if (filter != null && !filter(rel)) continue
                        copyEntry(zb, zip, e, target + rel); count++
                    }
                }
                log("Copying assets, native libraries and root files")
                copyDir("base/assets/", "assets/")
                val libAbis = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("base/lib/") }
                    .map { it.name.removePrefix("base/lib/").substringBefore('/') }.toSet()
                // Only filter if at least one wanted ABI exists; otherwise keep everything.
                val keepAbis = opts.abiFilter?.takeIf { f -> libAbis.any { it in f } }
                if (keepAbis != null && libAbis.any { it !in keepAbis })
                    log("Keeping native libraries for: ${libAbis.filter { it in keepAbis }.joinToString()}")
                copyDir("base/lib/", "lib/") { rel -> keepAbis == null || rel.substringBefore('/') in keepAbis }
                copyDir("base/root/", "")
                zb.finish()
            }
        }

        log("Signing (APK Signature Scheme v2)")
        ApkV2Signer.sign(unsigned, outApk, keys)
        unsigned.delete()
        return ConvertResult(count, warnings, pkg)
    }

    private fun copyEntry(zb: ZipBuilder, zip: ZipFile, e: ZipEntry, target: String) {
        val isLib = target.endsWith(".so")
        val stored = isLib || target == "resources.arsc" || e.method == ZipEntry.STORED
        if (stored && e.crc >= 0 && e.size >= 0) {
            val align = if (isLib) 16384 else 4
            zb.addStoredStream(target, e.crc, e.size, align, zip.getInputStream(e))
        } else zb.addDeflatedStream(target, zip.getInputStream(e))
    }
}

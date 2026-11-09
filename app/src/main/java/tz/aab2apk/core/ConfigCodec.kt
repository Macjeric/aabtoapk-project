package tz.aab2apk.core

/** Converts the proto Configuration message into the 64-byte binary ResTable_config. */
object ConfigCodec {
    const val SIZE = 64

    fun empty(): ByteArray = ByteArray(SIZE).also { it[0] = SIZE.toByte() }

    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }

    private fun packLang(b: ByteArray, o: Int, s: String, base: Char) {
        if (s.length == 2) { b[o] = s[0].code.toByte(); b[o + 1] = s[1].code.toByte(); return }
        val first = (s[0] - base) and 0x7f
        val second = (s[1] - base) and 0x7f
        val third = (s[2] - base) and 0x7f
        b[o] = (0x80 or (third shl 2) or (second ushr 3)).toByte()
        b[o + 1] = ((second shl 5) or first).toByte()
    }

    private fun applyLocale(b: ByteArray, tag: String) {
        if (tag.isEmpty()) return
        val parts = tag.removePrefix("b+").split('-', '+')
        if (parts.isEmpty() || parts[0].isEmpty()) return
        val lang = parts[0].lowercase()
        if (lang.length in 2..3) packLang(b, 8, lang, 'a')
        var haveScript = false; var haveRegion = false; var haveVariant = false
        for (i in 1 until parts.size) {
            val s = parts[i]
            when {
                s.length == 1 -> return
                s.length == 4 && s.all { it.isLetter() } && !haveScript && !haveRegion -> {
                    for (k in 0 until 4) b[36 + k] = s[k].code.toByte(); haveScript = true
                }
                s.length == 2 && s.all { it.isLetter() } && !haveRegion -> {
                    val u = s.uppercase(); b[10] = u[0].code.toByte(); b[11] = u[1].code.toByte(); haveRegion = true
                }
                s.length == 3 && s.all { it.isDigit() } && !haveRegion -> { packLang(b, 10, s, '0'); haveRegion = true }
                (s.length in 5..8 || (s.length == 4 && s[0].isDigit())) && !haveVariant -> {
                    for (k in 0 until s.length) b[40 + k] = s[k].code.toByte(); haveVariant = true
                }
            }
        }
    }

    fun parse(r: PbReader): ByteArray {
        val b = empty()
        var layoutSize = 0; var layoutLong = 0; var layoutDir = 0
        var round = 0; var wide = 0; var hdr = 0
        var uiType = 0; var night = 0
        var keysHidden = 0; var navHidden = 0
        var locale = ""
        r.fields { f, w ->
            when (f) {
                1 -> put16(b, 4, r.int32())
                2 -> put16(b, 6, r.int32())
                3 -> locale = r.string()
                4 -> layoutDir = r.int32()
                5 -> put16(b, 20, r.int32())
                6 -> put16(b, 22, r.int32())
                7 -> put16(b, 32, r.int32())
                8 -> put16(b, 34, r.int32())
                9 -> put16(b, 30, r.int32())
                10 -> layoutSize = r.int32()
                11 -> layoutLong = r.int32()
                12 -> round = r.int32()
                13 -> wide = r.int32()
                14 -> hdr = r.int32()
                15 -> b[12] = r.int32().toByte()
                16 -> uiType = r.int32()
                17 -> night = r.int32()
                18 -> put16(b, 14, r.int32())
                19 -> b[13] = r.int32().toByte()
                20 -> keysHidden = r.int32()
                21 -> b[16] = r.int32().toByte()
                22 -> navHidden = r.int32()
                23 -> b[17] = r.int32().toByte()
                24 -> put16(b, 24, r.int32())
                else -> r.skip(w)
            }
        }
        applyLocale(b, locale)
        val dirBits = when (layoutDir) { 1 -> 0x40; 2 -> 0x80; else -> 0 }
        val longBits = when (layoutLong) { 1 -> 0x20; 2 -> 0x10; else -> 0 }
        b[28] = (layoutSize or longBits or dirBits).toByte()
        b[29] = (uiType or when (night) { 1 -> 0x20; 2 -> 0x10; else -> 0 }).toByte()
        b[48] = when (round) { 1 -> 0x02; 2 -> 0x01; else -> 0 }.toByte()
        b[49] = (when (wide) { 1 -> 0x02; 2 -> 0x01; else -> 0 } or when (hdr) { 1 -> 0x08; 2 -> 0x04; else -> 0 }).toByte()
        b[18] = (when (keysHidden) { 1 -> 1; 2 -> 2; 3 -> 3; else -> 0 } or when (navHidden) { 1 -> 0x4; 2 -> 0x8; else -> 0 }).toByte()
        return b
    }

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xff
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)

    /** Which configuration axes this config constrains (ResTable_config CONFIG_* bits). */
    fun changeFlags(b: ByteArray): Int {
        var d = 0
        if (u16(b, 4) != 0) d = d or 0x1
        if (u16(b, 6) != 0) d = d or 0x2
        if ((8 until 12).any { b[it].toInt() != 0 } || (36 until 48).any { b[it].toInt() != 0 } ||
            (52 until 60).any { b[it].toInt() != 0 }) d = d or 0x4
        if (u8(b, 13) != 0) d = d or 0x8
        if (u8(b, 16) != 0) d = d or 0x10
        if ((u8(b, 18) and 0x0f) != 0) d = d or 0x20
        if (u8(b, 17) != 0) d = d or 0x40
        if (u8(b, 12) != 0) d = d or 0x80
        if (u16(b, 14) != 0) d = d or 0x100
        if (u16(b, 20) != 0 || u16(b, 22) != 0 || u16(b, 32) != 0 || u16(b, 34) != 0) d = d or 0x200
        if (u16(b, 24) != 0 || u16(b, 26) != 0) d = d or 0x400
        if ((u8(b, 28) and 0x3f) != 0) d = d or 0x800
        if ((u8(b, 28) and 0xc0) != 0) d = d or 0x4000
        if ((u8(b, 48) and 0x03) != 0) d = d or 0x8000
        if (u8(b, 49) != 0) d = d or 0x10000
        if (u8(b, 29) != 0) d = d or 0x1000
        if (u16(b, 30) != 0) d = d or 0x2000
        return d
    }
}

package tz.aab2apk.core

/** Small growable little-endian byte buffer used by all binary writers. */
class ByteBuf(initial: Int = 256) {
    private var a = ByteArray(initial)
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > a.size) a = a.copyOf(maxOf(a.size * 2, size + n))
    }

    fun u8(v: Int) { ensure(1); a[size++] = v.toByte() }
    fun u16(v: Int) { ensure(2); a[size++] = v.toByte(); a[size++] = (v ushr 8).toByte() }
    fun u32(v: Int) {
        ensure(4)
        a[size++] = v.toByte(); a[size++] = (v ushr 8).toByte()
        a[size++] = (v ushr 16).toByte(); a[size++] = (v ushr 24).toByte()
    }
    fun u64(v: Long) { u32(v.toInt()); u32((v ushr 32).toInt()) }
    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size - off) {
        ensure(len); System.arraycopy(b, off, a, size, len); size += len
    }
    fun zeros(n: Int) { ensure(n); java.util.Arrays.fill(a, size, size + n, 0.toByte()); size += n }
    fun put32(pos: Int, v: Int) {
        a[pos] = v.toByte(); a[pos + 1] = (v ushr 8).toByte()
        a[pos + 2] = (v ushr 16).toByte(); a[pos + 3] = (v ushr 24).toByte()
    }
    fun put16(pos: Int, v: Int) { a[pos] = v.toByte(); a[pos + 1] = (v ushr 8).toByte() }
    fun align4() { while ((size and 3) != 0) u8(0) }
    fun append(o: ByteBuf) = bytes(o.a, 0, o.size)
    fun toByteArray(): ByteArray = a.copyOf(size)
}

/** Minimal protobuf wire-format reader (only what Resources.proto needs). */
class PbReader(private val b: ByteArray, private var p: Int, private val end: Int) {
    constructor(b: ByteArray) : this(b, 0, b.size)

    fun more() = p < end
    fun tag(): Int = varint().toInt()

    fun varint(): Long {
        var r = 0L
        var s = 0
        while (true) {
            val c = b[p++].toInt()
            r = r or ((c and 0x7f).toLong() shl s)
            if ((c and 0x80) == 0) return r
            s += 7
        }
    }

    fun int32(): Int = varint().toInt()
    fun bool(): Boolean = varint() != 0L

    fun fixed32(): Int {
        val v = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or
            ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24)
        p += 4
        return v
    }

    fun string(): String {
        val n = varint().toInt()
        val s = String(b, p, n, Charsets.UTF_8)
        p += n
        return s
    }

    fun sub(): PbReader {
        val n = varint().toInt()
        val r = PbReader(b, p, p + n)
        p += n
        return r
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> p += 8
            2 -> { val n = varint().toInt(); p += n }
            5 -> p += 4
            else -> throw IllegalStateException("Unsupported protobuf wire type $wire")
        }
    }
}

inline fun PbReader.fields(f: (field: Int, wire: Int) -> Unit) {
    while (more()) {
        val t = tag()
        f(t ushr 3, t and 7)
    }
}

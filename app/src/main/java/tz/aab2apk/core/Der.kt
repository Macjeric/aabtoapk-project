package tz.aab2apk.core

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Tiny DER encoder, just enough to build a self-signed X.509 certificate. */
object Der {
    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), n.toByte())
    }

    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(tag); o.write(len(content.size)); o.write(content)
        return o.toByteArray()
    }

    private fun cat(parts: Array<out ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        for (p in parts) o.write(p)
        return o.toByteArray()
    }

    fun seq(vararg p: ByteArray) = tlv(0x30, cat(p))
    fun set(vararg p: ByteArray) = tlv(0x31, cat(p))
    fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun utf8(s: String) = tlv(0x0c, s.toByteArray(Charsets.UTF_8))
    fun bitString(b: ByteArray) = tlv(0x03, byteArrayOf(0) + b)
    fun nul() = byteArrayOf(0x05, 0x00)

    fun oid(s: String): ByteArray {
        val parts = s.split('.').map { it.toLong() }
        val o = ByteArrayOutputStream()
        o.write((parts[0] * 40 + parts[1]).toInt())
        for (i in 2 until parts.size) {
            var v = parts[i]
            val tmp = ArrayList<Int>()
            tmp.add((v and 0x7f).toInt()); v = v ushr 7
            while (v > 0) { tmp.add(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
            for (k in tmp.indices.reversed()) o.write(tmp[k])
        }
        return tlv(0x06, o.toByteArray())
    }

    fun time(d: Date): ByteArray {
        val tz = TimeZone.getTimeZone("UTC")
        val year = java.util.Calendar.getInstance(tz).apply { time = d }.get(java.util.Calendar.YEAR)
        return if (year < 2050) {
            tlv(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = tz }.format(d).toByteArray())
        } else {
            tlv(0x18, SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = tz }.format(d).toByteArray())
        }
    }
}

object CertBuilder {
    /** Creates a self-signed SHA256withRSA certificate for the given key pair. */
    fun selfSigned(kp: KeyPair, commonName: String, validYears: Int): ByteArray {
        val sigAlg = Der.seq(Der.oid("1.2.840.113549.1.1.11"), Der.nul())
        val name = Der.seq(Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8(commonName))))
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + validYears * 365L * 24 * 3600 * 1000)
        val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
        val tbs = Der.seq(
            Der.tlv(0xa0, Der.int(BigInteger.valueOf(2))),
            Der.int(serial),
            sigAlg,
            name,
            Der.seq(Der.time(notBefore), Der.time(notAfter)),
            name,
            kp.public.encoded
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(kp.private); update(tbs); sign()
        }
        return Der.seq(tbs, sigAlg, Der.bitString(sig))
    }
}

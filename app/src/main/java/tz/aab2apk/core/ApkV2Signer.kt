package tz.aab2apk.core

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.Signature

/** APK Signature Scheme v2 (RSA PKCS#1 v1.5 + SHA-256) for an already zip-aligned, unsigned APK. */
object ApkV2Signer {
    private const val CHUNK = 1024 * 1024
    private const val ALG_RSA_PKCS1_SHA256 = 0x0103
    private const val V2_ID = 0x7109871a

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private fun prefixed(b: ByteArray): ByteArray = ByteBuf(b.size + 4).also { it.u32(b.size); it.bytes(b) }.toByteArray()

    fun sign(unsigned: File, out: File, keys: SigningKeys) {
        RandomAccessFile(unsigned, "r").use { raf ->
            val len = raf.length()
            val eocdPos = len - 22
            val eocd = ByteArray(22)
            raf.seek(eocdPos); raf.readFully(eocd)
            check(le(eocd, 0) == 0x06054b50) { "Unexpected zip trailer" }
            val cdSize = le(eocd, 12).toLong() and 0xffffffffL
            val cdOffset = le(eocd, 16).toLong() and 0xffffffffL

            // --- Content digest (needs chunk count first, so digest chunks twice-pass friendly) ---
            val chunkDigests = java.io.ByteArrayOutputStream()
            val count = IntArray(1)
            fun section(start: Long, end: Long, tail: ByteArray? = null) {
                var pos = start
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(CHUNK)
                while (pos < end) {
                    val n = minOf(CHUNK.toLong(), end - pos).toInt()
                    raf.seek(pos); raf.readFully(buf, 0, n)
                    md.reset(); md.update(0xa5.toByte()); md.update(le32(n)); md.update(buf, 0, n)
                    chunkDigests.write(md.digest()); count[0]++
                    pos += n
                }
                if (tail != null) {
                    md.reset(); md.update(0xa5.toByte()); md.update(le32(tail.size)); md.update(tail)
                    chunkDigests.write(md.digest()); count[0]++
                }
            }
            section(0, cdOffset)
            section(cdOffset, cdOffset + cdSize)
            section(0, 0, eocd)   // EOCD (offset field already equals signing-block start)
            val top = MessageDigest.getInstance("SHA-256")
            top.update(0x5a.toByte()); top.update(le32(count[0])); top.update(chunkDigests.toByteArray())
            val contentDigest = top.digest()

            // --- Signed data ---
            val digestRecord = ByteBuf().also { it.u32(ALG_RSA_PKCS1_SHA256); it.u32(contentDigest.size); it.bytes(contentDigest) }.toByteArray()
            val digests = prefixed(prefixed(digestRecord))
            val certs = prefixed(prefixed(keys.certDer))
            val attrs = prefixed(ByteArray(0))
            val signedData = ByteBuf().also { it.bytes(digests); it.bytes(certs); it.bytes(attrs) }.toByteArray()

            val sig = Signature.getInstance("SHA256withRSA").run { initSign(keys.privateKey); update(signedData); sign() }
            val sigRecord = ByteBuf().also { it.u32(ALG_RSA_PKCS1_SHA256); it.u32(sig.size); it.bytes(sig) }.toByteArray()
            val signatures = prefixed(prefixed(sigRecord))
            val publicKey = prefixed(keys.certificate.publicKey.encoded)

            val signer = ByteBuf().also { it.bytes(prefixed(signedData)); it.bytes(signatures); it.bytes(publicKey) }.toByteArray()
            val signers = prefixed(prefixed(signer))

            val pairValue = signers
            val pair = ByteBuf().also { it.u64(4L + pairValue.size); it.u32(V2_ID); it.bytes(pairValue) }.toByteArray()
            val blockSize = pair.size.toLong() + 8 + 16
            val block = ByteBuf().also {
                it.u64(blockSize); it.bytes(pair); it.u64(blockSize); it.bytes("APK Sig Block 42".toByteArray(Charsets.US_ASCII))
            }.toByteArray()

            // --- Assemble output ---
            out.outputStream().buffered().use { os ->
                val buf = ByteArray(1 shl 16)
                fun copy(start: Long, end: Long) {
                    var pos = start
                    raf.seek(start)
                    while (pos < end) {
                        val n = minOf(buf.size.toLong(), end - pos).toInt()
                        raf.readFully(buf, 0, n); os.write(buf, 0, n); pos += n
                    }
                }
                copy(0, cdOffset)
                os.write(block)
                copy(cdOffset, cdOffset + cdSize)
                val newEocd = eocd.copyOf()
                val newOffset = cdOffset + block.size
                newEocd[16] = newOffset.toByte(); newEocd[17] = (newOffset ushr 8).toByte()
                newEocd[18] = (newOffset ushr 16).toByte(); newEocd[19] = (newOffset ushr 24).toByte()
                os.write(newEocd)
            }
        }
    }

    private fun le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
}

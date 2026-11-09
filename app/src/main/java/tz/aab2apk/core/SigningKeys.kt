package tz.aab2apk.core

import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/** A signing key + certificate persisted in a directory (pk8 + der files). */
class SigningKeys(val privateKey: PrivateKey, val certDer: ByteArray) {
    val certificate: X509Certificate
        get() = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate

    fun sha256Fingerprint(): String =
        MessageDigest.getInstance("SHA-256").digest(certDer).joinToString(":") { "%02X".format(it) }

    companion object {
        fun loadOrCreate(dir: File): SigningKeys {
            dir.mkdirs()
            val pk = File(dir, "key.pk8")
            val cert = File(dir, "cert.der")
            if (pk.exists() && cert.exists()) {
                try {
                    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pk.readBytes()))
                    return SigningKeys(key, cert.readBytes())
                } catch (_: Exception) { /* regenerate below */ }
            }
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val der = CertBuilder.selfSigned(kp, "AAB to APK Converter", 30)
            pk.writeBytes(kp.private.encoded)
            cert.writeBytes(der)
            return SigningKeys(kp.private, der)
        }

        fun reset(dir: File) {
            File(dir, "key.pk8").delete(); File(dir, "cert.der").delete()
        }
    }
}

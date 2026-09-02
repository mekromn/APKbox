package com.mekromn.apkbox.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * LibADB's legacy auth path performs raw RSA private-key operations (RSA/ECB/NoPadding), which an
 * AndroidKeyStore signing-only RSA key intentionally does not expose. APKbox therefore uses a
 * normal in-process RSA key while encrypting its PKCS#8 form at rest with a non-exportable
 * AndroidKeyStore AES-GCM wrapping key. The plaintext private key is never persisted.
 */
internal class AdbIdentityStore(context: Context) {
    data class Identity(
        val privateKey: PrivateKey,
        val certificate: Certificate,
    )

    companion object {
        private const val PREFS = "apkbox-adb-identity"
        private const val WRAP_ALIAS = "apkbox-adb-identity-wrap-v1"
        private const val KEY_SIZE = 2048
        private const val CERT_YEARS = 20L
        private const val OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
        private const val OID_COMMON_NAME = "2.5.4.3"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val random = SecureRandom()

    @Synchronized
    fun loadOrCreate(): Identity {
        load()?.let { return it }
        clearStoredIdentity()
        return create().also(::store)
    }

    private fun load(): Identity? = runCatching {
        val privateBytes = decryptBlob("private") ?: return@runCatching null
        val certBytes = decryptBlob("certificate") ?: return@runCatching null
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateBytes))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certBytes))
        // Fail closed if storage was mixed/corrupted and the public/private pair no longer matches.
        val challenge = ByteArray(32).also(random::nextBytes)
        val signer = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(challenge)
        }
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(certificate.publicKey)
            update(challenge)
        }
        check(verifier.verify(signer.sign())) { "Stored ADB identity keypair does not match." }
        Identity(privateKey, certificate)
    }.getOrNull()

    private fun create(): Identity {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(KEY_SIZE, random)
        val pair = generator.generateKeyPair()
        val certificate = generateSelfSignedCertificate(pair.private, pair.public.encoded)
        return Identity(pair.private, certificate)
    }

    private fun store(identity: Identity) {
        encryptBlob("private", identity.privateKey.encoded)
        encryptBlob("certificate", identity.certificate.encoded)
    }

    private fun clearStoredIdentity() {
        prefs.edit()
            .remove("private.cipher")
            .remove("private.iv")
            .remove("certificate.cipher")
            .remove("certificate.iv")
            .apply()
    }

    private fun encryptBlob(name: String, value: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val encrypted = cipher.doFinal(value)
        prefs.edit()
            .putString("$name.cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("$name.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
    }

    private fun decryptBlob(name: String): ByteArray? {
        val encrypted = prefs.getString("$name.cipher", null) ?: return null
        val iv = prefs.getString("$name.iv", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            wrappingKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
    }

    private fun wrappingKey(): SecretKey {
        (keyStore.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun generateSelfSignedCertificate(privateKey: PrivateKey, subjectPublicKeyInfo: ByteArray): Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 5 * 60_000L)
        val notAfter = Date(now + CERT_YEARS * 365L * 24L * 60L * 60L * 1_000L)
        val algorithm = Der.sequence(Der.oid(OID_SHA256_WITH_RSA), Der.nullValue())
        val name = Der.sequence(
            Der.set(
                Der.sequence(
                    Der.oid(OID_COMMON_NAME),
                    Der.utf8("APKbox Wireless ADB Bridge"),
                )
            )
        )
        val serial = BigInteger(96, random).max(BigInteger.ONE)
        val tbs = Der.sequence(
            Der.explicit(0, Der.integer(BigInteger.valueOf(2))), // X.509 v3
            Der.integer(serial),
            algorithm,
            name,
            Der.sequence(Der.time(notBefore), Der.time(notAfter)),
            name,
            subjectPublicKeyInfo,
        )
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(tbs)
        }.sign()
        val encoded = Der.sequence(
            tbs,
            algorithm,
            Der.bitString(signature),
        )
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(encoded))
    }

    /** Minimal DER writer for the one self-signed RSA certificate APKbox needs. */
    private object Der {
        fun sequence(vararg values: ByteArray): ByteArray = tagged(0x30, concat(values))
        fun set(vararg values: ByteArray): ByteArray = tagged(0x31, concat(values))
        fun explicit(number: Int, value: ByteArray): ByteArray = tagged(0xA0 + number, value)
        fun integer(value: BigInteger): ByteArray = tagged(0x02, value.toByteArray())
        fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)
        fun utf8(value: String): ByteArray = tagged(0x0C, value.toByteArray(StandardCharsets.UTF_8))
        fun bitString(value: ByteArray): ByteArray = tagged(0x03, byteArrayOf(0x00) + value)

        fun time(date: Date): ByteArray {
            val year = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(date).toInt()
            val format = if (year in 1950..2049) {
                SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
            } else {
                SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US)
            }.apply { timeZone = TimeZone.getTimeZone("UTC") }
            return tagged(if (year in 1950..2049) 0x17 else 0x18, format.format(date).toByteArray(StandardCharsets.US_ASCII))
        }

        fun oid(value: String): ByteArray {
            val parts = value.split('.').map(String::toLong)
            require(parts.size >= 2 && parts[0] in 0..2 && parts[1] >= 0) { "Invalid OID." }
            val output = ArrayList<Byte>()
            encodeBase128(parts[0] * 40 + parts[1], output)
            for (index in 2 until parts.size) encodeBase128(parts[index], output)
            return tagged(0x06, ByteArray(output.size) { output[it] })
        }

        private fun encodeBase128(value: Long, output: MutableList<Byte>) {
            require(value >= 0) { "Negative OID component." }
            var current = value
            val stack = ByteArray(10)
            var count = 0
            stack[count++] = (current and 0x7F).toByte()
            current = current ushr 7
            while (current > 0) {
                stack[count++] = ((current and 0x7F) or 0x80).toByte()
                current = current ushr 7
            }
            for (index in count - 1 downTo 0) output += stack[index]
        }

        private fun tagged(tag: Int, content: ByteArray): ByteArray =
            byteArrayOf(tag.toByte()) + length(content.size) + content

        private fun length(value: Int): ByteArray {
            require(value >= 0)
            if (value < 128) return byteArrayOf(value.toByte())
            var current = value
            val bytes = ByteArray(4)
            var count = 0
            while (current > 0) {
                bytes[bytes.lastIndex - count] = (current and 0xFF).toByte()
                current = current ushr 8
                count++
            }
            return byteArrayOf((0x80 or count).toByte()) + bytes.copyOfRange(bytes.size - count, bytes.size)
        }

        private fun concat(values: Array<out ByteArray>): ByteArray {
            val size = values.sumOf { it.size }
            val output = ByteArray(size)
            var offset = 0
            for (value in values) {
                value.copyInto(output, offset)
                offset += value.size
            }
            return output
        }
    }
}

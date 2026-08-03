package dev.minios.ocremote.data.sync

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** JVM-only payload encryption. Android Keystore is deliberately not involved here. */
object PasswordCrypto {
    const val ITERATIONS = 310_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): EncryptedSecrets {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val key = deriveKey(passphrase, salt)
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            doFinal(plaintext)
        }
        return EncryptedSecrets(
            iterations = ITERATIONS,
            salt = Base64.getEncoder().encodeToString(salt),
            iv = Base64.getEncoder().encodeToString(iv),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
        )
    }

    fun decrypt(envelope: EncryptedSecrets, passphrase: CharArray): ByteArray {
        require(envelope.algorithm == "AES-256-GCM" && envelope.kdf == "PBKDF2WithHmacSHA256") {
            "Unsupported encrypted secrets format"
        }
        val salt = Base64.getDecoder().decode(envelope.salt)
        val iv = Base64.getDecoder().decode(envelope.iv)
        val ciphertext = Base64.getDecoder().decode(envelope.ciphertext)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, envelope.iterations), GCMParameterSpec(128, iv))
                doFinal(ciphertext)
            }
        } catch (e: AEADBadTagException) {
            throw IllegalArgumentException("Incorrect passphrase or corrupted encrypted secrets", e)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int = ITERATIONS) =
        SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(passphrase, salt, iterations, KEY_BITS)).encoded,
            "AES",
        )
}

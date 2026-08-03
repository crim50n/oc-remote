package dev.minios.ocremote.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps transport credentials off DataStore and out of exported payloads. */
@Singleton
class LocalSyncSecretStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun get(key: SecretKey): String? = preferences.getString(key.preferenceKey, null)?.let(::decrypt)

    fun put(key: SecretKey, value: String?) {
        preferences.edit().apply {
            if (value.isNullOrEmpty()) remove(key.preferenceKey) else putString(key.preferenceKey, encrypt(value))
        }.apply()
    }

    fun clearAll() = preferences.edit().clear().apply()

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String? = runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12)
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }.getOrNull()

    private fun key() = (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        .getKey(ALIAS, null) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()) as javax.crypto.SecretKey

    enum class SecretKey(val preferenceKey: String) {
        GITHUB_TOKEN("github_token"),
        WEBDAV_PASSWORD("webdav_password"),
        SYNC_PASSPHRASE("sync_passphrase"),
    }

    companion object {
        private const val PREFERENCES = "sync_secrets"
        private const val ALIAS = "ocremote_sync_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

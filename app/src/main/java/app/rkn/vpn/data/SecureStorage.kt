package app.rkn.vpn.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM поверх Android Keystore для чувствительных значений SharedPreferences.
 *
 * Ключ никогда не покидает Keystore. В pref хранится префикс `enc:` и base64
 * от IV + ciphertext. Старые plaintext-значения читаются как есть и при первом
 * же чтении перезаписываются в зашифрованном виде — миграция не требует от
 * пользователя повторно вводить подписку.
 */
internal class SecureStorage(private val sp: SharedPreferences) {

    fun get(key: String): String? {
        val raw = sp.getString(key, null) ?: return null
        if (!raw.startsWith(PREFIX)) return raw.also { put(key, it) }

        val decrypted = decrypt(raw.removePrefix(PREFIX))
        if (decrypted == null) sp.edit().remove(key).apply()
        return decrypted
    }

    fun put(key: String, value: String?) {
        if (value == null) {
            sp.edit().remove(key).apply()
            return
        }
        sp.edit().putString(key, encrypt(value)).apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String? = runCatching {
        val combined = Base64.decode(payload, Base64.NO_WRAP)
        if (combined.size <= IV_BYTES) return@runCatching null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_BITS, combined.copyOfRange(0, IV_BYTES))
        )
        String(
            cipher.doFinal(combined.copyOfRange(IV_BYTES, combined.size)),
            Charsets.UTF_8
        )
    }.getOrNull()

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val PREFIX = "enc:"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "rknvpn_secure_prefs_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

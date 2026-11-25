package com.smartys.iptv

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Direkter Android-Keystore-AES-256-GCM-Speicher als Ersatz für die
 * veraltete androidx.security:security-crypto (EncryptedSharedPreferences +
 * MasterKey), die auf Android 15/16 beim Schlüssel-Zugriff (Tink-Keyset)
 * mit einer KeyStoreException crasht.
 *
 * Fail-soft wie SecCrypto: bei jedem Fehler null zurückgeben, NIE crashen.
 * Der Keystore-Schlüssel überlebt App-Updates (gleiche Signatur) und wird
 * nur bei Deinstallation/Geräte-Reset gelöscht — identisch zum bisherigen
 * Verhalten.
 */
object SecureStore {

    private const val KEY_ALIAS = "smartys_iptv_master_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12

    private fun getOrCreateKey(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: run {
                val kg = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
                )
                kg.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                kg.generateKey()
            }
    } catch (e: Exception) {
        android.util.Log.e("SecureStore", "Keystore-Schlüssel nicht verfügbar", e)
        null
    }

    /** Verschlüsselt zu base64(iv || ciphertext). null bei Fehler. */
    fun encrypt(plaintext: String): String? {
        return try {
            val key = getOrCreateKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("SecureStore", "Verschlüsseln fehlgeschlagen", e)
            null
        }
    }

    /** Entschlüsselt base64(iv || ciphertext). null bei Fehler. */
    fun decrypt(data: String): String? {
        return try {
            val key = getOrCreateKey() ?: return null
            val raw = Base64.decode(data, Base64.NO_WRAP)
            if (raw.size <= IV_LEN) return null
            val iv = raw.copyOfRange(0, IV_LEN)
            val ct = raw.copyOfRange(IV_LEN, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("SecureStore", "Entschlüsseln fehlgeschlagen", e)
            null
        }
    }
}

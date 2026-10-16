package com.smartys.iptv

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sicherheits-Passwort-Feature (Panel-Payload-Entschlüsselung) — Android-Port
 * von Desktop/desktop/src/jvmMain/kotlin/com/smartys/iptv/SecCrypto.kt.
 *
 * Backend-Vertrag (iptv-backend/app/crypto.py, bindend):
 *  - Key: PBKDF2-HMAC-SHA256, 32 Byte, Iterationen aus der API-Antwort
 *    (Standard 310.000), Salt = base64dekodiertes "salt"-Feld.
 *  - Cipher: AES-256-GCM. Token = base64(nonce[12] || ciphertext+tag).
 *  - AAD = "smartys-iptv-sec:" + Player-ID (UTF-8), bindet den Ciphertext
 *    an genau diesen Player.
 *  - Bei Defekt/falschem Passwort: leerer String, NIE crashen.
 *
 * android.util.Base64 statt java.util.Base64: minSdk 21.
 */
object SecCrypto {

    data class SecProvider(
        val label: String,
        val cipherServerUrl: String,
        val cipherUsername: String,
        val cipherPassword: String,
    )

    fun deriveKey(password: String, saltB64: String, iterations: Int): ByteArray {
        val salt = Base64.decode(saltB64, Base64.DEFAULT)
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    fun decryptField(key: ByteArray, tokenB64: String, playerId: String): String {
        if (tokenB64.isBlank()) return ""
        return try {
            val raw = Base64.decode(tokenB64, Base64.DEFAULT)
            if (raw.size <= 12) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, raw, 0, 12)
            )
            cipher.updateAAD(("smartys-iptv-sec:" + playerId).toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            ""   // Backend-Vertrag: bei Defekt leerer String, nie crashen
        }
    }
}

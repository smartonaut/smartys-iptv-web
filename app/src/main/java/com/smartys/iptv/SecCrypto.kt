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
        // PBKDF2WithHmacSHA256 gibt es in Android erst ab API 26. Fire OS 6
        // (Fire-TV-Stick, Android 7.1.2 = API 25) wirft hier
        // NoSuchAlgorithmException — die wurde verschluckt, und JEDES Passwort
        // meldete "Passwort falsch" (nur auf dem Stick, der Cube mit API 28
        // lief korrekt). Deshalb eigener RFC-2898-Fallback über Mac HmacSHA256.
        return try {
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } catch (e: Exception) {
            android.util.Log.w(
                "SmartysIPTV",
                "PBKDF2WithHmacSHA256 nicht verfuegbar (API ${android.os.Build.VERSION.SDK_INT}) — Eigen-Implementierung"
            )
            // Feature 5 (PBKDF2-Fallback f. API<26) NUR TV-Flavor (USE_PBKDF2_FALLBACK).
            if (!BuildConfig.USE_PBKDF2_FALLBACK) throw e
            pbkdf2HmacSha256(password, salt, iterations, 32)
        }
    }

    /**
     * PBKDF2-HMAC-SHA256 nach RFC 2898 ohne SecretKeyFactory (API-25-Fallback).
     * Gegen Python `hashlib.pbkdf2_hmac("sha256", …)` verifiziert: byte-identisch
     * (1000 und 310.000 Iterationen).
     */
    private fun pbkdf2HmacSha256(password: String, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val pw = password.toByteArray(Charsets.UTF_8)
        val hLen = 32
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        for (i in 1..blocks) {
            mac.init(SecretKeySpec(pw, "HmacSHA256"))
            mac.update(salt)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            mac.doFinal(u, 0)
            System.arraycopy(u, 0, t, 0, hLen)
            var j = 2
            while (j <= iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                j++
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        return out.copyOf(dkLen)
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

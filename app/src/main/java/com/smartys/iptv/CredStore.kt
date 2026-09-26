package com.smartys.iptv

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Speichert die Xtream-Zugangsdaten verschlüsselt auf dem Gerät.
 * NICHT im Klartext, NICHT hardcodiert.
 */
object CredStore {

    private const val PREFS = "smartys_iptv_creds"
    private const val K_URL = "provider_url"
    private const val K_USER = "username"
    private const val K_PASS = "password"
    private const val K_PLAYER_ID = "player_id"
    private const val K_PAIRING_PW = "pairing_pw"

    private fun prefs(ctx: Context) =
        EncryptedSharedPreferences.create(
            ctx,
            PREFS,
            MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    /**
     * Player-ID: rein kryptographischer Zufall (SecureRandom), 6 Bytes im
     * MAC-Format. ABSICHTLICH NICHT aus der Geraete-MAC abgeleitet:
     * - Jede deterministische Ableitung (HMAC etc.) waere mit dem in der App
     *   eingebetteten Salt per Brute-Force zur Geraete-MAC ZURUECKRECHENBAR
     *   (Reverse Engineering) — genau das soll verhindert werden.
     * - Zufall = es gibt schlicht nichts zurueckzurechnen.
     * Stabilität: einmal generiert, verschlüsselt gespeichert, commit()
     * (synchron) — überlebt App-Starts und Updates. Nur ein vollständiger
     * App-Daten-Wipe erzeugt eine neue ID (wie bei IBPlayer: neu registrieren).
     * Byte 0 = lokal-admin-Bit (02:...) => keine Kollision mit echten Hardware-MACs.
     */
    @Synchronized
    fun playerId(ctx: Context): String {
        val p = prefs(ctx)
        p.getString(K_PLAYER_ID, null)?.let { return it }
        val bytes = ByteArray(6)
        java.security.SecureRandom().nextBytes(bytes)
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()   // lokal administriert
        val id = bytes.joinToString(":") { String.format("%02X", it) }
        // commit() statt apply(): synchrone Disk-Speicherung. Die Player-ID ist
        // PAIRING-KRITISCH — ein verlorener async-Write wuerde beim naechsten
        // Start eine NEUE ID generieren und die Web-Registrierung entkoppeln.
        p.edit().putString(K_PLAYER_ID, id).commit()
        return id
    }

    /**
     * Pairing-Passwort: vom Gerät generiert (12 Zeichen, kryptographisch sicher).
     * Wird beim ersten Start erzeugt und im Pairing-Screen angezeigt (IBPlayer-Modell);
     * mit Player-ID zusammen das Login für die Website.
     */
    @Synchronized
    fun pairingPw(ctx: Context): String {
        val p = prefs(ctx)
        p.getString(K_PAIRING_PW, null)?.let { return it }
        val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        val rnd = java.security.SecureRandom()
        val sb = StringBuilder(12)
        val buf = ByteArray(12)
        rnd.nextBytes(buf)
        for (b in buf) sb.append(CHARS[Math.floorMod(b.toInt(), CHARS.length)])
        val pw = sb.toString()
        // commit() wie bei der Player-ID: Pairing-Passwort darf niemals durch
        // einen verlorenen async-Write "neu generiert" werden.
        p.edit().putString(K_PAIRING_PW, pw).commit()
        return pw
    }

    fun save(ctx: Context, url: String, user: String, pass: String) {
        prefs(ctx).edit()
            .putString(K_URL, url.trim().trimEnd('/'))
            .putString(K_USER, user.trim())
            .putString(K_PASS, pass)
            .apply()
    }

    data class Creds(val url: String, val user: String, val pass: String) {
        fun streamUrl(streamId: String, ext: String = "m3u8"): String =
            "$url/live/$user/$pass/$streamId.$ext"
    }

    fun load(ctx: Context): Creds? {
        val p = prefs(ctx)
        val url = p.getString(K_URL, null) ?: return null
        val user = p.getString(K_USER, null) ?: return null
        val pass = p.getString(K_PASS, null) ?: return null
        return Creds(url, user, pass)
    }

    fun clear(ctx: Context) = prefs(ctx).edit().clear().commit()
}
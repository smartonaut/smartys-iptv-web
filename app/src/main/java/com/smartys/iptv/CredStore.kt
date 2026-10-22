package com.smartys.iptv

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Speichert die Xtream-Zugangsdaten verschlüsselt auf dem Gerät.
 * NICHT im Klartext, NICHT hardcodiert.
 *
 * Zwei Speicher-Pfade, gesteuert über BuildConfig.USE_KEYSTORE_STORE:
 *  - tv-Flavor (FireTV): EncryptedSharedPreferences (unverändert, wie v0.8.6).
 *  - phone-Flavor (Handy, Android 16): direkter Android-Keystore
 *    (SecureStore) — EncryptedSharedPreferences crasht auf Android 15/16.
 */
object CredStore {

    private const val PREFS = "smartys_iptv_creds"
    private const val K_URL = "provider_url"
    private const val K_USER = "username"
    private const val K_PASS = "password"
    private const val K_PROVIDERS = "providers_json"
    private const val K_PLAYER_ID = "player_id"
    private const val K_PAIRING_PW = "pairing_pw"

    private val useKeystore = BuildConfig.USE_KEYSTORE_STORE

    private fun prefs(ctx: Context): SharedPreferences =
        if (useKeystore) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        } else {
            EncryptedSharedPreferences.create(
                ctx,
                PREFS,
                MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

    /** Schreibt einen Wert verschlüsselt (phone) bzw. via EncryptedPrefs (tv). */
    private fun putSecure(p: SharedPreferences, key: String, value: String) {
        val stored = if (useKeystore) (SecureStore.encrypt(value) ?: value) else value
        p.edit().putString(key, stored).commit()
    }

    /** Liest einen Wert entschlüsselt zurück. null wenn nicht vorhanden. */
    private fun getSecure(p: SharedPreferences, key: String): String? {
        val stored = p.getString(key, null) ?: return null
        return if (useKeystore) (SecureStore.decrypt(stored) ?: stored) else stored
    }

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
        getSecure(p, K_PLAYER_ID)?.let { return it }
        val bytes = ByteArray(6)
        java.security.SecureRandom().nextBytes(bytes)
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()   // lokal administriert
        val id = bytes.joinToString(":") { String.format("%02X", it) }
        // commit() statt apply(): synchrone Disk-Speicherung. Die Player-ID ist
        // PAIRING-KRITISCH — ein verlorener async-Write wuerde beim naechsten
        // Start eine NEUE ID generieren und die Web-Registrierung entkoppeln.
        putSecure(p, K_PLAYER_ID, id)
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
        getSecure(p, K_PAIRING_PW)?.let { return it }
        val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        val rnd = java.security.SecureRandom()
        val sb = StringBuilder(12)
        val buf = ByteArray(12)
        rnd.nextBytes(buf)
        for (b in buf) sb.append(CHARS[Math.floorMod(b.toInt(), CHARS.length)])
        val pw = sb.toString()
        // commit() wie bei der Player-ID: Pairing-Passwort darf niemals durch
        // einen verlorenen async-Write "neu generiert" werden.
        putSecure(p, K_PAIRING_PW, pw)
        return pw
    }

    fun save(ctx: Context, url: String, user: String, pass: String) {
        saveAll(ctx, listOf(Creds(url, user, pass)))
    }

    /** Speichert eine Liste von Providern (verschlüsselt, als JSON). */
    fun saveAll(ctx: Context, providers: List<Creds>) {
        val arr = org.json.JSONArray()
        providers.forEach { c ->
            arr.put(org.json.JSONObject().put("url", c.url).put("user", c.user).put("pass", c.pass))
        }
        putSecure(prefs(ctx), K_PROVIDERS, arr.toString())
        // Legacy-Einzelfelder weiterhin pflegen (primary = erster) für Rückwärtskompatibilität
        val first = providers.firstOrNull()
        if (first != null) {
            val p = prefs(ctx)
            putSecure(p, K_URL, first.url.trim().trimEnd('/'))
            putSecure(p, K_USER, first.user.trim())
            putSecure(p, K_PASS, first.pass)
        }
    }

    data class Creds(val url: String, val user: String, val pass: String) {
        /**
         * Stabiler Schluessel fuer die Profil-Bindung: URL + User. Ueberlebt
         * Umbenennungen der Line im Panel (anders als der Listen-Index).
         */
        val key: String get() = "$url|$user"
        fun streamUrl(streamId: String, ext: String = "m3u8"): String =
            "$url/live/$user/$pass/$streamId.$ext"

        fun movieUrl(streamId: String, ext: String = "mp4"): String =
            "$url/movie/$user/$pass/$streamId.$ext"

        fun seriesUrl(episodeId: String, ext: String = "mp4"): String =
            "$url/series/$user/$pass/$episodeId.$ext"

        /** Catch-up/Timeshift: start=Unix-Sekunden, duration=Archiv-Stunden. */
        fun timeshiftUrl(streamId: String, startEpoch: Long, durationHours: Int): String =
            "$url/timeshift/$user/$pass/$durationHours/$startEpoch/$streamId.m3u8"
    }

    fun load(ctx: Context): Creds? {
        val p = prefs(ctx)
        val url = getSecure(p, K_URL) ?: return null
        val user = getSecure(p, K_USER) ?: return null
        val pass = getSecure(p, K_PASS) ?: return null
        return Creds(url, user, pass)
    }

    /** Lädt ALLE gespeicherten Provider (JSON); Fallback auf Legacy-Einzelfeld. */
    fun loadAll(ctx: Context): List<Creds> {
        val p = prefs(ctx)
        val json = getSecure(p, K_PROVIDERS)
        if (json != null) {
            return runCatching {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Creds(o.getString("url"), o.getString("user"), o.getString("pass"))
                }
            }.getOrDefault(emptyList())
        }
        // Fallback: alter Einzel-Provider
        return load(ctx)?.let { listOf(it) } ?: emptyList()
    }

    fun clear(ctx: Context) = prefs(ctx).edit().clear().commit()
}

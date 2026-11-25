package com.smartys.iptv

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Player-Identität nach PLAYER-IDENTITY.md (v1, verbindlich).
 *
 * Die Panel-Line wird als AES-256-GCM-Ciphertext mit AAD "smartys-iptv-sec:<ID>"
 * gespeichert — verliert ein Gerät seine Player-ID, ist die zugewiesene Line
 * unwiederbringlich weg. Neue IDs werden deshalb deterministisch aus
 * gerätestabilen Merkmalen abgeleitet (ANDROID_ID + Hersteller + Modell),
 * NIE aus der Hardware-MAC.
 *
 * [derivePlayerId] ist eine reine Funktion ohne Android-Abhängigkeiten, damit
 * der Testvektor aus PLAYER-IDENTITY.md auf jeder Plattform identisch
 * geprüft werden kann.
 */
object PlayerIdentity {

    /** Vertragsbestandteil — Änderung ändert ALLE neu vergebenen IDs. */
    private const val NAMESPACE = "smartys-iptv-player-id-v1"

    /**
     * Reine Ableitung der Player-ID:
     * SHA-256 über UTF-8("smartys-iptv-player-id-v1|" + seed), erste 6 Bytes,
     * Byte 0 = lokal administriert + Unicast ((b[0] & 0xFC) | 0x02),
     * formatiert als "XX:XX:XX:XX:XX:XX" (UPPERCASE, Doppelpunkte).
     *
     * Keine Android-Abhängigkeiten — direkt per JVM-Unit-Test prüfbar.
     */
    fun derivePlayerId(seed: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((NAMESPACE + "|" + seed).toByteArray(Charsets.UTF_8))
        val b = digest.copyOf(6)
        b[0] = ((b[0].toInt() and 0xFC) or 0x02).toByte()
        return b.joinToString(":") { String.format("%02X", (it.toInt() and 0xFF)) }
    }

    /** Alphabet ohne verwechselbare Zeichen (kein O/0, I/l/1). */
    private const val PW_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"

    /**
     * Pairing-Passwort deterministisch aus demselben Geräte-Seed wie die
     * Player-ID.
     *
     * WARUM: Das Passwort wurde bisher gewürfelt und nur lokal gespeichert.
     * Nach einer Neuinstallation erzeugte das Gerät ein NEUES Passwort, das
     * Panel kannte nur das alte — das Pairing lief auf 401 und die App zeigte
     * "Noch keine Playlist hinterlegt". Der Nutzer musste das Panel-Passwort
     * von Hand nachziehen. Da die Player-ID bereits deterministisch ist und
     * über eine Neuinstallation hinweg gleich bleibt, muss das Passwort
     * dasselbe Verhalten zeigen.
     *
     * Ableitung: SHA-256 über "<namespace>|<seed>", die ersten 12 Bytes
     * abgebildet auf [PW_CHARS]. Byte wird als SIGNED interpretiert und per
     * floorMod auf den Wertebereich gebracht — dadurch ist das Ergebnis
     * plattformunabhängig reproduzierbar (Kotlin/JVM wie Testskript).
     */
    fun derivePairingPw(seed: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((PW_NAMESPACE + "|" + seed).toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(12)
        for (i in 0 until 12) {
            sb.append(PW_CHARS[Math.floorMod(digest[i].toInt(), PW_CHARS.length)])
        }
        return sb.toString()
    }

    /** Vertragsbestandteil — Änderung ändert ALLE abgeleiteten Passwörter. */
    private const val PW_NAMESPACE = "smartys-iptv-pairing-v1"

    /**
     * Seed aus gerätestabilen Merkmalen (Android/TV), feste Reihenfolge:
     * android_id + manufacturer + model.
     *
     * Fehlt ein Teil (leer/null), fällt er WEG aus dem Seed — nicht als
     * leerer String anhängen, sonst ist die Ableitung nicht reproduzierbar
     * (PLAYER-IDENTITY.md: "Seeds je Plattform"). Liefert "" wenn gar
     * nichts lesbar ist; der Aufrufer (CredStore) würfelt dann zufällig.
     *
     * ANDROID_ID ist seit Android 8 pro App-Signaturschlüssel + Nutzer stabil
     * und überlebt die Neuinstallation der App — genau das macht die daraus
     * abgeleitete ID reinstall-stabil.
     */
    fun deviceSeed(ctx: Context): String {
        val parts = ArrayList<String>(3)
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrBlank()) parts.add("android_id=$androidId")
        val manufacturer = Build.MANUFACTURER ?: ""
        if (manufacturer.isNotBlank()) parts.add("manufacturer=$manufacturer")
        val model = Build.MODEL ?: ""
        if (model.isNotBlank()) parts.add("model=$model")
        return parts.joinToString("|")
    }
}
package com.smartys.iptv

import android.content.Context

/**
 * Auswahl der EPG-Quellen (Einstellungen -> EPG).
 *
 * Standard: alle drei an. Wer nur eine Quelle will, kann die anderen
 * abschalten — abgeschaltete Quellen werden beim Laden übersprungen und
 * ihr vorhandener Zwischenspeicher wird verworfen, damit garantiert keine
 * Programmdaten aus einer nicht gewünschten Quelle angezeigt werden.
 */
object EpgPrefs {
    private const val PREFS = "smartys_epg_prefs"
    private const val K_PROVIDER = "quelle_anbieter"
    private const val K_EXTRA = "quelle_eigener_link"
    private const val K_FALLBACK = "quelle_epgpw"
    private const val K_EIGENE_URL = "eigene_epg_url"

    data class Auswahl(val anbieter: Boolean, val eigenerLink: Boolean, val epgpw: Boolean) {
        val keine: Boolean get() = !anbieter && !eigenerLink && !epgpw
    }

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lesen(c: Context): Auswahl {
        val s = sp(c)
        return Auswahl(
            anbieter = s.getBoolean(K_PROVIDER, true),
            eigenerLink = s.getBoolean(K_EXTRA, true),
            // Standard AUS: epg.pw liefert Zeiten, die um Stunden daneben liegen
            // (28.10.: dort "ZDF-Morgenmagazin" 12:30 statt 04:30). Nur auf Wunsch.
            epgpw = s.getBoolean(K_FALLBACK, false),
        )
    }

    /** Vom Nutzer eingetragene XMLTV-Adresse (leer = keine). */
    fun eigeneUrlLesen(c: Context): String = sp(c).getString(K_EIGENE_URL, "") ?: ""

    fun eigeneUrlSchreiben(c: Context, url: String) {
        sp(c).edit().putString(K_EIGENE_URL, url.trim()).apply()
    }

    fun schreiben(c: Context, a: Auswahl) {
        sp(c).edit()
            .putBoolean(K_PROVIDER, a.anbieter)
            .putBoolean(K_EXTRA, a.eigenerLink)
            .putBoolean(K_FALLBACK, a.epgpw)
            .apply()
    }
}

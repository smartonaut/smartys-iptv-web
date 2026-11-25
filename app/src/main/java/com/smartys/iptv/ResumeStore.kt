package com.smartys.iptv

import android.content.Context
import android.content.SharedPreferences

/**
 * Merkt sich die zuletzt erreichte Abspielposition für Filme und Episoden,
 * damit man einen abgebrochenen Titel an derselben Stelle fortsetzen kann.
 *
 * Schlüssel = stabiler Titel-Key (z. B. "movie:<streamId>" oder
 * "series:<episodeId>"). Position in Millisekunden. Ein Titel, der zu >95 %
 * durchgespielt wurde, gilt als "fertig" und wird beim Speichern gelöscht —
 * so taucht kein sinnloses "Fortsetzen" für einen abgeschlossenen Film auf.
 *
 * Speicher: normales (unkritisches) SharedPreferences — keine Secrets.
 */
object ResumeStore {

    private const val PREFS = "smartys_iptv_resume"

    private fun prefs(ctx: Context): SharedPreferences {
        val pid = ProfileStore.activeId(ctx) ?: "default"
        return ctx.getSharedPreferences("${PREFS}__$pid", Context.MODE_PRIVATE)
    }

    /** Position (ms) für einen Titel, oder 0 wenn nichts gespeichert. */
    fun position(ctx: Context, key: String): Long =
        prefs(ctx).getLong(key, 0L)

    /** Speichert die aktuelle Position. Bei ~Ende wird der Eintrag gelöscht. */
    fun save(ctx: Context, key: String, posMs: Long, durationMs: Long) {
        val p = prefs(ctx)
        val finished = durationMs > 0 && posMs > 0 && posMs >= durationMs * 95 / 100
        p.edit().apply {
            if (finished) remove(key) else putLong(key, posMs)
        }.apply()
    }

    fun clear(ctx: Context, key: String) {
        prefs(ctx).edit().remove(key).apply()
    }
}

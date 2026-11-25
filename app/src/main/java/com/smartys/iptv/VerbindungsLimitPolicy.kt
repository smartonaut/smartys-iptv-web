package com.smartys.iptv

import android.util.Log
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Wiederholt Ladefehler beim Anbieter deutlich geduldiger als der Standard.
 *
 * Warum: Bei einem Verbindungslimit antwortet der Server mit HTTP 509 (oder
 * 429). Media3 wiederholt standardmaessig nur dreimal im Sekundentakt und
 * meldet dann einen fatalen Fehler. Der Player warf daraufhin bis zu 20 s
 * bereits gepuffertes Video weg und verband neu — sichtbar als Schwarzbild,
 * obwohl das Material im Speicher lag.
 *
 * Diese Policy klopft bei einem Limit erst nach 15-30 s wieder an. In dieser
 * Zeit spielt der Player den vorhandenen Puffer leer. Meistens ist der Slot
 * dann wieder frei und es gibt weder Aussetzer noch Neustart.
 *
 * Nicht-Limit-Fehler (kurze WLAN-Aussetzer, Timeouts) werden wie ueblich nach
 * kurzer Zeit wiederholt — dort ist schnelles Nachfassen richtig.
 */
class VerbindungsLimitPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val code = httpCode(loadErrorInfo.exception)
        return if (code == 509 || code == 429) {
            // Kurz halten, wie es vorher funktioniert hat: bei 1-2 s Pause war
            // der Aussetzer kaum sichtbar. Lange Wartezeiten (10-30 s) haben
            // aus einem kurzen Lag ein 20-40-Sekunden-Standbild gemacht.
            Log.w("SmartysIPTV", "Verbindungslimit ($code) — neuer Versuch in 2s " +
                    "(Versuch ${loadErrorInfo.errorCount})")
            2_000L
        } else {
            super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    /** Drei Versuche reichen; danach uebernimmt der normale Reconnect-Pfad. */
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = 3

    /** Liest den HTTP-Status aus einer beliebigen Exception in der Cause-Kette. */
    private fun httpCode(e: Throwable?): Int {
        var t = e
        var tiefe = 0
        while (t != null && tiefe < 8) {
            if (t is HttpDataSource.InvalidResponseCodeException) return t.responseCode
            val msg = t.message ?: ""
            // Fallback: manche Provider-Fehler tragen den Code nur im Text.
            if (msg.contains("Response code: 509")) return 509
            if (msg.contains("Response code: 429")) return 429
            t = t.cause
            tiefe++
        }
        return 0
    }
}

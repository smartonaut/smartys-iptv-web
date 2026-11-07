package com.smartys.iptv

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * Gibt auf schwachen Fire-TV-Sticks Speicher frei, solange der Player laeuft.
 *
 * Auf einem Stick der 2. Generation (875 MB RAM) fressen Amazon-Dienste, die
 * mit Fernsehen nichts zu tun haben, rund 125 MB: Alexa (vizzini), die
 * Bildschirmtastatur, Telemetrie, Second-Screen. Gemessen vor/nach dem Stoppen:
 * MemAvailable 93 MB -> 165 MB, also ~70 MB gewonnen.
 *
 * Android erlaubt einer App NICHT, andere Apps hart zu beenden (am force-stop
 * ist Shell-only). Erlaubt ist killBackgroundProcesses() mit der Berechtigung
 * KILL_BACKGROUND_PROCESSES: das raeumt Prozesse ab, die ohnehin im Hintergrund
 * liegen. Genau die betroffenen Dienste sind das, waehrend der Player im
 * Vordergrund spielt.
 *
 * Zurueckholen muss man nichts: Android startet einen Dienst beim naechsten
 * Bedarf selbst neu (Alexa beim Sprachbefehl, die Tastatur beim Textfeld,
 * Telemetrie beim naechsten Systemstart).
 */
object MemoryGuard {

    /** Nur Dienste, deren Wegfall den Player nicht beruehrt. */
    private val ENTSORGBAR = listOf(
        "com.amazon.vizzini",                              // Alexa-Sprachdienst
        "com.amazon.tv.ime",                               // Bildschirmtastatur
        "com.amazon.alexamediaplayer.runtime.ftv",         // Alexa-Medien
        "com.amazon.alexa.externalmediaplayer.fireos",
        "com.amazon.whisperlink.core.android",             // Second Screen
        "com.amazon.whisperjoin.middleware.np",
        "com.amazon.client.metrics",                       // Telemetrie
        "com.amazon.kso.blackbird",                        // Telemetrie
        "com.amazon.tcomm",                                // Telemetrie
        "com.amazon.imp",
        "com.amazon.device.messaging",                     // Push
        "com.amazon.tv.alexaalerts",
        "com.amazon.cardinal",
        "com.amazon.discoveryservice.core.android",
    )

    /**
     * NIE anfassen: surfaceflinger/system/mediaserver sind Systemprozesse,
     * devicecontrol haelt die Fernbedienung, der Launcher kommt sofort wieder
     * und wuerde nur unnoetig Arbeit machen.
     */
    private val GESCHUETZT = setOf(
        "android", "system", "com.android.systemui",
        "com.amazon.tv.devicecontrol",
        "com.amazon.device.services",
        "com.amazon.tv.launcher",
        "com.smartys.iptv", "com.smartys.iptv.lite", "com.smartys.iptv.mobile",
    )

    @Volatile
    private var zuletztFrei = -1

    /** Vor dem Kanalstart aufrufen. Idempotent, laeuft im Hintergrund-Thread. */
    fun aufraeumen(ctx: Context) {
        // Nur im Lite-Build: auf Cube/Handy ist genug Speicher da, und dort
        // soll sich das Verhalten nicht aendern.
        if (!BuildConfig.LITE_DEVICE) return

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val vorher = freierSpeicher(am)

        var gestoppt = 0
        for (pkg in ENTSORGBAR) {
            if (pkg in GESCHUETZT) continue
            try {
                am.killBackgroundProcesses(pkg)
                gestoppt++
            } catch (e: Exception) {
                Log.w("SmartysIPTV", "Aufraeumen: $pkg nicht stoppbar (${e.javaClass.simpleName})")
            } catch (e: SecurityException) {
                Log.w("SmartysIPTV", "Aufraeumen: keine Berechtigung fuer $pkg")
            }
        }

        val nachher = freierSpeicher(am)
        zuletztFrei = nachher
        if (vorher > 0 && nachher > 0) {
            Log.i("SmartysIPTV", "Speicher freigeraeumt: ${vorher / 1024} MB -> ${nachher / 1024} MB " +
                    "(+${(nachher - vorher) / 1024} MB, $gestoppt Dienste)")
        }
    }

    /** Fuer die Statuszeile im Player-OSD/Settings. */
    fun freierSpeicherMb(ctx: Context): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return -1
        return freierSpeicher(am) / 1024
    }

    private fun freierSpeicher(am: ActivityManager): Int {
        val info = ActivityManager.MemoryInfo()
        return try {
            am.getMemoryInfo(info)
            (info.availMem / 1024).toInt()
        } catch (e: Exception) {
            -1
        }
    }
}

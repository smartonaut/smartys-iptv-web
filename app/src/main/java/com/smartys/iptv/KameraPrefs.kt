package com.smartys.iptv

import android.content.Context
import android.content.SharedPreferences

/**
 * Schalter fuer die drei Kamera-/Stream-Quellen. Jede Quelle laesst sich
 * einzeln an- und abschalten — Smarty will das steuern, nicht die App.
 *   firmen  = Firmen-Kameras ueber den CamViewer im Buero-VLAN
 *   frei    = oeffentliche Webcams im Internet
 *   streams = freie Streams (Demomodus)
 */
object KameraPrefs {

    private const val FILE = "smartys_kamera_prefs"
    private var p: SharedPreferences? = null

    /** Server des CamViewer-Backends im Buero (go2rtc/Protect-Proxy). */
    const val FIRMA_SERVER_STANDARD = ""   // oeffentliche Fassung: kein Firmenserver fest eincodiert

    fun init(ctx: Context) {
        if (p == null) p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun firmen(ctx: Context?): Boolean = v(ctx, "firmen", true)
    fun freie(ctx: Context?): Boolean = v(ctx, "frei", true)
    fun freieStreams(ctx: Context?): Boolean = v(ctx, "streams", true)

    fun setFirmen(ctx: Context, an: Boolean) = set(ctx, "firmen", an)
    fun setFreie(ctx: Context, an: Boolean) = set(ctx, "frei", an)
    fun setFreieStreams(ctx: Context, an: Boolean) = set(ctx, "streams", an)

    /** Eigenes Wand-Layout je Profil (jeder stellt sich seine Wand selbst ein). */
    fun wandLayout(ctx: Context?, profil: String): String? {
        init2(ctx)
        return p?.getString("wand_" + profil, null)
    }

    fun setWandLayout(ctx: Context, profil: String, wert: String) {
        init2(ctx)
        p?.edit()?.putString("wand_" + profil, wert)?.apply()
    }

    fun firmenServer(ctx: Context?): String {
        init2(ctx)
        return p?.getString("firmen_server", null)?.trim()?.ifBlank { null }
            ?: FIRMA_SERVER_STANDARD
    }

    fun setFirmenServer(ctx: Context, url: String) {
        init2(ctx)
        p?.edit()?.putString("firmen_server", url.trim())?.apply()
    }

    private fun init2(ctx: Context?) {
        if (p == null && ctx != null) init(ctx)
    }

    private fun v(ctx: Context?, key: String, std: Boolean): Boolean {
        init2(ctx)
        return p?.getBoolean(key, std) ?: std
    }

    private fun set(ctx: Context, key: String, an: Boolean) {
        init2(ctx)
        p?.edit()?.putBoolean(key, an)?.apply()
    }
}

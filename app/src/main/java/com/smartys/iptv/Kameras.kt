package com.smartys.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import android.content.Context
import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URI

/**
 * Kameras — Port des webOS-Cam-Viewers.
 *
 * Zwei Quellen: oeffentliche Webcams (jpg mit Refresh / hls) und die eigenen
 * Kameras ueber einen Server im LAN. Drei Server-Formate werden erkannt:
 *   - Smartys-Bridge:  <base>/cams.json      -> [name,url,type]
 *   - go2rtc:          <base>/api/streams    -> HLS je <base>/api/stream.m3u8?src=<name>
 *   - CamViewer:       <base>/api/cameras    -> HLS je <base>/hls/<id>/index.m3u8
 * RTSP spielt kein Player — eigene Kameras laufen ausschliesslich ueber den Server.
 */
object Kameras {

    data class Kamera(
        val name: String,
        val land: String,
        val typ: String,          // "jpg" | "hls"
        val url: String,
        val refreshSek: Int = 10,
        val lokal: Boolean = false,
        val id: String = ""       // Server-ID, fuer Layout (Reihenfolge + Kachelbreite)
    )

    /** Nur frei zugaengliche Webcams, jede in der App per HTTP geprueft. */
    val OEFFENTLICH: List<Kamera> = listOf(
        Kamera("Zugspitze Gipfelpanorama", "DE", "jpg", "https://www.foto-webcam.eu/webcam/zugspitze/current/1200.jpg", 60),
        Kamera("Eibsee mit Zugspitze", "DE", "jpg", "https://www.foto-webcam.eu/webcam/eibsee-nord/current/1200.jpg", 60),
        Kamera("Zell am See Seeufer", "AT", "jpg", "https://www.foto-webcam.eu/webcam/zellamsee/current/1200.jpg", 60),
        Kamera("Furkajoch Passstrasse", "AT", "jpg", "https://www.foto-webcam.eu/webcam/furkajoch/current/1200.jpg", 60),
        Kamera("Dornbirn Stadtpanorama", "AT", "jpg", "https://www.foto-webcam.eu/webcam/dornbirn/current/1200.jpg", 60),
        Kamera("Bludenz Zentrum", "AT", "jpg", "https://www.foto-webcam.eu/webcam/bludenz-zentrum/current/1200.jpg", 60),
        Kamera("Flensburger Hafen", "DE", "jpg", "https://www.foto-webcam.eu/webcam/flensburg/current/1200.jpg", 60),
        Kamera("Dachau Altstadt", "DE", "jpg", "https://www.foto-webcam.eu/webcam/dachau/current/1200.jpg", 60),
        Kamera("Opel-Zoo Kronberg", "DE", "jpg", "https://cam.opel-zoo.de/current.jpg", 30),
        Kamera("Brunnen Vierwaldstättersee", "CH", "jpg", "https://www.foto-webcam.eu/webcam/brunnen/current/1200.jpg", 60),
        Kamera("San Diego Zoo — Koala", "US", "hls", "https://zssd-koala.hls.camzonecdn.com/CamzoneStreams/zssd-koala/Playlist.m3u8", 0),
        Kamera("San Diego Zoo — Löwe", "US", "hls", "https://zssd-lion.hls.camzonecdn.com/CamzoneStreams/zssd-lion/Playlist.m3u8", 0),
        Kamera("San Diego Zoo — Schnabeltier", "US", "hls", "https://zssd-platypus.hls.camzonecdn.com/CamzoneStreams/zssd-platypus/Playlist.m3u8", 0),
        Kamera("San Francisco — I-80 Powell St", "US", "hls", "https://wzmedia.dot.ca.gov/D4/W80_at_Powell_St.stream/playlist.m3u8", 0),
        Kamera("Minneapolis — I-35 NB", "US", "hls", "https://video.dot.state.mn.us/public/D6-C132.stream/playlist.m3u8", 0),
        Kamera("Seattle — 12th Ave NE", "US", "hls", "https://61e0c5d388c2e.streamlock.net/live/12_NE_50.stream/playlist.m3u8", 0),
        Kamera("E39 Fidjane (Statens vegvesen)", "NO", "hls", "https://kamera.vegvesen.no/public/1029002_1/manifest.m3u8", 0),
        Kamera("Ribblehead Viaduct", "GB", "hls", "https://paxman.railcam.co.uk/Ribblehead_Viaduct/tracks-v1a1/mono.m3u8", 0),
        Kamera("Cianjur — Tugu Gentur", "ID", "hls", "https://atcs.cianjurkab.go.id:5443/LieApp/streams/BundaranTuguGentur.m3u8", 0),
        Kamera("Richmond — 14th & Dock", "US", "hls", "https://media-sfs6.vdotcameras.com:443/rtplive/richmondstream014/playlist.m3u8", 0),
    )

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext
            .getSharedPreferences("smartys_cameras", Context.MODE_PRIVATE)
        KameraPrefs.init(ctx)
    }

    fun server(): String? = prefs?.getString("server", null)?.trim()?.ifBlank { null }

    fun serverMerken(base: String?) {
        val p = prefs ?: return
        if (base.isNullOrBlank()) p.edit().remove("server").apply()
        else p.edit().putString("server", base.trimEnd('/')).apply()
    }

    /** HttpURLConnection — java.net.http fehlt in der jpackage-Runtime. */
    private fun hole(url: String): String? = runCatching {
        val c = URI.create(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 8000
        c.instanceFollowRedirects = true
        try {
            val code = c.responseCode
            android.util.Log.i("SmartysIPTV", "Kameras: GET $url -> HTTP $code")
            if (code in 200..299) c.inputStream.bufferedReader().readText() else null
        } finally {
            c.disconnect()
        }
    }.onFailure { e ->
        android.util.Log.w("SmartysIPTV", "Kameras: GET $url FEHLER: ${e.javaClass.simpleName}: ${e.message}")
    }.getOrNull()

    private fun prefixe(): List<String> {
        val p = LinkedHashSet<String>()
        val bekannt = (server() ?: "").removePrefix("http://").removePrefix("https://")
        if (bekannt.split(".").size == 4) p += bekannt.split(".").take(3).joinToString(".") + "."
        listOf("192.168.1.", "192.168.0.", "192.168.4.", "10.0.0.").forEach { p += it }
        // Buero-VLANs nur, wenn die Firmen-Kameras eingeschaltet sind.
        if (KameraPrefs.firmen(null)) listOf("10.10.23.", "10.10.25.").forEach { p += it }   // Bueronetze nur per Suche, ohne feste Server-Adresse
        return p.toList()
    }

    /** LAN nach einem Kamera-Server absuchen (alle drei Formate). */
    suspend fun imLanSuchen(): List<Kamera> = withContext(Dispatchers.IO) {
        val hosts = prefixe().flatMap { pr -> (1..254).map { "$pr$it" } }
        for (block in hosts.chunked(48)) {
            var treffer: List<Kamera> = emptyList()
            coroutineScope {
                val versuche = block.map { host -> async { vomServer("http://$host") } }
                versuche.awaitAll().firstOrNull { it.isNotEmpty() }?.let { treffer = it }
            }
            if (treffer.isNotEmpty()) {
                serverMerken("http://" + block.first().substringBeforeLast("."))
                return@withContext treffer
            }
        }
        emptyList()
    }

    /**
     * Wand-Layout des Servers: Spaltenzahl, Reihenfolge und Kachelbreite je Kamera.
     * Die Dual-Lens-Kamera steht dort z. B. mit w=2 — sie belegt zwei Rasterfelder.
     */
    data class Layout(val spalten: Int = 3, val reihenfolge: List<String> = emptyList(), val breiten: Map<String, Int> = emptyMap())

    /** Layout als Text: "spalten|id=breite,id=breite|id,id,id" — fuer die Einstellungen. */
    fun layoutText(l: Layout): String {
        val breiten = l.breiten.entries.joinToString(",") { "${it.key}=${it.value}" }
        return "${l.spalten}|$breiten|${l.reihenfolge.joinToString(",")}"
    }

    fun layoutAusText(t: String?): Layout? {
        if (t.isNullOrBlank()) return null
        val teile = t.split("|")
        if (teile.size < 2) return null
        val spalten = teile[0].toIntOrNull()?.coerceIn(1, 6) ?: return null
        val breiten = LinkedHashMap<String, Int>()
        if (teile[1].isNotBlank()) {
            teile[1].split(",").forEach { eintrag ->
                val kv = eintrag.split("=")
                if (kv.size == 2) kv[1].toIntOrNull()?.let { breiten[kv[0]] = it.coerceIn(1, 4) }
            }
        }
        val reihe = if (teile.size > 2 && teile[2].isNotBlank()) teile[2].split(",") else emptyList()
        return Layout(spalten, reihe, breiten)
    }

    fun layoutVomServer(base: String = KameraPrefs.firmenServer(null)): Layout {
        val b = base.trimEnd('/')
        val txt = hole("$b/api/layout") ?: return Layout()
        return runCatching {
            val o = com.google.gson.JsonParser.parseString(txt).asJsonObject
            val spalten = o.get("columns")?.asInt ?: 3
            val reihe = o.getAsJsonArray("order")?.mapNotNull { it.asString } ?: emptyList()
            val breiten = LinkedHashMap<String, Int>()
            o.getAsJsonObject("sizes")?.entrySet()?.forEach { (id, el) ->
                runCatching { el.asJsonObject.get("w")?.asInt }.getOrNull()?.let { breiten[id] = it.coerceIn(1, 4) }
            }
            Layout(spalten.coerceIn(1, 6), reihe, breiten)
        }.getOrElse { Layout() }
    }

    /** Firmen-Kameras (Buero) vom CamViewer-Backend — nur wenn eingeschaltet. */
    fun firmenKameras(): List<Kamera> {
        if (!KameraPrefs.firmen(null)) return emptyList()
        val base = KameraPrefs.firmenServer(null)
        return vomServer(base).map { it.copy(lokal = true) }
    }

    /** Kameraliste eines Servers holen — erkennt Bridge, go2rtc und CamViewer. */
    fun vomServer(base: String): List<Kamera> {
        val b = base.trimEnd('/')
        hole("$b/cams.json")?.let { txt ->
            runCatching {
                val arr = com.google.gson.JsonParser.parseString(txt)
                val liste = if (arr.isJsonArray) arr.asJsonArray else arr.asJsonObject.getAsJsonArray("cams")
                liste?.mapNotNull { el ->
                    val o = el.asJsonObject
                    val url = o.get("url")?.asString ?: return@mapNotNull null
                    Kamera(
                        o.get("name")?.asString ?: "Kamera",
                        "PC",
                        o.get("type")?.asString ?: "hls",
                        url,
                        o.get("refresh")?.asInt ?: 10,
                        true
                    )
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        hole("$b/api/streams")?.let { txt ->
            runCatching {
                com.google.gson.JsonParser.parseString(txt).asJsonObject.keySet()
                    .filter { it != "wall" }
                    .map { Kamera(it, "PC", "hls", "$b/api/stream.m3u8?src=$it", 10, true) }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        hole("$b/api/cameras")?.let { txt ->
            runCatching {
                // Das CamViewer-Backend liefert {"cameras":[...]}, aeltere
                // Fassungen ein nacktes Array — beides lesen.
                val wurzel = com.google.gson.JsonParser.parseString(txt)
                val reihe = if (wurzel.isJsonArray) wurzel.asJsonArray
                            else wurzel.asJsonObject.getAsJsonArray("cameras")
                reihe.mapNotNull { el ->
                    val o = el.asJsonObject
                    val id = o.get("id")?.asString ?: o.get("name")?.asString ?: return@mapNotNull null
                    Kamera(o.get("name")?.asString ?: id, "PC", "hls", "$b/hls/$id/index.m3u8", 10, true, id)
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }
}

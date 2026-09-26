package com.smartys.iptv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class XmltvProgramme(
    val start: Long,       // epoch millis
    val stop: Long,
    val title: String,
    val desc: String?
)

/**
 * EPG aus der XMLTV-Quelle des Providers (xmltv.php).
 * - Lädt das komplette XML einmal herunter (gecached auf Disk)
 * - Baut einen In-Memory-Index: channelId -> sortierte Programmliste
 * - getNowAndNext() liefert "läuft jetzt" + "als nächstes" für die Kanalzeile
 */
object XmltvEpg {

    // channelId -> Programme (nach start sortiert)
    private val index = HashMap<String, MutableList<XmltvProgramme>>()
    // Fallback-Index (epg.pw): display-NAME (normalisiert) -> Programme
    private val fallbackIndex = HashMap<String, MutableList<XmltvProgramme>>()
    @Volatile private var loaded = false
    @Volatile private var loading = false
    var lastRefreshMs: Long = 0
        private set

    fun isReady(): Boolean = loaded

    /**
     * Lädt/aktualisiert das XMLTV. Läuft im Hintergrund; returns true wenn Index bereit.
     * Cache-Datei: filesDir/xmltv.xml — älter als 12h => frischer Download.
     */
    suspend fun ensureLoaded(ctx: Context, xmltvUrl: String, forceRefresh: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            if (loaded && !forceRefresh) return@withContext true
            if (loading) return@withContext loaded
            loading = true
            try {
                val cache = File(ctx.filesDir, "xmltv.xml")
                val stale = !cache.exists() ||
                    System.currentTimeMillis() - cache.lastModified() > 12 * 3600 * 1000L
                if (forceRefresh || stale) {
                    Log.d("SmartysIPTV", "XMLTV Download start: $xmltvUrl")
                    val tmp = File(ctx.filesDir, "xmltv.xml.part")
                    URL(xmltvUrl).openStream().use { input ->
                        tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
                    }
                    tmp.renameTo(cache)
                    Log.d("SmartysIPTV", "XMLTV Download ok: ${cache.length()} bytes")
                }
                parse(cache)
                loaded = true
                lastRefreshMs = System.currentTimeMillis()
                Log.d("SmartysIPTV", "XMLTV Index: ${index.size} Kanäle")
                true
            } catch (e: Exception) {
                Log.e("SmartysIPTV", "XMLTV Load fehlgeschlagen", e)
                // alter Cache bleibt nutzbar falls vorhanden
                loaded = try {
                    val cache = File(ctx.filesDir, "xmltv.xml")
                    if (cache.exists()) { parse(cache); true } else false
                } catch (e2: Exception) { false }
                loaded
            } finally {
                loading = false
            }
        }

    private fun parse(file: File) {
        val newIndex = HashMap<String, MutableList<XmltvProgramme>>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        FileInputStream(file).use { fis ->
            parser.setInput(fis, "utf-8")
            var event = parser.eventType
            var curChannel: String? = null
            var curStart = 0L
            var curStop = 0L
            var curTitle: String? = null
            var curDesc: String? = null
            var inTitle = false
            var inDesc = false
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "programme" -> {
                            curChannel = parser.getAttributeValue(null, "channel")
                            curStart = parseXmltvTime(parser.getAttributeValue(null, "start"))
                            curStop = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                            curTitle = null; curDesc = null
                        }
                        "title" -> inTitle = true
                        "desc" -> inDesc = true
                    }
                    XmlPullParser.TEXT -> {
                        if (inTitle) curTitle = parser.text
                        if (inDesc) curDesc = parser.text
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "title" -> inTitle = false
                        "desc" -> inDesc = false
                        "programme" -> {
                            val ch = curChannel
                            if (ch != null && curTitle != null && curStart > 0) {
                                newIndex.getOrPut(ch) { mutableListOf() }.add(
                                    XmltvProgramme(curStart, curStop, curTitle!!.trim(), curDesc?.trim())
                                )
                            }
                            inTitle = false; inDesc = false
                        }
                    }
                }
                event = parser.next()
            }
        }
        // sortieren und atomar tauschen
        newIndex.values.forEach { it.sortBy { p -> p.start } }
        synchronized(index) {
            index.clear()
            index.putAll(newIndex)
        }
    }

    /** "20260925090500 +0200" -> epoch millis (UTC-korrekt) */
    private fun parseXmltvTime(s: String?): Long = try {
        if (s == null) 0L
        else {
            val core = s.substringBefore(" ").take(14)
            // Offset "+0200" / "-0300": HHMM -> Sekunden
            val tzSecs: Int = when {
                "+" in s -> s.substringAfterLast("+").let { o ->
                    (o.take(2).toIntOrNull() ?: 0) * 3600 + (o.drop(2).take(2).toIntOrNull() ?: 0) * 60
                }
                "-" in s.substring(9) -> s.substringAfterLast("-").let { o ->
                    -((o.take(2).toIntOrNull() ?: 0) * 3600 + (o.drop(2).take(2).toIntOrNull() ?: 0) * 60)
                }
                else -> 0
            }
            val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            // XMLTV-Zeit ist LOCAL mit Offset; UTC = local - offset
            fmt.parse(core)?.time?.minus(tzSecs * 1000L) ?: 0L
        }
    } catch (e: Exception) { 0L }

    /**
     * Lädt zusätzlich das freie Fallback-EPG (epg.pw, DE, ~2MB gzip) für Kanäle,
     * die im Provider-XMLTV keine EPG-ID haben (Sky Sport Einzelkanäle, DAZN etc.).
     * Fehlschlag ist unkritisch (Best-Effort).
     */
    suspend fun loadFallback(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val cache = File(ctx.filesDir, "epg_pw_de.xml")
            val stale = !cache.exists() ||
                System.currentTimeMillis() - cache.lastModified() > 24 * 3600 * 1000L
            if (stale) {
                Log.d("SmartysIPTV", "Fallback-EPG Download: epg.pw")
                val tmp = File(ctx.filesDir, "epg_pw_de.xml.part")
                val conn = java.net.URL("https://epg.pw/xmltv/epg_DE.xml.gz").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("Accept-Encoding", "gzip")
                conn.inputStream.use { input ->
                    java.util.zip.GZIPInputStream(input).use { gz ->
                        tmp.outputStream().use { gz.copyTo(it, 1 shl 16) }
                    }
                }
                tmp.renameTo(cache)
                Log.d("SmartysIPTV", "Fallback-EPG ok: ${cache.length()} bytes")
            }
            parseFallback(cache)
            Log.d("SmartysIPTV", "Fallback-Index: ${fallbackIndex.size} Kanäle")
            true
        } catch (e: Exception) {
            Log.e("SmartysIPTV", "Fallback-EPG fehlgeschlagen (unkritisch)", e)
            false
        }
    }

    private fun parseFallback(file: File) {
        val newIndex = HashMap<String, MutableList<XmltvProgramme>>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        FileInputStream(file).use { fis ->
            parser.setInput(fis, "utf-8")
            var event = parser.eventType
            // Phase 1: channel-ids -> display-names einsammeln
            val idToName = HashMap<String, String>()
            var curId: String? = null
            var inDisplayName = false
            var inTitle = false
            var inDesc = false
            var curChannel: String? = null
            var curStart = 0L
            var curStop = 0L
            var curTitle: String? = null
            var curDesc: String? = null
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "channel" -> curId = parser.getAttributeValue(null, "id")
                        "display-name" -> inDisplayName = true
                        "programme" -> {
                            curChannel = parser.getAttributeValue(null, "channel")
                            curStart = parseXmltvTime(parser.getAttributeValue(null, "start"))
                            curStop = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                            curTitle = null; curDesc = null
                        }
                        "title" -> inTitle = true
                        "desc" -> inDesc = true
                    }
                    XmlPullParser.TEXT -> {
                        if (inDisplayName && curId != null) idToName[curId!!] = parser.text.trim()
                        if (inTitle) curTitle = parser.text
                        if (inDesc) curDesc = parser.text
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "display-name" -> inDisplayName = false
                        "channel" -> curId = null
                        "title" -> inTitle = false
                        "desc" -> inDesc = false
                        "programme" -> {
                            val ch = curChannel
                            if (ch != null && curTitle != null && curStart > 0) {
                                val name = idToName[ch]
                                val key = name?.let { normName(it) } ?: ch
                                newIndex.getOrPut(key) { mutableListOf() }.add(
                                    XmltvProgramme(curStart, curStop, curTitle!!.trim(), curDesc?.trim())
                                )
                            }
                            inTitle = false; inDesc = false
                        }
                    }
                }
                event = parser.next()
            }
        }
        newIndex.values.forEach { it.sortBy { p -> p.start } }
        synchronized(fallbackIndex) {
            fallbackIndex.clear()
            fallbackIndex.putAll(newIndex)
        }
    }

    /** Kanalname normalisieren fürs Matching: "DE - SKY SPORT TENNIS FHD" -> "skysporttennis" */
    fun normName(n: String): String = n.lowercase()
        .replace("de - ", "").replace("de:", "").replace("de-", "")
        .replace("uhd", "").replace("fhd", "").replace("hd", "")
        .replace("4k", "").replace("ä", "a").replace("ö", "o").replace("ü", "u")
        .replace("ß", "ss").replace(".", "").replace(":", "").replace("+", "")
        .replace("&", "").replace("(", "").replace(")", "").replace("!", "")
        .replace(" ", "").trim()

    /** Fallback-Suche im epg.pw-Index per Kanalname. */
    fun fallbackNowAndNext(channelName: String?, now: Long = System.currentTimeMillis()): Pair<XmltvProgramme?, XmltvProgramme?> {
        if (channelName.isNullOrBlank()) return null to null
        val key = normName(channelName)
        if (key.isBlank()) return null to null
        val progs = synchronized(fallbackIndex) { fallbackIndex[key] } ?: return null to null
        return nowAndNextFrom(progs, now)
    }
    fun getNowAndNext(channelId: String?, now: Long = System.currentTimeMillis()): Pair<XmltvProgramme?, XmltvProgramme?> {
        if (channelId.isNullOrBlank()) return null to null
        val progs = synchronized(index) { index[channelId] } ?: return null to null
        return nowAndNextFrom(progs, now)
    }

    /** Komplette sortierte Programmliste eines Kanals (fürs EPG-Grid). */
    fun programmesFor(channelId: String?): List<XmltvProgramme> {
        if (channelId.isNullOrBlank()) return emptyList()
        return synchronized(index) { index[channelId]?.toList() } ?: emptyList()
    }

    /** Komplette Programmliste eines Kanals per normalisiertem Name (Fallback). */
    fun programmesForName(channelName: String?): List<XmltvProgramme> {
        if (channelName.isNullOrBlank()) return emptyList()
        val key = normName(channelName)
        return synchronized(fallbackIndex) { fallbackIndex[key]?.toList() } ?: emptyList()
    }

    /** Programme eines Kanals, die im Zeitfenster [from, to] liegen (fürs Tages-Grid). */
    fun programmesInRange(channelId: String?, channelName: String?, from: Long, to: Long): List<XmltvProgramme> {
        val progs = programmesFor(channelId)
            .ifEmpty { programmesForName(channelName) }
        return progs.filter { it.stop > from && it.start < to }
    }

    /** Gemeinsame Jetzt/Danach-Logik (sortierte Liste, robust gegen EPG-Überlappungen). */
    private fun nowAndNextFrom(progs: List<XmltvProgramme>, now: Long): Pair<XmltvProgramme?, XmltvProgramme?> {
        if (progs.isEmpty()) return null to null
        // binäre Suche: letztes Programm mit start <= now
        var lo = 0; var hi = progs.size - 1
        var cur: XmltvProgramme? = null
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (progs[mid].start <= now) { cur = progs[mid]; lo = mid + 1 }
            else hi = mid - 1
        }
        if (cur != null && cur.stop in 1..now) cur = null  // Lücke: Sendung zu Ende, nächste kommt noch nicht
        val next: XmltvProgramme? = if (cur != null) {
            // "Danach" = erste Sendung, die NACH dem Ende der aktuellen beginnt;
            // bei Überlappungen (EPG-Müll) Fallback: erste mit start > cur.start
            val byStop = progs.indexOfFirst { cur.stop > 0 && it.start >= cur.stop }
            if (byStop >= 0) progs[byStop] else progs.getOrNull(progs.indexOf(cur) + 1)
        } else {
            progs.getOrNull(progs.indexOfFirst { it.start > now })
        }
        return cur to next
    }

    /** Format "HH:mm" aus epoch */
    fun hhmm(epoch: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epoch))

    /** Leert den In-Memory-Index (nach Cache-Löschung). */
    fun clearCache() {
        synchronized(this) {
            index.clear()
            fallbackIndex.clear()
            loaded = false
        }
    }
}
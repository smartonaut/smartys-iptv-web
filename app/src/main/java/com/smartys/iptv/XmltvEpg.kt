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
    /**
     * Zeitfenster fuer den EPG-Index. LITE (alte Fire-TV-Sticks, 875 MB RAM,
     * 96/256 MB Heap) haelt nur 24 h vor und 3 h zurueck — das reicht fuer
     * "jetzt/danach" und halbiert den Speicherbedarf gegenueber 72 h.
     */
    private val windowBackH = if (BuildConfig.LITE_DEVICE) 3 else 6
    private val windowAheadH = if (BuildConfig.LITE_DEVICE) 24 else 72

    /** Beschreibung: im LITE-Build deckeln (groesster Speicherposten), sonst voll. */
    private fun desc(d: String?): String? =
        if (BuildConfig.LITE_DEVICE) d?.trim()?.take(180) else d?.trim()

    // channelId -> Programme (nach start sortiert)
    private val index = HashMap<String, MutableList<XmltvProgramme>>()
    // Fallback-Index (epg.pw): display-NAME (normalisiert) -> Programme
    private val fallbackIndex = HashMap<String, MutableList<XmltvProgramme>>()
    // Zusatzquelle (eigener EPG-Link aus dem Panel, z. B. Receiver):
    // display-NAME (normalisiert) -> Programme. Receiver liefern ihre Kanäle
    // über Namen, nicht über tvg-ids der Playlist.
    private val extraIndex = HashMap<String, MutableList<XmltvProgramme>>()
    @Volatile private var loaded = false
    @Volatile private var loading = false
    @Volatile private var extraLoading = false

    // Ende des jeweils letzten Programms je Quelle (0 = unbekannt).
    // Ist das Ende vor "jetzt", ist die Quelle abgelaufen und wird ignoriert.
    @Volatile private var providerEndMs = 0L
    @Volatile private var extraEndMs = 0L
    @Volatile private var fallbackEndMs = 0L
    private const val RAND_MS = 15 * 60 * 1000L

    private fun deckt(bis: Long, now: Long = System.currentTimeMillis()): Boolean =
        bis > now - RAND_MS
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
        // Zeitfenster: nur laufende und kommende Programme behalten (6 h zurueck,
        // 72 h voraus). Alles davor ist fuer die Anzeige wertlos, belegt aber Heap.
        val now = System.currentTimeMillis()
        // NUR im LITE-Build begrenzen. tv/phone lesen wie bisher das ganze XMLTV.
        val windowStart = if (BuildConfig.LITE_DEVICE) now - windowBackH * 3600 * 1000L else 0L
        val windowEnd = if (BuildConfig.LITE_DEVICE) now + windowAheadH * 3600 * 1000L else Long.MAX_VALUE
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
                            // Auf schwachen Geraeten (Fire TV Stick, 96/256 MB Heap) darf
                            // nicht das GANZE XMLTV im Speicher landen: ein 60-200-MB-File
                            // hat zehntausende Programme und laesst den Heap volllaufen
                            // (Symptom: nach wenigen Minuten OutOfMemory, App weg).
                            // Nur ein Fenster behalten: 6 h zurueck bis 72 h voraus.
                            if (ch != null && curTitle != null && curStart > 0 &&
                                curStop > windowStart && curStart < windowEnd) {
                                newIndex.getOrPut(ch) { mutableListOf() }.add(
                                    XmltvProgramme(
                                        curStart,
                                        curStop,
                                        curTitle!!.trim(),
                                        // Beschreibung deckeln — sie ist der groesste Posten
                                        desc(curDesc)
                                    )
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
        providerEndMs = newIndex.values.maxOfOrNull { l -> l.lastOrNull()?.stop ?: 0L } ?: 0L
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
    suspend fun loadFallback(ctx: Context, forceRefresh: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val cache = File(ctx.filesDir, "epg_pw_de.xml")
            val stale = forceRefresh || !cache.exists() ||
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


    /**
     * Lädt einen zusätzlichen EPG-Link (aus dem Panel, z. B. XMLTV-Export eines
     * Receivers). Kanäle werden über den Anzeigenamen zugeordnet, weil Receiver
     * andere Kennungen liefern als die Playlist (tvg-id).
     * Fehlschlag ist unkritisch: der bisherige Stand bleibt nutzbar.
     */
    suspend fun ensureExtraLoaded(ctx: Context, url: String, forceRefresh: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext false
            if (extraLoading) return@withContext extraIndex.isNotEmpty()
            extraLoading = true
            try {
                val cache = File(ctx.filesDir, "epg_extra.xml")
                val stale = forceRefresh || !cache.exists() ||
                    System.currentTimeMillis() - cache.lastModified() > 6 * 3600 * 1000L
                if (stale) {
                    Log.d("SmartysIPTV", "Zusatz-EPG Download: $url")
                    val tmp = File(ctx.filesDir, "epg_extra.xml.part")
                    val conn = URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 120_000
                    conn.setRequestProperty("User-Agent", "SmartysIPTV")
                    conn.inputStream.use { input ->
                        val bp = java.io.BufferedInputStream(input)
                        bp.mark(4)
                        val kopf = ByteArray(2); bp.read(kopf)
                        bp.reset()
                        val quelle = if (kopf.size == 2 && kopf[0] == 0x1f.toByte() && kopf[1] == 0x8b.toByte())
                            java.util.zip.GZIPInputStream(bp) else bp
                        tmp.outputStream().use { quelle.copyTo(it, 1 shl 16) }
                    }
                    tmp.renameTo(cache)
                    Log.d("SmartysIPTV", "Zusatz-EPG ok: ${cache.length()} bytes")
                }
                parseExtra(cache)
                true
            } catch (e: Exception) {
                Log.e("SmartysIPTV", "Zusatz-EPG fehlgeschlagen (unkritisch)", e)
                false
            } finally {
                extraLoading = false
            }
        }

    /** XMLTV des eigenen Links einlesen: Index über normalisierten Anzeigenamen. */
    private fun parseExtra(file: File) {
        val newIndex = HashMap<String, MutableList<XmltvProgramme>>()
        val idToName = HashMap<String, String>()
        val now = System.currentTimeMillis()
        // NUR im LITE-Build begrenzen. tv/phone lesen wie bisher das ganze XMLTV.
        val windowStart = if (BuildConfig.LITE_DEVICE) now - windowBackH * 3600 * 1000L else 0L
        val windowEnd = if (BuildConfig.LITE_DEVICE) now + windowAheadH * 3600 * 1000L else Long.MAX_VALUE
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        FileInputStream(file).use { fis ->
            parser.setInput(fis, "utf-8")
            var event = parser.eventType
            var curId: String? = null
            var inDisplay = false
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
                        "display-name" -> inDisplay = true
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
                        if (inDisplay && curId != null) idToName[curId!!] = parser.text.trim()
                        if (inTitle) curTitle = parser.text
                        if (inDesc) curDesc = parser.text
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "display-name" -> inDisplay = false
                        "channel" -> curId = null
                        "title" -> inTitle = false
                        "desc" -> inDesc = false
                        "programme" -> {
                            val ch = curChannel
                            if (ch != null && curTitle != null && curStart > 0 &&
                                curStop > windowStart && curStart < windowEnd) {
                                val prog = XmltvProgramme(curStart, curStop, curTitle!!.trim(), desc(curDesc))
                                // Zuordnung über Namen (Receiver) ...
                                // LITE: nur EINE Ablage (die zweite verdoppelte den Heap).
                                // tv/phone behalten beide Wege wie bisher.
                                val name = idToName[ch]
                                if (name != null) {
                                    newIndex.getOrPut(normName(name)) { mutableListOf() }.add(prog)
                                    if (!BuildConfig.LITE_DEVICE) {
                                        newIndex.getOrPut(ch) { mutableListOf() }.add(prog)
                                    }
                                } else {
                                    newIndex.getOrPut(ch) { mutableListOf() }.add(prog)
                                }
                            }
                            inTitle = false; inDesc = false
                        }
                    }
                }
                event = parser.next()
            }
        }
        newIndex.values.forEach { it.sortBy { pr -> pr.start } }
        extraEndMs = newIndex.values.maxOfOrNull { l -> l.lastOrNull()?.stop ?: 0L } ?: 0L
        synchronized(extraIndex) {
            extraIndex.clear()
            extraIndex.putAll(newIndex)
        }
        Log.d("SmartysIPTV", "Zusatz-EPG Index: ${extraIndex.size} Einträge")
    }

    private fun parseFallback(file: File) {
        val newIndex = HashMap<String, MutableList<XmltvProgramme>>()
        val now = System.currentTimeMillis()
        // NUR im LITE-Build begrenzen. tv/phone lesen wie bisher das ganze XMLTV.
        val windowStart = if (BuildConfig.LITE_DEVICE) now - windowBackH * 3600 * 1000L else 0L
        val windowEnd = if (BuildConfig.LITE_DEVICE) now + windowAheadH * 3600 * 1000L else Long.MAX_VALUE
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
                            if (ch != null && curTitle != null && curStart > 0 &&
                                curStop > windowStart && curStart < windowEnd) {
                                val name = idToName[ch]
                                val key = name?.let { normName(it) } ?: ch
                                newIndex.getOrPut(key) { mutableListOf() }.add(
                                    XmltvProgramme(curStart, curStop, curTitle!!.trim(), desc(curDesc))
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
        fallbackEndMs = newIndex.values.maxOfOrNull { l -> l.lastOrNull()?.stop ?: 0L } ?: 0L
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
        // Abgelaufene Quelle (letztes Programm liegt zurück) zählt wie "kein EPG",
        // damit die nächste Quelle (eigener Link, dann epg.pw) greift.
        if (!deckt(providerEndMs, now)) return null to null
        val progs = synchronized(index) { index[channelId] } ?: return null to null
        return nowAndNextFrom(progs, now)
    }

    /** Komplette sortierte Programmliste eines Kanals (fürs EPG-Grid). */
    fun programmesFor(channelId: String?): List<XmltvProgramme> {
        if (channelId.isNullOrBlank()) return emptyList()
        if (!deckt(providerEndMs)) return emptyList()
        return synchronized(index) { index[channelId]?.toList() } ?: emptyList()
    }

    /**
     * Eigener EPG-Link: zuerst über die Kanal-Kennung (der Server liefert die
     * Programme zusätzlich unter der Kennung der Playlist aus), danach über
     * den normalisierten Kanalnamen.
     */
    fun extraNowAndNext(
        channelId: String?,
        channelName: String?,
        now: Long = System.currentTimeMillis()
    ): Pair<XmltvProgramme?, XmltvProgramme?> {
        if (!deckt(extraEndMs, now)) return null to null
        if (!channelId.isNullOrBlank()) {
            val p1 = synchronized(extraIndex) { extraIndex[channelId] }
            if (p1 != null) {
                val r = nowAndNextFrom(p1, now)
                if (r.first != null || r.second != null) return r
            }
        }
        if (channelName.isNullOrBlank()) return null to null
        val key = normName(channelName)
        if (key.isBlank()) return null to null
        val progs = synchronized(extraIndex) { extraIndex[key] } ?: return null to null
        return nowAndNextFrom(progs, now)
    }

    /** Beste verfügbare Quelle: Provider (tvg-id) -> eigener Link (Name) -> epg.pw (Name). */
    fun bestNowAndNext(
        channelId: String?,
        channelName: String?,
        now: Long = System.currentTimeMillis()
    ): Pair<XmltvProgramme?, XmltvProgramme?> {
        val a = getNowAndNext(channelId, now)
        if (a.first != null || a.second != null) return a
        val b = extraNowAndNext(channelId, channelName, now)
        if (b.first != null || b.second != null) return b
        return fallbackNowAndNext(channelName, now)
    }

    /** Läuft gerade irgendeine Quelle mit aktuellen Daten? (für Hinweise im UI) */
    fun isProviderFresh(now: Long = System.currentTimeMillis()): Boolean = deckt(providerEndMs, now)
    fun isExtraFresh(now: Long = System.currentTimeMillis()): Boolean = deckt(extraEndMs, now)
    fun isFallbackFresh(now: Long = System.currentTimeMillis()): Boolean = deckt(fallbackEndMs, now)

    /** Komplette Programmliste eines Kanals per normalisiertem Name (Fallback). */
    fun programmesForName(channelName: String?): List<XmltvProgramme> {
        if (channelName.isNullOrBlank()) return emptyList()
        val key = normName(channelName)
        return synchronized(fallbackIndex) { fallbackIndex[key]?.toList() } ?: emptyList()
    }

    /** Programme eines Kanals, die im Zeitfenster [from, to] liegen (fürs Tages-Grid). */
    fun programmesInRange(channelId: String?, channelName: String?, from: Long, to: Long): List<XmltvProgramme> {
        val progs = programmesFor(channelId)
            .ifEmpty { programmesForExtra(channelId, channelName) }
            .ifEmpty { programmesForName(channelName) }
        return progs.filter { it.stop > from && it.start < to }
    }

    /** Programmliste aus dem eigenen EPG-Link: Kennung zuerst, dann Kanalname. */
    fun programmesForExtra(channelId: String?, channelName: String?): List<XmltvProgramme> {
        if (!deckt(extraEndMs)) return emptyList()
        if (!channelId.isNullOrBlank()) {
            val p = synchronized(extraIndex) { extraIndex[channelId]?.toList() }
            if (!p.isNullOrEmpty()) return p
        }
        if (channelName.isNullOrBlank()) return emptyList()
        val key = normName(channelName)
        if (key.isBlank()) return emptyList()
        return synchronized(extraIndex) { extraIndex[key]?.toList() } ?: emptyList()
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
    /** Löscht die zwischengespeicherten EPG-Dateien (erzwingt echten Neudownload). */
    fun clearFiles(ctx: Context) {
        listOf("xmltv.xml", "epg_extra.xml", "epg_pw_de.xml").forEach { n ->
            runCatching { File(ctx.filesDir, n).delete() }
        }
        clearCache()
    }

    fun clearCache() {
        synchronized(this) {
            index.clear()
            fallbackIndex.clear()
            extraIndex.clear()
            providerEndMs = 0L; extraEndMs = 0L; fallbackEndMs = 0L
            loaded = false
        }
    }
}
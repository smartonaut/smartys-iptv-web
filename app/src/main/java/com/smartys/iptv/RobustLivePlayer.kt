package com.smartys.iptv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Robuster Stream-Player für Live-TV UND VOD (Film/Serie).
 *
 * Live-TV: großer Puffer + Auto-Reconnect mit Backoff (2s, 4s, 8s, 16s, 30s …)
 * — der Kanal soll bei kurzen Aussetzern einfach weiterlaufen.
 *
 * VOD: KEIN endloser Reconnect. Ein Stream-Fehler startet an der aktuellen
 * Position neu (nicht bei 0), und ein regulär zu Ende gespielter Titel wird
 * NICHT neu gestartet. Das verhindert, dass ein abgebrochener Film plötzlich
 * von vorn beginnt.
 *
 * Puffer: bewusst moderat (max 40s). Ein 120s-Puffer auf einem 4K-Stream
 * (~35 Mbit/s) frisst auf einem 2-GB-Gerät den gesamten CMA-/Heap-Speicher
 * und lässt den Hardware-Decoder mit "Source error" aussteigen.
 */
class RobustLivePlayer(private val ctx: Context) {

    /** Puffergrößen (in ms) dynamisch an die Hardware anpassen.
     *  Schwache Geräte (wenig RAM / wenig CMA) bekommen kleine Puffer, damit der
     *  Hardware-Decoder bei 4K nicht wegen Speichermangel ("Source error") abstürzt;
     *  kräftige Geräte bekommen größere Puffer für stabileres Live-TV.
     */
    private fun detectBufferDurations(): IntArray {
        // Echten Gesamt-RAM über /proc/meminfo lesen (memoryClass deckt nur die Heap-Grenze ab).
        val totalRamMb = try {
            val line = java.io.File("/proc/meminfo").readLines()
                .firstOrNull { it.startsWith("MemTotal") } ?: ""
            line.replace(Regex("[^0-9]"), "").toLongOrNull()?.let { it / 1024 } ?: 0L
        } catch (e: Exception) { 0L }

        // CMA (Contiguous Memory Allocator) ist der eigentliche 4K-Engpass: fällt er auf 0,
        // bricht der Decoder ab. Ist CMA klein/belegt, Puffer klein halten.
        val cmaTotalMb = try {
            val line = java.io.File("/proc/meminfo").readLines()
                .firstOrNull { it.startsWith("CmaTotal") } ?: ""
            line.replace(Regex("[^0-9]"), "").toLongOrNull()?.let { it / 1024 } ?: 0L
        } catch (e: Exception) { 0L }

        return when {
            // Starke Geräte: viel RAM (>4 GB) ODER viel CMA (>512 MB) → großer Puffer
            totalRamMb >= 4096 -> intArrayOf(30_000, 60_000, 5_000, 10_000)
            // Mittelklasse: 2–4 GB RAM, ausreichend CMA → moderater Puffer
            totalRamMb >= 2048 -> intArrayOf(20_000, 40_000, 2_500, 5_000)
            // Schwache Geräte: <2 GB RAM → kleiner Puffer, Decoder-Sicherheit geht vor
            else -> intArrayOf(10_000, 20_000, 2_000, 4_000)
        }.also {
            // Zusätzliche Absicherung: Ist CMA verdächtig klein (unter 256 MB, z. B. Fire TV Sticks),
            // den Max-Puffer deckeln — 4K braucht CMA, kein Heap.
            if (cmaTotalMb in 1..255) {
                val capped = intArrayOf(it[0].coerceAtMost(15_000), it[1].coerceAtMost(25_000), it[2], it[3])
                Log.i("SmartysIPTV", "Puffer wegen kleinem CMA ($cmaTotalMb MB) gedrosselt: ${capped.joinToString("/")}")
                return capped
            }
            Log.i("SmartysIPTV", "Puffer je RAM=$totalRamMb MB / CMA=$cmaTotalMb MB: ${it.joinToString("/")}")
        }
    }

    /** Einmalig berechnete Puffergrößen [min, max, playback, afterRebuffer] in ms. */
    private val bufferDurations: IntArray = detectBufferDurations()

    /** Timeshift: wie weit man bei pausiertem Live-TV zurueckspulen darf (0 = Standard). */
    private val liveLookbackMs = PlayPrefs.lookback(ctx).toInt()

    /**
     * Nutzer-Einstellung (Sekunden, 0 = automatisch), hart gedeckelt auf das,
     * was dieses Geraet laut DeviceCap verkraftet. Ein zu grosser Puffer kippt
     * auf schwachen Boxen den Hardware-Decoder ("Source error"). Gilt pro
     * Player-Instanz, also ab dem naechsten Kanalstart.
     */
    private val requestedBufferSec: Int = PlayPrefs.bufferSec(ctx)
    private val deviceMaxBufferMs: Int = DeviceCap.maxBufferSec(ctx) * 1000
    /**
     * Start-Puffer. Mit Nutzer-Einstellung wird auch das Minimum auf die
     * gewuenschte Zeit gesetzt — sonst haette ein Reglerwert unter dem
     * Auto-Minimum (10 s) keine Wirkung: 5 s eingestellt, aber 10 s gepuffert.
     */
    private val minBufferMs: Int = run {
        val base = minOf(bufferDurations[0], deviceMaxBufferMs)
        if (requestedBufferSec > 0) minOf(base, requestedBufferSec * 1000) else base
    }
    private val maxBufferMs: Int = run {
        val requestedMs = if (requestedBufferSec > 0) requestedBufferSec * 1000 else bufferDurations[1]
        val capped = minOf(maxOf(requestedMs, minBufferMs), deviceMaxBufferMs)
        // Timeshift (Lookback) braucht VIEL Puffer. Nur im LITE-Build wird er
        // gegen die Geraetegrenze geklemmt: auf einem alten Stick killt ein
        // 15-Minuten-Puffer den Decoder (Symptom: laeuft kurz an, dann weg).
        // Der tv/phone-Build verhaelt sich unveraendert.
        val lookbackErlaubt = !BuildConfig.LITE_DEVICE || liveLookbackMs in 1..deviceMaxBufferMs
        val withLookback = if (lookbackErlaubt) maxOf(capped, liveLookbackMs) else capped
        if (BuildConfig.LITE_DEVICE && liveLookbackMs > 0 && !lookbackErlaubt) {
            Log.w("SmartysIPTV", "Timeshift ${liveLookbackMs / 1000}s deaktiviert: " +
                    "Geraet verkraftet nur ${deviceMaxBufferMs / 1000}s Puffer")
        }
        Log.i("SmartysIPTV", "Puffer: gewaehlt=${requestedBufferSec}s auto=${bufferDurations[1] / 1000}s " +
                "geraet=${deviceMaxBufferMs / 1000}s -> ${withLookback / 1000}s")
        withLookback
    }

    val player: ExoPlayer = ExoPlayer.Builder(ctx)
        .setTrackSelector(DefaultTrackSelector(ctx))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    minBufferMs,
                    maxBufferMs,
                    bufferDurations[2],
                    bufferDurations[3]
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        // Ladefehler NICHT sofort fatal werden lassen. Der Standard wiederholt
        // nur dreimal im Sekundentakt und gibt dann auf — bei einem 509 des
        // Anbieters ist das zu kurz, und der Player warf dabei 20 s bereits
        // gepuffertes Video weg, obwohl er sie haette weiterspielen koennen.
        // Diese Policy klopft bei 509/429 deutlich spaeter erneut an, waehrend
        // der Puffer leerlaeuft: kein Schwarzbild, kein Neustart.
        .setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ctx)
                .setLoadErrorHandlingPolicy(VerbindungsLimitPolicy())
        )
        .build()
        .apply {
            playWhenReady = true
            // Audio-Fokus anfordern: pausiert andere Musik-Apps (Amazon Music, Spotify …)
            // automatisch, sobald hier etwas abgespielt wird. Verhindert, dass ein
            // Hörspiel im Hintergrund parallel weiterläuft.
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true  // handleAudioFocus = true
            )
        }

    private var currentUrl: String? = null
    private var currentChannelId: String? = null
    private var onChannelDead: ((String) -> Unit)? = null
    private var reconnectAttempts = 0

    /**
     * Der letzte Fehler war ein Verbindungslimit des Anbieters (HTTP 509/429).
     * Dann ist schnelles Neuverbinden zwecklos: der Slot bleibt belegt, und
     * jeder Versuch erzeugt einen weiteren Schwarzbild-Moment. scheduleReconnect
     * wartet in diesem Fall 20-30 s statt 2 s.
     */
    private var letzterFehlerWarLimit = false

    /** Erkennt "zu viele Verbindungen" in einer PlaybackException. */
    private fun istVerbindungsLimit(error: PlaybackException): Boolean {
        // Die Ursache steht in der Cause-Kette, nicht in der Message des
        // PlaybackException — deshalb die Kette ablaufen statt zu raten.
        var t: Throwable? = error
        var tiefe = 0
        while (t != null && tiefe < 8) {
            val m = t.message ?: ""
            if (m.contains("509") || m.contains("429") ||
                m.contains("InvalidResponseCode", ignoreCase = true) && (m.contains("509") || m.contains("429"))
            ) return true
            // Manche Media3-Versionen liefern den Code nur im Cause-Objekt
            if (t.javaClass.simpleName.contains("InvalidResponseCode")) {
                val code = runCatching {
                    t.javaClass.getMethod("getResponseCode").invoke(t) as? Int
                }.getOrNull()
                if (code == 509 || code == 429) return true
            }
            t = t.cause
            tiefe++
        }
        return false
    }
    private var scheduledReconnect = false
    private var pendingSeekMs: Long? = null
    private var isVod = false
    private var onVodEnded: (() -> Unit)? = null
    private var vodEndedFired = false

    // ------------------------------------------------------------------
    // Stillstands-Waechter (Paritaet zum Desktop-Player, dort 3 s).
    //
    // Bei einem haengenden Stream kommt KEIN Player-Fehler — der Decoder
    // steht einfach. Ohne diesen Waechter bleibt das Bild stehen, bis der
    // Nutzer den Sender wechselt. Er prueft im Sekundentakt, ob die
    // Wiedergabeposition noch weiterlaeuft.
    // ------------------------------------------------------------------
    private val wdHandler = Handler(Looper.getMainLooper())
    private var wdLastPos = -1L
    private var wdStillMs = 0L
    private var wdStartedAt = 0L
    private var wdActive = false

    private val wdTick = object : Runnable {
        override fun run() {
            try {
                if (wdActive && !isVod && currentUrl != null) {
                    val anlauf = System.currentTimeMillis() - wdStartedAt
                    // Anlaufphase: der Puffer muss sich erst fuellen.
                    if (anlauf > 15_000L
                        && player.playbackState == Player.STATE_READY
                        && player.isPlaying
                    ) {
                        val pos = player.currentPosition
                        if (pos == wdLastPos && pos > 0L) {
                            wdStillMs += 1000L
                            if (wdStillMs >= 3_000L) {
                                Log.w("SmartysIPTV",
                                    "Live-Wiedergabe steht seit ${wdStillMs}ms -> Reconnect")
                                wdStillMs = 0L
                                scheduleReconnect()
                            }
                        } else {
                            wdStillMs = 0L
                            wdLastPos = pos
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("SmartysIPTV", "Waechter: ${e.message}")
            }
            if (wdActive) wdHandler.postDelayed(this, 1000L)
        }
    }

    private fun wdStart() {
        wdStartedAt = System.currentTimeMillis()
        wdLastPos = -1L
        wdStillMs = 0L
        if (!wdActive) {
            wdActive = true
            wdHandler.postDelayed(wdTick, 1000L)
        }
    }

    private fun wdStop() {
        wdActive = false
        wdHandler.removeCallbacks(wdTick)
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e("SmartysIPTV", "Player-Fehler (errorCode=${error.errorCodeName}): ${error.message}")
                // Verbindungslimit erkennen: der Anbieter antwortet mit 509
                // (Bandwidth/Connections) oder 429 (Too Many Requests). Dann
                // ist schnelles Neuverbinden sinnlos — der Slot bleibt belegt.
                letzterFehlerWarLimit = istVerbindungsLimit(error)
                if (letzterFehlerWarLimit) {
                    Log.w("SmartysIPTV", "Verbindungslimit des Anbieters erreicht — " +
                            "warte laenger statt im 2-s-Takt neu anzuklopfen")
                }
                scheduleReconnect()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    if (isVod) {
                        // Film/Episode regulär zu Ende — KEIN Reconnect, sonst startet er von vorn.
                        Log.i("SmartysIPTV", "VOD zu Ende gespielt")
                        if (!vodEndedFired) {
                            vodEndedFired = true
                            onVodEnded?.invoke()
                        }
                    } else {
                        // Live-Stream sollte nie ENDED sein — Provider hat die Verbindung gekappt
                        Log.w("SmartysIPTV", "Live-Stream endete unerwartet -> Reconnect")
                        scheduleReconnect()
                    }
                }
                if (state == Player.STATE_READY) {
                    reconnectAttempts = 0
                    scheduledReconnect = false
                    // Limit-Flag zuruecksetzen: der Stream laeuft wieder, beim
                    // naechsten Abbruch darf der schnelle Backoff greifen.
                    letzterFehlerWarLimit = false
                    // Gewünschte Position (Fortsetzen oder Reconnect) erst nach READY anspringen.
                    pendingSeekMs?.let { ms ->
                        pendingSeekMs = null
                        if (ms > 0L) {
                            player.seekTo(ms)
                            Log.d("SmartysIPTV", "seekTo ${ms}ms")
                        }
                    }
                }
            }
        })
    }

    /** Titel abspielen. channelId mit "vod:"-Präfix markiert VOD (Film/Serie). */
    fun playChannel(url: String, channelId: String, onDead: ((String) -> Unit)? = null, startPosMs: Long = 0L, onVodEnded: (() -> Unit)? = null) {
        currentUrl = url
        currentChannelId = channelId
        onChannelDead = onDead
        this.onVodEnded = onVodEnded
        vodEndedFired = false
        isVod = channelId.startsWith("vod:")
        reconnectAttempts = 0
        scheduledReconnect = false
        pendingSeekMs = if (startPosMs > 0L) startPosMs else null
        // LITE: vor dem Dekoderstart Hintergrunddienste freigeben. Ohne das
        // bleibt auf einem 875-MB-Stick zu wenig fuer Videopuffer (gemessen:
        // +70 MB frei). Nur im lite-Build, tv/phone unveraendert.
        if (BuildConfig.LITE_DEVICE) {
            Thread {
                try {
                    MemoryGuard.aufraeumen(ctx)
                } catch (e: Exception) {
                    Log.w("SmartysIPTV", "Aufraeumen fehlgeschlagen: ${e.javaClass.simpleName}")
                }
            }.start()
        }
        startStream()
    }

    private fun startStream(istReconnect: Boolean = false) {
        val url = currentUrl ?: return
        player.apply {
            // Bei einem RECONNECT kein stop(): das loescht das letzte Bild und
            // der Nutzer sieht einen schwarzen Schirm, obwohl oft nur eine
            // Sekunde fehlt. setMediaItem + prepare auf der laufenden Instanz
            // laesst das letzte Frame stehen, bis wirklich neue Daten kommen.
            // Beim KanalWECHSEL wird dagegen sauber gestoppt (kein altes Bild
            // des vorherigen Senders stehen lassen).
            if (!istReconnect) stop()
            val item = MediaItem.Builder()
                .setUri(url)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setMaxOffsetMs(liveLookbackMs.toLong())
                        .build()
                )
                .build()
            setMediaItem(item)
            prepare()
            play()
        }
        Log.d("SmartysIPTV", "Stream ${if (istReconnect) "neu verbunden" else "gestartet"}: $url")
        wdStart()
    }

    private fun scheduleReconnect() {
        if (scheduledReconnect) return
        if (currentUrl == null) return
        scheduledReconnect = true

        // VOD: aktuelle Position merken, damit der Reconnect dort weitermacht statt bei 0.
        if (isVod) {
            val pos = player.currentPosition
            if (pos > 0L) {
                pendingSeekMs = pos
                Log.i("SmartysIPTV", "VOD-Reconnect: merke Position ${pos}ms")
            }
        }

        // Bei einem Verbindungslimit NICHT laenger warten: gestern lief es mit
        // dem kurzen 2-s-Takt 1,5 Stunden lang mit max. 1-2 s Aussetzern.
        // Lange Pausen (20/30 s) haben daraus 20-40 s Standbild gemacht — die
        // Wartezeit war schlimmer als der Fehler selbst.
        val delayMs = when {
            reconnectAttempts == 0 -> 2_000L
            reconnectAttempts == 1 -> 3_000L
            reconnectAttempts == 2 -> 5_000L
            else -> 8_000L
        }
        reconnectAttempts++
        Log.w("SmartysIPTV", "Reconnect in ${delayMs / 1000}s (Versuch $reconnectAttempts" +
                (if (letzterFehlerWarLimit) ", Verbindungslimit" else "") + ")")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            scheduledReconnect = false
            // Nur wenn der Titel noch aktuell ist (User könnte inzwischen umgeschaltet haben)
            if (currentUrl != null) {
                // istReconnect = true: kein stop(), das letzte Bild bleibt stehen
                startStream(istReconnect = true)
            }
        }, delayMs)
    }

    fun stop() {
        wdStop()
        currentUrl = null
        currentChannelId = null
        player.stop()
    }

    /** true, wenn der Player freigegeben wurde — Aufrufer muss neu bauen. */
    @Volatile
    var released = false
        private set

    /**
     * Gibt den KOMPLETTEN Puffer frei (RAM + Allocator-Segmente).
     *
     * Der Live-Puffer ist der groesste Speicherblock der App. Ohne diesen
     * Aufruf bleibt er nach dem Verlassen des Players belegt, weil das
     * RobustLivePlayer-Objekt app-weit lebt — andere Apps (Netflix, YouTube)
     * finden dann auf einer 1,8-GB-Box keinen Platz mehr. `stop()` allein
     * gibt die Segmente nur an den Allocator zurueck, nicht ans System.
     */
    fun shutdown() {
        if (released) return
        wdStop()
        val freiVorher = freiMb()
        try {
            player.stop()
            player.clearMediaItems()
            player.release()
        } catch (e: Exception) {
            Log.w("SmartysIPTV", "shutdown: ${e.message}")
        }
        released = true
        currentUrl = null
        currentChannelId = null
        // Allocator-Pool und native Puffer ans System zurueckgeben
        Runtime.getRuntime().gc()
        Log.i("SmartysIPTV", "Player beendet, Puffer frei: ${freiVorher} MB -> ${freiMb()} MB")
    }

    private fun freiMb(): Long = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.availMem / 1048576L
    } catch (_: Exception) { 0L }

    fun release() = shutdown()
}

/** Timeshift-Einstellung (Live-TV-Pause + Zurueckspulen). App-weit, nicht pro Profil. */
object PlayPrefs {
    private const val PREFS = "smartys_play"
    fun lookback(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("live_lookback_ms", 0L)
    fun setLookback(ctx: Context, ms: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("live_lookback_ms", ms).apply()
    }
    /** Live-TV-Puffer in Sekunden (0 = automatisch nach Geraetefaehigkeit). */
    fun bufferSec(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("buffer_sec", 0)
    fun setBufferSec(ctx: Context, sec: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("buffer_sec", sec).apply()
    }
    /**
     * Lautstaerke 0.0-1.0. Wird gebraucht, weil die alte Fire-TV-Fernbedienung
     * keine Volume-Tasten hat: im Player regeln Links/Rechts den Pegel, und
     * der Wert muss den Kanalwechsel ueberleben.
     */
    fun volume(ctx: Context): Float =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("volume", 1f)
    fun setVolume(ctx: Context, v: Float) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("volume", v.coerceIn(0f, 1f)).apply()
    }
}

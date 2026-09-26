package com.smartys.iptv

import android.content.Context
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
class RobustLivePlayer(ctx: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(ctx)
        .setTrackSelector(DefaultTrackSelector(ctx))
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Puffer: 20s Minimum, max 40s vorhalten — reicht für Live-Aussetzer,
                // ohne 4K-VOD den Speicher leerzufressen.
                .setBufferDurationsMs(
                    /* minBufferMs = */ 20_000,
                    /* maxBufferMs = */ 40_000,
                    /* bufferForPlaybackMs = */ 2_500,
                    /* bufferForPlaybackAfterRebufferMs = */ 5_000
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
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
    private var scheduledReconnect = false
    private var pendingSeekMs: Long? = null
    private var isVod = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e("SmartysIPTV", "Player-Fehler (errorCode=${error.errorCodeName}): ${error.message}")
                scheduleReconnect()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    if (isVod) {
                        // Film/Episode regulär zu Ende — KEIN Reconnect, sonst startet er von vorn.
                        Log.i("SmartysIPTV", "VOD zu Ende gespielt")
                    } else {
                        // Live-Stream sollte nie ENDED sein — Provider hat die Verbindung gekappt
                        Log.w("SmartysIPTV", "Live-Stream endete unerwartet -> Reconnect")
                        scheduleReconnect()
                    }
                }
                if (state == Player.STATE_READY) {
                    reconnectAttempts = 0
                    scheduledReconnect = false
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
    fun playChannel(url: String, channelId: String, onDead: ((String) -> Unit)? = null, startPosMs: Long = 0L) {
        currentUrl = url
        currentChannelId = channelId
        onChannelDead = onDead
        isVod = channelId.startsWith("vod:")
        reconnectAttempts = 0
        scheduledReconnect = false
        pendingSeekMs = if (startPosMs > 0L) startPosMs else null
        startStream()
    }

    private fun startStream() {
        val url = currentUrl ?: return
        player.apply {
            stop()  // alter State weg, Reconnect ist sauber
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            play()
        }
        Log.d("SmartysIPTV", "Stream gestartet/gestartet-neu: $url")
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

        val delayMs = when {
            reconnectAttempts == 0 -> 2_000L
            reconnectAttempts == 1 -> 4_000L
            reconnectAttempts == 2 -> 8_000L
            reconnectAttempts == 3 -> 16_000L
            else -> 30_000L
        }
        reconnectAttempts++
        Log.w("SmartysIPTV", "Reconnect in ${delayMs / 1000}s (Versuch $reconnectAttempts)")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            scheduledReconnect = false
            // Nur wenn der Titel noch aktuell ist (User könnte inzwischen umgeschaltet haben)
            if (currentUrl != null) {
                startStream()
            }
        }, delayMs)
    }

    fun stop() {
        currentUrl = null
        currentChannelId = null
        player.stop()
    }

    fun release() {
        currentUrl = null
        player.release()
    }
}

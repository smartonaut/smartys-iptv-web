package com.smartys.iptv

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Robuster Live-Stream-Player:
 * - Großer Puffer: kurze Netz-Aussetzer fallen nicht auf
 * - Auto-Reconnect bei Stream-Fehlern/Abbruch: DER Player hört nicht einfach auf zu spielen.
 *   Reconnect mit Backoff: 2s, 4s, 8s, 16s, 30s, 30s ... (unbegrenzt solange Kanal angewählt)
 */
class RobustLivePlayer(ctx: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(ctx)
        .setTrackSelector(DefaultTrackSelector(ctx))
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Puffer: 30s Minimum bevor Playback startet, bis zu 120s vorhalten.
                // Live-HLS mit 10s-Segmenten: 12 Segmente Stack = 2 Minuten Puffer vor Aussetzern.
                .setBufferDurationsMs(
                    /* minBufferMs = */ 30_000,
                    /* maxBufferMs = */ 120_000,
                    /* bufferForPlaybackMs = */ 2_500,
                    /* bufferForPlaybackAfterRebufferMs = */ 5_000
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        .build()
        .apply { playWhenReady = true }

    private var currentUrl: String? = null
    private var currentChannelId: String? = null
    private var onChannelDead: ((String) -> Unit)? = null
    private var reconnectAttempts = 0
    private var scheduledReconnect = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e("SmartysIPTV", "Player-Fehler (errorCode=${error.errorCodeName}): ${error.message}")
                scheduleReconnect()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    // Live-Stream sollte nie ENDED sein — Provider hat die Verbindung gekappt
                    Log.w("SmartysIPTV", "Live-Stream endete unerwartet -> Reconnect")
                    scheduleReconnect()
                }
                if (state == Player.STATE_READY) {
                    reconnectAttempts = 0
                    scheduledReconnect = false
                }
            }
        })
    }

    /** Kanal abspielen. Idempotent — gleicher Kanal wird nicht doppelt angestoßen. */
    fun playChannel(url: String, channelId: String, onDead: ((String) -> Unit)? = null) {
        currentUrl = url
        currentChannelId = channelId
        onChannelDead = onDead
        reconnectAttempts = 0
        scheduledReconnect = false
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
            // Nur wenn der Kanal noch aktuell ist (User könnte inzwischen umgeschaltet haben)
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
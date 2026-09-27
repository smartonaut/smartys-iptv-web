package com.smartys.iptv

import android.graphics.Bitmap
import android.view.TextureView
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/**
 * Erkennt den Beginn eines Abspanns (Credits) durch Frame-Analyse am Ende
 * eines VOD-Titels — OHNE Provider-Metadaten.
 *
 * Signatur eines Abspanns:
 *   - DUNKEL: fast schwarzer Hintergrund (rollender Text, keine Szene)
 *   - RUHIG:  kaum Bildänderung zwischen Frames (kein Szenenwechsel)
 *
 * Beide Merkmale zusammen über mehrere aufeinanderfolgende Samples (~2,4 s)
 * => Abspann. Der Detektor läuft nur im letzten Drittel der Laufzeit, damit
 * dunkle Szenen mitten im Film nicht fälschlich triggern.
 *
 * Fallback bleibt der STATE_ENDED-Hook im Player: Wird kein Abspann erkannt
 * (z. B. heller Abspann), greift am Ende trotzdem der Auto-Advance.
 */
class CreditsWatcher(
    private val textureView: TextureView,
    private val player: ExoPlayer,
    private val onCreditsDetected: () -> Unit
) {
    // Analyse-Schwellen (0..1)
    private companion object {
        const val SAMPLE_INTERVAL_MS = 2500L   // sparsam: nur ~alle 2,5 s ein Frame
        const val DARK_THRESHOLD = 0.16f     // mittlere Luminanz darunter = "schwarz"
        const val QUIET_THRESHOLD = 0.12f    // mittlere Frame-Differenz darunter = "ruhig"
        const val REQUIRED_STREAK = 2        // aufeinanderfolgende dunkle+ruhige Samples
        const val SAMPLE_W = 48              // Downsample-Größe für billige Analyse
        const val SAMPLE_H = 27
        const val ACTIVE_WINDOW_MS = 180_000L  // Erkennung läuft nur in den letzten 3 Minuten
    }

    @Volatile private var stopped = false
    private var fired = false
    private var streak = 0
    private var prev: Bitmap? = null

    /** Loop läuft bis stop() oder bis einmal erkannt. Nur in den letzten 3 Minuten aktiv. */
    suspend fun run() {
        while (!stopped) {
            delay(SAMPLE_INTERVAL_MS)
            if (stopped || fired) return
            if (player.duration <= 0L) continue
            // Nur innerhalb des letzten Fensters (letzte 3 Minuten) analysieren.
            val remaining = player.duration - player.currentPosition
            if (remaining > ACTIVE_WINDOW_MS) continue

            val bmp = try { textureView.getBitmap(SAMPLE_W, SAMPLE_H) } catch (e: Exception) { null }
                ?: continue

            val (brightness, motion) = analyze(bmp, prev)
            prev?.recycle()
            prev = bmp

            val darkQuiet = brightness < DARK_THRESHOLD && motion < QUIET_THRESHOLD
            if (darkQuiet) {
                streak++
                if (streak >= REQUIRED_STREAK && !fired) {
                    fired = true
                    onCreditsDetected()
                    return
                }
            } else {
                streak = 0
            }
        }
    }

    fun stop() {
        stopped = true
        prev?.recycle()
        prev = null
    }

    /**
     * Liefert (mittlere Luminanz 0..1, mittlere Pixel-Differenz zum Vorframe 0..1).
     * Beides über ein kleines Downsample-Bitmap gerechnet => billig.
     */
    private fun analyze(bmp: Bitmap, prevBmp: Bitmap?): Pair<Float, Float> {
        val w = bmp.width
        val h = bmp.height
        val n = w * h
        val px = IntArray(n)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        var lumSum = 0.0
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lumSum += 0.299 * r + 0.587 * g + 0.114 * b
        }
        val brightness = (lumSum / (n * 255.0)).toFloat()

        if (prevBmp == null || prevBmp.width != w || prevBmp.height != h) {
            return brightness to 1f   // kein Vorframe => keine Bewegung einschätzbar
        }
        val pp = IntArray(n)
        prevBmp.getPixels(pp, 0, w, 0, 0, w, h)

        var diffSum = 0.0
        for (i in 0 until n) {
            val a = px[i]
            val b = pp[i]
            val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
            val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
            val db = (a and 0xFF) - (b and 0xFF)
            diffSum += kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db)
        }
        val motion = (diffSum / (n * 3 * 255.0)).toFloat()
        return brightness to motion
    }
}

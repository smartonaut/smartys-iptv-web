package com.smartys.iptv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Live-TV-Aufnahme: MPEG-TS-Stream direkt auf Disk schreiben.
 *
 * Fire TV hat KEIN ffmpeg (armeabi-v7a, kein /system/bin/ffmpeg) — der
 * Desktop-/Enigma2-Weg (`ffmpeg -c copy`) fällt hier aus. Stattdessen wird der
 * `.ts`-Stream (Xtream liefert Live-TV auch als rohes MPEG-TS) über einen
 * einfachen HTTP-Stream ohne Re-encode 1:1 in eine Datei geschrieben. Das ist
 * exakt das, was `ffmpeg -c copy` macht — nur ohne ffmpeg.
 *
 * Wichtig:
 * - KEIN Puffern des ganzen Streams im RAM: blockweise lesen + sofort schreiben.
 * - Streaming-Timeout großzügig (lesen kann lange dauern), connect kurz.
 * - stop() schließt sauber und hinterlässt eine abspielbare Datei (MPEG-TS ist
 *   auch bei abruptem Abbruch abspielbar — kein Index am Ende nötig).
 */
object Recorder {

    private const val TAG = "SmartysIPTV-Recorder"

    private var job: Job? = null
    private var targetFile: File? = null

    /** true, solange eine Aufnahme läuft. */
    val isRecording: Boolean get() = job?.isActive == true

    /** Läuft gerade eine Aufnahme? */
    fun active(): Boolean = isRecording

    /**
     * Startet die Aufnahme des gegebenen Streams.
     * @return die Zieldatei, oder null bei sofortigem Fehler (z. B. ungültige URL).
     */
    fun start(ctx: Context, url: String, channelName: String, fileName: String): File? {
        if (isRecording) {
            Log.w(TAG, "Aufnahme läuft bereits — start() ignoriert")
            return null
        }
        val dir = recordingsDir(ctx)
        val file = File(dir, "$fileName.ts")
        targetFile = file
        job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            download(ctx, url, channelName, file)
        }
        return file
    }

    /** Stoppt die laufende Aufnahme (Datei bleibt abspielbar). */
    fun stop() {
        val j = job ?: return
        if (j.isActive) {
            Log.i(TAG, "Aufnahme stoppen angefordert")
            j.cancel()
        }
        job = null
        targetFile = null
    }

    /** Verzeichnis für Aufnahmen (App-extern, damit der User sie findet). */
    private fun recordingsDir(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(null), "recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private suspend fun download(ctx: Context, url: String, channelName: String, file: File) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // Lese-Timeout bewusst lang: ein Live-Stream liefert laufend Daten,
            // kurze Stalls dürfen die Aufnahme nicht killen. 0 = kein Timeout.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "SmartysIPTV/1.0")
            .build()

        Log.i(TAG, "Aufnahme start: $channelName -> ${file.name} ($url)")

        withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "Stream nicht erreichbar: HTTP ${resp.code}")
                        file.delete()
                        return@withContext
                    }
                    val body = resp.body ?: run {
                        Log.e(TAG, "Leerer Body")
                        file.delete()
                        return@withContext
                    }
                    body.byteStream().use { input ->
                        FileOutputStream(file).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (isActive) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                total += n
                            }
                            Log.i(TAG, "Aufnahme fertig: ${file.name} (${total / 1024 / 1024} MB)")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Bewusst: Aufnahme abgebrochen, Datei bleibt abspielbar.
                Log.i(TAG, "Aufnahme abgebrochen: ${file.name} (${file.length() / 1024 / 1024} MB)")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Aufnahme-Fehler: ${e.message}")
                if (file.length() == 0L) file.delete()
            }
        }
    }
}

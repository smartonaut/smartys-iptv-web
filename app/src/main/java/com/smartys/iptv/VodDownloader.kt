package com.smartys.iptv

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Ein heruntergeladener VOD-Titel (Filme; Serien-Episoden später). */
data class DownloadItem(
    val id: String,
    val title: String,
    val url: String,
    val fileName: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: String,   // running | paused | complete | error
    val error: String? = null
) {
    val progress: Float get() =
        if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * VOD-Downloader mit HTTP-Range-Resume: Bricht die Verbindung ab (oder wird
 * die App beendet), setzt der nächste Start exakt an der gespeicherten
 * Position fort. Persistenz über SharedPreferences + .part-Dateien im
 * App-externen Ordner (keine Laufzeit-Berechtigung nötig).
 */
object VodDownloader {

    private const val PREFS = "smartys_downloads"
    private const val K_ITEMS = "items_json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val paused = ConcurrentHashMap<String, Boolean>()

    /** Reaktive Liste fuer die UI (Compose). */
    val items = mutableStateListOf<DownloadItem>()

    fun downloadsDir(ctx: Context): File =
        (ctx.getExternalFilesDir(null) ?: ctx.filesDir).resolve("downloads")

    /** Laedt persistierte Downloads beim App-Start. */
    fun load(ctx: Context) {
        val json = prefs(ctx).getString(K_ITEMS, null) ?: return
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return
        items.clear()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            items.add(
                DownloadItem(
                    o.getString("id"), o.getString("title"), o.getString("url"),
                    o.getString("fileName"), o.optLong("total", 0L),
                    o.optLong("downloaded", 0L), o.optString("status", "error"),
                    o.optString("error").takeIf { it.isNotBlank() }
                )
            )
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        items.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id).put("title", d.title).put("url", d.url)
                    .put("fileName", d.fileName).put("total", d.totalBytes)
                    .put("downloaded", d.downloadedBytes).put("status", d.status)
                    .put("error", d.error ?: "")
            )
        }
        prefs(ctx).edit().putString(K_ITEMS, arr.toString()).apply()
    }

    private fun update(id: String, transform: (DownloadItem) -> DownloadItem) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) items[idx] = transform(items[idx])
    }

    /** Startet einen Download (idempotent; laufende/fertige werden nicht doppelt gestartet). */
    fun start(ctx: Context, id: String, title: String, url: String, ext: String) {
        val appCtx = ctx.applicationContext
        if (items.any { it.id == id && it.status == "complete" }) return
        if (jobs.containsKey(id)) return
        val fileName = "$id.$ext"
        paused[id] = false
        val existing = items.indexOfFirst { it.id == id }
        if (existing >= 0) update(id) { it.copy(status = "running", error = null) }
        else items.add(DownloadItem(id, title, url, fileName, 0L, 0L, "running"))
        persist(appCtx)
        jobs[id] = scope.launch { runDownload(appCtx, id) }
    }

    private suspend fun runDownload(ctx: Context, id: String) {
        val dir = downloadsDir(ctx)
        dir.mkdirs()
        val item = items.firstOrNull { it.id == id } ?: return
        val partFile = File(dir, item.fileName + ".part")
        val finalFile = File(dir, item.fileName)

        var raf: RandomAccessFile? = null
        try {
            var startFrom = if (partFile.exists()) partFile.length() else 0L
            var conn = (URL(item.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                // Immer einen Range-Request senden (auch beim Erststart):
                // manche Xtream-/nginx-Server liefern bei einem plain GET ohne
                // Range Content-Length:0 => leere Datei. Range erzwingt Streaming.
                setRequestProperty("Range", "bytes=$startFrom-")
                setRequestProperty("User-Agent", "SmartysIPTV/0.4 (okhttp)")
            }
            var code = conn.responseCode
            val resume = code == 206 && startFrom > 0L
            var total = if (resume)
                conn.getHeaderField("Content-Range")?.substringAfter("/")?.toLongOrNull()
            else null
            if (total == null) total = conn.contentLength.toLong().takeIf { it > 0 } ?: -1L

            // Server ignoriert Range => von vorne beginnen.
            if (code == 200 && startFrom > 0L) {
                partFile.delete()
                startFrom = 0L
                conn = (URL(item.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                }
                code = conn.responseCode
            }

            raf = RandomAccessFile(partFile, "rw")
            if (code == 206) raf.seek(startFrom) else raf.setLength(0)

            update(id) { it.copy(totalBytes = total, downloadedBytes = startFrom) }

            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                var downloaded = startFrom
                while (true) {
                    if (paused[id] == true) {
                        persist(ctx)
                        return
                    }
                    val read = input.read(buf)
                    if (read < 0) break
                    raf.write(buf, 0, read)
                    downloaded += read
                    update(id) { it.copy(downloadedBytes = downloaded) }
                }
            }
            raf.close(); raf = null

            // Vollständigkeits-Prüfung: niemals "complete" markieren, wenn der
            // Server nichts (0 Bytes) oder weniger als erwartet geliefert hat.
            // (Bug: leere Streams wie Content-Length:0 wurden als fertig gezeigt.)
            val got = partFile.length()
            when {
                got <= 0L -> {
                    partFile.delete()
                    update(id) { it.copy(downloadedBytes = 0L, status = "error", error = "Server lieferte keine Daten (0 Bytes)") }
                    persist(ctx)
                }
                total > 0L && got < total -> {
                    // Abgebrochen/trunkiert: .part behalten fuer spaeteres Resume.
                    update(id) { it.copy(downloadedBytes = got, status = "error", error = "Unvollständig: $got von $total Bytes") }
                    persist(ctx)
                }
                else -> {
                    // Fertig: .part -> endgueltiger Name.
                    partFile.renameTo(finalFile)
                    update(id) { it.copy(downloadedBytes = finalFile.length(), status = "complete", error = null) }
                    persist(ctx)
                }
            }
        } catch (e: Exception) {
            // Bei absichtlichem Pause/Abbruch nicht als Fehler markieren.
            if (paused[id] != true && jobs[id]?.isCancelled != true) {
                update(id) { it.copy(status = "error", error = e.message) }
                persist(ctx)
            }
        } finally {
            raf?.close()
            jobs.remove(id)
        }
    }

    fun pause(id: String) {
        paused[id] = true
        update(id) { it.copy(status = "paused") }
        jobs[id]?.cancel()
        jobs.remove(id)
    }

    fun resume(ctx: Context, id: String) {
        val appCtx = ctx.applicationContext
        val item = items.firstOrNull { it.id == id } ?: return
        if (item.status == "complete") return
        paused[id] = false
        update(id) { it.copy(status = "running", error = null) }
        persist(appCtx)
        jobs[id] = scope.launch { runDownload(appCtx, id) }
    }

    /** Löscht einen Download (Eintrag + Dateien). */
    fun delete(ctx: Context, id: String) {
        jobs[id]?.cancel()
        jobs.remove(id)
        paused.remove(id)
        val item = items.firstOrNull { it.id == id }
        items.removeAll { it.id == id }
        persist(ctx)
        if (item != null) {
            val dir = downloadsDir(ctx)
            File(dir, item.fileName).delete()
            File(dir, item.fileName + ".part").delete()
        }
    }

    /** Pfad zur fertigen Datei, oder null wenn nicht komplett. */
    fun localFile(ctx: Context, id: String): File? {
        val item = items.firstOrNull { it.id == id } ?: return null
        if (item.status != "complete") return null
        val f = File(downloadsDir(ctx), item.fileName)
        return if (f.exists()) f else null
    }
}

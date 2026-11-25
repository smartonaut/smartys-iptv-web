package com.smartys.iptv

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/**
 * Jellyfin als zweite VOD-Quelle — Android-Fassung der Desktop-/webOS-Anbindung.
 *
 * Ablauf wie ueberall: Server suchen (8096, /System/Info/Public, 1200 ms je Host,
 * Prefix-Fallback), anmelden (/Users/AuthenticateByName), Filme/Serien als
 * Xtream-foermige Objekte mit Praefix "jf:", Wiedergabe ausschliesslich per HLS
 * (master.m3u8) MIT MediaSourceId — ohne den Parameter antwortet Jellyfin 10.11
 * mit HTTP 400. Das Passwort wird nie gespeichert, nur das Token.
 */
object Jellyfin {

    data class Server(val base: String, val name: String, val version: String)
    data class Auth(val base: String, val token: String, val userId: String, val userName: String)

    private const val TIMEOUT_MS = 1200
    private const val PORT = 8096
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext
            .getSharedPreferences("smartys_jellyfin", Context.MODE_PRIVATE)
    }

    fun gespeichert(): Auth? {
        val p = prefs ?: return null
        val base = p.getString("base", null) ?: return null
        val token = p.getString("token", null) ?: return null
        val uid = p.getString("userId", null) ?: return null
        if (base.isBlank() || token.isBlank() || uid.isBlank()) return null
        return Auth(base, token, uid, p.getString("userName", "") ?: "")
    }

    fun merken(a: Auth?) {
        val p = prefs ?: return
        val e = p.edit()
        if (a == null) {
            e.clear().apply(); return
        }
        e.putString("base", a.base)
            .putString("token", a.token)
            .putString("userId", a.userId)
            .putString("userName", a.userName)
            .apply()
    }

    // ---------- HTTP (HttpURLConnection: auf Android wie Desktop immer vorhanden) ----------

    private fun get(url: String, token: String? = null): String? = runCatching {
        val c = URI.create(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = 6000
        c.instanceFollowRedirects = true
        if (token != null) c.setRequestProperty("X-Emby-Token", token)
        try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun post(url: String, body: String, headers: Map<String, String>): Pair<Int, String>? = runCatching {
        val c = URI.create(url).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 3000
        c.readTimeout = 8000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            code to txt
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private val clientHeader =
        "MediaBrowser Client=\"SmartysPlayer\", Device=\"Android\", DeviceId=\"android-tv\", Version=\"0.8.44\""

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ---------- Server suchen ----------

    private fun prefixe(): List<String> {
        val p = LinkedHashSet<String>()
        val bekannt = (gespeichert()?.base ?: "").removePrefix("http://").removePrefix("https://")
        if (bekannt.split(".").size == 4) p += bekannt.split(".").take(3).joinToString(".") + "."
        listOf("192.168.1.", "192.168.0.", "192.168.4.", "10.0.0.").forEach { p += it }
        return p.toList()
    }

    suspend fun suchen(): List<Server> = withContext(Dispatchers.IO) {
        val hosts = prefixe().flatMap { pr -> (1..254).map { "$pr$it" } }
        val treffer = mutableListOf<Server>()
        hosts.chunked(48).forEach { block ->
            if (treffer.isNotEmpty()) return@forEach
            coroutineScope {
                block.map { host ->
                    async {
                        val body = get("http://$host:$PORT/System/Info/Public") ?: return@async null
                        runCatching {
                            val o = JsonParser.parseString(body).asJsonObject
                            val v = o.get("Version")?.asString ?: return@runCatching null
                            Server("http://$host:$PORT", o.get("ServerName")?.asString ?: "", v)
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull().forEach { treffer += it }
            }
        }
        treffer
    }

    suspend fun anmelden(base: String, user: String, pw: String): Auth? = withContext(Dispatchers.IO) {
        val r = post(
            "${base.trimEnd('/')}/Users/AuthenticateByName",
            "{\"Username\":${q(user)},\"Pw\":${q(pw)}}",
            mapOf("X-Emby-Authorization" to clientHeader)
        ) ?: return@withContext null
        if (r.first == 401) return@withContext null
        runCatching {
            val o = JsonParser.parseString(r.second).asJsonObject
            val token = o.get("AccessToken")?.asString ?: return@runCatching null
            val u = o.getAsJsonObject("User")
            Auth(base.trimEnd('/'), token, u?.get("Id")?.asString ?: return@runCatching null,
                u?.get("Name")?.asString ?: user)
        }.getOrNull()?.also { merken(it) }
    }

    // ---------- Inhalte ----------

    suspend fun filme(a: Auth): List<VodStream> = withContext(Dispatchers.IO) {
        val url = "${a.base}/Users/${a.userId}/Items?IncludeItemTypes=Movie&Recursive=true" +
            "&Fields=ProductionYear,Overview,CommunityRating,ImageTags,MediaSources" +
            "&ImageTypeLimit=1&SortBy=SortName&SortOrder=Ascending&Limit=2000"
        val body = get(url, a.token) ?: return@withContext emptyList()
        runCatching {
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("Items")?.mapNotNull { el ->
                val o = el.asJsonObject
                val id = o.get("Id")?.asString ?: return@mapNotNull null
                val ms = o.getAsJsonArray("MediaSources")
                if (ms == null || ms.size() == 0) return@mapNotNull null
                VodStream(
                    num = null,
                    name = o.get("Name")?.asString ?: "",
                    streamType = "movie",
                    streamId = "jf:$id",
                    streamIcon = poster(a, id, o),
                    rating = o.get("CommunityRating")?.asDouble?.toString(),
                    rating5Based = o.get("CommunityRating")?.asDouble?.div(2.0),
                    added = null,
                    categoryId = CAT_FILME,
                    containerExtension = "m3u8",
                    directSource = null,
                    trailer = null,
                    tmdb = null
                ).also { it.categoryName = "Jellyfin" }
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    suspend fun serien(a: Auth): List<SeriesStream> = withContext(Dispatchers.IO) {
        val url = "${a.base}/Users/${a.userId}/Items?IncludeItemTypes=Series&Recursive=true" +
            "&Fields=Overview,CommunityRating,ImageTags,RecursiveItemCount&ImageTypeLimit=1" +
            "&SortBy=SortName&SortOrder=Ascending&Limit=2000"
        val body = get(url, a.token) ?: return@withContext emptyList()
        runCatching {
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("Items")?.mapNotNull { el ->
                val o = el.asJsonObject
                val id = o.get("Id")?.asString ?: return@mapNotNull null
                val anzahl = o.get("RecursiveItemCount")?.asInt ?: 0
                if (anzahl <= 0) return@mapNotNull null
                SeriesStream(
                    num = null,
                    name = o.get("Name")?.asString ?: "",
                    seriesId = "jf:$id",
                    cover = poster(a, id, o),
                    plot = o.get("Overview")?.asString,
                    cast = null,
                    director = null,
                    genre = null,
                    releaseDate = null,
                    rating = o.get("CommunityRating")?.asDouble?.toString(),
                    rating5Based = o.get("CommunityRating")?.asDouble?.div(2.0),
                    backdropPath = null,
                    categoryId = CAT_SERIEN,
                    episodeRunTime = null
                ).also { it.categoryName = "Jellyfin" }
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    suspend fun folgen(a: Auth, seriesId: String): SeriesInfo? = withContext(Dispatchers.IO) {
        val id = seriesId.removePrefix("jf:")
        val url = "${a.base}/Shows/$id/Episodes?UserId=${a.userId}" +
            "&Fields=MediaSources,Overview&SortBy=ParentIndexNumber,IndexNumber"
        val body = get(url, a.token) ?: return@withContext null
        runCatching {
            val items = JsonParser.parseString(body).asJsonObject.getAsJsonArray("Items")
                ?: return@runCatching null
            val proStaffel = LinkedHashMap<Int, MutableList<SeriesEpisode>>()
            items.forEach { el ->
                val o = el.asJsonObject
                val epId = o.get("Id")?.asString ?: return@forEach
                val ms = o.getAsJsonArray("MediaSources")
                if (ms == null || ms.size() == 0) return@forEach
                val staffel = o.get("ParentIndexNumber")?.asInt ?: 1
                val nummer = o.get("IndexNumber")?.asInt ?: 0
                proStaffel.getOrPut(staffel) { mutableListOf() } += SeriesEpisode(
                    id = "jf:$epId",
                    episodeNum = nummer.toString(),
                    title = o.get("Name")?.asString,
                    containerExtension = "m3u8",
                    info = null,
                    rating = o.get("CommunityRating")?.asDouble?.toString()
                )
            }
            val saisons = proStaffel.keys.sorted().map { s ->
                SeriesSeason(s, "Staffel $s", proStaffel[s]?.sortedBy { it.episodeNum?.toIntOrNull() ?: 0 })
            }
            SeriesInfo(
                seasons = saisons,
                info = null,
                episodes = proStaffel.mapKeys { it.key.toString() }
            )
        }.getOrNull()
    }

    private fun poster(a: Auth, id: String, o: com.google.gson.JsonObject): String? {
        val tag = o.getAsJsonObject("ImageTags")?.get("Primary")?.asString
        val suffix = if (tag != null) "&tag=$tag" else ""
        return "${a.base}/Items/$id/Images/Primary?maxHeight=420$suffix&api_key=${a.token}"
    }

    fun videoUrl(a: Auth, itemId: String): String {
        val id = itemId.removePrefix("jf:")
        return "${a.base}/Videos/$id/master.m3u8?api_key=${a.token}&MediaSourceId=$id" +
            "&VideoCodec=h264&AudioCodec=aac&TranscodingContainer=ts&SegmentContainer=ts" +
            "&MaxStreamingBitrate=12000000&Context=Streaming"
    }

    // ---------- Wiedergabe-Adressen: Jellyfin oder Line ----------

    fun filmUrl(c: CredStore.Creds?, streamId: String, ext: String?): String {
        if (streamId.startsWith("jf:")) {
            val a = gespeichert()
            if (a != null) return videoUrl(a, streamId)
        }
        return c?.movieUrl(streamId, ext ?: "mp4") ?: ""
    }

    fun folgeUrl(c: CredStore.Creds?, episodeId: String, ext: String?): String {
        if (episodeId.startsWith("jf:")) {
            val a = gespeichert()
            if (a != null) return videoUrl(a, episodeId)
        }
        return c?.seriesUrl(episodeId, ext ?: "mp4") ?: ""
    }

    const val CAT_FILME = "jf-filme"
    const val CAT_SERIEN = "jf-serien"

    fun kategorieFilme() = Category(CAT_FILME, "Jellyfin")
    fun kategorieSerien() = Category(CAT_SERIEN, "Jellyfin")
}

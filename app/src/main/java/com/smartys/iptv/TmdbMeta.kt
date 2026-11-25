package com.smartys.iptv

/**
 * TMDB-Metadaten (Film-Beschreibungen, Genres, Laufzeit) für die VOD-Detailseiten.
 *
 * Architektur-Entscheidung: Der App-Code enthält KEINEN API-Key (nicht dekompilierbar,
 * nicht leachable). Stattdessen läuft die Anfrage DURCH UNSER BACKEND:
 *
 *   App  ->  GET https://panel.smartys.tech/api/v1/tmdb/movie/{tmdbId}
 *   Backend -> GET https://api.themoviedb.org/3/movie/{id}?api_key=...&language=de-DE
 *   (Key liegt nur im Backend-Env, außerhalb der DB, nie im Client)
 *
 * Cache: Backend cached Antworten 24h auf Disk (TMDB-Ratelimit ist großzügig,
 * aber wir wollen die Detailseiten trotzdem flott und offline-tauglich).
 */
object TmdbMeta {

    private const val BASE = "https://panel.smartys.tech/api/v1/tmdb/movie/"

    data class MovieMeta(
        val overview: String?,
        val runtime: Int?,          // Minuten
        val genres: List<String>,
        val tagline: String?,
        val backdrop: String?,      // https://image.tmdb.org/t/p/w780/...
        val poster: String?         // https://image.tmdb.org/t/p/w342/... (Cover-Fallback)
    )

    /** In-Memory-Cache für die Poster-URLs (verhindert N-fache Backend-Calls pro Liste). */
    private val posterCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Ruft die Metadaten für eine TMDB-ID ab (auf IO-Thread aufrufen!). null = kein Treffer/offline. */
    fun fetchMovie(tmdbId: String): MovieMeta? = runCatching {
        val conn = java.net.URL(BASE + tmdbId.trim())
            .openConnection() as javax.net.ssl.HttpsURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        if (conn.responseCode != 200) return@runCatching null
        val root = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
        val genres = mutableListOf<String>()
        val ga = root.optJSONArray("genres") ?: org.json.JSONArray()
        for (i in 0 until ga.length()) genres.add(ga.getJSONObject(i).optString("name"))
        MovieMeta(
            overview = root.optString("overview").takeIf { it.isNotBlank() },
            runtime = if (root.has("runtime") && !root.isNull("runtime")) root.getInt("runtime") else null,
            genres = genres,
            tagline = root.optString("tagline").takeIf { it.isNotBlank() },
            backdrop = root.optString("backdrop_path").takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/w780$it" },
            poster = root.optString("poster_path").takeIf { it.isNotBlank() }
                ?.let { "https://image.tmdb.org/t/p/w342$it" }
        )
    }.getOrNull()

    /** Nur die Poster-URL für eine TMDB-ID (leichtgewichtiger Fallback, gecacht). */
    fun posterUrl(tmdbId: String): String? {
        val id = tmdbId.trim()
        if (id.isBlank()) return null
        posterCache[id]?.let { return it }
        return fetchMovie(id)?.poster?.also { posterCache[id] = it }
    }
}
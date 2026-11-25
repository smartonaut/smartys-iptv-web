package com.smartys.iptv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

// ---------- Meine Liste (Watchlist für Filme + Serien) ----------

/**
 * Ein gemerkter Titel.
 *
 * Bewusst **selbsttragend** (Name, Poster, Kennungen …) statt nur ein Verweis:
 * Android lädt Filme NUR kategorieweise (siehe loadVodCategory, 192-MB-Heap) —
 * ein gemerkter Film lässt sich also später nicht über vm.vodMovies auflösen,
 * weil seine Kategorie gar nicht geladen ist. Aus denselben Feldern bauen
 * [toMovie]/[toSeries] die Objekte für die Detailseiten wieder zusammen.
 *
 * Die JSON-Schlüssel sind mit dem Desktop-Teil abgestimmt.
 */
data class WatchItem(
    val kind: String,           // "movie" oder "series"
    val id: String,             // streamId (Film) / seriesId (Serie)
    val name: String,
    val poster: String? = null, // streamIcon (Film) / cover (Serie)
    val rating: String? = null,
    val ext: String? = null,    // container_extension (nur Film)
    val tmdb: String? = null,   // TMDB-Kennung (nur Film)
    val catId: String = "",
    val plot: String? = null,   // Beschreibung (nur Serie)
    val added: Long = 0L        // Zeitpunkt des Merkens (Sortierung: neueste zuerst)
) {
    fun key(): String = "$kind:$id"

    fun toMovie(): VodStream = VodStream(
        num = null, name = name, streamType = "movie", streamId = id,
        streamIcon = poster, rating = rating, rating5Based = null, added = null,
        categoryId = catId, containerExtension = ext ?: "mp4",
        directSource = null, trailer = null, tmdb = tmdb
    )

    fun toSeries(): SeriesStream = SeriesStream(
        num = null, name = name, seriesId = id, cover = poster, plot = plot,
        cast = null, director = null, genre = null, releaseDate = null,
        rating = rating, rating5Based = null, backdropPath = null,
        categoryId = catId, episodeRunTime = null
    )

    companion object {
        /** Aus einem geladenen Film — nur die Felder, die die Liste selbst braucht. */
        fun fromMovie(m: VodStream) = WatchItem(
            kind = "movie", id = m.streamId, name = m.name, poster = m.poster(),
            rating = m.rating, ext = m.containerExtension, tmdb = m.tmdb,
            catId = m.categoryId
        )

        /** Aus einer geladenen Serie; plot kommt mit, damit die Detailseite
         *  auch ohne Nachladen etwas anzeigen kann. */
        fun fromSeries(s: SeriesStream) = WatchItem(
            kind = "series", id = s.seriesId, name = s.name, poster = s.cover,
            rating = s.rating, catId = s.categoryId, plot = s.plot
        )
    }
}

/**
 * Persistenz der Merkliste.
 *
 * Profilgebunden wie Favoriten/Resume (gleiche Prefs-Datei
 * "smartys_iptv_state__<ProfilId>"), damit ein Profilwechsel sauber trennt
 * und niemand fremde Merkzettel sieht.
 */
object WatchlistStore {

    private const val K_JSON = "watchlist_json"

    private fun prefs(ctx: Context): SharedPreferences {
        val pid = ProfileStore.activeId(ctx) ?: "default"
        return ctx.getSharedPreferences("smartys_iptv_state__$pid", Context.MODE_PRIVATE)
    }

    fun items(ctx: Context): List<WatchItem> {
        val json = prefs(ctx).getString(K_JSON, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val name = o.optString("name")
                // Ein kaputter Datensatz darf nie die ganze Liste kosten —
                // lieber still überspringen.
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                WatchItem(
                    kind = o.optString("kind", "movie"),
                    id = id,
                    name = name,
                    poster = o.optString("poster").takeIf { it.isNotBlank() },
                    rating = o.optString("rating").takeIf { it.isNotBlank() },
                    ext = o.optString("ext").takeIf { it.isNotBlank() },
                    tmdb = o.optString("tmdb").takeIf { it.isNotBlank() },
                    catId = o.optString("catId"),
                    plot = o.optString("plot").takeIf { it.isNotBlank() },
                    added = o.optLong("added", 0L)
                )
            }.sortedByDescending { it.added }
        }.getOrDefault(emptyList())
    }

    fun keys(ctx: Context): Set<String> = items(ctx).map { it.key() }.toSet()

    fun isMarked(ctx: Context, kind: String, id: String): Boolean =
        items(ctx).any { it.kind == kind && it.id == id }

    private fun write(ctx: Context, list: List<WatchItem>) {
        val arr = JSONArray()
        list.forEach { w ->
            arr.put(
                JSONObject()
                    .put("kind", w.kind).put("id", w.id).put("name", w.name)
                    .put("poster", w.poster).put("rating", w.rating).put("ext", w.ext)
                    .put("tmdb", w.tmdb).put("catId", w.catId).put("plot", w.plot)
                    .put("added", w.added)
            )
        }
        prefs(ctx).edit().putString(K_JSON, arr.toString()).apply()
    }

    /**
     * Merkt den Titel bzw. entfernt ihn wieder; gibt den neuen Stand zurück.
     * `added` wird hier gestempelt (nicht vom Aufrufer), damit die Reihenfolge
     * nicht davon abhängt, welcher Screen das Item gebaut hat.
     */
    fun toggle(ctx: Context, item: WatchItem): List<WatchItem> {
        val cur = items(ctx).toMutableList()
        val k = item.key()
        val schonGemerkt = cur.any { it.key() == k }
        if (schonGemerkt) {
            cur.removeAll { it.key() == k }
        } else {
            cur.add(item.copy(added = System.currentTimeMillis()))
        }
        val neu = cur.sortedByDescending { it.added }
        write(ctx, neu)
        return neu
    }

    fun remove(ctx: Context, kind: String, id: String): List<WatchItem> {
        val neu = items(ctx).filterNot { it.kind == kind && it.id == id }
        write(ctx, neu)
        return neu
    }

    fun clear(ctx: Context) {
        write(ctx, emptyList())
    }
}

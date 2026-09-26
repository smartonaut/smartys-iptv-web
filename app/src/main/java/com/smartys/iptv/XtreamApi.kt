package com.smartys.iptv

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

// ---------- Xtream Panel API models ----------

data class UserInfo(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String,
    @SerializedName("status") val status: String?,
    @SerializedName("exp_date") val expDate: String?,
    @SerializedName("max_connections") val maxConnections: String?,
    @SerializedName("active_cons") val activeCons: String?
)

data class ServerInfo(
    @SerializedName("url") val url: String,
    @SerializedName("port") val port: String,
    @SerializedName("server_protocol") val serverProtocol: String?
)

data class PanelAuth(
    @SerializedName("user_info") val userInfo: UserInfo,
    @SerializedName("server_info") val serverInfo: ServerInfo
)

data class Category(
    @SerializedName("category_id") val id: String,
    @SerializedName("category_name") val name: String,
    @SerializedName("parent_id") val parentId: String? = null
)

data class LiveStream(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String,
    @SerializedName("stream_type") val streamType: String?,
    @SerializedName("stream_id") val streamId: String,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("epg_channel_id") val epgChannelId: String?,
    @SerializedName("added") val added: String?,
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("tv_archive") val tvArchive: Int? = null,
    @SerializedName("tv_archive_duration") val tvArchiveDuration: Int? = null
) {
    var categoryName: String = ""
    /** Index des Providers, von dem dieser Kanal stammt (transient, nicht serialisiert). */
    var providerIndex: Int = 0
    /** true, wenn dieser Kanal Timeshift/Replay unterstützt. */
    fun hasArchive(): Boolean = (tvArchive ?: 0) > 0
    /** Archiv-Dauer in Stunden (Fallback: 6h wenn unbekannt aber tv_archive gesetzt). */
    fun archiveHours(): Int = (tvArchiveDuration ?: 0).coerceAtLeast(if (hasArchive()) 6 else 0)
}

data class EpgEntry(
    @SerializedName("id") val id: String?,
    @SerializedName("epg_id") val epgId: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("lang") val lang: String?,
    @SerializedName("start") val start: String?,
    @SerializedName("end") val end: String?
)

data class ShortEpg(
    @SerializedName("epg_listings") val epgListings: List<EpgEntry>?
)

// ---------- VOD (Movies + Series) models ----------

data class VodStream(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String,
    @SerializedName("stream_type") val streamType: String?,
    @SerializedName("stream_id") val streamId: String,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("rating_5based") val rating5Based: Double?,
    @SerializedName("added") val added: String?,
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("direct_source") val directSource: String?,
    @SerializedName("trailer") val trailer: String? = null,
    @SerializedName("tmdb") val tmdb: String? = null
) {
    var categoryName: String = ""
    fun poster(): String? = streamIcon ?: directSource
    /** true, wenn ein YouTube-Trailer (Video-ID) hinterlegt ist. */
    fun hasTrailer(): Boolean = !trailer.isNullOrBlank()
}

data class SeriesStream(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String,
    @SerializedName("series_id") val seriesId: String,
    @SerializedName("cover") val cover: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("cast") val cast: String?,
    @SerializedName("director") val director: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("releaseDate") val releaseDate: String?,
    @SerializedName("rating") val rating: String?,
    @SerializedName("rating_5based") val rating5Based: Double?,
    @SerializedName("backdrop_path") val backdropPath: List<String>?,
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("episode_run_time") val episodeRunTime: String?
) {
    var categoryName: String = ""
}

data class VodInfo(
    @SerializedName("info") val info: Map<String, Any?>?,
    @SerializedName("movie_data") val movieData: Map<String, Any?>?
)

data class SeriesEpisode(
    @SerializedName("id") val id: String,
    @SerializedName("episode_num") val episodeNum: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("info") val info: Map<String, Any?>?,
    @SerializedName("rating") val rating: String?
)

data class SeriesSeason(
    @SerializedName("season_number") val seasonNumber: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("episodes") val episodes: List<SeriesEpisode>?
)

data class SeriesInfo(
    @SerializedName("seasons") val seasons: List<SeriesSeason>?,
    @SerializedName("info") val info: Map<String, Any?>?,
    @SerializedName("episodes") val episodes: Map<String, List<SeriesEpisode>>?
)

// ---------- API ----------

interface XtreamApi {

    @GET("panel_api.php")
    suspend fun auth(
        @Query("username") user: String,
        @Query("password") pass: String
    ): PanelAuth

    @GET("player_api.php")
    suspend fun authPlayer(
        @Query("username") user: String,
        @Query("password") pass: String
    ): PanelAuth

    @GET("player_api.php")
    suspend fun liveCategories(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_live_categories"
    ): List<Category>

    @GET("player_api.php")
    suspend fun liveStreams(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_live_streams"
    ): List<LiveStream>

    @GET("player_api.php")
    suspend fun shortEpg(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_short_epg",
        @Query("stream_id") streamId: String,
        @Query("limit") limit: String = "2"
    ): ShortEpg

    @GET("player_api.php")
    suspend fun simpleEpg(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_simple_data_table",
        @Query("stream_id") streamId: String
    ): Map<String, Any?>

    @GET("player_api.php")
    suspend fun vodCategories(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_vod_categories"
    ): List<Category>

    @GET("player_api.php")
    suspend fun vodStreams(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_vod_streams",
        @Query("category_id") categoryId: String = ""
    ): List<VodStream>

    @GET("player_api.php")
    suspend fun vodInfo(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_vod_info",
        @Query("vod_id") vodId: String
    ): VodInfo

    @GET("player_api.php")
    suspend fun seriesCategories(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_series_categories"
    ): List<Category>

    @GET("player_api.php")
    suspend fun seriesStreams(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_series",
        @Query("category_id") categoryId: String = ""
    ): List<SeriesStream>

    @GET("player_api.php")
    suspend fun seriesInfo(
        @Query("username") user: String,
        @Query("password") pass: String,
        @Query("action") action: String = "get_series_info",
        @Query("series_id") seriesId: String
    ): SeriesInfo
}
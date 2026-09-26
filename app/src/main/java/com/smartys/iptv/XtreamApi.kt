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
    @SerializedName("tv_archive") val tvArchive: Int? = null
) {
    var categoryName: String = ""
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
}
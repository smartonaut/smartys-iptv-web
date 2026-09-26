package com.smartys.iptv

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

sealed class AppState {
    object NeedsLogin : AppState()
    object Loading : AppState()
    data class Ready(val categories: List<Category>, val channels: List<LiveStream>) : AppState()
    data class Error(val message: String) : AppState()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf<AppState>(AppState.NeedsLogin)
        private set

    var creds by mutableStateOf<CredStore.Creds?>(null)
        private set

    // Favoriten + zuletzt gesehen (simple Persistenz via SharedPreferences reicht für MVP)
    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set
    var lastWatched by mutableStateOf<String?>(null)
        private set
    // Zuletzt-gesehen-Historie: neueste zuerst, max. 10, ohne Duplikate
    var recentIds by mutableStateOf<List<String>>(emptyList())
        private set

    private var api: XtreamApi? = null
    private var channelsCache: List<LiveStream> = emptyList()

    // ---------- EPG-Cache ----------
    // mutableStateMapOf => Compose recomponiert die Zeile automatisch, sobald das EPG ankommt.
    val epgCache = mutableStateMapOf<String, List<EpgEntry>>()
    private val epgInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val epgSemaphore = kotlinx.coroutines.sync.Semaphore(6)

    /** EPG für Kanal holen (Cache-first). Mehrfachaufrufe sind sicher (dedup). */
    fun epgFor(channel: LiveStream) {
        val id = channel.streamId
        if (epgCache.containsKey(id)) return
        if (!epgInFlight.add(id)) return
        val c = creds ?: run { epgInFlight.remove(id); return }
        viewModelScope.launch(Dispatchers.IO) {
            epgSemaphore.withPermit {
                try {
                    val epg = withTimeout(8000) {
                        api?.shortEpg(c.user, c.pass, streamId = id)
                    }
                    // Xtream liefert Titel/Description base64-kodiert
                    val list = (epg?.epgListings.orEmpty()).map { e ->
                        e.copy(
                            title = e.title?.let { decodeB64(it) } ?: e.title,
                            start = e.start?.let { fmtTime(it) },
                            end = e.end?.let { fmtTime(it) }
                        )
                    }
                    epgCache[id] = list
                } catch (e: Exception) {
                    android.util.Log.d("SmartysIPTV", "EPG-Fehler Kanal $id: ${e.message}")
                    epgCache[id] = emptyList() // Fail-soft: nicht dauerhaft neu versuchen
                } finally {
                    epgInFlight.remove(id)
                }
            }
        }
    }

    /** Prefetch für die ersten N Kanäle (nach Login/Kanalliste). */
    fun prefetchEpg(channels: List<LiveStream>, count: Int = 30) {
        channels.take(count).forEach { epgFor(it) }
    }

    // ---------- XMLTV-EPG (Provider-weite EPG-Quelle) ----------

    /** Lädt das XMLTV des Providers (xmltv.php) im Hintergrund. Danach Fallback-EPG (epg.pw). */
    fun loadXmltvEpg() {
        val c = creds ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val url = "${c.url}/xmltv.php?username=${c.user}&password=${c.pass}"
            val ok = XmltvEpg.ensureLoaded(getApplication(), url)
            if (ok) {
                withContextMain { xmltvReady.value = true }
                // Fallback für Kanäle ohne Provider-EPG (Sky Sport Einzelkanäle, DAZN...)
                val fbOk = XmltvEpg.loadFallback(getApplication())
                if (fbOk) {
                    withContextMain { fallbackReady.value = true }
                }
            }
        }
    }

    val xmltvReady = mutableStateOf(false)
    val fallbackReady = mutableStateOf(false)

    /** "Jetzt"-EPG für einen Kanal: Provider-XMLTV zuerst, dann epg.pw-Fallback per Name. */
    fun nowNext(ch: LiveStream): Pair<XmltvProgramme?, XmltvProgramme?> {
        // 1. Provider-EPG per tvg-id
        val (cur, next) = XmltvEpg.getNowAndNext(ch.epgChannelId)
        if (cur != null || next != null) return cur to next
        // 2. Fallback: per normalisiertem Kanalname (nur wenn Kanal keine EPG-ID hat)
        if (ch.epgChannelId.isNullOrBlank()) {
            return XmltvEpg.fallbackNowAndNext(ch.name)
        }
        return null to null
    }

    private fun decodeB64(s: String): String? = try {
        val decoded = android.util.Base64.decode(s.trim(), android.util.Base64.DEFAULT)
        String(decoded, Charsets.UTF_8).trim()
    } catch (e: Exception) { s }

    private fun fmtTime(xt: String): String = try {
        // "2026-09-26 08:55:00" -> "08:55"
        xt.split(" ").getOrNull(1)?.take(5) ?: xt
    } catch (e: Exception) { xt }

    fun login(url: String, user: String, pass: String, onResult: (Boolean, String?) -> Unit) {
        android.util.Log.d("SmartysIPTV", "login() gestartet: url=$url user=$user")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val clean = url.trim().trimEnd('/')
                android.util.Log.d("SmartysIPTV", "baue Retrofit für $clean")
                val retrofit = Retrofit.Builder()
                    .baseUrl("$clean/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                val testApi = retrofit.create(XtreamApi::class.java)

                val auth = withTimeout(12000) { testApi.auth(user, pass) }
                android.util.Log.d("SmartysIPTV", "auth ok: status=${auth.userInfo.status} user=${auth.userInfo.username}")
                if (auth.userInfo.username != user) {
                    withContextMain { onResult(false, "Panel antwortete, aber Login fehlgeschlagen") }
                    return@launch
                }
                val status = auth.userInfo.status
                if (status != null && status.lowercase() !in listOf("active", "trial")) {
                    withContextMain { onResult(false, "Abo-Status: $status") }
                    return@launch
                }

                // OkHttp mit vernünftigen Timeouts fürs Streaming-Setup
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                val fullApi = Retrofit.Builder()
                    .baseUrl("$clean/")
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(XtreamApi::class.java)

                api = fullApi
                CredStore.save(getApplication(), clean, user, pass)
                creds = CredStore.Creds(clean, user, pass)
                loadContent()
                withContextMain { onResult(true, null) }
            } catch (e: Exception) {
                android.util.Log.e("SmartysIPTV", "login fehlgeschlagen", e)
                withContextMain {
                    onResult(false, "Keine Verbindung: ${e.message}")
                }
            }
        }
    }

    fun restoreSession() {
        val c = CredStore.load(getApplication()) ?: run {
            state = AppState.NeedsLogin
            return
        }
        creds = c
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        api = Retrofit.Builder()
            .baseUrl("${c.url}/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(XtreamApi::class.java)
        loadContent()
        favorites = getPrefs().getStringSet("favorites", emptySet()) ?: emptySet()
        lastWatched = getPrefs().getString("last_watched", null)
        loadRecent()
    }

    private fun loadContent() {
        state = AppState.Loading
        val c = creds ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentApi = api ?: return@launch
                val cats = withTimeout(30000) { currentApi.liveCategories(c.user, c.pass) }
                val streams = withTimeout(60000) { currentApi.liveStreams(c.user, c.pass) }

                val catNames = cats.associate { it.id to it.name }
                streams.forEach { it.categoryName = catNames[it.categoryId] ?: "" }

                // Marker/Fake-Einträge rausfiltern (z.B. "##### DE ALLGEMEINES #####")
                val clean = streams.filter { !it.name.contains("####") }
                channelsCache = clean
                withContextMain {
                    state = AppState.Ready(cats, clean)
                }
                // XMLTV-EPG des Providers laden (groß, im Hintergrund; UI kommt sofort)
                loadXmltvEpg()
            } catch (e: Exception) {
                withContextMain {
                    state = AppState.Error("Kanäle laden fehlgeschlagen: ${e.message}")
                }
            }
        }
    }

    fun retry() = loadContent()

    fun toggleFavorite(streamId: String) {
        favorites = if (streamId in favorites) favorites - streamId else favorites + streamId
        getPrefs().edit().putStringSet("favorites", favorites).apply()
    }

    fun markWatched(streamId: String) {
        lastWatched = streamId
        getPrefs().edit().putString("last_watched", streamId).apply()
        recentIds = (listOf(streamId) + recentIds.filter { it != streamId }).take(10)
        getPrefs().edit().putString("recent_ids", recentIds.joinToString(",")).apply()
    }

    private fun loadRecent() {
        recentIds = getPrefs().getString("recent_ids", null)
            ?.split(',')?.filter { it.isNotBlank() }?.take(10) ?: emptyList()
    }

    fun logout(ctx: Context) {
        CredStore.clear(ctx)
        creds = null
        api = null
        state = AppState.NeedsLogin
    }

    // ---------- Backend-Integration (Status, Lizenz, Support) ----------

    /** Fragt den Player-Status ab: (existiert, freigeschaltet). NIEMALS das Passwort. */
    fun fetchPlayerStatus(playerId: String): Pair<Boolean, Boolean>? = runCatching {
        val url = java.net.URL("https://panel.smartys.tech/api/v1/player/info?mac=$playerId")
        val conn = url.openConnection() as javax.net.ssl.HttpsURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        if (conn.responseCode != 200) return@runCatching null
        val body = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
        Pair(body.optBoolean("exists"), body.optBoolean("active"))
    }.getOrNull()

    /**
     * IBPlayer-Flow: Pairing mit panel.smartys.tech (Player registriert sich
     * beim ersten Aufruf selbst), dann zugewiesene Playlist laden und einloggen.
     * Rückgabe true = Kanäle geladen (state -> Ready/Loading), false = noch
     * keine Playlist hinterlegt oder Backend nicht erreichbar.
     */
    fun pairAndLoad(playerId: String, pairingPw: String): Boolean = runCatching {
        val url = java.net.URL("https://panel.smartys.tech/api/v1/pair")
        val conn = url.openConnection() as javax.net.ssl.HttpsURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use {
            it.write("mac=${java.net.URLEncoder.encode(playerId, "UTF-8")}&password=${java.net.URLEncoder.encode(pairingPw, "UTF-8")}".toByteArray())
        }
        if (conn.responseCode != 200) return@runCatching false
        val body = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
        val token = body.getString("token")
        // Provider-Liste des Players abrufen:
        val purl = java.net.URL("https://panel.smartys.tech/api/v1/providers")
        val pconn = purl.openConnection() as javax.net.ssl.HttpsURLConnection
        pconn.setRequestProperty("Authorization", "Bearer $token")
        pconn.connectTimeout = 8000; pconn.readTimeout = 8000
        if (pconn.responseCode != 200) return@runCatching false
        val providers = org.json.JSONArray(pconn.inputStream.bufferedReader().readText())
        if (providers.length() == 0) return@runCatching false
        val p = providers.getJSONObject(0)
        val c = CredStore.Creds(
            p.getString("server_url"),
            p.getString("username"),
            p.getString("password")
        )
        // Speichern + als normale Session weitermachen:
        creds = c
        CredStore.save(getApplication(), c.url, c.user, c.pass)
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        api = Retrofit.Builder()
            .baseUrl("${c.url}/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(XtreamApi::class.java)
        loadContent()
        true
    }.getOrDefault(false)

    /** Sendet Support-Anfrage ans Backend (landet als Mail beim Admin). */
    fun sendSupport(mac: String, message: String): Boolean = runCatching {
        val payload = org.json.JSONObject()
            .put("mac", mac)
            .put("message", message)
            .put("app_version", "0.2")
        val conn = java.net.URL("https://panel.smartys.tech/api/v1/support")
            .openConnection() as javax.net.ssl.HttpsURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        conn.responseCode == 200
    }.getOrDefault(false)

    /** Leert den EPG-Speicher (nach Cache-Löschung aus den Einstellungen). */
    fun clearEpg() {
        xmltvReady.value = false
        XmltvEpg.clearCache()
    }

    private fun getPrefs() =
        getApplication<Application>().getSharedPreferences("smartys_iptv_state", Context.MODE_PRIVATE)

    private suspend fun <T> withContextMain(block: () -> T) =
        kotlinx.coroutines.withContext(Dispatchers.Main) { block() }
}
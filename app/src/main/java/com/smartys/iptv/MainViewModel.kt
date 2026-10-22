package com.smartys.iptv

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
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

    // ---------- VOD (Movies + Series) ----------
    var vodCategories by mutableStateOf<List<Category>>(emptyList())
        private set
    var vodMovies by mutableStateOf<List<VodStream>>(emptyList())
        private set
    var seriesCategories by mutableStateOf<List<Category>>(emptyList())
        private set
    var seriesShows by mutableStateOf<List<SeriesStream>>(emptyList())
        private set
    var vodLoading by mutableStateOf(false)
        private set
    var vodError by mutableStateOf<String?>(null)
        private set

    /** Lädt VOD-Kategorien + Filme (einmalig, nach Login). */
    fun loadVod() {
        val c = creds ?: return
        val a = api ?: return
        if (vodLoading) return
        vodLoading = true; vodError = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cats = withTimeout(30000) { a.vodCategories(c.user, c.pass) }
                val movies = withTimeout(60000) { a.vodStreams(c.user, c.pass) }
                val catNames = cats.associate { it.id to it.name }
                movies.forEach { it.categoryName = catNames[it.categoryId] ?: "" }
                withContextMain {
                    vodCategories = cats
                    vodMovies = movies
                }
            } catch (e: Exception) {
                withContextMain { vodError = "VOD laden fehlgeschlagen: ${e.message}" }
            } finally {
                withContextMain { vodLoading = false }
            }
        }
    }

    /** Lädt Serien-Kategorien + Serien (einmalig, nach Login). */
    fun loadSeries() {
        val c = creds ?: return
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cats = withTimeout(30000) { a.seriesCategories(c.user, c.pass) }
                val shows = withTimeout(60000) { a.seriesStreams(c.user, c.pass) }
                val catNames = cats.associate { it.id to it.name }
                shows.forEach { it.categoryName = catNames[it.categoryId] ?: "" }
                withContextMain {
                    seriesCategories = cats
                    seriesShows = shows
                }
            } catch (e: Exception) {
                android.util.Log.d("SmartysIPTV", "Serien laden fehlgeschlagen: ${e.message}")
            }
        }
    }

    /** Serien-Episoden (Staffeln) einer Serie laden. */
    suspend fun fetchSeriesInfo(seriesId: String): SeriesInfo? = runCatching {
        val c = creds ?: return@runCatching null
        val a = api ?: return@runCatching null
        withTimeout(15000) { a.seriesInfo(c.user, c.pass, seriesId = seriesId) }
    }.getOrNull()

    private var api: XtreamApi? = null
    private var channelsCache: List<LiveStream> = emptyList()

    // ---------- Multi-Provider ----------
    // alle gespeicherten Provider (Index-aligned mit `apis`); `creds` bleibt primary (erster).
    var allProviders by mutableStateOf<List<CredStore.Creds>>(emptyList())
        private set
    private var apis: List<XtreamApi> = emptyList()

    // ---------- Sicherheits-Passwort (Line im Panel verschlüsselt) ----------
    // Panel liefert encrypted:true + salt + cipher_*-Felder statt Klartext.
    // Dann: UI fragt das Sicherheits-Passwort ab, unlockLine() entschlüsselt
    // und legt plain in RAM (Session) + auf der Platte (CredStore =
    // EncryptedSharedPreferences) ab — ab dem nächsten Start kein Passwort
    // mehr nötig. Gleicher Workflow wie Desktop + Enigma.
    var unlockNeeded by mutableStateOf(false)
        private set
    var unlockError by mutableStateOf("")
    private var pendingSaltB64 = ""
    private var pendingIterations = 310_000
    private var pendingCiphers: List<SecCrypto.SecProvider> = emptyList()
    private var pendingPlayerId = ""

    /** Stream-URL eines Kanals über SEINEN Provider (statt immer den ersten). */
    fun streamUrlFor(channel: LiveStream): String? {
        val c = allProviders.getOrNull(channel.providerIndex) ?: creds ?: return null
        return c.streamUrl(channel.streamId)
    }

    /** MPEG-TS-Stream-URL für die Aufnahme (Xtream liefert Live-TV auch als rohes .ts). */
    fun tsUrlFor(channel: LiveStream): String? {
        val c = allProviders.getOrNull(channel.providerIndex) ?: creds ?: return null
        return c.streamUrl(channel.streamId, "ts")
    }

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
                CredStore.saveAll(getApplication(), listOf(CredStore.Creds(clean, user, pass)))
                creds = CredStore.Creds(clean, user, pass)
                allProviders = listOf(creds!!)
                apis = listOf(fullApi)
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
        val providers = CredStore.loadAll(getApplication())
        if (providers.isEmpty()) {
            state = AppState.NeedsLogin
            return
        }
        allProviders = providers
        apis = providers.map { buildApi(it) }
        // Profil-Bindung: die zugeordnete Line ist die primaere.
        val bound = boundProviderIndex() ?: 0
        creds = providers.getOrNull(bound) ?: providers.first()
        api = apis.getOrNull(bound) ?: apis.firstOrNull()
        loadContent()
        favorites = getPrefs().getStringSet("favorites", emptySet()) ?: emptySet()
        lastWatched = getPrefs().getString("last_watched", null)
        loadRecent()
    }

    /** Pairing-/Unlock-Screen anzeigen (z. B. wenn neu geladene Lines
     *  verschluesselt sind und das Sicherheits-Passwort fehlt). */
    fun requestPairingScreen() {
        state = AppState.NeedsLogin
    }

    // ---------- Profil -> Panel-Line ----------

    /** Index der Line, die am aktiven Profil haengt (null = nicht gebunden). */
    fun boundProviderIndex(): Int? {
        val pid = ProfileStore.activeId(getApplication()) ?: return null
        val key = ProfileStore.lineKey(getApplication(), pid) ?: return null
        val idx = allProviders.indexOfFirst { it.key == key }
        return if (idx >= 0) idx else null
    }

    /** Anzeigename einer Line fuer die Auswahl. */
    private fun lineLabel(c: CredStore.Creds, idx: Int): String {
        val host = c.url.removePrefix("http://").removePrefix("https://").trimEnd('/')
        return if (c.user.isBlank()) "Line ${idx + 1} · $host"
        else "Line ${idx + 1} · $host (${c.user})"
    }

    /** Alle Lines fuer die Auswahl in den Einstellungen. */
    fun lines(): List<Pair<Int, String>> = allProviders.mapIndexed { i, c -> i to lineLabel(c, i) }

    /** Name der Line, die an einem Profil haengt (null = "Automatisch"). */
    fun lineLabelFor(profileId: String): String? {
        val key = ProfileStore.lineKey(getApplication(), profileId) ?: return null
        val idx = allProviders.indexOfFirst { it.key == key }
        if (idx < 0) return null
        return lineLabel(allProviders[idx], idx)
    }

    /** Profil an eine Line binden (index = null -> "Automatisch"). */
    fun setProfileLine(profileId: String, index: Int?) {
        ProfileStore.setLineKey(
            getApplication(), profileId,
            index?.let { allProviders.getOrNull(it)?.key }
        )
        if (profileId == ProfileStore.activeId(getApplication())) applyActiveLine()
    }

    /** Aktive Line aus der Profil-Bindung setzen und Inhalte neu laden. */
    fun applyActiveLine() {
        val idx = boundProviderIndex() ?: 0
        val p = allProviders.getOrNull(idx) ?: return
        val changed = creds?.key != p.key
        creds = p
        api = apis.getOrNull(idx) ?: apis.firstOrNull()
        loadContent()
        loadVod()
        loadSeries()
        android.util.Log.i("SmartysIPTV", "Profil-Line aktiv: $idx (${lineLabel(p, idx)}) gewechselt=$changed")
    }

    private fun buildApi(c: CredStore.Creds): XtreamApi {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl("${c.url}/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(XtreamApi::class.java)
    }

    private fun loadContent() {
        state = AppState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val providers = allProviders
                if (providers.isEmpty()) return@launch
                val all = mutableListOf<LiveStream>()
                val allCats = mutableListOf<Category>()
                // Ist das Profil an eine Line gebunden, nur diese laden.
                val boundIdx = boundProviderIndex()
                providers.forEachIndexed { idx, c ->
                    if (boundIdx != null && idx != boundIdx) return@forEachIndexed
                    val a = apis.getOrNull(idx) ?: return@forEachIndexed
                    val cats = withTimeout(30000) { a.liveCategories(c.user, c.pass) }
                    val streams = withTimeout(60000) { a.liveStreams(c.user, c.pass) }
                    val catNames = cats.associate { it.id to it.name }
                    streams.forEach {
                        it.categoryName = catNames[it.categoryId] ?: ""
                        it.providerIndex = idx
                    }
                    all.addAll(streams)
                    allCats.addAll(cats)
                }

                // Marker/Fake-Einträge rausfiltern (z.B. "##### DE ALLGEMEINES #####")
                val clean = all.filter { !it.name.contains("####") }
                channelsCache = clean
                withContextMain {
                    state = AppState.Ready(allCats, clean)
                }
                // XMLTV-EPG des (ersten) Providers laden
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

    /** Liest profilgebundene Favoriten/Zuletzt-gesehen nach einem Profilwechsel neu. */
    fun reloadProfileState() {
        favorites = getPrefs().getStringSet("favorites", emptySet()) ?: emptySet()
        lastWatched = getPrefs().getString("last_watched", null)
        loadRecent()
    }

    fun logout(ctx: Context) {
        CredStore.clear(ctx)
        creds = null
        api = null
        allProviders = emptyList()
        apis = emptyList()
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
        val respBody = org.json.JSONObject(pconn.inputStream.bufferedReader().readText())
        // VERSCHLÜSSELTE Line (Panel-Workflow, wie Desktop/Enigma):
        // {encrypted:true, name, salt, iterations, kdf, providers:[
        //   {id,label, cipher_server_url, cipher_username, cipher_password, cipher_epg_url}]}
        // => kein Klartext abrufbar; UI fragt das Sicherheits-Passwort ab.
        if (respBody.optBoolean("encrypted")) {
            val arrEnc = respBody.optJSONArray("providers") ?: org.json.JSONArray()
            pendingPlayerId = playerId
            pendingSaltB64 = respBody.optString("salt")
            pendingIterations = respBody.optInt("iterations", 310_000)
            pendingCiphers = (0 until arrEnc.length()).map { i ->
                val p = arrEnc.getJSONObject(i)
                SecCrypto.SecProvider(
                    label = p.optString("label"),
                    cipherServerUrl = p.optString("cipher_server_url"),
                    cipherUsername = p.optString("cipher_username"),
                    cipherPassword = p.optString("cipher_password")
                )
            }.filter { it.cipherServerUrl.isNotBlank() }
            unlockError = ""
            unlockNeeded = true
            android.util.Log.d("SmartysIPTV", "panel: verschlüsselte Line empfangen (${pendingCiphers.size} Provider) — Sicherheits-Passwort nötig")
            return@runCatching false   // noch nicht fertig: UI fragt Passwort ab
        }
        // Backend liefert {name, providers:[{id,label,server_url,username,password,...}]}
        val providersArr = respBody.optJSONArray("providers") ?: org.json.JSONArray()
        if (providersArr.length() == 0) return@runCatching false
        val providers = (0 until providersArr.length()).map { i ->
            val p = providersArr.getJSONObject(i)
            CredStore.Creds(
                p.optString("server_url"),
                p.optString("username"),
                p.optString("password")
            )
        }.filter { it.url.isNotBlank() && it.user.isNotBlank() }
        if (providers.isEmpty()) return@runCatching false
        unlockNeeded = false
        unlockError = ""
        // Speichern + als normale Session weitermachen:
        CredStore.saveAll(getApplication(), providers)
        creds = providers.first()
        allProviders = providers
        apis = providers.map { buildApi(it) }
        api = apis.firstOrNull()
        loadContent()
        true
    }.getOrDefault(false)

    /**
     * Entschlüsselt die ausstehende Panel-Line mit dem Sicherheits-Passwort
     * (PBKDF2-HMAC-SHA256 + AES-256-GCM, AAD = "smartys-iptv-sec:"+Player-ID;
     * identisch zu backend/crypto.py, Desktop-SecCrypto, Enigma-crypto.py).
     * Erfolg -> plain in RAM (Session) + auf Platte (CredStore/EncryptedPrefs),
     * d. h. beim nächsten Start läuft die App OHNE Passwort (wie Desktop/Enigma).
     * Falsches Passwort -> false + unlockError, Line bleibt verschlüsselt.
     */
    fun unlockLine(password: String): Boolean {
        if (pendingSaltB64.isEmpty()) return false
        val provs = try {
            val key = SecCrypto.deriveKey(password, pendingSaltB64, pendingIterations)
            pendingCiphers.mapNotNull { p ->
                val url = SecCrypto.decryptField(key, p.cipherServerUrl, pendingPlayerId)
                val user = SecCrypto.decryptField(key, p.cipherUsername, pendingPlayerId)
                val pass = SecCrypto.decryptField(key, p.cipherPassword, pendingPlayerId)
                if (url.isNotBlank() && user.isNotBlank()) CredStore.Creds(url, user, pass) else null
            }
        } catch (e: Exception) {
            android.util.Log.w("SmartysIPTV", "unlockLine: Entschluesselung fehlgeschlagen", e)
            emptyList()
        }
        if (provs.isEmpty()) {
            unlockError = "❌ Passwort falsch — die Line bleibt verschlüsselt."
            return false
        }
        // RAM: Session setzen, damit Kanäle/Streams sofort geladen werden
        creds = provs.first()
        allProviders = provs
        apis = provs.map { buildApi(it) }
        api = apis.firstOrNull()
        // Platte: plain in den Encrypted-Prefs — nächster Start ohne Passwort
        CredStore.saveAll(getApplication(), provs)
        android.util.Log.d("SmartysIPTV", "Line entschlüsselt: ${provs.size} Provider, plain gespeichert")
        unlockError = ""
        pendingSaltB64 = ""
        pendingCiphers = emptyList()
        loadContent()
        return true
    }

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

    private fun getPrefs(): SharedPreferences {
        val app = getApplication<Application>()
        val pid = ProfileStore.activeId(app) ?: "default"
        return app.getSharedPreferences("smartys_iptv_state__$pid", Context.MODE_PRIVATE)
    }

    private suspend fun <T> withContextMain(block: () -> T) =
        kotlinx.coroutines.withContext(Dispatchers.Main) { block() }
}
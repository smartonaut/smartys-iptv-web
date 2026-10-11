package com.smartys.iptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.set(this)

        // Debug/Auto-Login per Start-Extra (z.B. via adb):
        // am start -n com.smartys.iptv/.MainActivity --es url http://tv.smarty.tips --es user X --es pass Y
        val autoUrl = intent.getStringExtra("url")
        val autoUser = intent.getStringExtra("user")
        val autoPass = intent.getStringExtra("pass")

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ScaledUi {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        App(autoUrl = autoUrl, autoUser = autoUser, autoPass = autoPass)
                    }
                }
            }
        }
    }
}

/**
 * Dynamische Bildschirmanpassung: Die UI ist auf Fire-TV kalibriert
 * (1920x1080 @ 320dpi => 540 dp kurze Seite). Auf kleineren Screens
 * (z.B. Handy im Querformat => 360 dp) werden Cover, Buttons und Layout
 * (dp) proportional verkleinert. Texte (sp) behalten ihre Größe, damit
 * Menüs lesbar bleiben. Fire-TV bleibt unveraendert (Faktor 1.0).
 */
@Composable
fun ScaledUi(content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val base = LocalDensity.current
    val shortSide = minOf(config.screenWidthDp, config.screenHeightDp).toFloat()
    val scale = (shortSide / 540f).coerceIn(0.5f, 1f)
    // NUR density skalieren (dp: Cover/Buttons/Layout). fontScale gegenläufig,
    // damit sp-Texte ihre Größe BEHALTEN — sp hängt von density UND fontScale
    // ab, sonst würde der Text doppelt verkleinert und unlesbar.
    // Auf kleinen Screens zusätzlich ein leichtes Absenken (0.9), damit der
    // Text etwas kompakter wird — auf Fire-TV (scale 1.0) bleibt er unverändert.
    val textFactor = if (scale < 1f) 0.9f else 1f
    val scaled = Density(base.density * scale, (base.fontScale / scale) * textFactor)
    CompositionLocalProvider(LocalDensity provides scaled) {
        content()
    }
}

@Composable
fun App(vm: MainViewModel = viewModel(), autoUrl: String? = null, autoUser: String? = null, autoPass: String? = null) {
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    val player = remember { mutableStateOf<RobustLivePlayer?>(null) }
    val playing = remember { mutableStateOf<LiveStream?>(null) }
    val vodPlaying = remember { mutableStateOf<VodPlayback?>(null) }
    // Serien-Warteschlange + Index für Auto-Advance (nächste Folge beim Abspann)
    var seriesQueue by remember { mutableStateOf<List<VodPlayback>?>(null) }
    var seriesIndex by remember { mutableStateOf(0) }
    // advanceSuppressed: Nutzer hat den Abspann-Wechsel abgebrochen => kein Auto-Advance
    var advanceSuppressed by remember { mutableStateOf(false) }
    // advancing: Re-Entry-Schutz (verhindert Doppel-Wechsel)
    var advancing by remember { mutableStateOf(false) }
    // Next-Episode-Overlay (Netflix-Stil): sichtbar + Countdown-Wert
    var nextPending by remember { mutableStateOf(false) }
    var nextTitle by remember { mutableStateOf<String?>(null) }
    var nextCountdown by remember { mutableStateOf(5) }
    val openSeries = remember { mutableStateOf<SeriesStream?>(null) }
    val openMovie = remember { mutableStateOf<VodStream?>(null) }
    // VOD-Kategorie-Wahl hier in App() heben: MovieGrid/SeriesGrid werden beim
    // Oeffnen einer Detailseite aus dem Baum ENTFERNT (when-Zweig) — ein Zustand
    // innerhalb der Grids ueberlebt das nicht. Von hier aus ueberlebt er alles.
    val movieCat = remember { mutableStateOf<String?>(null) }
    val seriesCat = remember { mutableStateOf<String?>(null) }
    // Zuletzt geöffneter Film/Serie: beim Ruecksprung aus der Detailseite den
    // Grid-Fokus dorthin restaurieren (statt oben links).
    val lastMovieId = remember { mutableStateOf<String?>(null) }
    val lastSeriesId = remember { mutableStateOf<String?>(null) }
    // Parental Control: PIN-Gate beim Start
    var pinUnlocked by remember { mutableStateOf(!ParentalControl.isEnabled(appCtx)) }
    // Profil-Auswahl: aktives Profil (null = Picker anzeigen). Beim Start default anlegen.
    var activeProfile by remember { mutableStateOf<Profile?>(null) }
    var profilesReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Beim App-Start direkt das aktive (Standard-)Profil wählen — kein Picker.
        // Profilwechsel bleibt über "Einstellungen -> Profil wechseln" möglich.
        activeProfile = ProfileStore.ensureDefault(appCtx)
        VodDownloader.load(appCtx)
        profilesReady = true
    }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    // Haupt-Tab: live | movies | series
    var mainTab by remember { mutableStateOf("live") }
    var showEpg by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }

    // Position des laufenden VOD-Titels sichern und Playback beenden (Fortsetzen-Feature).
    // Zentral für beide Wege: physische Back-Taste UND "←"-Button im Player.
    fun stopVod() {
        val vod = vodPlaying.value
        val exo = player.value?.player
        if (vod != null && exo != null) {
            val pos = exo.currentPosition
            if (pos > 0L) ResumeStore.save(appCtx, vod.key, pos, exo.duration)
        }
        vodPlaying.value = null
        seriesQueue = null
        nextPending = false
        player.value?.stop()
    }

    // Auto-Save der VOD-Position: alle 2 Minuten sichern, damit auch ein
    // unsauberes Verlassen (Fehldruck, Home-Taste, App-Kill) fortsetzbar bleibt.
    // Läuft nur solange ein VOD-Titel aktiv ist.
    LaunchedEffect(vodPlaying.value) {
        if (vodPlaying.value == null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(120_000L)
            val vod = vodPlaying.value ?: break
            val exo = player.value?.player
            if (exo != null) {
                val pos = exo.currentPosition
                if (pos > 0L) ResumeStore.save(appCtx, vod.key, pos, exo.duration)
            }
        }
    }

    // Zusätzlich: Position beim Verlassen in den Hintergrund sichern (Home-Taste etc.).
    DisposableEffect(appCtx) {
        val owner = appCtx as? androidx.lifecycle.LifecycleOwner
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                val vod = vodPlaying.value
                val exo = player.value?.player
                if (vod != null && exo != null) {
                    val pos = exo.currentPosition
                    if (pos > 0L) ResumeStore.save(appCtx, vod.key, pos, exo.duration)
                }
            }
        }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs) }
    }

    // Globale Back-Navigation: Sub-Screens zuerst schließen, dann Tab zurueck,
    // erst auf Live-TV-Ebene doppelt-Back => "App wirklich schließen?"-Dialog.
    androidx.activity.compose.BackHandler(enabled = true) {
        when {
            showSettings -> showSettings = false
            showEpg -> showEpg = false
            vodPlaying.value != null -> { stopVod() }
            playing.value != null -> { playing.value = null; player.value?.stop() }
            openMovie.value != null -> openMovie.value = null
            openSeries.value != null -> openSeries.value = null
            mainTab != "live" -> mainTab = "live"
            showExitDialog -> { showExitDialog = false }
            else -> showExitDialog = true
        }
    }

    LaunchedEffect(Unit) {
        val ctx = appCtx
        player.value = RobustLivePlayer(ctx)
        if (autoUrl != null && autoUser != null && autoPass != null) {
            vm.login(autoUrl, autoUser, autoPass) { _, _ -> }
        } else {
            vm.restoreSession()
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.value?.release() }
    }

    // VOD + Serien nach erfolgreichem Login laden
    LaunchedEffect(vm.state) {
        if (vm.state is AppState.Ready) {
            vm.loadVod()
            vm.loadSeries()
        }
    }

    val play: (LiveStream) -> Unit = { channel ->
        val url = vm.streamUrlFor(channel)
        if (url != null) {
            playing.value = channel
            vm.markWatched(channel.streamId)
            player.value?.playChannel(url, channel.streamId)
        }
    }

    val playVod: (VodPlayback) -> Unit = { vod ->
        vodPlaying.value = vod
        seriesQueue = null
        nextPending = false
        advanceSuppressed = false
        advancing = false
        // Fortsetzen: gespeicherte Position beim Start wiederherstellen.
        val resume = if (vod.startPosMs > 0L) vod.startPosMs else ResumeStore.position(appCtx, vod.key)
        player.value?.playChannel(vod.url, "vod:${vod.title}", startPosMs = resume)
    }

    // Nächste Episode aus der Queue, oder null wenn keine mehr folgt.
    fun nextEpisode(): VodPlayback? = seriesQueue?.getOrNull(seriesIndex + 1)

    // Queue beenden und zurück (letzte Folge erreicht oder abgebrochen).
    fun endSeriesPlayback() {
        seriesQueue = null
        nextPending = false
        vodPlaying.value = null
        player.value?.stop()
    }

    // Abspann erkannt ODER Episode zu Ende: Overlay "Nächste Folge in 5s" zeigen.
    fun showNextOverlay() {
        val next = nextEpisode() ?: return
        if (advanceSuppressed || advancing) return
        nextTitle = next.title
        nextPending = true
    }

    // Wechsel zur nächsten Folge (nach Countdown-Ablauf).
    fun advanceNext() {
        if (advancing) return
        val next = nextEpisode() ?: run { endSeriesPlayback(); return }
        advancing = true
        nextPending = false
        seriesIndex += 1
        val resume = ResumeStore.position(appCtx, next.key)
        vodPlaying.value = next
        player.value?.playChannel(next.url, "vod:${next.title}", startPosMs = resume)
        advancing = false
    }

    // Nutzer bricht den Wechsel ab: Credits laufen weiter, kein Auto-Advance.
    fun cancelNext() {
        advanceSuppressed = true
        nextPending = false
    }

    // Eine Episode starten. onVodEnded = Fallback, falls kein Abspann erkannt wurde.
    fun startEpisode(ep: VodPlayback, resumeMs: Long = 0L) {
        vodPlaying.value = ep
        player.value?.playChannel(ep.url, "vod:${ep.title}", startPosMs = resumeMs) {
            if (advanceSuppressed) {
                endSeriesPlayback()
            } else if (nextEpisode() != null) {
                advanceNext()
            } else {
                endSeriesPlayback()
            }
        }
    }

    // Serien-Warteschlange starten: Folge `index`, danach automatisch weiter.
    val playSeries: (List<VodPlayback>, Int) -> Unit = { list, index ->
        if (list.isNotEmpty()) {
            seriesQueue = list
            seriesIndex = index.coerceIn(0, list.size - 1)
            advanceSuppressed = false
            advancing = false
            nextPending = false
            val ep = seriesQueue!![seriesIndex]
            val resume = ResumeStore.position(appCtx, ep.key)
            startEpisode(ep, resume)
        }
    }

    // Countdown fürs Next-Episode-Overlay: bei 0 => zur nächsten Folge.
    LaunchedEffect(nextPending) {
        if (!nextPending) return@LaunchedEffect
        var c = 5
        nextCountdown = c
        while (c > 0 && nextPending) {
            kotlinx.coroutines.delay(1000)
            c--
            nextCountdown = c
        }
        if (nextPending) advanceNext()
    }

    if (!pinUnlocked) {
        PinGate(onUnlocked = { pinUnlocked = true })
    } else when (val s = vm.state) {
        is AppState.NeedsLogin -> PairingScreen(vm)
        is AppState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is AppState.Error -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Fehler: ${s.message}", color = MaterialTheme.colorScheme.error)
            Button(onClick = { vm.retry() }, Modifier.padding(top = 16.dp)) { Text("Erneut versuchen") }
        }
        is AppState.Ready -> {
            // Profil-Picker erst NACH erfolgreichem Login/Pairing (nie davor!).
            // Reihenfolge: PinGate -> Pairing/Loading/Error -> erst bei Ready der Picker.
            if (!profilesReady) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFFFFC94D)) }
            } else if (activeProfile == null) {
                ProfilePickerScreen(onSelected = { p ->
                    ProfileStore.setActive(appCtx, p.id)
                    activeProfile = p
                    vm.reloadProfileState()
                })
            } else {
                val cur = playing.value
                val vodCur = vodPlaying.value
                val seriesCur = openSeries.value
                val movieCur = openMovie.value
                when {
                    showSettings -> SettingsScreen(
                        vm = vm,
                        onClose = { showSettings = false },
                        onSwitchProfile = {
                            showSettings = false
                            activeProfile = null  // => Profil-Picker anzeigen
                        }
                    )
                    showEpg -> EpgGrid(
                        vm = vm, channels = s.channels,
                        onPlay = play,
                        onBack = { showEpg = false }
                    )
                    cur != null -> PlayerScreen(
                        vm = vm, player = player.value?.player, channel = cur,
                        channels = s.channels,
                        onPlay = play,
                        onBack = { playing.value = null; player.value?.stop() }
                    )
                    vodCur != null -> VodPlayerScreen(
                        vod = vodCur,
                        player = player.value?.player,
                        hasNextEpisode = nextEpisode() != null,
                        nextTitle = nextTitle,
                        nextPending = nextPending,
                        nextCountdown = nextCountdown,
                        onCreditsDetected = { showNextOverlay() },
                        onCancelNext = { cancelNext() },
                        onAdvanceNow = { advanceNext() },
                        onBack = { stopVod() }
                    )
                    movieCur != null -> MovieDetail(
                        vm = vm, movie = movieCur,
                        onPlay = playVod,
                        onBack = { openMovie.value = null }
                    )
                    seriesCur != null -> SeriesDetail(
                        vm = vm, show = seriesCur,
                        onPlaySeries = playSeries,
                        onBack = { openSeries.value = null }
                    )
                    else -> {
                        Column(Modifier.fillMaxSize()) {
                            MainTabs(current = mainTab, onChange = { mainTab = it })
                            when (mainTab) {
                                "movies" -> MovieGrid(
                                    vm = vm, categories = vm.vodCategories, movies = vm.vodMovies,
                                    selectedCategory = movieCat.value,
                                    onSelectCategory = { movieCat.value = it },
                                    onOpen = { lastMovieId.value = it.streamId; openMovie.value = it },
                                    focusRestoreId = if (openMovie.value == null) lastMovieId.value else null,
                                    onBack = { mainTab = "live" },
                                    onOpenSettings = { showSettings = true }
                                )
                                "series" -> SeriesGrid(
                                    vm = vm, categories = vm.seriesCategories, shows = vm.seriesShows,
                                    selectedCategory = seriesCat.value,
                                    onSelectCategory = { seriesCat.value = it },
                                    onOpen = { lastSeriesId.value = it.seriesId; openSeries.value = it },
                                    focusRestoreId = if (openSeries.value == null) lastSeriesId.value else null,
                                    onBack = { mainTab = "live" },
                                    onOpenSettings = { showSettings = true }
                                )
                                "downloads" -> DownloadsScreen(
                                    appCtx = appCtx,
                                    onPlay = playVod,
                                    onBack = { mainTab = "live" }
                                )
                                else -> ChannelGrid(
                                    vm = vm,
                                    categories = s.categories,
                                    channels = s.channels,
                                    searchQuery = searchQuery,
                                    selectedCategory = selectedCategory,
                                    onSearch = { searchQuery = it },
                                    onSelectCategory = { selectedCategory = it },
                                    onPlay = play,
                                    onOpenSettings = { showSettings = true },
                                    onOpenEpg = { showEpg = true }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Exit-Bestaetigung: erst beim ZWEITEN Back auf Live-TV-Ebene gefragt
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("App schließen?", color = Color(0xFFE8EEFC), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            text = { Text("Möchtest du Smartys IPTV wirklich beenden?", color = Color(0xFF8FA3C8), fontSize = 15.sp) },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    (appCtx as? android.app.Activity)?.finish()
                }) { Text("Beenden", color = Color(0xFFFFC94D), fontSize = 15.sp) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("Weiter schauen", color = Color(0xFF6B7A99), fontSize = 15.sp) }
            },
            containerColor = Color(0xFF10141F)
        )
    }
}

@Composable
fun MainTabs(current: String, onChange: (String) -> Unit) {
    val tabs = listOf("live" to "Live-TV", "movies" to "Filme", "series" to "Serien", "downloads" to "Downloads")
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF0B0E14)).padding(horizontal = 32.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        tabs.forEach { (key, label) ->
            val active = current == key
            var focused by remember { mutableStateOf(false) }
            Surface(
                onClick = { onChange(key) },
                shape = RoundedCornerShape(10.dp),
                color = if (active) Color(0xFFFFC94D) else Color(0xFF11172A),
                border = androidx.compose.foundation.BorderStroke(
                    if (focused || active) 2.dp else 1.dp,
                    when {
                        focused -> Color(0xFFFFC94D)
                        active -> Color(0xFFFFC94D)
                        else -> Color(0xFF232C42)
                    }
                ),
                modifier = Modifier
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
            ) {
                Text(
                    label,
                    color = when {
                        active -> Color(0xFF0B0E14)
                        focused -> Color(0xFFE8EEFC)
                        else -> Color(0xFF8FA3C8)
                    },
                    fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 9.dp)
                )
            }
        }
    }
}

@Composable
fun VodPlayerScreen(
    vod: VodPlayback,
    player: ExoPlayer?,
    hasNextEpisode: Boolean,
    nextTitle: String?,
    nextPending: Boolean,
    nextCountdown: Int,
    onCreditsDetected: () -> Unit,
    onCancelNext: () -> Unit,
    onAdvanceNow: () -> Unit,
    onBack: () -> Unit
) {
    var showOsd by remember { mutableStateOf(false) }
    var osdMsg by remember { mutableStateOf("") }
    var textureView by remember { mutableStateOf<android.view.TextureView?>(null) }

    // OSD nach kurzer Zeit automatisch ausblenden.
    LaunchedEffect(showOsd) {
        if (showOsd) {
            kotlinx.coroutines.delay(2500)
            showOsd = false
        }
    }

    fun fmt(ms: Long): String {
        if (ms <= 0L) return "--:--"
        val s = ms / 1000
        return String.format("%d:%02d", s / 60, s % 60)
    }

    // Vor-/Zurückspulen: 10s pro Tastendruck, begrenzt auf 0..Dauer.
    fun seek(deltaMs: Long) {
        val exo = player ?: return
        val dur = exo.duration
        val max = if (dur > 0L) dur else Long.MAX_VALUE
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, max)
        exo.seekTo(target)
        osdMsg = (if (deltaMs >= 0L) "+" else "") + (deltaMs / 1000) + "s"
        showOsd = true
    }

    // Abspann-Erkennung (nur bei Serien mit Folge danach). Läuft in den letzten
    // 3 Minuten, sampelt sparsam; erkennt dunkle + ruhige Frames => onCreditsDetected.
    LaunchedEffect(textureView, hasNextEpisode, vod.key) {
        if (!hasNextEpisode) return@LaunchedEffect
        val tv = textureView ?: return@LaunchedEffect
        val exo = player ?: return@LaunchedEffect
        val watcher = CreditsWatcher(tv, exo, onCreditsDetected)
        try {
            watcher.run()
        } finally {
            watcher.stop()
        }
    }

    // Beim Verlassen die TextureView-Bindung lösen.
    DisposableEffect(Unit) {
        onDispose {
            textureView?.let { player?.clearVideoTextureView(it) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (keyEvent.key) {
                    Key.Back, Key.Escape -> {
                        if (nextPending) onCancelNext() else onBack()
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (nextPending) { onAdvanceNow(); true }
                        else false
                    }
                    Key.DirectionLeft, Key.MediaRewind -> { if (!nextPending) seek(-10_000L); true }
                    Key.DirectionRight, Key.MediaFastForward -> { if (!nextPending) seek(10_000L); true }
                    Key.MediaPlayPause -> {
                        if (!nextPending) {
                            val exo = player
                            if (exo != null) {
                                if (exo.isPlaying) { exo.pause(); osdMsg = "Pause" }
                                else { exo.play(); osdMsg = "▶" }
                                showOsd = true
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        // Nur wenn Credits-Erkennung gebraucht wird (Serien-Folge mit Nachfolger),
        // eine TextureView verwenden. Sonst normale PlayerView (SurfaceView) = null
        // Zusatz-Overhead, wichtig für schwache Geräte (Fire TV Stick).
        if (hasNextEpisode) {
            AndroidView(
                factory = { ctx ->
                    android.view.TextureView(ctx).also { tv ->
                        textureView = tv
                        player?.setVideoTextureView(tv)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply { this.player = player; useController = false }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Text("← ${vod.title}", color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showOsd) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(28.dp)
                    .width(420.dp)
                    .background(Color(0x660B0E14), MaterialTheme.shapes.large)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(vod.title, color = Color(0xFFE8EEFC), fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (osdMsg.isNotBlank()) {
                        Text(
                            osdMsg, color = Color(0xFF0B0E14), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.background(Color(0xFFFFC94D), MaterialTheme.shapes.extraLarge).padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        "${fmt(player?.currentPosition ?: 0L)} / ${fmt(player?.duration ?: 0L)}",
                        color = Color(0xFF8FA3C8), fontSize = 15.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ---- Next-Episode-Overlay (Abspann erkannt): Countdown + Abbrechen ----
        if (nextPending) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(28.dp)
                    .width(640.dp)
                    .background(Color(0xE60B0E14), MaterialTheme.shapes.large)
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Nächste Folge in $nextCountdown s",
                    color = Color(0xFFFFC94D), fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                nextTitle?.let {
                    Text(it, color = Color(0xFFE8EEFC), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(14.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        onClick = onAdvanceNow,
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFFFC94D)
                    ) {
                        Text(
                            "▶ Jetzt starten",
                            color = Color(0xFF0B0E14), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                    Surface(
                        onClick = onCancelNext,
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF141A2A),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A4666))
                    ) {
                        Text(
                            "Abbrechen",
                            color = Color(0xFFE8EEFC), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

// ---------- Einstellungen ----------

@Composable
fun SettingsScreen(vm: MainViewModel, onClose: () -> Unit, onSwitchProfile: () -> Unit = {}) {
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    var playerId by remember { mutableStateOf("Lade…") }
    var pairingPw by remember { mutableStateOf<String?>(null) }
    var cacheSize by remember { mutableStateOf("") }
    var supportText by remember { mutableStateOf("") }
    var supportState by remember { mutableStateOf<String?>(null) }  // null=Formular, sonst Meldung
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Aktives Profil für die Anzeige
    var activeProfile by remember { mutableStateOf<Profile?>(null) }
    LaunchedEffect(Unit) { activeProfile = ProfileStore.active(appCtx) }

    // Generierte Player-ID + Pairing-Passwort (Geraet-generiert, IBPlayer-Modell)
    LaunchedEffect(Unit) {
        playerId = CredStore.playerId(appCtx)
        pairingPw = CredStore.pairingPw(appCtx)
    }

    // Cache-Groesse berechnen (EPG-XML + Logo-Diskcache)
    fun refreshCacheSize() {
        cacheSize = try {
            var bytes = 0L
            val dirs = listOf(appCtx.filesDir, java.io.File(appCtx.cacheDir, "image_cache"),
                java.io.File(appCtx.filesDir, "coil"), appCtx.cacheDir)
            for (d in dirs) d?.walkTopDown()?.forEach { f -> if (f.isFile) bytes += f.length() }
            "${bytes / 1024 / 1024} MB"
        } catch (e: Exception) { "?" }
    }
    LaunchedEffect(Unit) { refreshCacheSize() }

    // Player-Status vom Backend holen (aktiviert? freigeschaltet?) — auf IO
    var playerStatus by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }  // (exists, active)
    LaunchedEffect(playerId) {
        if (playerId == "Lade…") return@LaunchedEffect
        playerStatus = kotlinx.coroutines.withTimeoutOrNull(10000L) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                vm.fetchPlayerStatus(playerId)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0E14))) {
        Column(Modifier.fillMaxSize().padding(48.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Einstellungen", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8EEFC))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("← Zurück", color = Color(0xFF8FA3C8), fontSize = 16.sp) }
            }
            Spacer(Modifier.height(28.dp))

            // ---- Profile ----
            Section("Profile") {
                InfoRow("Aktives Profil", activeProfile?.name ?: "—")
                TextButton(onClick = onSwitchProfile, modifier = Modifier.align(Alignment.Start)) {
                    Text("Profil wechseln", color = Color(0xFFFFC94D), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(18.dp))

            // ---- Mein Player (Pairing) ----
            Section("Mein Player") {
                InfoRow("Player-ID", playerId)
                val statusText = when {
                    playerStatus == null -> "wird geprüft…"
                    playerStatus?.first == false -> "noch nicht registriert — Website-Login registriert dich"
                    playerStatus?.second == true -> "✓ aktiv"
                    else -> "gesperrt — Support kontaktieren"
                }
                InfoRow("Status", statusText)
                Text(
                    "Mit Player-ID und Pairing-Passwort meldest du dich auf panel.smartys.tech an. " +
                    "Das Passwort erhältst du vom Support.",
                    color = Color(0xFF6B7A99), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(18.dp))

            // ---- Speicher ----
            Section("Speicher") {
                InfoRow("Cache-Größe (EPG + Logos)", cacheSize)
                Row {
                    TextButton(onClick = {
                        // EPUG/Logo-Cache loeschen
                        try {
                            java.io.File(appCtx.filesDir, "xmltv.xml").delete()
                            java.io.File(appCtx.filesDir, "epg_pw_de.xml").delete()
                            appCtx.cacheDir?.walkTopDown()?.forEach { f -> if (f.isFile) f.delete() }
                            vm.clearEpg()
                            refreshCacheSize()
                        } catch (_: Exception) {}
                    }) { Text("Cache löschen", color = Color(0xFFFFC94D), fontSize = 14.sp) }
                }
            }
            Spacer(Modifier.height(18.dp))

            // ---- Downloads (VOD lokal) ----
            Section("Downloads") {
                val downloads = VodDownloader.items
                if (downloads.isEmpty()) {
                    Text("Noch keine Downloads. In der Filmdetailseite „⬇ Herunterladen“ wählen.", color = Color(0xFF6B7A99), fontSize = 13.sp)
                } else {
                    downloads.forEach { d ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(d.title, color = Color(0xFFE8EEFC), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when (d.status) {
                                        "complete" -> "✓ fertig (${d.downloadedBytes / 1024 / 1024} MB)"
                                        "running" -> "lädt… ${(d.progress * 100).toInt()}%"
                                        "paused" -> "pausiert (${(d.progress * 100).toInt()}%)"
                                        else -> "Fehler: ${d.error ?: "unbekannt"}"
                                    },
                                    color = Color(0xFF6B7A99), fontSize = 12.sp
                                )
                            }
                            if (d.status == "running") {
                                TextButton(onClick = { VodDownloader.pause(d.id) }) { Text("Pause", color = Color(0xFFFFC94D), fontSize = 13.sp) }
                            } else if (d.status == "paused") {
                                TextButton(onClick = { VodDownloader.resume(appCtx, d.id) }) { Text("Fortsetzen", color = Color(0xFF6FCF9F), fontSize = 13.sp) }
                            }
                            TextButton(onClick = { VodDownloader.delete(appCtx, d.id) }) { Text("Löschen", color = Color(0xFFFF6B6B), fontSize = 13.sp) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            // ---- Kindersicherung ----
            Section("Kindersicherung") {
                PinSettings()
            }
            Spacer(Modifier.height(18.dp))

            // ---- Support ----
            Section("Support") {
                if (supportState != null) {
                    Text(supportState!!, color = Color(0xFF6FCF9F), fontSize = 14.sp)
                } else {
                    OutlinedTextField(
                        value = supportText, onValueChange = { supportText = it },
                        placeholder = { Text("Beschreibe dein Problem…", color = Color(0xFF6B7A99)) },
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color(0xFF232C42),
                            focusedBorderColor = Color(0xFFFFC94D)
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        enabled = supportText.isNotBlank(),
                        onClick = {
                            supportState = "Sende…"
                            scope.launch(Dispatchers.IO) {
                                val ok = vm.sendSupport(playerId, supportText)
                                supportState = if (ok)
                                    "✓ Anfrage gesendet — wir melden uns per E-Mail."
                                else
                                    "✗ Versand fehlgeschlagen — bitte später erneut versuchen."
                            }
                        }
                    ) { Text("Absenden", color = Color(0xFFFFC94D), fontSize = 14.sp) }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Smarty Player v0.3 · panel.smartys.tech",
                color = Color(0xFF4D5B78), fontSize = 11.sp
            )
        }
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFF11172A), MaterialTheme.shapes.large)
            .padding(24.dp)
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFC94D))
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Color(0xFF8FA3C8), fontSize = 14.sp, modifier = Modifier.width(280.dp))
        Text(value, color = Color(0xFFE8EEFC), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------- Login ----------
// ---------- Pairing (IBPlayer-Start) ----------

fun qrCodeBitmap(content: String, size: Int = 512): android.graphics.Bitmap {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
        content, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            // dunkles Modul auf hellem Grund (gut scannbar)
            pixels[y * size + x] = if (matrix[x, y]) 0xFF10141F.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return android.graphics.Bitmap.createBitmap(
        size, size, android.graphics.Bitmap.Config.ARGB_8888
    ).apply { setPixels(pixels, 0, size, 0, 0, size, size) }
}

@Composable
fun QrCode(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { qrCodeBitmap(content) }
    androidx.compose.foundation.Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR-Code zum Pairing",
        modifier = modifier
    )
}

@Composable
fun PairingScreen(vm: MainViewModel) {
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    val playerId = remember { CredStore.playerId(appCtx) }
    val pairingPw = remember { CredStore.pairingPw(appCtx) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val checkFocus = androidx.compose.ui.focus.FocusRequester()

    // Beim Screen-Start: Fokus AUTOMATISCH auf den Pruefen-Button legen,
    // damit D-Pad/OK (Fire-TV-Fernbedienung) sofort trifft.
    // Kleiner Delay noetig: der Button komponiert erst nach dem ersten Frame.
    LaunchedEffect(Unit) {
        // Mehrere Versuche: Der Button komponiert erst nach dem ersten Frame,
        // FocusRequester muss ATTACHED sein, bevor requestFocus() wirkt.
        repeat(10) {
            kotlinx.coroutines.delay(200)
            val ok = runCatching { checkFocus.requestFocus() }.isSuccess
            if (ok) return@LaunchedEffect
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Smarty Player", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFC94D))
        Spacer(Modifier.height(4.dp))
        Text("Player freischalten", color = Color.Gray, fontSize = 15.sp)
        Spacer(Modifier.height(28.dp))

        // Player-ID + Passwort gross und deutlich (IBPlayer-Prinzip)
        Column(
            Modifier.background(Color(0xFF10141F), RoundedCornerShape(14.dp)).padding(horizontal = 36.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Deine Player-ID", color = Color(0xFF8FA3C8), fontSize = 13.sp)
            Text(playerId, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8EEFC))
            Spacer(Modifier.height(14.dp))
            Text("Dein Passwort", color = Color(0xFF8FA3C8), fontSize = 13.sp)
            Text(pairingPw, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFC94D))
        }
        Spacer(Modifier.height(20.dp))

        // QR-Code: scannen statt auf der Fernbedienung abtippen
        QrCode(
            "https://panel.smartys.tech/pair?mac=$playerId&pw=$pairingPw",
            modifier = Modifier.width(220.dp).height(220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White).padding(12.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text("QR-Code mit dem Handy scannen", color = Color(0xFF8FA3C8), fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))

        Text(
            "1. QR-Code scannen und Playlist hinterlegen\n" +
            "2. Oder manuell: panel.smartys.tech\n" +
            "   mit Player-ID + Passwort anmelden\n" +
            "3. Danach hier auf „Prüfen“ drücken",
            color = Color(0xFF8FA3C8), fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 24.sp
        )
        Spacer(Modifier.height(22.dp))

        if (busy) {
            CircularProgressIndicator(color = Color(0xFFFFC94D))
            msg?.let { Text(it, color = Color(0xFF8FA3C8), fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
        } else {
            Button(onClick = {
                android.util.Log.d("SmartysIPTV", "Pruefen-Button geklickt, pairAndLoad startet")
                busy = true; msg = "Verbinde mit panel.smartys.tech…"
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    val ok = vm.pairAndLoad(playerId, pairingPw)
                    android.util.Log.d("SmartysIPTV", "pairAndLoad Ergebnis: $ok")
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        busy = false
                        msg = when {
                            ok -> "Playlist gefunden! Lade Kanäle…"
                            else -> "Noch keine Playlist hinterlegt. Auf der Website einrichten, dann erneut prüfen."
                        }
                    }
                }
            }, modifier = Modifier.focusRequester(checkFocus),
               colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC94D), contentColor = Color(0xFF0B0E14))) {
                Text("Prüfen — Playlist laden", Modifier.padding(horizontal = 26.dp, vertical = 6.dp), fontSize = 16.sp)
            }
            Spacer(Modifier.height(8.dp))
            msg?.let { Text(it, color = Color(0xFF8FA3C8), fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }

    }
}


// ---------- Kanal-Übersicht ----------

@Composable
fun ChannelGrid(
    vm: MainViewModel,
    categories: List<Category>,
    channels: List<LiveStream>,
    searchQuery: String,
    selectedCategory: String?,
    onSearch: (String) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onPlay: (LiveStream) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenEpg: () -> Unit = {}
) {
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    var favMode by remember { mutableStateOf(false) }
    // Sortier-Modus + Kanal-Langdruck-Menü + FSK18-PIN-Sperre
    var sortMode by remember { mutableStateOf(ChannelPrefs.sortMode(appCtx)) }
    var channelMenuFor by remember { mutableStateOf<LiveStream?>(null) }
    var lockPinFor by remember { mutableStateOf<LiveStream?>(null) }
    var playGateFor by remember { mutableStateOf<LiveStream?>(null) }  // FSK18: PIN vor Abspielen
    val catListState = rememberLazyListState()
    val firstRowFocus = remember { FocusRequester() }
    val searchFieldFocus = remember { FocusRequester() }
    // Tick: +1 bei jedem Klick auf eine Kategorie => Fokus auf 1. Kanal lenken
    var focusListTick by remember { mutableStateOf(0) }

    val shown = remember(channels, searchQuery, selectedCategory, favMode, vm.favorites, sortMode) {
        var list = channels
        if (favMode) list = list.filter { it.streamId in vm.favorites }
        if (selectedCategory != null) list = list.filter { it.categoryId == selectedCategory }
        if (searchQuery.isNotBlank()) list = list.filter { it.name.contains(searchQuery, true) }
        ChannelPrefs.apply(appCtx, list)
    }

    // Nach Kategorie-Klick: Fokus sofort auf den OBERSTEN Kanal (nicht "in der Luft")
    LaunchedEffect(focusListTick) {
        if (focusListTick > 0) {
            kotlinx.coroutines.delay(80)
            runCatching { firstRowFocus.requestFocus() }
        }
    }

    // Sidebar: zur ausgewählten Kategorie scrollen, damit der Rückweg aus der Liste
    // die zuletzt aktive Kategorie im Blick behält (kein Sprung in die Mitte)
    LaunchedEffect(selectedCategory, favMode, categories) {
        val idx = when {
            favMode -> 1  // "Favoriten"-Eintrag
            selectedCategory != null -> {
                val i = categories.indexOfFirst { it.id == selectedCategory }
                if (i >= 0) i + 2 else 0   // +2: "Alle Kanäle" + "Favoriten" oben
            }
            else -> 0
        }
        runCatching { catListState.scrollToItem(idx) }
    }

    fun categoryClicked(pick: String?, newFav: Boolean) {
        onSelectCategory(pick)
        favMode = newFav
        focusListTick++
    }

    Row(Modifier.fillMaxSize()) {
        // ---- Kategorien-Sidebar (002-Stil: Aktiv = Gold + linker Balken) ----
        LazyColumn(
            state = catListState,
            modifier = Modifier.width(300.dp).fillMaxHeight()
                .background(Color(0xFF11172A)).padding(vertical = 14.dp)
        ) {
            item {
                CategoryItem("Alle Kanäle", selectedCategory == null && !favMode, onClick = { categoryClicked(null, false) })
                CategoryItem("Favoriten (${vm.favorites.size})", favMode, onClick = { categoryClicked(null, true) })
                Spacer(Modifier.height(8.dp))
            }
            items(categories) { cat ->
                CategoryItem(cat.name, selectedCategory == cat.id, onClick = { categoryClicked(cat.id, false) })
            }
        }

        // ---- Hauptbereich ----
        Column(Modifier.weight(1f).padding(horizontal = 32.dp, vertical = 20.dp)) {
            // Kopfzeile: Such-Pill rechts (002-Stil)
            // WICHTIG: Suche ist NUR per OK-Klick aktivierbar. Fokus (D-Pad) darf das Feld
            // NICHT sofort oeffnen (Fire-TV-Tastatur-Problem). Erst Klick => Tastatur.
            var searchArmed by remember { mutableStateOf(false) }
            var searchFocused by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (favMode) "Favoriten" else categories.firstOrNull { it.id == selectedCategory }?.name ?: "Alle Kanäle",
                        color = Color(0xFFE8EEFC), fontSize = 24.sp, fontWeight = FontWeight.Bold
                    )
                }

                if (searchArmed) {
                    // Aktiver Such-Modus: echtes Eingabefeld, Fokus dorthin
                    OutlinedTextField(
                        value = searchQuery, onValueChange = onSearch,
                        placeholder = { Text("Kanal suchen…", color = Color(0xFF6B7A99)) },
                        modifier = Modifier
                            .width(340.dp)
                            .focusRequester(searchFieldFocus)
                            .onFocusChanged { searchFocused = it.isFocused },
                        singleLine = true,
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color(0xFF232C42),
                            focusedBorderColor = Color(0xFFFFC94D)
                        )
                    )
                } else {
                    // Ruhender Zustand: Pill-Optik, klickbar, aber kein Textfeld-Fokus
                    Surface(
                        onClick = { searchArmed = true },
                        shape = MaterialTheme.shapes.extraLarge,
                        color = Color(0xFF0B0E14),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, if (searchFocused) Color(0xFFFFC94D) else Color(0xFF232C42)
                        ),
                        modifier = Modifier.width(340.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp)
                        ) {
                            Text(
                                "🔍", fontSize = 15.sp, color = Color(0xFF6B7A99)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                searchQuery.ifBlank { "Kanal suchen…" },
                                color = if (searchQuery.isBlank()) Color(0xFF6B7A99) else Color(0xFFE8EEFC),
                                fontSize = 15.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                // Sortier-Button (Nummer / Name / Manuell)
                Surface(
                    onClick = {
                        val modes = listOf("num", "name", "manual")
                        val next = modes[(modes.indexOf(sortMode) + 1) % modes.size]
                        sortMode = next
                        ChannelPrefs.setSortMode(appCtx, next)
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = Color(0xFF11172A),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232C42))
                ) {
                    Text(
                        when (sortMode) {
                            "name" -> "Sortieren: A–Z"
                            "manual" -> "Sortieren: Manuell"
                            else -> "Sortieren: Nummer"
                        },
                        color = Color(0xFFE8EEFC), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = onOpenEpg) {
                    Text("📅", fontSize = 22.sp, color = Color(0xFF8FA3C8))
                }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onOpenSettings) {
                    Text("⚙", fontSize = 22.sp, color = Color(0xFF8FA3C8))
                }
            }
            Spacer(Modifier.height(14.dp))

            // Fokus ins Suchfeld lenken, sobald es "scharf" geschaltet wird (nach OK-Klick)
            LaunchedEffect(searchArmed) {
                if (searchArmed) {
                    kotlinx.coroutines.delay(100)
                    runCatching { searchFieldFocus.requestFocus() }
                }
            }
            // Such-Modus verlassen, wenn das Feld den Fokus verliert (Fokus wandert weiter)
            LaunchedEffect(searchFocused) {
                if (!searchFocused && searchArmed) {
                    kotlinx.coroutines.delay(200)
                    // immer noch nicht fokussiert? => zurueck in den Ruhemodus
                    if (!searchFocused) searchArmed = false
                }
            }

            LazyColumn {
                items(shown, key = { it.streamId }) { ch ->
                    ChannelRow(
                        channel = ch,
                        isFav = ch.streamId in vm.favorites,
                        onPlay = {
                            if (ChannelPrefs.isLocked(appCtx, ch.streamId)) playGateFor = ch
                            else onPlay(ch)
                        },
                        onToggleFav = { vm.toggleFavorite(ch.streamId) },
                        vm = vm,
                        onLongPress = { channelMenuFor = ch },
                        focusOnAppear = if (shown.indexOfFirst { it.streamId == ch.streamId } == 0) firstRowFocus else null
                    )
                }
            }
        }
    }

    // ---- Kanal-Langdruck-Menü: verschieben / ausblenden / FSK18-Sperre ----
    channelMenuFor?.let { ch ->
        val idx = shown.indexOfFirst { it.streamId == ch.streamId }
        val isHidden = ChannelPrefs.isHidden(appCtx, ch.streamId)
        val isLocked = ChannelPrefs.isLocked(appCtx, ch.streamId)
        AlertDialog(
            onDismissRequest = { channelMenuFor = null },
            title = { Text(ch.name, color = Color(0xFFE8EEFC), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(horizontalAlignment = Alignment.Start) {
                    Text("Kanal verwalten", color = Color(0xFF6B7A99), fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            onClick = { ChannelPrefs.move(appCtx, shown, ch.streamId, -1); channelMenuFor = null },
                            enabled = idx > 0,
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF141A2A),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A4666))
                        ) {
                            Text("▲ Nach oben", color = Color(0xFFE8EEFC), fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                        }
                        Surface(
                            onClick = { ChannelPrefs.move(appCtx, shown, ch.streamId, +1); channelMenuFor = null },
                            enabled = idx < shown.size - 1,
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF141A2A),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A4666))
                        ) {
                            Text("▼ Nach unten", color = Color(0xFFE8EEFC), fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        onClick = {
                            if (isHidden) ChannelPrefs.unhide(appCtx, ch.streamId)
                            else ChannelPrefs.hide(appCtx, ch.streamId)
                            channelMenuFor = null
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF141A2A),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A4666))
                    ) {
                        Text(if (isHidden) "👁 Wieder einblenden" else "🚫 Ausblenden",
                            color = if (isHidden) Color(0xFF6FCF9F) else Color(0xFFFF9B9B), fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        onClick = { lockPinFor = ch; channelMenuFor = null },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF141A2A),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A4666))
                    ) {
                        Text(if (isLocked) "🔓 FSK18-Sperre aufheben" else "🔒 FSK18 sperren",
                            color = if (isLocked) Color(0xFF6FCF9F) else Color(0xFFFFC94D), fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { channelMenuFor = null }) { Text("Schließen", color = Color(0xFF6B7A99)) } },
            containerColor = Color(0xFF10141F)
        )
    }

    // ---- FSK18-PIN-Abfrage: Kanal abspielen nur nach PIN ----
    lockPinFor?.let { ch ->
        val locked = ChannelPrefs.isLocked(appCtx, ch.streamId)
        var pin by remember { mutableStateOf("") }
        var err by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { lockPinFor = null },
            title = { Text(if (locked) "FSK18-Sperre aufheben" else "FSK18 sperren", color = Color(0xFFE8EEFC), fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("PIN eingeben:", color = Color(0xFF8FA3C8), fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pin, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                        label = { Text("PIN") }, singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFFC94D),
                            unfocusedBorderColor = Color(0xFF232C42)
                        )
                    )
                    if (err) Text("Falsche PIN", color = Color(0xFFFF6B6B), fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (ParentalControl.verify(appCtx, pin)) {
                        ChannelPrefs.setLocked(appCtx, ch.streamId, !locked)
                        lockPinFor = null
                    } else { err = true; pin = "" }
                }) { Text("OK", color = Color(0xFFFFC94D), fontSize = 15.sp) }
            },
            dismissButton = { TextButton(onClick = { lockPinFor = null }) { Text("Abbrechen", color = Color(0xFF6B7A99)) } },
            containerColor = Color(0xFF10141F)
        )
    }

    // ---- FSK18-PIN vor dem Abspielen ----
    playGateFor?.let { ch ->
        var pin by remember { mutableStateOf("") }
        var err by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { playGateFor = null },
            title = { Text("🔒 FSK18", color = Color(0xFFE8EEFC), fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Dieser Kanal ist gesperrt. PIN eingeben:", color = Color(0xFF8FA3C8), fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pin, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                        label = { Text("PIN") }, singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFFC94D),
                            unfocusedBorderColor = Color(0xFF232C42)
                        )
                    )
                    if (err) Text("Falsche PIN", color = Color(0xFFFF6B6B), fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (ParentalControl.verify(appCtx, pin)) { playGateFor = null; onPlay(ch) }
                    else { err = true; pin = "" }
                }) { Text("Abspielen", color = Color(0xFFFFC94D), fontSize = 15.sp) }
            },
            dismissButton = { TextButton(onClick = { playGateFor = null }) { Text("Abbrechen", color = Color(0xFF6B7A99)) } },
            containerColor = Color(0xFF10141F)
        )
    }
}

@Composable
fun CategoryItem(name: String, selected: Boolean, onClick: () -> Unit, focusRequester: FocusRequester? = null) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        // Aktiv: DEUTLICH dunkler als die Sidebar (fast schwarz) => klar erkennbar
        color = when {
            selected -> Color(0xFF0A0D16)
            focused -> Color(0xFF1B2438)
            else -> Color.Transparent
        },
        shape = MaterialTheme.shapes.medium,
        // Fokus-Cursor: goldener Rahmen wie bei den Kanalzeilen
        border = if (focused) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFFC94D)) else null,
        modifier = Modifier.fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        Row {
            // Aktiver Eintrag: goldener Balken links (002-Stil)
            if (selected) Box(
                Modifier.width(5.dp).fillMaxHeight()
                    .background(Color(0xFFFFC94D))
            )
            Text(
                name,
                color = when {
                    selected -> Color(0xFFFFC94D)
                    focused -> Color(0xFFE8EEFC)
                    else -> Color(0xFF8FA3C8)
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                fontSize = 15.sp,
                fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------- Kanal-Zeile ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(
    channel: LiveStream,
    isFav: Boolean,
    onPlay: () -> Unit,
    onToggleFav: () -> Unit,
    vm: MainViewModel,
    onLongPress: () -> Unit = {},
    focusOnAppear: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }

    // XMLTV-EPG: jetzt/danach aus dem In-Memory-Index (sofort da nach dem Laden)
    val xmltvReady = vm.xmltvReady.value
    val epgText = remember(channel.streamId, xmltvReady) {
        val (cur, next) = vm.nowNext(channel)
        when {
            cur != null -> "${XmltvEpg.hhmm(cur.start)}  ${cur.title}" +
                (next?.let { "  →  ${XmltvEpg.hhmm(it.start)} ${it.title}" } ?: "")
            next != null -> "${XmltvEpg.hhmm(next.start)}  ${next.title}"
            channel.epgChannelId.isNullOrBlank() -> null   // Kanal ohne EPG-ID
            else -> if (xmltvReady) "Kein EPG" else ""       // Index lädt noch
        }
    }

    Surface(
        color = when {
            focused -> Color(0xFF1A2238)          // Fokus: leicht heller + Gold-Rahmen
            else -> Color(0xFF10141F)
        },
        shape = MaterialTheme.shapes.medium,
        border = if (focused) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFFC94D)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .then(if (focusOnAppear != null) Modifier.focusRequester(focusOnAppear) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onPlay, onLongClick = onLongPress)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            // Kanalnummer (002-Stil: rechtsbündig, gedämpft)
            Text(
                "${channel.num ?: ""}",
                color = Color(0xFF55648A), fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.width(56.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
            Spacer(Modifier.width(18.dp))
            AsyncImage(
                model = channel.streamIcon,
                contentDescription = null,
                modifier = Modifier
                    .size(width = 92.dp, height = 52.dp)
                    .background(Color(0xFF141A2A), MaterialTheme.shapes.small)
                    .padding(4.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, color = Color(0xFFE8EEFC), fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    epgText != null -> Text(
                        epgText,
                        color = if (focused) Color(0xFFFFC94D) else Color(0xFF7D8BA8), fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    else -> Text("", fontSize = 13.sp)
                }
            }
            TextButton(onClick = onToggleFav) {
                Text(if (isFav) "★" else "☆", fontSize = 22.sp,
                    color = if (isFav) Color(0xFFFFC94D) else Color(0xFF4D5B78))
            }
        }
    }
}

// ---------- Player ----------

@Composable
fun PlayerScreen(
    vm: MainViewModel,
    player: ExoPlayer?,
    channel: LiveStream,
    channels: List<LiveStream>,
    onPlay: (LiveStream) -> Unit,
    onBack: () -> Unit
) {
    var showOverlay by remember { mutableStateOf(true) }
    var showReplay by remember { mutableStateOf(false) }
    val xmltvReady = vm.xmltvReady.value
    val epgInfo = remember(channel.streamId, xmltvReady) { vm.nowNext(channel) }

    // ---- Kanalnummer-Direkteingabe: Ziffern tippen, 1.5s nach letzter Ziffer schaltet der Player ----
    var numInput by remember { mutableStateOf("") }
    LaunchedEffect(numInput) {
        if (numInput.isNotEmpty()) {
            kotlinx.coroutines.delay(1500)
            val target = channels.firstOrNull { (it.num ?: -1) == numInput.toIntOrNull() }
            if (target != null && target.streamId != channel.streamId) {
                onPlay(target)
            } else {
                // keine Kanal-Nummer gefunden: kurz als "Kanal X?" anzeigen und verwerfen
                kotlinx.coroutines.delay(600)
            }
            numInput = ""
        }
    }

    LaunchedEffect(channel) {
        showOverlay = true
        kotlinx.coroutines.delay(4000)
        showOverlay = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val numKey = keyEvent.key.toString()
                val digit = when (numKey) {
                    "0","1","2","3","4","5","6","7","8","9" -> numKey
                    else -> null
                }
                if (digit != null) {
                    if (numInput.length < 5) numInput += digit
                    showOverlay = true
                    return@onPreviewKeyEvent true
                }
                when (keyEvent.key) {
                    Key.DirectionUp, Key.DirectionDown -> {
                        val idx = channels.indexOfFirst { it.streamId == channel.streamId }
                        if (idx >= 0 && channels.isNotEmpty()) {
                            val next = if (keyEvent.key == Key.DirectionDown)
                                channels[(idx + 1) % channels.size]
                            else channels[(idx - 1).mod(channels.size)]
                            if (next.streamId != channel.streamId) {
                                onPlay(next)
                                true
                            } else false
                        } else false
                    }
                    Key.DirectionCenter, Key.Enter -> { showOverlay = !showOverlay; true }
                    Key.Back, Key.Escape -> { onBack(); true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // OSD (002-Stil: Logo + Fortschrittsbalken)
        if (showOverlay) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(28.dp)
                    .width(520.dp)
                    .background(
                        androidx.compose.ui.graphics.Color(0x660B0E14),
                        MaterialTheme.shapes.large
                    )
                    .padding(horizontal = 24.dp, vertical = 18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = channel.streamIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(width = 92.dp, height = 52.dp)
                            .background(Color(0xFF141A2A), MaterialTheme.shapes.small)
                            .padding(4.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.width(20.dp))
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(channel.name, color = Color(0xFFE8EEFC), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            if (numInput.isNotEmpty()) {
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    "→ Kanal $numInput",
                                    color = Color(0xFF0B0E14), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .background(Color(0xFFFFC94D), MaterialTheme.shapes.extraLarge)
                                        .padding(horizontal = 12.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Text(
                            "Kanal ${channel.num ?: "-"}  ·  ${channel.categoryName}",
                            color = Color(0xFF6B7A99), fontSize = 13.sp
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                epgInfo.first?.let { cur ->
                    val prog = remember(cur.start, cur.stop) {
                        val s = cur.start; val e = cur.stop
                        val now = System.currentTimeMillis()
                        when {
                            e <= s -> 0f
                            now <= s -> 0f
                            now >= e -> 1f
                            else -> (now - s).toFloat() / (e - s).toFloat()
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            XmltvEpg.hhmm(cur.start), color = Color(0xFFFFC94D),
                            fontSize = 15.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            cur.title,
                            color = Color(0xFFE8EEFC), fontSize = 15.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(XmltvEpg.hhmm(cur.stop), color = Color(0xFF6B7A99), fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    // Fortschrittsbalken der laufenden Sendung (002-Gold)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0xFF232C42), MaterialTheme.shapes.extraLarge)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(prog)
                                .height(4.dp)
                                .background(Color(0xFFFFC94D), MaterialTheme.shapes.extraLarge)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    epgInfo.second?.let { nxt ->
                        Text(
                            "Danach:  ${XmltvEpg.hhmm(nxt.start)}  ${nxt.title}",
                            color = Color(0xFF8FA3C8), fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (epgInfo.first == null) {
                    Text("Kein EPG für diesen Kanal", color = Color(0xFF6B7A99), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "↑/↓ zappen · Nummer tippen = Direktwahl · OK Info · Zurück = Liste",
                    color = Color(0xFF5A6B8C), fontSize = 11.sp
                )
                if (channel.hasArchive()) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        onClick = { showReplay = true },
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF141A2A),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFC94D))
                    ) {
                        Text(
                            "⏪ Replay — letzte ${channel.archiveHours()} Std.",
                            color = Color(0xFFFFC94D), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }
            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp)
            ) { Text("← Zurück", color = Color.White) }
        }

        // ---- Catch-up / Replay-Dialog ----
        if (showReplay) {
            ReplayDialog(
                channel = channel,
                vm = vm,
                onJump = { offsetMinutes ->
                    val c = vm.creds ?: return@ReplayDialog
                    val startEpoch = (System.currentTimeMillis() / 1000) - offsetMinutes * 60L
                    val url = c.timeshiftUrl(channel.streamId, startEpoch, channel.archiveHours())
                    player?.setMediaItem(MediaItem.fromUri(url))
                    player?.prepare()
                    player?.play()
                    showReplay = false
                },
                onClose = { showReplay = false }
            )
        }
    }
}

@Composable
fun ReplayDialog(channel: LiveStream, vm: MainViewModel, onJump: (Int) -> Unit, onClose: () -> Unit) {
    val jumps = listOf(
        "Jetzt (Live)" to 0,
        "-10 Min" to 10,
        "-30 Min" to 30,
        "-1 Std." to 60,
        "-2 Std." to 120
    )
    Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
        Column(
            Modifier
                .align(Alignment.Center)
                .width(460.dp)
                .background(Color(0xFF0B0E14), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Text("Replay", color = Color(0xFFE8EEFC), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(channel.name, color = Color(0xFF6B7A99), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            jumps.forEach { (label, mins) ->
                Surface(
                    onClick = { onJump(mins) },
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF141A2A),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Text(label, color = Color(0xFFE8EEFC), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Schließen", color = Color(0xFF8FA3C8))
            }
        }
    }
}
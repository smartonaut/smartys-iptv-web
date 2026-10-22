package com.smartys.iptv

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import android.os.Bundle
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.unit.IntOffset
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
import android.app.Activity
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

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
                    // DECKENDER Root-Hintergrund: ohne ihn malt Compose nur die
                    // geänderten Regionen. Auf dem Fire-TV-Stick (Fire OS 6 /
                    // API 25) bleiben beim Screen-Wechsel die alten Pixel stehen —
                    // das "Überlagern" von Prüfen-Screen und Passwort-Eingabe.
                    // Hintergrund VOR dem Insets-Padding, damit die volle
                    // Fensterfläche neu gezeichnet wird.
                    Box(
                        Modifier.fillMaxSize()
                            .background(Color(0xFF0B0E14))
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                    ) {
                        App(autoUrl = autoUrl, autoUser = autoUser, autoPass = autoPass)
                    }
                }
            }
        }
    }
}

/** Blendet die System-Bars (Status + Navigation) ein/aus — Handy-Vollbild. */
fun setSystemBarsHidden(context: Context, hidden: Boolean) {
    val activity = context as? Activity ?: return
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars())
    else controller.show(WindowInsetsCompat.Type.systemBars())
}

/** Hält den Bildschirm wach — verhindert den FireTV-Bildschirmschoner beim Schauen. */
fun keepScreenOn(context: Context, on: Boolean) {
    val activity = context as? Activity ?: return
    if (on) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

/** Hängt Touch-Gesten an die Player-View: Doppel-Tap = Vollbild, Einfach-Tap = OSD. */
fun attachPlayerGestures(view: android.view.View, onTap: () -> Unit, onDoubleTap: () -> Unit) {
    val detector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { onDoubleTap(); return true }
    })
    view.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
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
    // dp UND sp skalieren GEMEINSAM: sp = density * fontScale, also reicht es,
    // die density zu skalieren und fontScale unangetastet zu lassen.
    // Früher wurde fontScale gegenläufig geteilt (1/scale), damit Schrift
    // "lesbar" bleibt — auf einem Canvas unter 540 dp schrumpften dann die
    // Container, während die Schrift gleich groß blieb: Text lief aus den
    // Zeilen, Überschriften überlagerten sich, Ränder wurden abgeschnitten
    // (genau das Fehlerbild auf dem Fire-TV-Stick, während der Cube mit
    // 960x540 dp = scale 1.0 unauffällig bleibt).
    val scaled = Density(base.density * scale, base.fontScale)
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

    // Player-Lebenszyklus: beim Verlassen wird der Puffer KOMPLETT freigegeben
    // (shutdown), damit danach gestartete Apps den RAM bekommen. Ein erneuter
    // Start baut die Instanz frisch auf.
    fun ensurePlayer(): RobustLivePlayer {
        val cur = player.value
        if (cur != null && !cur.released) return cur
        return RobustLivePlayer(appCtx).also { player.value = it }
    }

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
        player.value?.shutdown()
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
            playing.value != null -> { playing.value = null; player.value?.shutdown() }
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
            ensurePlayer().playChannel(url, channel.streamId)
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
        ensurePlayer().playChannel(vod.url, "vod:${vod.title}", startPosMs = resume)
    }

    // Nächste Episode aus der Queue, oder null wenn keine mehr folgt.
    fun nextEpisode(): VodPlayback? = seriesQueue?.getOrNull(seriesIndex + 1)

    // Queue beenden und zurück (letzte Folge erreicht oder abgebrochen).
    fun endSeriesPlayback() {
        seriesQueue = null
        nextPending = false
        vodPlaying.value = null
        player.value?.shutdown()
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
        ensurePlayer().playChannel(next.url, "vod:${next.title}", startPosMs = resume)
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
        ensurePlayer().playChannel(ep.url, "vod:${ep.title}", startPosMs = resumeMs) {
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
                    // Profil-Bindung: Line des neuen Profils sofort aktiv machen.
                    vm.applyActiveLine()
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
                        onBack = { playing.value = null; player.value?.shutdown() }
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
                // Aktiv NICHT gold füllen: eine goldene Fläche verschluckt den
                // Fokus-Marker (Fire OS 6 malt keinen System-Fokusring).
                // Aktiv = Goldrahmen + Goldschrift, Fokus = hellere Fläche + 3dp.
                color = when {
                    focused -> Color(0xFF232C42)
                    active -> Color(0xFF1A2238)
                    else -> Color(0xFF11172A)
                },
                border = androidx.compose.foundation.BorderStroke(
                    // Rahmen = FOKUS (3 dp Gold). Auswahl wird nur ueber die
                    // Goldschrift gezeigt, damit "aktiv" und "fokussiert" nicht
                    // gleich aussehen (auf Fire OS 6 gibt es keinen System-Ring).
                    if (focused) 3.dp else 1.dp,
                    if (focused) Color(0xFFFFC94D) else Color(0xFF232C42)
                ),
                // Kein extra .focusable(): Surface(onClick) ist bereits fokussierbar
                modifier = Modifier.onFocusChanged { focused = it.isFocused }
            ) {
                Text(
                    label,
                    color = when {
                        active -> Color(0xFFFFC94D)
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
    var fullscreen by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // Bildschirm anlassen (FireTV: kein Bildschirmschoner beim Schauen);
    // beim Verlassen des Players die System-Bars wieder zeigen.
    DisposableEffect(Unit) {
        keepScreenOn(ctx, true)
        onDispose {
            setSystemBarsHidden(ctx, false)
            keepScreenOn(ctx, false)
        }
    }

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
                factory = { viewCtx ->
                    android.view.TextureView(viewCtx).also { tv ->
                        textureView = tv
                        player?.setVideoTextureView(tv)
                        attachPlayerGestures(tv,
                            onTap = { showOsd = !showOsd },
                            onDoubleTap = {
                                fullscreen = !fullscreen
                                setSystemBarsHidden(ctx, fullscreen)
                            })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AndroidView(
                factory = { viewCtx ->
                    PlayerView(viewCtx).apply {
                        this.player = player
                        useController = false
                        attachPlayerGestures(this,
                            onTap = { showOsd = !showOsd },
                            onDoubleTap = {
                                fullscreen = !fullscreen
                                setSystemBarsHidden(ctx, fullscreen)
                            })
                    }
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
                    .widthIn(max = 640.dp)
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

/** Fragt den Panel-Status dieser Box ab: (existiert, freigeschaltet) oder null. */
private fun fetchPanelInfo(mac: String): Pair<Boolean, Boolean>? = try {
    val url = java.net.URL(
        "https://panel.smartys.tech/api/v1/player/info?mac=" +
            java.net.URLEncoder.encode(mac, "UTF-8")
    )
    val conn = url.openConnection() as javax.net.ssl.HttpsURLConnection
    conn.connectTimeout = 6000
    conn.readTimeout = 6000
    if (conn.responseCode != 200) {
        null
    } else {
        val o = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
        o.optBoolean("exists") to o.optBoolean("active")
    }
} catch (_: Exception) {
    null
}

/** Lesbarer Gerätename für die Einstellungen ("Fire TV Stick 4K · AFTMM · Android 7.1.2"). */
private fun deviceModelText(): String {
    val model = android.os.Build.MODEL.orEmpty()
    val device = android.os.Build.DEVICE.orEmpty().lowercase()
    val friendly = when (device) {
        "mantis" -> "Fire TV Stick 4K"
        "mustang" -> "Fire TV Stick 4K Max"
        "sheldon", "sheldonp" -> "Fire TV Stick Lite"
        "tank" -> "Fire TV Stick (2. Gen)"
        "aftka" -> "Fire TV Stick (3. Gen)"
        "raven" -> "Fire TV Cube (2. Gen)"
        "aftgazl", "gazelle" -> "Fire TV Cube (3. Gen)"
        "aftt" -> "Fire TV (2. Gen)"
        else -> when (model.uppercase()) {
            "AFTMM" -> "Fire TV Stick 4K"
            "AFTKA" -> "Fire TV Stick (3. Gen)"
            "AFTKM" -> "Fire TV Stick 4K Max"
            "AFTA" -> "Fire TV Stick (1. Gen)"
            "AFTR" -> "Fire TV Cube (2. Gen)"
            else -> null
        }
    }
    val android = "Android ${android.os.Build.VERSION.RELEASE}"
    return when {
        friendly != null && model.isNotBlank() -> "$friendly · $model · $android"
        model.isNotBlank() -> "$model · $android"
        else -> android
    }
}

// ---------- Einstellungen ----------

@Composable
fun SettingsScreen(vm: MainViewModel, onClose: () -> Unit, onSwitchProfile: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val profileName = ProfileStore.active(ctx)?.name
    val playName = (profileName ?: "Dieser Player")
    val ps = ctx.getSharedPreferences("smartys_panel", Context.MODE_PRIVATE)
    val playerId = ProfileStore.activeId(ctx)
    // Der Panel-Status kam frueher aus der Preference "smartys_panel/host", die
    // NIRGENDS geschrieben wird -> die Box meldete immer "Nicht mit Panel
    // verknuepft". Jetzt wird der echte Zustand am Panel abgefragt.
    var panelInfo by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    var panelChecked by remember { mutableStateOf(false) }
    // Die MAC der Box (nicht die Profil-UUID) ist der Schluessel im Panel.
    val boxMac = remember { CredStore.playerId(ctx) }
    val boxPairPw = remember { CredStore.pairingPw(ctx) }
    LaunchedEffect(boxMac) {
        if (boxMac.isBlank()) {
            panelChecked = true
        } else {
            panelInfo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                fetchPanelInfo(boxMac)
            }
            panelChecked = true
        }
    }
    val panelUrl = "https://panel.smartys.tech"
    val panelPairUrl = panelUrl.trimEnd('/') + "/pair"

    val recDir = File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "Aufnahmen")
    val cacheDir = ctx.cacheDir
    val logoDir = File(ctx.filesDir, "logos")
    val dlDir = File(ctx.filesDir, "downloads")
    var cacheWarn by remember { mutableStateOf(false) }
    var storageTick by remember { mutableStateOf(0) }
    // Profil -> Panel-Line: welches Profil bearbeitet gerade seine Bindung?
    var lineDialogFor by remember { mutableStateOf<Profile?>(null) }
    var reloadingLines by remember { mutableStateOf(false) }
    var lookback by remember { mutableStateOf(PlayPrefs.lookback(ctx)) }
    // Nie mehr als die Hardware schafft: ein zu grosser Puffer kippt auf
    // schwachen Boxen den Hardware-Decoder ("Source error").
    var bufferSec by remember {
        val cap = DeviceCap.maxBufferSec(ctx)
        val saved = PlayPrefs.bufferSec(ctx)
        val clamped = saved.coerceIn(0, cap)
        if (clamped != saved) PlayPrefs.setBufferSec(ctx, clamped)
        mutableStateOf(clamped)
    }
    val recs = remember(storageTick) {
        if (recDir.exists()) recDir.listFiles()?.filter { it.length() > 0 }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        else emptyList()
    }

    fun dirBytes(d: File): Long {
        if (!d.exists()) return 0L
        return d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
    fun mb(l: Long) = String.format(java.util.Locale.GERMAN, "%.1f MB", l / 1048576.0)

    LazyColumn(
        modifier = Modifier.fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 28.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text("Einstellungen", color = Color(0xFFE8EEFC), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }

        item {
            Section("Profil") {
                val profiles = remember { ProfileStore.list(ctx) }
                val activeId = ProfileStore.activeId(ctx)
                val availableLines = vm.lines()
                profiles.forEach { prof ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                prof.name + if (prof.id == activeId) "  ·  aktiv" else "",
                                color = if (prof.id == activeId) Color(0xFFFFC94D) else Color(0xFFE8EEFC),
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Line: " + (vm.lineLabelFor(prof.id) ?: "Automatisch"),
                                color = Color(0xFF8FA3C8), fontSize = 12.sp
                            )
                        }
                        BtnSmall("Line wählen") { lineDialogFor = prof }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (availableLines.isEmpty()) "Keine Lines geladen."
                    else "Lines (${availableLines.size}): " +
                        availableLines.joinToString(" · ") { it.second } +
                        "   — aktiv: " + (vm.lineLabelFor(activeId ?: "") ?: "Automatisch"),
                    color = Color(0xFF6B7A99), fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))
                // Lines neu vom Panel ziehen (z. B. nachdem im Panel eine Line
                // hinzugekommen ist) — wie im Desktop. Verschlüsselte Lines
                // landen im Unlock-Screen (dort Passwort eingeben).
                BtnSmall(if (reloadingLines) "lade Lines …" else "🔄 Lines vom Panel neu laden") {
                    if (!reloadingLines) {
                        reloadingLines = true
                        scope.launch {
                            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                vm.pairAndLoad(boxMac, boxPairPw)
                            }
                            reloadingLines = false
                            if (!ok && vm.unlockNeeded) {
                                onClose()
                                vm.requestPairingScreen()
                            } else if (ok) {
                                vm.applyActiveLine()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                BtnSmall("Profil wechseln (alle Profile)") { onSwitchProfile() }

                // Auswahl: welche Line (Panel-Provider) gehoert zu diesem Profil?
                lineDialogFor?.let { prof ->
                    AlertDialog(
                        onDismissRequest = { lineDialogFor = null },
                        title = {
                            Text("Line für ${prof.name}",
                                color = Color(0xFFE8EEFC), fontSize = 18.sp)
                        },
                        text = {
                            Column {
                                Text("Welche Line aus dem Panel nutzt dieses Profil?",
                                    color = Color(0xFF8FA3C8), fontSize = 13.sp)
                                Spacer(Modifier.height(10.dp))
                                BtnSmall("Automatisch (alle Lines)",
                                    selected = vm.lineLabelFor(prof.id) == null) {
                                    vm.setProfileLine(prof.id, null)
                                    lineDialogFor = null
                                }
                                Spacer(Modifier.height(6.dp))
                                availableLines.forEach { (idx, label) ->
                                    BtnSmall(label, selected = vm.lineLabelFor(prof.id) == label) {
                                        vm.setProfileLine(prof.id, idx)
                                        lineDialogFor = null
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { lineDialogFor = null }) {
                                Text("Fertig", color = Color(0xFFFFC94D), fontSize = 15.sp)
                            }
                        },
                        containerColor = Color(0xFF10141F)
                    )
                }
            }
        }

        item {
            Section("Mein Player") {
                val linked = panelInfo?.first == true
                val active = panelInfo?.second == true
                val statusText = when {
                    !panelChecked -> "Prüfe Panel-Verbindung …"
                    boxMac.isBlank() -> "Nicht mit Panel verknüpft"
                    panelInfo == null -> "Panel nicht erreichbar — Status unbekannt"
                    !linked -> "Nicht mit Panel verknüpft"
                    active -> "Verbunden mit Panel"
                    else -> "Im Panel vorhanden — noch nicht freigeschaltet"
                }
                val statusColor = when {
                    !panelChecked || panelInfo == null -> Color(0xFF6B7A99)
                    linked && active -> Color(0xFF4ADE80)
                    linked -> Color(0xFFFFC94D)
                    else -> Color(0xFF6B7A99)
                }
                val pairPw = remember { CredStore.pairingPw(ctx) }
                // Vollständige Pairing-URL: MAC + Passwort dieser Box. Damit kommt
                // man nach dem Scannen direkt in den richtigen Panel-Eintrag —
                // zum Ändern der Line, ohne die Nummer abzutippen.
                val pairUrl = "https://panel.smartys.tech/pair?mac=" +
                    java.net.URLEncoder.encode(boxMac, "UTF-8") + "&pw=" +
                    java.net.URLEncoder.encode(pairPw, "UTF-8")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Profil", color = Color(0xFF8FA3C8), fontSize = 12.sp)
                        Text(playName, color = Color(0xFFE8EEFC), fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Gerät: ${deviceModelText()}",
                            color = Color(0xFF8FA3C8), fontSize = 13.sp)
                        Text("MAC: " + if (boxMac.isBlank()) "—" else boxMac,
                            color = Color(0xFFE8EEFC), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Pairing-Passwort: $pairPw",
                            color = Color(0xFFE8EEFC), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(statusColor))
                            Spacer(Modifier.width(8.dp))
                            Text(statusText, color = statusColor, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Zum Ändern der Line: QR scannen (öffnet das Panel mit dieser Box) " +
                             "oder panel.smartys.tech öffnen.",
                            color = Color(0xFF6B7A99), fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(20.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(150.dp).background(Color.White, shape = RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            QrCode(pairUrl, size = 134.dp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Panel-Zugang · Änderungen", color = Color(0xFF6B7A99), fontSize = 10.sp)
                    }
                }
            }
        }

        item {
            Section("Cache & Gerät") {
                val devMax = DeviceCap.maxBufferSec(ctx)
                InfoRow("Arbeitsspeicher (RAM)", DeviceCap.ramText())
                InfoRow("Decoder-Speicher (CMA)", DeviceCap.cmaText())
                InfoRow("Freier App-Speicher", DeviceCap.freeText(ctx))
                InfoRow("Gerät schafft", "bis $devMax s Cache")
                Spacer(Modifier.height(12.dp))
                Text("Live-TV-Puffer (Cache-Dauer)", color = Color(0xFFE8EEFC), fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                BufferSlider(
                    value = bufferSec,
                    max = devMax,
                    onChange = { sec ->
                        bufferSec = sec
                        PlayPrefs.setBufferSec(ctx, sec)
                    }
                )
                Spacer(Modifier.height(6.dp))
                if (bufferSec == 0) {
                    Text("Auto: das Gerät wählt selbst (bis $devMax s). Links/Rechts stellt die Sekunden ein.",
                        color = Color(0xFF6B7A99), fontSize = 12.sp)
                } else {
                    Text("$bufferSec s von maximal $devMax s (mehr schafft diese Box nicht). " +
                         "Gilt ab dem nächsten Kanalstart.",
                        color = Color(0xFF8FD694), fontSize = 12.sp)
                }
            }
        }

        item {
            Section("Speicher & Cache") {
                val cacheBytesTotal = dirBytes(cacheDir) + dirBytes(logoDir)
                val dlBytesTotal = dirBytes(dlDir)
                InfoRow("Cache (EPG + Logos)", mb(cacheBytesTotal))
                InfoRow("Downloads (Serien)", mb(dlBytesTotal))
                Text("EPG, Logos und heruntergeladene Serien werden lokal gespeichert.",
                    color = Color(0xFF6B7A99), fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BtnSmall("Cache & Logos löschen", danger = true) {
                        try { cacheDir.deleteRecursively(); logoDir.deleteRecursively() } catch (_: Exception) {}
                        storageTick++
                    }
                    BtnSmall("Downloads löschen", danger = true) {
                        try { dlDir.deleteRecursively() } catch (_: Exception) {}
                        storageTick++
                    }
                }
            }
        }

        item {
            Section("Aufnahmen & Timeshift") {
                // Timeshift: wie weit zurueck bei pausiertem Live-TV
                Text("Timeshift (Live-Pause: Zurueckspulen)", color = Color(0xFFE8EEFC), fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BtnSmall("Standard (auto)", selected = lookback == 0L) {
                        lookback = 0L; PlayPrefs.setLookback(ctx, 0L)
                    }
                    BtnSmall("15 Min", selected = lookback == 900_000L) {
                        lookback = 900_000L; PlayPrefs.setLookback(ctx, 900_000L)
                    }
                    BtnSmall("30 Min", selected = lookback == 1_800_000L) {
                        lookback = 1_800_000L; PlayPrefs.setLookback(ctx, 1_800_000L)
                    }
                    BtnSmall("60 Min", selected = lookback == 3_600_000L) {
                        lookback = 3_600_000L; PlayPrefs.setLookback(ctx, 3_600_000L)
                    }
                }
                Text("Gruesser = laengeres Puffern beim Live-TV. NUR auf dieser Box lokal.",
                    color = Color(0xFF6B7A99), fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                // vorhandene Aufnahmen verwalten
                if (recs.isEmpty()) {
                    Text("Noch keine Aufnahmen.", color = Color(0xFF6B7A99), fontSize = 13.sp)
                } else {
                    val fmt = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.GERMAN)
                    recs.take(6).forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.nameWithoutExtension, color = Color(0xFFE8EEFC), fontSize = 14.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(fmt.format(java.util.Date(f.lastModified())) + " · " + mb(f.length()),
                                    color = Color(0xFF6B7A99), fontSize = 12.sp)
                            }
                            BtnSmall("Löschen", danger = true) {
                                scope.launch(Dispatchers.IO) {
                                    runCatching { f.delete() }
                                    kotlinx.coroutines.withContext(Dispatchers.Main) { storageTick++ }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (recs.size > 6) Text("… und " + (recs.size - 6) + " weitere in: Aufnahmen",
                        color = Color(0xFF6B7A99), fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                BtnSmall("Alle Aufnahmen löschen", danger = true) {
                    scope.launch(Dispatchers.IO) {
                        runCatching { recDir.deleteRecursively() }
                        kotlinx.coroutines.withContext(Dispatchers.Main) { storageTick++ }
                    }
                }
            }
        }

        item {
            Section("Kindersicherung") {
                PinSettings()
            }
        }

        item {
            Section("Kategorien & Sortierung") {
                Text(
                    "Live-TV:\n" +
                    "• Menü-Taste (oder Langdruck) auf einer Kategorie = Sortieren · Ausblenden · Umbenennen.\n" +
                    "• Kanäle: Menü-Taste = Verschieben · FSK18 · Ausblenden.\n" +
                    "• Sortierreihenfolge oben links: Nummer / A–Z / Manuell.",
                    color = Color(0xFF8FA3C8), fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BtnSmall("Live-Kategorien zurücksetzen") {
                        CategoryPrefs.resetAll("live")
                        storageTick++
                    }
                    BtnSmall("Kanal-Verwaltung zurücksetzen (FSK/Sortierung/Sichtbarkeit)") {
                        ChannelPrefs.resetAll(ctx)
                        storageTick++
                    }
                }
            }
        }

        item {
            Section("Support") {
                Text("Kontakt:", color = Color(0xFF8FA3C8), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                BtnSmall("support@smartys.tech") {
                    scope.launch {
                        runCatching {
                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("mailto:support@smartys.tech")))
                        }
                    }
                }
                BtnSmall("panel.smartys.tech") {
                    scope.launch {
                        runCatching {
                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(panelUrl.ifBlank { "https://panel.smartys.tech" })))
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text("Smarty Player v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE}) · panel.smartys.tech · Kontakt: support@smartys.tech",
                color = Color(0xFF4A5878), fontSize = 12.sp)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFF141A2A), shape = RoundedCornerShape(14.dp))
            .padding(18.dp)
    ) {
        Text(title, color = Color(0xFF8FA3C8), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFF8FA3C8), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color(0xFFE8EEFC), fontSize = 13.sp)
    }
}

@Composable
private fun ProfileSwitcher(ctx: Context, onResult: (String, String) -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val profiles = remember(tick) { ProfileStore.list(ctx) }
    val activeId = ProfileStore.activeId(ctx)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        profiles.forEach { p ->
            BtnSmall(p.name, selected = (p.id == activeId)) {
                ProfileStore.setActive(ctx, p.id)
                tick++
                onResult(p.name, p.id)
            }
        }
        BtnSmall("+ Neu") { showAdd = true }
    }
    if (showAdd) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Neues Profil", color = Color(0xFFE8EEFC), fontSize = 18.sp) },
            text = {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        ProfileStore.add(ctx, name.trim())
                        tick++
                        showAdd = false
                    }
                }) { Text("Anlegen") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Abbrechen") } },
            containerColor = Color(0xFF10141F)
        )
    }
}

/**
 * Cache-Regler im Desktop-Stil, aber D-Pad-tauglich (Fire OS 6 zeigt keinen
 * System-Fokus): Links/Rechts ändert die Sekunden, der Rahmen zeigt den Fokus.
 * max = harte Gerätegrenze (DeviceCap.maxBufferSec) — höher geht es nicht.
 */
@Composable
private fun BufferSlider(value: Int, max: Int, onChange: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var trackW by remember { mutableStateOf(0) }
    val frac = if (max > 0) (value.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f
    val thumbPx = with(LocalDensity.current) { 18.dp.toPx() }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onChange((value - 1).coerceIn(0, max)); true }
                    Key.DirectionRight -> { onChange((value + 1).coerceIn(0, max)); true }
                    else -> false
                }
            }
            .then(
                if (focused) Modifier.border(3.dp, Color(0xFFFFC94D), RoundedCornerShape(10.dp))
                else Modifier.border(1.dp, Color(0xFF3A4666), RoundedCornerShape(10.dp))
            )
            .background(if (focused) Color(0xFF232C42) else Color(0xFF141A2A), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f).height(28.dp).onSizeChanged { trackW = it.width }) {
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxWidth().height(6.dp)
                    .background(Color(0xFF2A3450), RoundedCornerShape(3.dp))
            )
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxWidth(frac).height(6.dp)
                    .background(Color(0xFFFFC94D), RoundedCornerShape(3.dp))
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(((trackW - thumbPx) * frac).toInt(), 0) }
                    .size(18.dp)
                    .background(Color(0xFFFFC94D), CircleShape)
                    .border(3.dp, Color(0xFF0B0E14), CircleShape)
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            if (value <= 0) "aus" else "$value s",
            color = if (focused) Color(0xFFFFC94D) else Color(0xFFE8EEFC),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.widthIn(min = 52.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BtnSmall(text: String, selected: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // KEINE gold gefüllten Buttons mehr: eine goldene Fläche verschluckt den
    // Fokus-Marker (Fire OS 6 zeichnet keinen System-Fokusring). Auswahl wird
    // über Goldschrift + Goldrahmen gezeigt, der Fokus über hellere Fläche + 3 dp.
    val fg = when {
        danger -> Color(0xFFFFB3B3)
        selected -> Color(0xFFFFC94D)
        else -> Color(0xFFE8EEFC)
    }
    val bg = when {
        focused -> Color(0xFF232C42)
        selected -> Color(0xFF1A2238)
        danger -> Color(0xFF2A1A1A)
        else -> Color(0xFF141A2A)
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = bg,
        border = when {
            // Nur der Fokus bekommt den Goldrahmen; "ausgewaehlt" ist an der
            // Goldschrift erkennbar. Sonst wirken Auswahl und Fokus gleich.
            focused -> androidx.compose.foundation.BorderStroke(3.dp, Color(0xFFFFC94D))
            danger -> androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF5A3A3A))
            else -> androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A4666))
        },
        // KEIN zusätzliches .focusable(): Surface(onClick=…) ist schon fokussierbar,
        // ein zweiter Fokus-Knoten lässt onFocusChanged den falschen Knoten melden
        // (Fokus-Marker erscheint nie / auf allen Buttons gleichzeitig).
        modifier = Modifier.onFocusChanged { focused = it.isFocused }
    ) {
        Text(text, color = fg, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
    }
}


// ---------- QR-Code (ZXing BitMatrix auf weiße Fläche, ohne UI-Abhängigkeit) ----------
@Composable
fun QrCode(text: String, size: androidx.compose.ui.unit.Dp = 160.dp, modifier: Modifier = Modifier) {
    // MARGIN=0: ZXing zentriert das Symbol sonst mit eigenem, viel zu großem Rand
    // in die Zielmatrix (260x260) — dadurch blieb ein Drittel der weißen Karte leer.
    // 0x0 = natürliche Modulgröße, keine Hochskalierung.
    val matrix = remember(text) {
        runCatching {
            val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 0)
            com.google.zxing.qrcode.QRCodeWriter()
                .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, hints)
        }.getOrNull()
    }
    if (matrix != null) {
        Canvas(modifier = modifier.background(Color.White).size(size)) {
            // NUR mit Pixeln rechnen (this.size = gezeichnete Fläche in px).
            // size.value waere dp und erzeugte auf 2x-Displays die halbe Kantenlaenge.
            val n = matrix.width.coerceAtLeast(1)
            val px = this.size.width
            // Ruhezone exakt 4 Module (QR-Standard) — der Rest ist Symbolflaeche.
            val quiet = px * 4f / n
            val c = (px - 2 * quiet) / n
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    drawRect(Color.Black,
                        androidx.compose.ui.geometry.Offset(quiet + x * c, quiet + y * c),
                        androidx.compose.ui.geometry.Size(c, c))
                }
            }
        }
    }
}

@Composable
fun PairingScreen(vm: MainViewModel) {
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    val playerId = remember { CredStore.playerId(appCtx) }
    val pairingPw = remember { CredStore.pairingPw(appCtx) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var unlockInput by remember { mutableStateOf("") }
    var unlockBusy by remember { mutableStateOf(false) }
    // Fokus-Marker fuer die Aktionen: der Button ist selbst gold, ein goldener
    // Fokusrahmen waere unsichtbar -> invertierter dunkler Ring (Skill-Regel
    // "inverted ring on accent-colored buttons").
    var checkBtnFocus by remember { mutableStateOf(false) }
    var unlockBtnFocus by remember { mutableStateOf(false) }
    val checkFocus = androidx.compose.ui.focus.FocusRequester()
    val unlockFocus = androidx.compose.ui.focus.FocusRequester()

    // Fokus automatisch: normal auf "Prüfen", im Unlock-Modus auf das
    // Passwort-Feld — D-Pad/OK der Fire-TV-Fernbedienung trifft sofort.
    // FokusRequester erst nach dem ersten Frame ATTACHED, daher Wiederholversuche.
    LaunchedEffect(vm.unlockNeeded) {
        repeat(15) {
            kotlinx.coroutines.delay(200)
            if (vm.unlockNeeded) {
                if (runCatching { unlockFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            } else {
                if (runCatching { checkFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    }

    // 2 Spalten statt durchgehendem Hochformat: Auf dem Cube (960×540dp)
    // scrollte das alte Layout die Player-ID/PIN aus dem Bild, wenn der
    // Fokus unten lag — MAC/Passwort waren nicht kontrollierbar.
    // Jetzt liegt alles auf einem Blick, ohne Scrollen.
    // QR-Größe relativ zum Canvas statt fester dp: auf schmalen Canvassen
    // (Stick meldet z.B. 640x360 dp) war die Zeile breiter als der Screen.
    val actCfg = LocalConfiguration.current
    val qrSize = minOf((actCfg.screenWidthDp * 0.26f).dp, (actCfg.screenHeightDp * 0.44f).dp)
        .coerceIn(110.dp, 260.dp)
    Row(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ==================== LINKE SPALTE ====================
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Smarty Player", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFC94D))
            Spacer(Modifier.height(3.dp))
            Text("Player freischalten", color = Color.Gray, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))

            // Player-ID + Passwort groß und auf einer Zeile (IBPlayer-Prinzip)
            Row(
                Modifier.fillMaxWidth(0.94f).background(Color(0xFF10141F), RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(Modifier.weight(1.6f)) {
                    Text("Deine Player-ID", color = Color(0xFF8FA3C8), fontSize = 12.sp)
                    Text(playerId, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8EEFC))
                }
                Column(Modifier.weight(1f)) {
                    Text("Dein Passwort", color = Color(0xFF8FA3C8), fontSize = 12.sp)
                    Text(pairingPw, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFC94D))
                }
            }
            Spacer(Modifier.height(12.dp))

            Text(
                "1. QR-Code scannen und Playlist hinterlegen\n" +
                "2. Oder manuell: panel.smartys.tech mit Player-ID + Passwort\n" +
                "3. Danach hier auf „Prüfen“ drücken",
                color = Color(0xFF8FA3C8), fontSize = 13.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(16.dp))

            // ==================== AKTION ====================
            if (vm.unlockNeeded) {
                // ------- Sicherheits-Passwort (Line im Panel ist verschlüsselt) -------
                Text(
                    "Line ist verschlüsselt — Sicherheits-Passwort eingeben",
                    color = Color(0xFFE8EEFC), fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    "(das Passwort, mit dem die Playlist im Panel abgesichert wurde)",
                    color = Color(0xFF8FA3C8), fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                if (!unlockBusy) {
                    OutlinedTextField(
                        value = unlockInput,
                        onValueChange = { unlockInput = it; vm.unlockError = "" },
                        modifier = Modifier.fillMaxWidth(0.85f).focusRequester(unlockFocus),
                        singleLine = true,
                        placeholder = { Text("Sicherheits-Passwort", fontSize = 14.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFFC94D),
                            unfocusedBorderColor = Color(0xFF3A4358),
                            focusedLabelColor = Color(0xFFFFC94D)
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        )
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (unlockBusy) {
                    Text("Entsperre…", color = Color(0xFF8FA3C8), fontSize = 14.sp)
                } else {
                    Button(onClick = {
                        android.util.Log.d("SmartysIPTV", "Entsperren-Button geklickt")
                        val pw = unlockInput
                        if (pw.isEmpty()) { msg = "Bitte Sicherheits-Passwort eingeben."; return@Button }
                        unlockBusy = true
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            // PBKDF2 (310k Iterationen) dauert kurz: auf IO-Rechner
                            val ok = vm.unlockLine(pw)
                            android.util.Log.d("SmartysIPTV", "unlockLine Ergebnis: $ok")
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                unlockBusy = false
                                if (!ok) {
                                    msg = if (vm.unlockError.isNotEmpty()) "Passwort falsch oder ungültig — Line bleibt verschlüsselt."
                                          else "Entsperren fehlgeschlagen."
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .onFocusChanged { unlockBtnFocus = it.isFocused },
                    // NICHT gold füllen: auf Gold ist kein Fokus-Marker sichtbar
                    // (Fire OS 6 malt keinen System-Fokusring). Dunkle Fläche +
                    // Goldschrift, Fokus = goldener Rahmen + hellere Fläche.
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (unlockBtnFocus) 3.dp else 1.dp,
                        color = if (unlockBtnFocus) Color(0xFFFFC94D) else Color(0xFF3A4666)
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (unlockBtnFocus) Color(0xFF1C2438) else Color(0xFF141A2A),
                        contentColor = Color(0xFFFFC94D))) {
                        Text("Entsperren — Line laden", Modifier.padding(horizontal = 26.dp, vertical = 6.dp), fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                val m = msg
                val statusText = when {
                    busy -> "Verbinde mit panel.smartys.tech…"
                    vm.unlockError.isNotEmpty() -> vm.unlockError
                    m != null -> m
                    else -> ""
                }
                if (statusText.isNotEmpty()) {
                    val isError = vm.unlockError.isNotEmpty() || m?.startsWith("Passwort") == true
                    Text(
                        statusText,
                        color = if (isError) Color(0xFFFF6B6B) else Color(0xFF8FA3C8),
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
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
                                // verschlüsselte Line empfangen -> unlockNeeded wird
                                // gesetzt, UI blendet den Passwort-Prompt ein
                                if (!ok && !vm.unlockNeeded) {
                                    msg = "Noch keine Playlist hinterlegt. Auf der Website einrichten, dann erneut prüfen."
                                }
                            }
                        }
                    }, modifier = Modifier
                       .focusRequester(checkFocus)
                       .onFocusChanged { checkBtnFocus = it.isFocused },
                       border = androidx.compose.foundation.BorderStroke(
                           width = if (checkBtnFocus) 3.dp else 1.dp,
                           color = if (checkBtnFocus) Color(0xFFFFC94D) else Color(0xFF3A4666)
                       ),
                       colors = ButtonDefaults.buttonColors(
                           containerColor = if (checkBtnFocus) Color(0xFF1C2438) else Color(0xFF141A2A),
                           contentColor = Color(0xFFFFC94D))) {
                        Text("Prüfen — Playlist laden", Modifier.padding(horizontal = 26.dp, vertical = 6.dp), fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    msg?.let { Text(it, color = Color(0xFF8FA3C8), fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
            }
        }

        Spacer(Modifier.width(24.dp))

        // ==================== RECHTE SPALTE: QR ====================
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // size= MUSS gesetzt sein: QrCode() ruft intern .size(size) auf und
            // überschreibt jede width/height des Aufrufers — Default ist 160.dp,
            // deshalb blieb der QR trotz 240.dp-Wunsch klein.
            QrCode(
                "https://panel.smartys.tech/pair?mac=$playerId&pw=$pairingPw",
                size = qrSize,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.height(10.dp))
            Text("QR-Code mit dem Handy scannen", color = Color(0xFF8FA3C8), fontSize = 13.sp)
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
    // Responsiv: Fire TV Cube meldet 1920x1080 @ 320dpi => nur ~960x540dp effektiv.
    // Feste dp-Werte (300dp Sidebar etc.) sind da viel zu groß. Compact-Modus
    // skaliert die Elemente herunter, damit mehrere Sender sichtbar sind.
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val compact = cfg.screenWidthDp < 1000
    var favMode by remember { mutableStateOf(false) }
    // Sortier-Modus + Kanal-Langdruck-Menü + FSK18-PIN-Sperre
    var sortMode by remember { mutableStateOf(ChannelPrefs.sortMode(appCtx)) }
    var channelMenuFor by remember { mutableStateOf<LiveStream?>(null) }
    // Kategorie-Verwaltung (Menu-Taste / Langdruck in Live-Sidebar)
    var catMenuFor by remember { mutableStateOf<Category?>(null) }
    // Kategorien sortiert/umbenannt/ausgeblendet (wie VOD, Scope "live")
    val orderedCats = remember(categories, catMenuFor) { CategoryPrefs.applyTo("live", categories) }
    var lockPinFor by remember { mutableStateOf<LiveStream?>(null) }
    var playGateFor by remember { mutableStateOf<LiveStream?>(null) }  // FSK18: PIN vor Abspielen
    val catListState = rememberLazyListState()
    val firstRowFocus = remember { FocusRequester() }
    val searchFieldFocus = remember { FocusRequester() }
    // Tick: +1 bei jedem Klick auf eine Kategorie => Fokus auf 1. Kanal lenken
    var focusListTick by remember { mutableStateOf(0) }

    val shown = remember(channels, searchQuery, selectedCategory, favMode, vm.favorites, sortMode, catMenuFor) {
        var list = channels
        if (favMode) list = list.filter { it.streamId in vm.favorites }
        if (selectedCategory != null) list = list.filter { it.categoryId == selectedCategory }
        else {
            // „Alle Kanäle": Kanäle ausgeblendeter Gruppen raus (Sonst steht z.B. der 1. Start-Kanal aus einer ausgeblendeten Gruppe)
            val hiddenCats = CategoryPrefs.hiddenIds("live")
            if (hiddenCats.isNotEmpty()) list = list.filter { it.categoryId !in hiddenCats }
        }
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
                val i = orderedCats.indexOfFirst { it.id == selectedCategory }
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
            modifier = Modifier.width(if (compact) 190.dp else 300.dp).fillMaxHeight()
                .background(Color(0xFF11172A)).padding(vertical = if (compact) 8.dp else 14.dp)
        ) {
            item {
                CategoryItem("Alle Kanäle", selectedCategory == null && !favMode, onClick = { categoryClicked(null, false) }, compact = compact)
                CategoryItem("Favoriten (${vm.favorites.size})", favMode, onClick = { categoryClicked(null, true) }, compact = compact)
                Spacer(Modifier.height(8.dp))
            }
            items(orderedCats) { cat ->
                CategoryItem(
                    CategoryPrefs.labelOf("live", cat),
                    selectedCategory == cat.id,
                    onClick = { categoryClicked(cat.id, false) },
                    onMenu = { catMenuFor = cat },
                    compact = compact
                )
            }
        }

        // ---- Hauptbereich ----
        Column(Modifier.weight(1f).padding(horizontal = if (compact) 18.dp else 32.dp, vertical = if (compact) 12.dp else 20.dp)) {
            // Kopfzeile: Such-Pill rechts (002-Stil)
            // WICHTIG: Suche ist NUR per OK-Klick aktivierbar. Fokus (D-Pad) darf das Feld
            // NICHT sofort oeffnen (Fire-TV-Tastatur-Problem). Erst Klick => Tastatur.
            var searchArmed by remember { mutableStateOf(false) }
            var searchFocused by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (favMode) "Favoriten" else categories.firstOrNull { it.id == selectedCategory }?.name ?: "Alle Kanäle",
                        color = Color(0xFFE8EEFC), fontSize = if (compact) 18.sp else 24.sp, fontWeight = FontWeight.Bold
                    )
                }

                if (searchArmed) {
                    // Aktiver Such-Modus: echtes Eingabefeld, Fokus dorthin
                    OutlinedTextField(
                        value = searchQuery, onValueChange = onSearch,
                        placeholder = { Text("Kanal suchen…", color = Color(0xFF6B7A99)) },
                        modifier = Modifier
                            .width(if (compact) 220.dp else 340.dp)
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
                    val pillF = remember { mutableStateOf(false) }
                    Surface(
                        onClick = { searchArmed = true },
                        shape = MaterialTheme.shapes.extraLarge,
                        color = if (pillF.value) Color(0xFF1B2438) else Color(0xFF0B0E14),
                        border = androidx.compose.foundation.BorderStroke(
                            if (pillF.value) 2.dp else 1.dp,
                            if (pillF.value || searchFocused) Color(0xFFFFC94D) else Color(0xFF232C42)
                        ),
                        modifier = Modifier
                            .width(if (compact) 220.dp else 340.dp)
                            .onFocusChanged { pillF.value = it.isFocused }
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
                val sortF = remember { mutableStateOf(false) }
                Surface(
                    onClick = {
                        val modes = listOf("num", "name", "manual")
                        val next = modes[(modes.indexOf(sortMode) + 1) % modes.size]
                        sortMode = next
                        ChannelPrefs.setSortMode(appCtx, next)
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (sortF.value) Color(0xFF1B2438) else Color(0xFF11172A),
                    border = androidx.compose.foundation.BorderStroke(
                        if (sortF.value) 2.dp else 1.dp,
                        if (sortF.value) Color(0xFFFFC94D) else Color(0xFF232C42)
                    ),
                    modifier = Modifier.onFocusChanged { sortF.value = it.isFocused }
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
                IconBtn("📅", onClick = onOpenEpg)
                Spacer(Modifier.width(6.dp))
                IconBtn("⚙", onClick = onOpenSettings)
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
                        onMenu = { channelMenuFor = ch },
                        focusOnAppear = if (shown.indexOfFirst { it.streamId == ch.streamId } == 0) firstRowFocus else null,
                        compact = compact
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

    // ---- Kategorie-Verwaltung: MENU-Taste (FireTV) oder Langdruck ----
    catMenuFor?.let { c ->
        CategoryMenuDialog(
            scope = "live",
            cat = c,
            cats = categories,
            onDismiss = { catMenuFor = null }
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

// Icon-Button für die Top-Bar: deutlicher TV-Fokus (Gold-Rahmen + heller Grund)
@Composable
private fun IconBtn(icon: String, onClick: () -> Unit) {
    val f = remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (f.value) Color(0xFF1B2438) else Color(0xFF11172A),
        border = androidx.compose.foundation.BorderStroke(
            if (f.value) 2.dp else 1.dp,
            if (f.value) Color(0xFFFFC94D) else Color(0xFF232C42)
        ),
        modifier = Modifier.onFocusChanged { f.value = it.isFocused }
    ) {
        Text(icon, fontSize = 20.sp, color = if (f.value) Color(0xFFE8EEFC) else Color(0xFF8FA3C8),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CategoryItem(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    onMenu: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    compact: Boolean = false
) {
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
            // FireTV: MENU-Taste => Kategorie-Menue (Sortieren/Ausblenden/Umbenennen)
            .onPreviewKeyEvent { e ->
                val m = onMenu
                if (m != null && e.key == Key.Menu && e.type == KeyEventType.KeyDown) { m(); true } else false
            }
            .combinedClickable(onClick = onClick, onLongClick = onMenu)
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
                modifier = Modifier.padding(horizontal = if (compact) 14.dp else 20.dp, vertical = if (compact) 6.dp else 10.dp),
                fontSize = if (compact) 13.sp else 15.sp,
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
    onMenu: (() -> Unit)? = null,
    focusOnAppear: FocusRequester? = null,
    compact: Boolean = false
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
            // FireTV: MENU-Taste => Kanal-Menue
            .onPreviewKeyEvent { e ->
                val m = onMenu
                if (m != null && e.key == Key.Menu && e.type == KeyEventType.KeyDown) { m(); true } else false
            }
            .combinedClickable(onClick = onPlay, onLongClick = onLongPress)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 6.dp else 10.dp)) {
            // Kanalnummer (002-Stil: rechtsbündig, gedämpft)
            Text(
                "${channel.num ?: ""}",
                color = Color(0xFF55648A), fontSize = if (compact) 14.sp else 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.width(if (compact) 42.dp else 56.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
            Spacer(Modifier.width(if (compact) 12.dp else 18.dp))
            AsyncImage(
                model = channel.streamIcon,
                contentDescription = null,
                modifier = Modifier
                    .size(width = if (compact) 68.dp else 92.dp, height = if (compact) 38.dp else 52.dp)
                    .background(Color(0xFF141A2A), MaterialTheme.shapes.small)
                    .padding(4.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.width(if (compact) 14.dp else 20.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, color = Color(0xFFE8EEFC), fontSize = if (compact) 15.sp else 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    epgText != null -> Text(
                        epgText,
                        color = if (focused) Color(0xFFFFC94D) else Color(0xFF7D8BA8), fontSize = if (compact) 12.sp else 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    else -> Text("", fontSize = 13.sp)
                }
            }
            TextButton(onClick = onToggleFav) {
                Text(if (isFav) "★" else "☆", fontSize = if (compact) 19.sp else 22.sp,
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

    // ---- Aufnahme (Menu-Taste) ----
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    var recording by remember { mutableStateOf(Recorder.active()) }
    var showMenu by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }

    // Bildschirm anlassen (FireTV: kein Bildschirmschoner beim Schauen);
    // beim Verlassen des Players die System-Bars wieder zeigen.
    DisposableEffect(Unit) {
        keepScreenOn(appCtx, true)
        onDispose {
            setSystemBarsHidden(appCtx, false)
            keepScreenOn(appCtx, false)
        }
    }

    fun toggleRecording() {
        if (recording) {
            Recorder.stop()
            recording = false
        } else {
            val url = vm.tsUrlFor(channel)
            if (url != null) {
                val name = "${channel.name.replace(Regex("[^A-Za-z0-9äöüÄÖÜß _-]"), "_")}-${System.currentTimeMillis()}"
                val f = Recorder.start(appCtx, url, channel.name, name)
                recording = f != null
            }
        }
        showMenu = false
        showOverlay = true
    }

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
                    Key.Menu -> { showMenu = !showMenu; true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { viewCtx ->
                PlayerView(viewCtx).apply {
                    this.player = player
                    useController = false
                    attachPlayerGestures(this,
                        onTap = { showOverlay = !showOverlay },
                        onDoubleTap = {
                            fullscreen = !fullscreen
                            setSystemBarsHidden(appCtx, fullscreen)
                        })
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
                            if (recording) {
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    "● REC",
                                    color = Color(0xFFFFFFFF), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .background(Color(0xFFCC2222), MaterialTheme.shapes.extraLarge)
                                        .padding(horizontal = 10.dp, vertical = 3.dp)
                                )
                            }
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

        // ---- Kontextmenü (Menu-Taste): Aufnahme starten/stoppen ----
        if (showMenu) {
            Box(Modifier.fillMaxSize().background(Color(0x80000000))) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .width(420.dp)
                        .background(Color(0xFF10141F), MaterialTheme.shapes.large)
                        .padding(16.dp)
                ) {
                    Text("Menü", color = Color(0xFF6B7A99), fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        onClick = { toggleRecording() },
                        shape = RoundedCornerShape(10.dp),
                        color = if (recording) Color(0xFF2A1A1A) else Color(0xFF141A2A),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (recording) Color(0xFFFF6B6B) else Color(0xFFFFC94D)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (recording) "⏹ Aufnahme stoppen" else "● Aufnahme starten",
                            color = if (recording) Color(0xFFFF9B9B) else Color(0xFFFFC94D),
                            fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        onClick = { showMenu = false },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF141A2A),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A4666)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Schließen",
                            color = Color(0xFF8FA3C8),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                        )
                    }
                }
            }
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
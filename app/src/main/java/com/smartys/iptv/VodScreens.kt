package com.smartys.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// ---------- VOD / Serien-Typen für die Wiedergabe ----------

data class VodPlayback(val url: String, val title: String, val key: String, val startPosMs: Long = 0L)

// ---------- Filme-Grid ----------

@Composable
fun MovieGrid(
    vm: MainViewModel,
    categories: List<Category>,
    movies: List<VodStream>,
    selectedCategory: String?,
    onSelectCategory: (String?) -> Unit,
    onOpen: (VodStream) -> Unit,
    focusRestoreId: String? = null,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var searchQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var searchArmed by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    val searchFieldFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val restoreFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    val compact = LocalConfiguration.current.screenWidthDp < 1000
    var catMenuRefresh by remember { mutableStateOf(0) }
    val shown = remember(movies, selectedCategory, searchQuery, catMenuRefresh) {
        var list = movies
        if (selectedCategory != null) list = list.filter { it.categoryId == selectedCategory }
        else {
            val hiddenCats = CategoryPrefs.hiddenIds("movies")
            if (hiddenCats.isNotEmpty()) list = list.filter { it.categoryId !in hiddenCats }
        }
        if (searchQuery.isNotBlank()) list = list.filter { it.name.contains(searchQuery, true) }
        list
    }

    // Ruecksprung aus der Detailseite: zum zuletzt geoeffneten Film scrollen + fokussieren
    val restoreIdx = shown.indexOfFirst { it.streamId == focusRestoreId }
    val firstCatFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(focusRestoreId, shown.size) {
        if (restoreIdx >= 0) {
            kotlinx.coroutines.delay(150)
            runCatching {
                gridState.scrollToItem(restoreIdx.coerceAtLeast(0))
                restoreFocus.requestFocus()
            }
        } else {
            // Erster Einstieg: Fokus aufs Kategoriemenue statt aufs Suchfeld.
            kotlinx.coroutines.delay(150)
            runCatching { firstCatFocus.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Kopfzeile: Titel + Suche + Settings
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 32.dp, vertical = 20.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Filme", color = Color(0xFFE8EEFC), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("${shown.size} Filme", color = Color(0xFF6B7A99), fontSize = 12.sp)
            }
            if (searchArmed) {
                OutlinedTextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    placeholder = { Text("Film suchen…", color = Color(0xFF6B7A99)) },
                    modifier = Modifier.width(340.dp)
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
                Surface(
                    onClick = { searchArmed = true },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = Color(0xFF0B0E14),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232C42)),
                    modifier = Modifier.width(300.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp)) {
                        Text("🔍", fontSize = 15.sp, color = Color(0xFF6B7A99))
                        Spacer(Modifier.width(10.dp))
                        Text(searchQuery.ifBlank { "Film suchen…" }, color = if (searchQuery.isBlank()) Color(0xFF6B7A99) else Color(0xFFE8EEFC), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            TextButton(onClick = onBack) { Text("←", fontSize = 22.sp, color = Color(0xFF8FA3C8)) }
            TextButton(onClick = onOpenSettings) { Text("⚙", fontSize = 22.sp, color = Color(0xFF8FA3C8)) }
        }

        LaunchedEffect(searchArmed) { if (searchArmed) { kotlinx.coroutines.delay(100); runCatching { searchFieldFocus.requestFocus() } } }
        LaunchedEffect(searchFocused) { if (!searchFocused && searchArmed) { kotlinx.coroutines.delay(200); if (!searchFocused) searchArmed = false } }

        if (vm.vodLoading && movies.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFFFFC94D)) }
        } else {
            Row(Modifier.fillMaxSize()) {
                // Kategorien-Sidebar (sortierbar/ausblendbar via Lang-OK)
                var catMenuFor by remember { mutableStateOf<Category?>(null) }
                val orderedCats = remember(categories, catMenuFor) {
                    CategoryPrefs.applyTo("movies", categories)
                }
                LazyColumn(Modifier.width(if (compact) 240.dp else 300.dp).fillMaxHeight().background(Color(0xFF11172A)).padding(vertical = 14.dp)) {
                    item { CategoryItem("Alle Filme", selectedCategory == null, onClick = { onSelectCategory(null) }, focusRequester = firstCatFocus) }
                    items(orderedCats) { cat ->
                        VodCategoryItem(
                            cat = cat,
                            selected = selectedCategory == cat.id,
                            onClick = { onSelectCategory(cat.id) },
                            onLongPress = { catMenuFor = cat },
                            onMenu = { catMenuFor = cat },
                            label = CategoryPrefs.labelOf("movies", cat)
                        )
                    }
                }
                if (catMenuFor != null) {
                    CategoryMenuDialog(
                        scope = "movies",
                        cat = catMenuFor!!,
                        cats = categories,
                        onDismiss = { catMenuFor = null; catMenuRefresh++ }
                    )
                }
                // Poster-Grid
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    state = gridState,
                    contentPadding = PaddingValues(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(shown, key = { it.streamId }) { movie ->
                        val isRestore = movie.streamId == focusRestoreId
                        MovieCard(
                            movie = movie,
                            onClick = { onOpen(movie) },
                            focusRequester = if (isRestore) restoreFocus else null
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MovieCard(movie: VodStream, onClick: () -> Unit, focusRequester: FocusRequester? = null) {
    var focused by remember { mutableStateOf(false) }
    // TMDb-Poster-Fallback: kein stream_icon, aber tmdb-Id vorhanden -> Cover nachladen.
    var tmdbPoster by remember(movie) { mutableStateOf<String?>(null) }
    val posterUrl = movie.poster() ?: tmdbPoster
    LaunchedEffect(movie) {
        if (movie.poster() == null && !movie.tmdb.isNullOrBlank()) {
            tmdbPoster = withContext(Dispatchers.IO) { TmdbMeta.posterUrl(movie.tmdb) }
        }
    }
    Column(
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        // Poster: goldener Rahmen NUR bei Fokus (eckig, wie im Menü)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .border(
                    width = if (focused) 3.dp else 0.dp,
                    color = Color(0xFFFFC94D)
                )
        ) {
            AsyncImage(
                model = posterUrl,
                contentDescription = movie.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            movie.rating5Based?.let { r ->
                if (r > 0) {
                    Text(
                        "★ ${String.format("%.1f", r)}",
                        color = Color(0xFF0B0E14), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .background(Color(0xFFFFC94D), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            if (focused) {
                Text(
                    "▶",
                    color = Color(0xFF0B0E14), fontSize = 40.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                        .background(Color(0xFFFFC94D), RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }
        }
        // Titel: fokussiert = voller Gold-Balken mit dunkler Schrift
        Text(
            movie.name,
            color = if (focused) Color(0xFF0B0E14) else Color(0xFFB8C4DE),
            fontSize = 13.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focused) Modifier.background(Color(0xFFFFC94D)) else Modifier)
                .padding(10.dp)
        )
    }
}

// ---------- Serien-Grid ----------

@Composable
fun SeriesGrid(
    vm: MainViewModel,
    categories: List<Category>,
    shows: List<SeriesStream>,
    selectedCategory: String?,
    onSelectCategory: (String?) -> Unit,
    onOpen: (SeriesStream) -> Unit,
    focusRestoreId: String? = null,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var searchQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }

    val compact = LocalConfiguration.current.screenWidthDp < 1000
    var catMenuRefresh by remember { mutableStateOf(0) }
    val shown = remember(shows, selectedCategory, searchQuery, catMenuRefresh) {
        var list = shows
        if (selectedCategory != null) list = list.filter { it.categoryId == selectedCategory }
        else {
            val hiddenCats = CategoryPrefs.hiddenIds("series")
            if (hiddenCats.isNotEmpty()) list = list.filter { it.categoryId !in hiddenCats }
        }
        if (searchQuery.isNotBlank()) list = list.filter { it.name.contains(searchQuery, true) }
        list
    }

    // Ruecksprung aus der Serien-Detailseite: Fokus auf zuletzt geoeffnete Serie
    val seriesGridState = rememberLazyGridState()
    val seriesRestoreFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val firstCatFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val restoreIdx = shown.indexOfFirst { it.seriesId == focusRestoreId }
    LaunchedEffect(focusRestoreId, shown.size) {
        if (restoreIdx >= 0) {
            kotlinx.coroutines.delay(150)
            runCatching {
                seriesGridState.scrollToItem(restoreIdx.coerceAtLeast(0))
                seriesRestoreFocus.requestFocus()
            }
        } else {
            // Beim ersten Einstieg (kein Ruecksprung): Fokus aufs Kategoriemenue
            // statt aufs Suchfeld — D-Pad runter soll zuerst die Kategorie treffen.
            kotlinx.coroutines.delay(150)
            runCatching { firstCatFocus.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 32.dp, vertical = 20.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Serien", color = Color(0xFFE8EEFC), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("${shown.size} Serien", color = Color(0xFF6B7A99), fontSize = 12.sp)
            }
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                placeholder = { Text("Serie suchen…", color = Color(0xFF6B7A99)) },
                modifier = Modifier.width(340.dp),
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFF232C42), focusedBorderColor = Color(0xFFFFC94D))
            )
            Spacer(Modifier.width(14.dp))
            TextButton(onClick = onBack) { Text("←", fontSize = 22.sp, color = Color(0xFF8FA3C8)) }
            TextButton(onClick = onOpenSettings) { Text("⚙", fontSize = 22.sp, color = Color(0xFF8FA3C8)) }
        }
        Row(Modifier.fillMaxSize()) {
            var catMenuFor by remember { mutableStateOf<Category?>(null) }
            val orderedCats = remember(categories, catMenuFor) {
                CategoryPrefs.applyTo("series", categories)
            }
            LazyColumn(Modifier.width(if (compact) 240.dp else 300.dp).fillMaxHeight().background(Color(0xFF11172A)).padding(vertical = 14.dp)) {
                item { CategoryItem("Alle Serien", selectedCategory == null, onClick = { onSelectCategory(null) }, focusRequester = firstCatFocus) }
                items(orderedCats) { cat ->
                    VodCategoryItem(
                        cat = cat,
                        selected = selectedCategory == cat.id,
                        onClick = { onSelectCategory(cat.id) },
                        onLongPress = { catMenuFor = cat },
                        onMenu = { catMenuFor = cat },
                        label = CategoryPrefs.labelOf("series", cat)
                    )
                }
            }
            if (catMenuFor != null) {
                CategoryMenuDialog(
                    scope = "series",
                    cat = catMenuFor!!,
                    cats = categories,
                    onDismiss = { catMenuFor = null; catMenuRefresh++ }
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                state = seriesGridState,
                contentPadding = PaddingValues(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(shown, key = { it.seriesId }) { show ->
                    var focused by remember { mutableStateOf(false) }
                    Column(
                        Modifier
                            .then(if (show.seriesId == focusRestoreId) Modifier.focusRequester(seriesRestoreFocus) else Modifier)
                            .onFocusChanged { focused = it.isFocused }
                            .clickable { onOpen(show) }
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                                .border(
                                    width = if (focused) 3.dp else 0.dp,
                                    color = Color(0xFFFFC94D)
                                )
                        ) {
                            AsyncImage(model = show.cover, contentDescription = show.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            if (focused) {
                                Text("▶", color = Color(0xFF0B0E14), fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center).background(Color(0xFFFFC94D), RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 8.dp))
                            }
                        }
                        Text(show.name, color = if (focused) Color(0xFF0B0E14) else Color(0xFFB8C4DE), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().then(if (focused) Modifier.background(Color(0xFFFFC94D)) else Modifier).padding(10.dp))
                    }
                }
            }
        }
    }
}

// ---------- Serien-Detail (Staffeln + Episoden) ----------

@Composable
fun SeriesDetail(
    vm: MainViewModel,
    show: SeriesStream,
    onPlaySeries: (List<VodPlayback>, Int) -> Unit,
    onBack: () -> Unit
) {
    var info by remember { mutableStateOf<SeriesInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(show.seriesId) {
        info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.fetchSeriesInfo(show.seriesId) }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        // Kopf
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)) {
            TextButton(onClick = onBack) { Text("← Zurück", color = Color(0xFF8FA3C8), fontSize = 16.sp) }
            Spacer(Modifier.width(12.dp))
            Text(show.name, color = Color(0xFFE8EEFC), fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFFFFC94D)) }
        } else {
            val episodes = info?.episodes.orEmpty()
            val seasons = info?.seasons.orEmpty()
            // Staffel-Keys aus episodes-Map (falls seasons-Meta fehlt)
            val seasonKeys = remember(episodes) { episodes.keys.sortedBy { it.toIntOrNull() ?: 0 } }

            Column(Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
                // Banner/Info-Zeile
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = show.cover, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF141A2A)))
                    Spacer(Modifier.width(20.dp))
                    Column {
                        show.genre?.let { Text(it, color = Color(0xFF6B7A99), fontSize = 13.sp) }
                        show.plot?.let { Text(it, color = Color(0xFF8FA3C8), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    }
                }
                Spacer(Modifier.height(20.dp))

                // Staffel-Auswahl
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        Text("Staffeln", color = Color(0xFF8FA3C8), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            seasonKeys.forEach { sk ->
                                val isSel = selectedSeason == sk.toIntOrNull() || (selectedSeason == null && sk == seasonKeys.firstOrNull())
                                Surface(
                                    onClick = { selectedSeason = sk.toIntOrNull() },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSel) Color(0xFFFFC94D) else Color(0xFF141A2A),
                                    border = if (!isSel) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF232C42)) else null
                                ) {
                                    Text("Staffel $sk", color = if (isSel) Color(0xFF0B0E14) else Color(0xFF8FA3C8), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                }
                            }
                        }
                    }
                    // Episoden der gewählten Staffel
                    val activeSeason = seasonKeys.firstOrNull { selectedSeason == it.toIntOrNull() } ?: seasonKeys.firstOrNull()
                    val eps = activeSeason?.let { episodes[it] }.orEmpty()
                    // Wiedergabeliste: Episoden der Staffel numerisch sortiert => Auto-Advance
                    val playlist = eps.sortedBy { it.episodeNum?.toIntOrNull() ?: Int.MAX_VALUE }
                    items(playlist) { ep ->
                        val c = vm.creds
                        val idx = playlist.indexOfFirst { it.id == ep.id }
                        var epFocused by remember { mutableStateOf(false) }
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val dlId = "series:${ep.id}"
                        val dl = VodDownloader.items.firstOrNull { it.id == dlId }
                        val dlState = when {
                            dl == null -> "idle"
                            dl.status == "complete" -> "done"
                            dl.status == "running" -> "running"
                            dl.status == "paused" -> "paused"
                            else -> "error"
                        }
                        val epTitle = ep.title ?: "Episode ${ep.episodeNum}"
                        Surface(
                            onClick = {
                                c?.let {
                                    val list = playlist.map { e ->
                                        VodPlayback(
                                            c.seriesUrl(e.id, e.containerExtension ?: "mp4"),
                                            e.title ?: "${show.name} S${activeSeason}E${e.episodeNum}",
                                            key = "series:${e.id}"
                                        )
                                    }
                                    onPlaySeries(list, idx)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (epFocused) Color(0xFF1A2238) else Color(0xFF10141F),
                            border = if (epFocused) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFFC94D)) else null,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                .onFocusChanged { epFocused = it.isFocused }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text("S${activeSeason ?: "?"}E${ep.episodeNum ?: "-"}", color = Color(0xFF55648A), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                                Text(epTitle, color = if (epFocused) Color(0xFFE8EEFC) else Color(0xFFE8EEFC), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                // Download-Button (pro Episode): offline speichern / Status
                                var dlFocus by remember { mutableStateOf(false) }
                                Surface(
                                    onClick = {
                                        c?.let {
                                            val url = c.seriesUrl(ep.id, ep.containerExtension ?: "mp4")
                                            when (dlState) {
                                                "idle", "error" -> VodDownloader.start(ctx, dlId, "${show.name} S${activeSeason}E${ep.episodeNum} - ${epTitle}", url, ep.containerExtension ?: "mp4")
                                                "running" -> VodDownloader.pause(dlId)
                                                "paused" -> VodDownloader.resume(ctx, dlId)
                                                "done" -> { /* bereits heruntergeladen */ }
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (dlFocus) Color(0xFF1A2238) else Color(0xFF0B0E14),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, when (dlState) {
                                        "done" -> Color(0xFF6FCF9F)
                                        "running", "paused" -> Color(0xFFFFC94D)
                                        else -> Color(0xFF3A4666)
                                    }),
                                    modifier = Modifier.onFocusChanged { dlFocus = it.isFocused }
                                ) {
                                    Text(
                                        when (dlState) {
                                            "idle" -> "⬇"
                                            "running" -> "${(dl?.progress?.times(100)?.toInt() ?: 0)}%"
                                            "paused" -> "▶"
                                            "done" -> "✓"
                                            else -> "⚠"
                                        },
                                        color = when (dlState) {
                                            "done" -> Color(0xFF6FCF9F)
                                            "running", "paused" -> Color(0xFFFFC94D)
                                            "error" -> Color(0xFFFF6B6B)
                                            else -> Color(0xFF8FA3C8)
                                        },
                                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text("▶", color = if (epFocused) Color(0xFFFFC94D) else Color(0xFF8FA3C8), fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Film-Detailseite ----------

/** Jahr aus dem Filmtitel ziehen ("EN - Foo (2026)" -> "2026"), sonst aus added-Timestamp. */
fun movieYear(movie: VodStream): String {
    val m = Regex("\\((\\d{4})\\)").find(movie.name)
    if (m != null) return m.groupValues[1]
    val added = movie.added?.toLongOrNull() ?: return ""
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = added * 1000L
    return cal.get(java.util.Calendar.YEAR).toString()
}

@Composable
fun MovieDetail(
    vm: MainViewModel,
    movie: VodStream,
    onPlay: (VodPlayback) -> Unit,
    onBack: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val playFocus = remember { FocusRequester() }

    // TMDB-Metadaten (Beschreibung, Genres, Laufzeit) via Backend-Proxy laden
    var tmdbMeta by remember { mutableStateOf<TmdbMeta.MovieMeta?>(null) }
    var tmdbTried by remember { mutableStateOf(false) }
    LaunchedEffect(movie.streamId) {
        tmdbTried = true
        tmdbMeta = kotlinx.coroutines.withContext(Dispatchers.IO) {
            movie.tmdb?.takeIf { it.isNotBlank() }?.let { TmdbMeta.fetchMovie(it) }
        }
    }

    // Fokus beim Öffnen auf "Abspielen" legen
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { playFocus.requestFocus() }
    }

    Row(Modifier.fillMaxSize().background(Color(0xFF0B0E14)).padding(40.dp)) {
        // Cover links
        Box(
            Modifier
                .width(320.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF141A2A))
        ) {
            AsyncImage(model = movie.poster(), contentDescription = movie.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(40.dp))

        // Infos rechts
        Column(Modifier.weight(1f)) {
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
                Text("← Zurück", color = Color(0xFF8FA3C8), fontSize = 15.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                movie.name,
                color = Color(0xFFE8EEFC), fontSize = 28.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            // Meta-Zeile: Jahr · Kategorie · Bewertung
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val year = movieYear(movie)
                if (year.isNotBlank()) Text(year, color = Color(0xFF6B7A99), fontSize = 15.sp)
                if (movie.categoryName.isNotBlank()) Text(movie.categoryName, color = Color(0xFF6B7A99), fontSize = 15.sp)
                movie.rating5Based?.let { r ->
                    if (r > 0) {
                        Text(
                            "★ ${String.format("%.1f", r)}",
                            color = Color(0xFF0B0E14), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.background(Color(0xFFFFC94D), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            // Action-Buttons: Abspielen (bzw. Fortsetzen) + Trailer
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val resumePos = remember(movie.streamId) { ResumeStore.position(ctx, "movie:${movie.streamId}") }
                val canResume = resumePos > 30_000L  // unter 30s lohnt Fortsetzen nicht
                Surface(
                    onClick = {
                        val c = vm.creds ?: return@Surface
                        onPlay(VodPlayback(c.movieUrl(movie.streamId, movie.containerExtension ?: "mp4"), movie.name, key = "movie:${movie.streamId}", startPosMs = if (canResume) resumePos else 0L))
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFFC94D),
                    modifier = Modifier.focusRequester(playFocus)
                ) {
                    Column(Modifier.padding(horizontal = 28.dp, vertical = 13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (canResume) "▶ Fortsetzen" else "▶ Abspielen",
                            color = Color(0xFF0B0E14), fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        )
                        if (canResume) {
                            val mins = resumePos / 60000
                            Text(
                                "bei ${mins} Min",
                                color = Color(0xFF5C3A00), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                if (canResume) {
                    // Zweiter Button: von vorne beginnen (löscht die gespeicherte Position)
                    Surface(
                        onClick = {
                            val c = vm.creds ?: return@Surface
                            ResumeStore.clear(ctx, "movie:${movie.streamId}")
                            onPlay(VodPlayback(c.movieUrl(movie.streamId, movie.containerExtension ?: "mp4"), movie.name, key = "movie:${movie.streamId}", startPosMs = 0L))
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF141A2A),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFC94D))
                    ) {
                        Text(
                            "↺ Von vorne",
                            color = Color(0xFFFFC94D), fontSize = 17.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 13.dp)
                        )
                    }
                }
                if (movie.hasTrailer()) {
                    Surface(
                        onClick = {
                            // YouTube-Trailer öffnen (App oder Browser)
                            runCatching {
                                val url = "https://www.youtube.com/watch?v=${movie.trailer}"
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF141A2A),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFC94D))
                    ) {
                        Text(
                            "▶ Trailer",
                            color = Color(0xFFFFC94D), fontSize = 17.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 13.dp)
                        )
                    }
                }
                // Download-Button: VOD lokal speichern (mit Resume-Support)
                val dl = VodDownloader.items.firstOrNull { it.id == "movie:${movie.streamId}" }
                val dlState = when {
                    dl == null -> "idle"
                    dl.status == "complete" -> "done"
                    dl.status == "running" -> "running"
                    dl.status == "paused" -> "paused"
                    else -> "error"
                }
                Surface(
                    onClick = {
                        val c = vm.creds ?: return@Surface
                        val url = c.movieUrl(movie.streamId, movie.containerExtension ?: "mp4")
                        when (dlState) {
                            "idle", "error" -> VodDownloader.start(ctx, "movie:${movie.streamId}", movie.name, url, movie.containerExtension ?: "mp4")
                            "running" -> VodDownloader.pause("movie:${movie.streamId}")
                            "paused" -> VodDownloader.resume(ctx, "movie:${movie.streamId}")
                            "done" -> { /* bereits heruntergeladen */ }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF141A2A),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A4666))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 28.dp, vertical = 13.dp)) {
                        Text(
                            when (dlState) {
                                "idle" -> "⬇ Herunterladen"
                                "running" -> "⏸ ${(dl?.progress?.times(100)?.toInt() ?: 0)}%"
                                "paused" -> "▶ Fortsetzen"
                                "done" -> "✓ Heruntergeladen"
                                else -> "⚠ Erneut versuchen"
                            },
                            color = when {
                                dlState == "done" -> Color(0xFF6FCF9F)
                                else -> Color(0xFFE8EEFC)
                            },
                            fontSize = 17.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            // Beschreibung: TMDB-Metadaten (via Backend-Proxy), sonst Platzhalter
            when {
                tmdbMeta?.overview != null -> {
                    tmdbMeta?.tagline?.let {
                        Text(it, color = Color(0xFFFFC94D), fontSize = 15.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                        Spacer(Modifier.height(6.dp))
                    }
                    // Genres + Laufzeit als Meta-Chips
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tmdbMeta?.runtime?.let { rt ->
                            Text("${rt} Min", color = Color(0xFF0B0E14), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.background(Color(0xFF55648A), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                        tmdbMeta?.genres?.take(3)?.forEach { g ->
                            Text(g, color = Color(0xFF8FA3C8), fontSize = 12.sp,
                                modifier = Modifier.background(Color(0xFF141A2A), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        tmdbMeta?.overview ?: "",
                        color = Color(0xFFB8C4DE), fontSize = 15.sp, lineHeight = 22.sp
                    )
                }
                movie.tmdb.isNullOrBlank() -> Text(
                    "Dieser Anbieter liefert keine Inhaltsbeschreibung für diesen Titel.",
                    color = Color(0xFF55648A), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                else -> Text(
                    if (tmdbTried) "Keine Beschreibung zu diesem Titel gefunden." else "Beschreibung wird geladen…",
                    color = Color(0xFF55648A), fontSize = 14.sp
                )
            }
        }
    }
}

// ---------- Kategorie-Verwaltung (Lang-OK in der VOD-Sidebar) ----------

/** Kategorie-Eintrag mit Langdruck (OK lange drücken) => Verwaltungs-Menü. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VodCategoryItem(
    cat: Category,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onMenu: (() -> Unit)? = null,
    label: String = cat.name
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        color = when {
            selected -> Color(0xFF0A0D16)
            focused -> Color(0xFF1B2438)
            else -> Color.Transparent
        },
        shape = MaterialTheme.shapes.medium,
        border = if (focused) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFFC94D)) else null,
        modifier = Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            // FireTV: MENU-Taste => Kategorie verwalten (Sortieren/Ausblenden/Umbenennen)
            .onPreviewKeyEvent { e ->
                val m = onMenu
                if (m != null && e.key == Key.Menu && e.type == KeyEventType.KeyDown) { m(); true } else false
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
    ) {
        Row {
            if (selected) Box(Modifier.width(5.dp).fillMaxHeight().background(Color(0xFFFFC94D)))
            Text(
                label.ifBlank { cat.name },
                color = when {
                    selected -> Color(0xFFFFC94D)
                    focused -> Color(0xFFE8EEFC)
                    else -> Color(0xFF8FA3C8)
                },
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).weight(1f)
            )
            if (CategoryPrefs.isHidden(hiddenScopeFor(cat), cat.id)) {
                Text("👁", fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
            }
        }
    }
}

/** Hilfs-Dummy: hidden-Markierung im Item brauchen wir nicht anzeigen — Scope kommt vom Aufrufer. */
private var currentScope = "movies"
private fun hiddenScopeFor(@Suppress("UNUSED_PARAMETER") cat: Category): String = currentScope

/**
 * Kategorie-Verwaltung (Langdruck-OK in der VOD-Sidebar).
 * Modi: 0 = Einzel-Kategorie (Sort/Umbenennen/Ausblenden),
 *       1 = Mehrfach-Markierung (Block-Sort/Mehrfach-Ausblenden),
 *       2 = Umbenennen (Textfeld).
 */
@Composable
fun CategoryMenuDialog(scope: String, cat: Category, cats: List<Category>, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(0) }
    var marked by remember { mutableStateOf(setOf(cat.id)) }
    var label by remember { mutableStateOf(CategoryPrefs.customLabel(scope, cat.id) ?: cat.name) }
    var refresh by remember { mutableStateOf(0) }
    val visible = remember(cats, refresh) { CategoryPrefs.applyTo(scope, cats) }
    val hasCustom = remember(refresh) { CategoryPrefs.customLabel(scope, cat.id) != null }
    val renameFocus = remember { FocusRequester() }
    val kbd = LocalSoftwareKeyboardController.current
    LaunchedEffect(mode) {
        if (mode == 2) { kotlinx.coroutines.delay(150); runCatching { renameFocus.requestFocus() } }
        if (mode == 0) runCatching { kbd?.hide() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (mode) {
                    0 -> CategoryPrefs.labelOf(scope, cat)
                    1 -> "Kategorien markieren (${marked.size} gewählt)"
                    else -> "Kategorie umbenennen"
                },
                color = Color(0xFFE8EEFC), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            when (mode) {
                0 -> Column {
                    Text("Kategorie sortieren, umbenennen oder ausblenden",
                        color = Color(0xFF6B7A99), fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MenuBtn("▲ Nach oben") { CategoryPrefs.moveUp(scope, cat.id, cats); refresh++; onDismiss() }
                        MenuBtn("▼ Nach unten") { CategoryPrefs.moveDown(scope, cat.id, cats); refresh++; onDismiss() }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MenuBtn("✏️ Umbenennen") {
                            label = CategoryPrefs.customLabel(scope, cat.id) ?: cat.name
                            mode = 2
                        }
                        if (hasCustom) {
                            MenuBtn("🔁 Name zurücksetzen", fg = Color(0xFF8FA3C8)) {
                                CategoryPrefs.setLabel(scope, cat.id, null); refresh++
                            }
                        }
                        MenuBtn("🎯 Mehrere markieren") {
                            marked = setOf(cat.id); mode = 1
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MenuBtn("🚫 Ausblenden", fg = Color(0xFFFF9B9B), bg = Color(0xFF2A1A1A), bd = Color(0xFF664444)) {
                            CategoryPrefs.hide(scope, cat.id); refresh++; onDismiss()
                        }
                        MenuBtn("👁 Alle wieder einblenden", fg = Color(0xFF8FA3C8)) {
                            CategoryPrefs.unhideAll(scope); refresh++; onDismiss()
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    MenuBtn("♻️ Alles zurücksetzen (Reihenfolge · Namen · Ausblenden)", fg = Color(0xFF8FA3C8)) {
                        CategoryPrefs.resetAll(scope); refresh++
                    }
                }
                1 -> Column {
                    Text("Anklicken markiert. ", color = Color(0xFF6B7A99), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniBtn("✅ Alle markieren") { marked = visible.map { it.id }.toSet() }
                        MiniBtn("⬜ Aufheben") { marked = emptySet() }
                        MiniBtn("👁 Alle einblenden") { CategoryPrefs.unhideAll(scope); refresh++ }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniBtn("▲ Block oben (${marked.size})") {
                            if (marked.isNotEmpty()) { CategoryPrefs.moveBlock(scope, marked.toList(), cats, true); refresh++ }
                        }
                        MiniBtn("▼ Block unten (${marked.size})") {
                            if (marked.isNotEmpty()) { CategoryPrefs.moveBlock(scope, marked.toList(), cats, false); refresh++ }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniBtn("🚫 Nur Markierte behalten", red = true) {
                            val toHide = visible.filter { it.id !in marked }.map { it.id }
                            if (toHide.isNotEmpty()) { CategoryPrefs.hideMany(scope, toHide); refresh++ }
                        }
                        MiniBtn("🚫 Markierte ausblenden (${marked.size})", red = true) {
                            if (marked.isNotEmpty()) { CategoryPrefs.hideMany(scope, marked.toList()); refresh++ }
                        }
                        MiniBtn("✓ Fertig", gold = true) { onDismiss() }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Markiert: ${marked.size} von ${visible.size}",
                        color = Color(0xFF8FA3C8), fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    BoxWithConstraints {
                        val maxList = (maxHeight * 0.45f).coerceAtMost(400.dp)
                        Column(
                            modifier = Modifier
                                .heightIn(max = maxList)
                                .verticalScroll(rememberScrollState())
                        ) {
                        val focusedRowId = remember { mutableStateOf<String?>(null) }
                        visible.forEach { c ->
                            val isMarked = c.id in marked
                            val rowF = (focusedRowId.value == c.id)
                            Surface(
                                onClick = { marked = if (isMarked) marked - c.id else marked + c.id },
                                shape = RoundedCornerShape(8.dp),
                                color = when {
                                    rowF -> if (isMarked) Color(0xFF2A3A66) else Color(0xFF243055)
                                    isMarked -> Color(0xFF1E2A45)
                                    else -> Color(0xFF141A2A)
                                },
                                border = when {
                                    rowF -> androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFFC94D))
                                    isMarked -> androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFC94D))
                                    else -> null
                                },
                                modifier = Modifier.fillMaxWidth()
                                    .onFocusChanged {
                                        if (it.isFocused) focusedRowId.value = c.id
                                        else if (focusedRowId.value == c.id) focusedRowId.value = null
                                    }
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                                    Text(if (isMarked) "☑" else "☐", fontSize = 16.sp, color = Color(0xFFE8EEFC))
                                    Spacer(Modifier.width(10.dp))
                                    Text(CategoryPrefs.labelOf(scope, c),
                                        color = if (isMarked) Color(0xFFE8EEFC) else Color(0xFF8FA3C8),
                                        fontSize = 14.sp,
                                        fontWeight = if (isMarked) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f))
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                        }
                        }
                    }
                }

                else -> Column {
                    Text("Neuen Namen für die Kategorie eingeben (leer = Original-Name).",
                        color = Color(0xFF6B7A99), fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF141A2A),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A4666))
                    ) {
                        TextField(
                            value = label,
                            onValueChange = { label = it },
                            modifier = Modifier.fillMaxWidth()
                                .focusRequester(renameFocus)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            singleLine = true,
                            placeholder = { Text(cat.name, color = Color(0xFF55627F)) },
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = Color(0xFFE8EEFC),
                                unfocusedTextColor = Color(0xFFE8EEFC),
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (mode == 2) {
                TextButton(onClick = {
                    val t = label.trim()
                    CategoryPrefs.setLabel(scope, cat.id, t.ifEmpty { null })
                    refresh++; onDismiss()
                }) { Text("Speichern", color = Color(0xFFFFC94D), fontSize = 15.sp) }
            }
        },
        dismissButton = {
            if (mode != 2) {
                TextButton(onClick = onDismiss) { Text("Schließen", color = Color(0xFF6B7A99)) }
            }
        },
        containerColor = Color(0xFF10141F)
    )
}

@Composable
private fun MenuBtn(
    text: String,
    fg: Color = Color(0xFFE8EEFC),
    bg: Color = Color(0xFF141A2A),
    bd: Color = Color(0xFF3A4666),
    onClick: () -> Unit
) {
    // TV-Fokus: deutlicher Gold-Rahmen + hellerer Grund + hellerer Text
    val focused = remember { mutableStateOf(false) }
    val isGold = (bg == Color(0xFFFFC94D))
    val ring = if (isGold) Color(0xFF1A2238) else Color(0xFFFFC94D)
    val bgF = if (isGold) Color(0xFFFFD975) else Color(0xFF1B2438)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (focused.value) bgF else bg,
        border = androidx.compose.foundation.BorderStroke(
            if (focused.value) 2.dp else 1.dp,
            if (focused.value) ring else bd
        ),
        modifier = Modifier.onFocusChanged { focused.value = it.isFocused }
    ) {
        Text(text, color = if (focused.value && isGold) Color(0xFFFFFFFF) else fg, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}
@Composable
private fun MiniBtn(text: String, red: Boolean = false, gold: Boolean = false, onClick: () -> Unit) {
    val label = text
    val fg = when { gold -> Color(0xFF0A0D16); red -> Color(0xFFFF9B9B); else -> Color(0xFFE8EEFC) }
    val bg = when { gold -> Color(0xFFFFC94D); red -> Color(0xFF2A1A1A); else -> Color(0xFF141A2A) }
    val bd = when { gold -> Color(0xFFFFC94D); red -> Color(0xFF664444); else -> Color(0xFF3A4666) }
    // TV-Fokus: deutlicher Rahmen (non-gold: Gold-Ring, gold: dunkler Ring)
    val focused = remember { mutableStateOf(false) }
    val ring = if (gold) Color(0xFF1A2238) else Color(0xFFFFC94D)
    val bgF = if (gold) Color(0xFFFFD975) else if (red) Color(0xFF3A2424) else Color(0xFF1B2438)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(7.dp),
        color = if (focused.value) bgF else bg,
        border = androidx.compose.foundation.BorderStroke(
            if (focused.value) 2.dp else 1.dp,
            if (focused.value) ring else bd
        ),
        modifier = Modifier.onFocusChanged { focused.value = it.isFocused }
    ) {
        Text(label, color = fg, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
    }
}


// ---------- Downloads-Übersicht (offline verfügbare VOD-Titel) ----------

@Composable
fun DownloadsScreen(
    appCtx: android.content.Context,
    onPlay: (VodPlayback) -> Unit,
    onBack: () -> Unit
) {
    val ctx = appCtx
    // Reaktiv auf die Downloader-Liste reagieren (Compose-State).
    val items = VodDownloader.items
    val finished = items.filter { it.status == "complete" }
    var deleteConfirmFor by remember { mutableStateOf<DownloadItem?>(null) }

    Column(Modifier.fillMaxSize()) {
        // Kopfzeile
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("Downloads", color = Color(0xFFE8EEFC), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (finished.isEmpty()) "Keine offline verfügbaren Titel"
                    else "${finished.size} offline verfügbar",
                    color = Color(0xFF6B7A99), fontSize = 12.sp
                )
            }
            TextButton(onClick = onBack) { Text("← Zurück", color = Color(0xFF8FA3C8), fontSize = 16.sp) }
        }

        if (finished.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📥", fontSize = 44.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Noch keine Downloads", color = Color(0xFFE8EEFC), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "In der Filmdetailseite „⬇ Herunterladen“ wählen,\num einen Film offline verfügbar zu machen.",
                        color = Color(0xFF6B7A99), fontSize = 14.sp, textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp)
            ) {
                items(finished, key = { it.id }) { d ->
                    val file = VodDownloader.localFile(ctx, d.id)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title, color = Color(0xFFE8EEFC), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "✓ offline · ${d.downloadedBytes / 1024 / 1024} MB",
                                color = Color(0xFF6FCF9F), fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        // Abspielen (nur wenn Datei wirklich da)
                        Surface(
                            onClick = {
                                if (file != null) {
                                    onPlay(VodPlayback(file.toURI().toString(), d.title, key = "vod:download:${d.id}", startPosMs = 0L))
                                }
                            },
                            enabled = file != null,
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF141A2A),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFC94D))
                        ) {
                            Text("▶ Abspielen", color = Color(0xFFFFC94D), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = { deleteConfirmFor = d }) {
                            Text("🗑 Löschen", color = Color(0xFFFF6B6B), fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }

    // Lösch-Bestätigung
    deleteConfirmFor?.let { d ->
        AlertDialog(
            onDismissRequest = { deleteConfirmFor = null },
            title = { Text("Download löschen?", color = Color(0xFFE8EEFC), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            text = { Text("„${d.title}“ (${d.downloadedBytes / 1024 / 1024} MB) wird vom Gerät entfernt.", color = Color(0xFF8FA3C8), fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { VodDownloader.delete(ctx, d.id); deleteConfirmFor = null }) {
                    Text("Löschen", color = Color(0xFFFF6B6B), fontSize = 15.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmFor = null }) { Text("Abbrechen", color = Color(0xFF6B7A99), fontSize = 15.sp) }
            },
            containerColor = Color(0xFF10141F)
        )
    }
}

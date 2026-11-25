package com.smartys.iptv

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

private val KamBg = Color(0xFF0B0E14)
private val KamCard = Color(0xFF11172A)
private val KamGold = Color(0xFFFFC94D)
private val KamText = Color(0xFFE8EEFC)
private val KamMid = Color(0xFF8FA3C8)

/**
 * Tab "Kameras": eigene Kameras vom LAN-Server plus oeffentliche Webcams.
 * HLS-Kameras laufen ueber den normalen Player (onPlayHls), JPEG-Kameras
 * oeffnen den Bildschirm mit Aktualisierung im hinterlegten Takt.
 */
@Composable
fun KameraScreen(
    onBack: () -> Unit,
    onPlayHls: (name: String, url: String) -> Unit,
    onShowBild: (Kameras.Kamera) -> Unit,
    // Wand als eigenes Vollbild oeffnen: im Tab wuerde die Hauptnavigation
    // darueber liegen, deshalb laeuft sie ausserhalb des Tab-Gerüsts.
    onWand: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var lokal by remember { mutableStateOf<List<Kameras.Kamera>>(emptyList()) }
    var firmen by remember { mutableStateOf<List<Kameras.Kamera>>(emptyList()) }
    var suche by remember { mutableStateOf(false) }
    var meldung by remember { mutableStateOf<String?>(null) }
    // FireTV schlaeft nach 20 Minuten ohne Eingabe; Kamera-Ansicht zaehlt
    // als Wiedergabe und haelt den Bildschirm deshalb wach.
    androidx.compose.runtime.DisposableEffect(Unit) {
        keepScreenOn(ctx, true)
        onDispose { keepScreenOn(ctx, false) }
    }
    // Schalter aus den Einstellungen — jede Quelle einzeln an/aus.
    var freiAn by remember { mutableStateOf(KameraPrefs.freie(ctx)) }
    var firmenAn by remember { mutableStateOf(KameraPrefs.firmen(ctx)) }
    var wand by remember { mutableStateOf(false) }
    var layoutState by remember { mutableStateOf(Kameras.Layout()) }
    val profil = remember { runCatching { ProfileStore.activeId(ctx) }.getOrNull() ?: "default" }

    BackHandler { onBack() }

    LaunchedEffect(Unit) {
        freiAn = KameraPrefs.freie(ctx)
        firmenAn = KameraPrefs.firmen(ctx)
        if (firmenAn) {
            firmen = withContext(Dispatchers.IO) { Kameras.firmenKameras() }
            layoutState = withContext(Dispatchers.IO) {
                Kameras.layoutAusText(KameraPrefs.wandLayout(ctx, profil))
                    ?: Kameras.layoutVomServer(KameraPrefs.firmenServer(ctx))
            }
            if (firmen.isEmpty()) meldung = "Firmen-Kameras: ${KameraPrefs.firmenServer(ctx)} antwortet nicht"
        }
        val bekannt = Kameras.server()
        if (bekannt != null) {
            lokal = withContext(Dispatchers.IO) { Kameras.vomServer(bekannt) }
            if (lokal.isEmpty() && firmen.isEmpty()) meldung = "Server $bekannt antwortet nicht mehr"
        }
    }

    fun oeffne(k: Kameras.Kamera) {
        if (k.typ == "hls") onPlayHls(k.name, k.url) else onShowBild(k)
    }

    if (wand) {
        if (firmen.isEmpty()) {
            KameraWandLaden(onBack = { wand = false })
            return
        }
        KameraWand(
            firmen, layoutState,
            onBack = { wand = false },
            onOeffnen = { oeffne(it) },
            onLayout = { l ->
                layoutState = l
                KameraPrefs.setWandLayout(ctx, profil, Kameras.layoutText(l))
            }
        )
        return
    }

    Column(Modifier.fillMaxSize().background(KamBg).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WandKnopf(text = "← Zurück", aktiv = false) { onBack() }
            Spacer(Modifier.width(12.dp))
            Text("Kameras", color = KamText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            // Der Knopf haengt NUR am Schalter: die Wand laedt ihre Kameras selbst,
            // damit sie auch dann erreichbar ist, wenn die Liste (noch) leer ist.
            if (firmenAn) {
                WandKnopf(text = "Wand wie am Pi", aktiv = false) { onWand() }
                Spacer(Modifier.width(12.dp))
            }
            WandKnopf(text = if (suche) "suche …" else "Server im LAN suchen", aktiv = false) {
                suche = true; meldung = null
                scope.launch {
                    val gefunden = withContext(Dispatchers.IO) { Kameras.imLanSuchen() }
                    lokal = gefunden
                    suche = false
                    if (gefunden.isEmpty()) meldung = "Kein Kamera-Server im Netz gefunden"
                }
            }
            Spacer(Modifier.width(12.dp))
            meldung?.let { Text(it, color = KamMid, fontSize = 13.sp) }
        }
        Spacer(Modifier.height(14.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (firmenAn && firmen.isNotEmpty()) {
                item { Text("Firmen-Kameras (VLAN)", color = KamGold, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                items(firmen) { k -> KameraZeile(k) { oeffne(k) } }
                item { Spacer(Modifier.height(10.dp)) }
            }
            if (lokal.isNotEmpty()) {
                item { Text("Eigene Kameras", color = KamGold, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                items(lokal) { k -> KameraZeile(k) { oeffne(k) } }
                item { Spacer(Modifier.height(10.dp)) }
            }
            if (freiAn) {
                item { Text("Öffentliche Webcams", color = KamGold, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                items(Kameras.OEFFENTLICH) { k -> KameraZeile(k) { oeffne(k) } }
            }
            if (!freiAn && !firmenAn && lokal.isEmpty()) {
                item {
                    Text(
                        "Alle Kamera-Quellen sind aus. In den Einstellungen unter " +
                            "\"Kameras & freie Streams\" einschalten.",
                        color = KamMid, fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun KameraZeile(k: Kameras.Kamera, onClick: () -> Unit) {
    var fokus by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = if (fokus) KamGold else KamCard,
            contentColor = if (fokus) Color.Black else KamText
        ),
        modifier = Modifier.fillMaxWidth()
            .padding(vertical = 2.dp)
            // Ohne onFocusChanged blieb "fokus" immer false -> auf dem TV war nicht
            // zu sehen, in welcher Zeile man steht.
            .onFocusChanged { fokus = it.hasFocus }
            .border(
                width = if (fokus) 3.dp else 0.dp,
                color = if (fokus) KamGold else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                k.name, color = if (fokus) Color.Black else KamText,
                fontSize = 15.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (k.lokal) "LAN · ${k.typ.uppercase()}" else "${k.land} · ${k.typ.uppercase()}",
                color = if (fokus) Color(0xFF1A1A1A) else KamMid, fontSize = 12.sp
            )
        }
    }
}

/** JPEG-Kamera: laedt das Bild und erneuert es im hinterlegten Takt. */
@Composable
fun KameraBildScreen(k: Kameras.Kamera, onBack: () -> Unit) {
    // Auf der Fire-Fernbedienung ist BACK der einzige Weg zurueck, den man blind findet.
    BackHandler { onBack() }
    val kctx = LocalContext.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        keepScreenOn(kctx, true)
        onDispose { keepScreenOn(kctx, false) }
    }
    var bild by remember(k.url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var fehler by remember(k.url) { mutableStateOf<String?>(null) }

    LaunchedEffect(k.url) {
        while (true) {
            val geladen = withContext(Dispatchers.IO) {
                runCatching {
                    val c = URI.create(k.url).toURL().openConnection() as HttpURLConnection
                    c.connectTimeout = 4000; c.readTimeout = 8000
                    c.instanceFollowRedirects = true
                    try { c.inputStream.use { BitmapFactory.decodeStream(it) } } finally { c.disconnect() }
                }.getOrNull()
            }
            if (geladen != null) { bild = geladen; fehler = null } else fehler = "Bild nicht erreichbar"
            delay((k.refreshSek.coerceAtLeast(5)) * 1000L)
        }
    }

    Column(Modifier.fillMaxSize().background(KamBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WandKnopf(text = "← Zurück", aktiv = false) { onBack() }
            Spacer(Modifier.width(12.dp))
            Text(k.name, color = KamText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${k.land} · alle ${k.refreshSek}s", color = KamMid, fontSize = 12.sp)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val b = bild
            if (b != null) {
                Image(bitmap = b.asImageBitmap(), contentDescription = k.name, modifier = Modifier.fillMaxSize())
            } else {
                Text(fehler ?: "lade Bild …", color = KamMid, fontSize = 16.sp)
            }
        }
    }
}

/**
 * Kachel-Wand wie die Kiosk-Ansicht am Raspberry Pi: jede Kamera ein Live-Feld,
 * die Dual-Lens-Kamera belegt laut Server-Layout zwei Felder (w=2).
 */
@Composable
fun KameraWand(
    kameras: List<Kameras.Kamera>,
    layout: Kameras.Layout,
    onBack: () -> Unit,
    onOeffnen: (Kameras.Kamera) -> Unit,
    onLayout: (Kameras.Layout) -> Unit = {}
    ) {
    val ctx = LocalContext.current
    BackHandler { onBack() }
    androidx.compose.runtime.DisposableEffect(Unit) {
        keepScreenOn(ctx, true)
        onDispose { keepScreenOn(ctx, false) }
    }

    val spalten = layout.spalten.coerceIn(1, 6)
    val geordnet = remember(kameras, layout) {
        kameras.sortedBy { k -> layout.reihenfolge.indexOf(k.id).let { i -> if (i < 0) 999 else i } }
    }

    // Kein stehender Balken im Bild: die Bedienleiste erscheint nur bei Tastendruck
    // und blendet nach 6 s wieder aus -> reine Vollbild-Wand.
    var leiste by remember { mutableStateOf(false) }
    LaunchedEffect(leiste) { if (leiste) { delay(6000); leiste = false } }
    // Erste Kachel bekommt den Startfokus, damit die Fernbedienung sofort navigiert.
    val ersterFokus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(geordnet.size, spalten) {
        delay(600)
        runCatching { ersterFokus.requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(18.dp)
            // onPreviewKeyEvent liest nur mit und laesst die Taste weiterlaufen (false),
            // dadurch bleibt die Fokus-Navigation der Fernbedienung erhalten.
            .onPreviewKeyEvent { _ -> leiste = true; false }
    ) {
        if (leiste) Row(verticalAlignment = Alignment.CenterVertically) {
            WandKnopf(text = "← Zurück", aktiv = false) { onBack() }
            Spacer(Modifier.width(12.dp))
            Text("Kamera-Wand", color = KamText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Text("Spalten:", color = KamMid, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            // Eigenes Layout: Spaltenzahl frei waehlbar, wird pro Profil gespeichert.
            listOf(1, 2, 3, 4).forEach { n ->
                WandKnopf(text = "$n", aktiv = spalten == n) {
                    onLayout(layout.copy(spalten = n))
                }
                Spacer(Modifier.width(4.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("${geordnet.size} Kameras", color = KamMid, fontSize = 13.sp)
        }
        if (leiste) Spacer(Modifier.height(12.dp))
        // maxHeight ist hier der Platz UNTER der Kopfzeile — kein fester Abzug,
        // damit die Wand unabhaengig von TV-Dichte und Schriftgroesse passt.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) {
        val luecke = 8.dp
        val zeilen = remember(geordnet, layout, spalten) {
            var belegt = 0; var z = 1
            geordnet.forEach { k ->
                val breite = (layout.breiten[k.id] ?: 1).coerceIn(1, spalten)
                if (belegt + breite > spalten) { z++; belegt = 0 }
                belegt += breite
            }
            z
        }
        val kachelHoehe = ((maxHeight - luecke * (zeilen - 1)) / zeilen).coerceAtLeast(60.dp)
        LazyVerticalGrid(
            columns = GridCells.Fixed(spalten),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = geordnet,
                span = { k ->
                    GridItemSpan((layout.breiten[k.id] ?: 1).coerceIn(1, spalten))
                }
            ) { k ->
                WandKachel(
                    k = k,
                    index = geordnet.indexOf(k),
                    breite = (layout.breiten[k.id] ?: 1),
                    maxBreite = spalten,
                    hoehe = kachelHoehe,
                    fokusAnfang = if (geordnet.indexOf(k) == 0) ersterFokus else null,
                    onKlick = { onOeffnen(k) },
                    onBreite = { neu ->
                        val b = LinkedHashMap(layout.breiten); b[k.id] = neu.coerceIn(1, spalten)
                        onLayout(layout.copy(breiten = b))
                    },
                    onHoch = {
                        val reihe = geordnet.map { it.id }.toMutableList()
                        val i = reihe.indexOf(k.id)
                        if (i > 0) {
                            val t = reihe[i - 1]; reihe[i - 1] = reihe[i]; reihe[i] = t
                            onLayout(layout.copy(reihenfolge = reihe))
                        }
                    }
                )
            }
        }
        }
    }
}

/** Kleiner Knopf in der Kachelecke — Fokus ebenfalls sichtbar (goldener Rahmen). */
@androidx.compose.runtime.Composable
private fun WandChip(text: String, farbe: androidx.compose.ui.graphics.Color, onKlick: () -> Unit) {
    var fokus by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (fokus) KamGold.copy(alpha = 0.25f) else Color.Transparent)
            .onFocusChanged { fokus = it.isFocused }
            .focusable()
            .clickable { onKlick() }
            .border(
                width = if (fokus) 2.dp else 0.dp,
                color = if (fokus) KamGold else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (fokus) Color.White else farbe, fontSize = 9.sp) }
}

/**
 * Knopf fuer die Fernbedienung: der Fokus muss auf dem TV deutlich sichtbar sein,
 * sonst weiss niemand, wo der Zeiger steht. Fokus = goldener Rahmen + helle Flaeche.
 */
@androidx.compose.runtime.Composable
private fun WandKnopf(text: String, aktiv: Boolean, onKlick: () -> Unit) {
    var fokus by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (fokus) 3.dp else 1.dp,
                color = if (fokus) KamGold else if (aktiv) KamGold.copy(alpha = 0.6f) else Color(0xFF3A4666),
                shape = RoundedCornerShape(8.dp)
            )
            .background(if (fokus) KamGold else if (aktiv) Color(0xFF232C42) else Color(0xFF141A2A))
            .onFocusChanged { fokus = it.isFocused }
            .focusable()
            .clickable { onKlick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (fokus) Color.Black else if (aktiv) KamGold else KamMid,
            fontSize = 13.sp,
            fontWeight = if (fokus || aktiv) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/** Eine Kachel: HLS laeuft im eigenen ExoPlayer, JPEG wird im Takt erneuert. */
@androidx.compose.runtime.Composable
private fun WandKachel(
    k: Kameras.Kamera,
    index: Int,
    breite: Int,
    maxBreite: Int,
    hoehe: androidx.compose.ui.unit.Dp,
    fokusAnfang: androidx.compose.ui.focus.FocusRequester? = null,
    onKlick: () -> Unit,
    onBreite: (Int) -> Unit,
    onHoch: () -> Unit
) {
    val ctx = LocalContext.current
    var fokus by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().height(hoehe)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (fokusAnfang != null) Modifier.focusRequester(fokusAnfang) else Modifier
            )
            .onFocusChanged { fokus = it.hasFocus }
            .background(Color.Black)
            .clickable { onKlick() }
    ) {
        if (k.typ == "hls") {
            // Fire-TV-Decoder limitiert: Kacheln nacheinander starten, nicht alle
            // gleichzeitig — sonst bleiben die ersten Kacheln schwarz, weil die
            // Hardware-Decoder beim Init weggezogen werden.
            var starten by remember(k.url) { mutableStateOf(index == 0) }
            // Die Dual-Lens-Kachel (2 Felder) hat die schwerste Quelle und bekommt
            // zusaetzlichen Anlauf, statt dass ihr Player zu frueh aufgibt.
            val anlauf = index * 1300L + if (breite > 1) 6000L else 0L
            LaunchedEffect(k.url, anlauf) {
                if (anlauf > 0) delay(anlauf)
                starten = true
            }
            // KEIN Ab- und Neubauen des Players: jeder Neuaufbau belegt erneut einen
            // Hardware-Decoder, und der Cube hat davon nur wenige — das hat vier
            // Kacheln gekostet. Ein Player pro Kachel, fertig.
            val exo = remember(k.url) {
                androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().apply {
                    volume = 0f
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(k.url))
                    prepare()
                    playWhenReady = true
                }
            }
            var bildDa by remember(k.url) { mutableStateOf(false) }
            androidx.compose.runtime.DisposableEffect(exo) {
                val horcher = object : androidx.media3.common.Player.Listener {
                    override fun onRenderedFirstFrame() { bildDa = true }
                }
                exo.addListener(horcher)
                onDispose { exo.removeListener(horcher); exo.release() }
            }
            // Bleibt eine Kachel leer, wird DERSELBE Player erneut angestossen.
            // Kein release/Neuaufbau — der wuerde erneut einen Hardware-Decoder
            // belegen, und davon hat der Cube nur wenige.
            LaunchedEffect(k.url, starten) {
                if (!starten) return@LaunchedEffect
                delay(16_000)
                if (!bildDa) runCatching { exo.prepare() }
            }
            if (!starten) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("lädt …", color = KamMid, fontSize = 12.sp)
                }
            }
            AndroidView(
                factory = { c ->
                    androidx.media3.ui.PlayerView(c).apply {
                        player = exo
                        useController = false
                        // Ohne das zieht die Video-Ansicht den Fokus an sich und der
                        // goldene Rahmen der Kachel erscheint nie.
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            var bild by remember(k.url) { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(k.url) {
                while (true) {
                    val geladen = withContext(Dispatchers.IO) {
                        runCatching {
                            val c = URI.create(k.url).toURL().openConnection() as HttpURLConnection
                            c.connectTimeout = 4000; c.readTimeout = 8000
                            c.instanceFollowRedirects = true
                            try { c.inputStream.use { BitmapFactory.decodeStream(it) } } finally { c.disconnect() }
                        }.getOrNull()
                    }
                    if (geladen != null) bild = geladen
                    delay((k.refreshSek.coerceAtLeast(5)) * 1000L)
                }
            }
            bild?.let {
                Image(
                    bitmap = it.asImageBitmap(), contentDescription = k.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomStart)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Kein Hintergrundbalken: nur der Name, damit das Bild durchgehend sichtbar ist.
            Text(
                text = (if (fokus) "● " else "") + k.name,
                color = if (fokus) KamGold else Color.White,
                fontSize = 10.sp,
                fontWeight = if (fokus) FontWeight.Bold else FontWeight.Medium,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black, offset = androidx.compose.ui.geometry.Offset(1f, 1f),
                        blurRadius = 4f
                    )
                )
            )
            Spacer(Modifier.weight(1f))
            // Eigener Stil je Kamera: Breite in Feldern und Position in der Reihenfolge.
            WandChip(text = if (breite > 1) "${breite}F" else "1F", farbe = KamGold) {
                onBreite(if (breite > 1) 1 else 2)
            }
            WandChip(text = "▲", farbe = KamMid) { onHoch }
        }
    }
}

/** Wand ohne Anmeldung — liest Firmen-Kameras + Layout selbst (Kiosk/Test). */
@Composable
fun KameraWandLaden(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var kameras by remember { mutableStateOf<List<Kameras.Kamera>>(emptyList()) }
    var layout by remember { mutableStateOf(Kameras.Layout()) }
    var meldung by remember { mutableStateOf<String?>(null) }
    var laedt by remember { mutableStateOf(true) }

    val profil = remember { runCatching { ProfileStore.activeId(ctx) }.getOrNull() ?: "default" }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val server = KameraPrefs.firmenServer(ctx)
            // Eigenes Layout hat Vorrang, sonst das des Servers.
            layout = Kameras.layoutAusText(KameraPrefs.wandLayout(ctx, profil))
                ?: Kameras.layoutVomServer(server)
            kameras = Kameras.firmenKameras()
        }
        laedt = false
        if (kameras.isEmpty()) meldung = "Keine Kameras von ${KameraPrefs.firmenServer(ctx)}"
    }

    fun speichern(l: Kameras.Layout) {
        layout = l
        KameraPrefs.setWandLayout(ctx, profil, Kameras.layoutText(l))
    }

    if (kameras.isEmpty()) {
        Box(Modifier.fillMaxSize().background(KamBg), contentAlignment = Alignment.Center) {
            Text(meldung ?: "Kamera-Wand wird geladen …", color = KamMid, fontSize = 16.sp)
        }
    } else {
        KameraWand(kameras, layout, onBack, onOeffnen = { /* Klick oeffnet spaeter Vollbild */ }, onLayout = { speichern(it) })
    }
}

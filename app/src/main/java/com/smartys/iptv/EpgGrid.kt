package com.smartys.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * EPG-Grid: linke Spalte = Kanäle, rechts = zeitlich gescrollte Programmkacheln.
 * Ein Tages-Grid (heute, 00:00 bis 24:00). Horizontales Scrollen für den Tagesverlauf.
 */
@Composable
fun EpgGrid(
    vm: MainViewModel,
    channels: List<LiveStream>,
    onPlay: (LiveStream) -> Unit,
    onBack: () -> Unit
) {
    val xmltvReady = vm.xmltvReady.value
    val now = remember { System.currentTimeMillis() }
    // Tagesfenster: heute 00:00 bis morgen 00:00 (lokal)
    val dayStart = remember {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }
    val dayEnd = dayStart + 24 * 3600 * 1000L

    // 1 Stunde = 300px; gesamte Breite = 24h * 300px
    val pxPerHour = 300
    val totalWidth = 24 * pxPerHour

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0E14))) {
        // Kopf
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 14.dp)
        ) {
            Text("Programmübersicht", color = Color(0xFFE8EEFC), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                SimpleDateFormat("EEEE, dd. MMMM", Locale.GERMAN).format(Date(now)),
                color = Color(0xFF6B7A99), fontSize = 14.sp
            )
            Spacer(Modifier.width(16.dp))
            TextButton(onClick = onBack) { Text("← Zurück", color = Color(0xFF8FA3C8), fontSize = 15.sp) }
        }

        if (!xmltvReady) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFFFFC94D))
                    Spacer(Modifier.height(12.dp))
                    Text("EPG-Daten werden geladen…", color = Color(0xFF6B7A99), fontSize = 14.sp)
                }
            }
            return
        }

        val listState = rememberLazyListState()
        val timeScroll = rememberScrollState()

        Row(Modifier.fillMaxSize()) {
            // Linke Spalte: Kanalnamen (fest)
            LazyColumn(
                state = listState,
                modifier = Modifier.width(260.dp).fillMaxHeight().background(Color(0xFF11172A))
            ) {
                // Kopfzeile (leer, aligniert mit Zeitachse oben)
                item {
                    Box(Modifier.height(52.dp).fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                        Text("Kanal", color = Color(0xFF55648A), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                items(channels, key = { it.streamId }) { ch ->
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .height(64.dp)
                            .fillMaxWidth()
                            .background(if (focused) Color(0xFF1A2238) else Color(0xFF11172A))
                            .clickable { onPlay(ch) }
                            .onFocusChanged { focused = it.isFocused }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = ch.streamIcon, contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.width(56.dp).height(32.dp).background(Color(0xFF141A2A), RoundedCornerShape(4.dp)).padding(3.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(ch.name, color = if (focused) Color(0xFFFFC94D) else Color(0xFFE8EEFC), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // Rechte Spalte: Programmkacheln, zeitlich gescrollt (gemeinsam für alle Zeilen)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // Zeitachse (horizontal gescrollt)
                Row(Modifier.fillMaxWidth().height(52.dp).horizontalScroll(timeScroll).background(Color(0xFF0B0E14))) {
                    for (h in 0..23) {
                        Box(
                            Modifier.width((pxPerHour).dp).fillMaxHeight(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                String.format("%02d:00", h),
                                color = Color(0xFF55648A), fontSize = 12.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }

                // Kanalzeilen mit Kacheln (alle teilen dieselbe Zeitachse via horizontalScroll)
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    items(channels, key = { it.streamId }) { ch ->
                        val progs = remember(ch.streamId, xmltvReady) {
                            vm.epgFor(ch) // trigger fetch falls nötig (cache-first)
                            XmltvEpg.programmesInRange(ch.epgChannelId, ch.name, dayStart, dayEnd)
                        }
                        // Jede Zeile: horizontal gescrollt, Kacheln positioniert nach Startzeit
                        Box(Modifier.height(64.dp).fillMaxWidth().horizontalScroll(timeScroll)) {
                            Row(Modifier.width((totalWidth).dp).height(64.dp)) {
                                progs.forEach { p ->
                                    val startOffset = ((p.start - dayStart).coerceAtLeast(0) / 3600000.0 * pxPerHour).toInt()
                                    val durPx = (((p.stop - p.start).coerceAtLeast(0)) / 3600000.0 * pxPerHour).toInt().coerceAtLeast(90)
                                    Spacer(Modifier.width(startOffset.dp))
                                    val isNow = p.start <= now && p.stop > now
                                    Box(
                                        Modifier
                                            .width(durPx.dp)
                                            .height(60.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isNow) Color(0xFF1F2A44) else Color(0xFF161C2E))
                                            .then(if (isNow) Modifier.border(1.dp, Color(0xFFFFC94D), RoundedCornerShape(6.dp)) else Modifier)
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Column {
                                            Text(
                                                "${XmltvEpg.hhmm(p.start)}–${XmltvEpg.hhmm(p.stop)}",
                                                color = if (isNow) Color(0xFFFFC94D) else Color(0xFF55648A),
                                                fontSize = 10.sp, fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                p.title, color = if (isNow) Color(0xFFE8EEFC) else Color(0xFF8FA3C8),
                                                fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

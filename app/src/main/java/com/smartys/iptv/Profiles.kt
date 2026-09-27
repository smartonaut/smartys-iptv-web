package com.smartys.iptv

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// ---------- Profil-Typ + Persistenz ----------

data class Profile(
    val id: String,
    val name: String,
    val color: Long
)

/**
 * Benutzerprofile (Netflix-Stil). Jedes Profil hat eigene Favoriten,
 * Fortsetz-Positionen, Zuletzt-gesehen und VOD-Kategorie-Einstellungen.
 *
 * Geräte-/accountweit (NICHT pro Profil) bleiben: Provider-/Playlist-Zugangsdaten
 * (CredStore) und die Kindersicherungs-PIN (ParentalControl).
 */
object ProfileStore {

    private const val PREFS = "smartys_profiles"
    private const val K_PROFILES = "profiles_json"
    private const val K_ACTIVE = "active_profile_id"

    // Akzentfarben für neue Profile (reihum vergeben).
    val COLORS = listOf(
        0xFFFFC94D, // Gold
        0xFF6FCF9F, // Grün
        0xFF6FB1FF, // Blau
        0xFFFF9B9B, // Rot
        0xFFB48CFF, // Violett
        0xFF4FD1C5  // Türkis
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun writeProfiles(ctx: Context, list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("color", p.color))
        }
        prefs(ctx).edit().putString(K_PROFILES, arr.toString()).apply()
    }

    fun list(ctx: Context): List<Profile> {
        val json = prefs(ctx).getString(K_PROFILES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Profile(o.getString("id"), o.getString("name"), o.optLong("color", COLORS[0]))
            }
        }.getOrDefault(emptyList())
    }

    fun activeId(ctx: Context): String? = prefs(ctx).getString(K_ACTIVE, null)

    fun active(ctx: Context): Profile? =
        activeId(ctx)?.let { id -> list(ctx).firstOrNull { it.id == id } }

    fun setActive(ctx: Context, id: String) {
        prefs(ctx).edit().putString(K_ACTIVE, id).commit()
    }

    /** Stellt sicher, dass mindestens ein Profil existiert (Standard-Profil beim Erststart). */
    fun ensureDefault(ctx: Context): Profile {
        val existing = list(ctx)
        if (existing.isNotEmpty()) {
            val active = activeId(ctx)?.let { id -> existing.firstOrNull { it.id == id } }
                ?: existing.first()
            if (activeId(ctx) == null) setActive(ctx, active.id)
            return active
        }
        val p = add(ctx, "Standard")
        setActive(ctx, p.id)
        return p
    }

    fun add(ctx: Context, name: String): Profile {
        val trimmed = name.trim().ifBlank { "Profil" }
        val list = list(ctx).toMutableList()
        val color = COLORS[list.size % COLORS.size]
        val p = Profile(UUID.randomUUID().toString(), trimmed, color)
        list.add(p)
        writeProfiles(ctx, list)
        return p
    }

    fun rename(ctx: Context, id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        writeProfiles(ctx, list(ctx).map { if (it.id == id) it.copy(name = trimmed) else it })
    }

    /** Löscht ein Profil (nie das letzte). Ist es aktiv, wird das erste verbleibende aktiv. */
    fun delete(ctx: Context, id: String) {
        val remaining = list(ctx).filter { it.id != id }
        if (remaining.isEmpty()) return
        writeProfiles(ctx, remaining)
        if (activeId(ctx) == id) setActive(ctx, remaining.first().id)
    }

    /** Präfix für profilgebundene Werte in SharedPreferences (z. B. Favoriten, Resume). */
    fun scoped(ctx: Context, base: String): String {
        val id = activeId(ctx) ?: "default"
        return "${base}__$id"
    }
}

// ---------- Profil-Auswahl-UI (Netflix-Stil) ----------

@Composable
fun ProfilePickerScreen(onSelected: (Profile) -> Unit) {
    val ctx = LocalContext.current
    var profiles by remember { mutableStateOf(ProfileStore.list(ctx)) }
    var showAdd by remember { mutableStateOf(false) }
    var manageFor by remember { mutableStateOf<Profile?>(null) }

    fun refresh() { profiles = ProfileStore.list(ctx) }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B0E14)).padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Wer schaut?", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE8EEFC))
        Spacer(Modifier.height(4.dp))
        Text(
            "Favoriten & Fortsetzen sind pro Profil getrennt",
            color = Color(0xFF6B7A99), fontSize = 14.sp
        )
        Spacer(Modifier.height(32.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 200.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(profiles, key = { it.id }) { p ->
                ProfileCard(
                    profile = p,
                    onClick = { onSelected(p) },
                    onLongPress = { manageFor = p }
                )
            }
            item {
                AddProfileCard(onClick = { showAdd = true })
            }
        }
    }

    if (showAdd) {
        AddProfileDialog(
            onAdd = { name -> ProfileStore.add(ctx, name); refresh(); showAdd = false },
            onDismiss = { showAdd = false }
        )
    }
    manageFor?.let { p ->
        ManageProfileDialog(
            profile = p,
            onRename = { name -> ProfileStore.rename(ctx, p.id, name); refresh(); manageFor = null },
            onDelete = { ProfileStore.delete(ctx, p.id); refresh(); manageFor = null },
            onDismiss = { manageFor = null }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ProfileCard(profile: Profile, onClick: () -> Unit, onLongPress: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(profile.color))
                .then(if (focused) Modifier.border(3.dp, Color(0xFFFFC94D), RoundedCornerShape(24.dp)) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Text(
                profile.name.firstOrNull()?.toString()?.uppercase() ?: "?",
                color = Color(0xFF0B0E14), fontSize = 46.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            profile.name,
            color = if (focused) Color(0xFFFFC94D) else Color(0xFFE8EEFC),
            fontSize = 16.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AddProfileCard(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF11172A))
                .border(if (focused) 2.dp else 1.dp, if (focused) Color(0xFFFFC94D) else Color(0xFF232C42), RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("＋", color = Color(0xFF8FA3C8), fontSize = 44.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Text("Neues Profil", color = Color(0xFF8FA3C8), fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun AddProfileDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neues Profil", color = Color(0xFFE8EEFC), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFFC94D),
                    unfocusedBorderColor = Color(0xFF232C42)
                )
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onAdd(name) }) {
                Text("Anlegen", color = Color(0xFFFFC94D), fontSize = 15.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen", color = Color(0xFF6B7A99), fontSize = 15.sp) }
        },
        containerColor = Color(0xFF10141F)
    )
}

@Composable
fun ManageProfileDialog(profile: Profile, onRename: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(profile.name) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Profil löschen?", color = Color(0xFFE8EEFC), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Favoriten und Fortsetz-Positionen von „${profile.name}“ werden entfernt.",
                    color = Color(0xFF8FA3C8), fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = onDelete) { Text("Löschen", color = Color(0xFFFF6B6B), fontSize = 15.sp) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen", color = Color(0xFF6B7A99), fontSize = 15.sp) }
            },
            containerColor = Color(0xFF10141F)
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Profil verwalten", color = Color(0xFFE8EEFC), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFFC94D),
                            unfocusedBorderColor = Color(0xFF232C42)
                        )
                    )
                    Spacer(Modifier.height(14.dp))
                    TextButton(onClick = { if (name.isNotBlank()) onRename(name) }, modifier = Modifier.align(Alignment.Start)) {
                        Text("Umbenennen", color = Color(0xFFFFC94D), fontSize = 14.sp)
                    }
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.Start)) {
                        Text("Löschen", color = Color(0xFFFF6B6B), fontSize = 14.sp)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Schließen", color = Color(0xFF6B7A99), fontSize = 15.sp) }
            },
            containerColor = Color(0xFF10141F)
        )
    }
}

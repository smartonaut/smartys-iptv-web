package com.smartys.iptv

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest

/**
 * Parental Control / PIN-Sperre.
 * - PIN wird als SHA-256-Hash in SharedPreferences gespeichert (nie Klartext).
 * - Ist ein PIN gesetzt, wird beim App-Start ein PIN-Gate gezeigt.
 * - In den Einstellungen kann der PIN gesetzt/geändert/entfernt werden.
 */
object ParentalControl {

    private const val PREFS = "smartys_iptv_parental"
    private const val K_PIN_HASH = "pin_hash"
    private const val K_ENABLED = "pin_enabled"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ENABLED, false)

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format("%02x", it) }

    /** Setzt/ändert den PIN (4–6 Ziffern). */
    fun setPin(ctx: Context, pin: String) {
        prefs(ctx).edit()
            .putString(K_PIN_HASH, hash(pin))
            .putBoolean(K_ENABLED, true)
            .apply()
    }

    /** Entfernt den PIN (deaktiviert die Sperre). */
    fun disable(ctx: Context) {
        prefs(ctx).edit()
            .remove(K_PIN_HASH)
            .putBoolean(K_ENABLED, false)
            .apply()
    }

    fun verify(ctx: Context, pin: String): Boolean {
        val stored = prefs(ctx).getString(K_PIN_HASH, null) ?: return false
        return stored == hash(pin)
    }
}

/**
 * PIN-Gate: Vollbild-Sperre. Zeigt ein Nummernfeld; bei korrekter PIN onUnlocked().
 */
@Composable
fun PinGate(onUnlocked: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val buttons = remember { listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "⌫") }

    fun submit(pin: String) {
        if (ParentalControl.verify(ctx, pin)) {
            onUnlocked()
        } else {
            error = true
            input = ""
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B0E14)).padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🔒", fontSize = 40.sp)
        Spacer(Modifier.height(12.dp))
        Text("PIN eingeben", color = Color(0xFFE8EEFC), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))

        // Eingabepunkte
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(6) { i ->
                Box(
                    Modifier.size(14.dp).background(
                        if (i < input.length) Color(0xFFFFC94D) else Color(0xFF232C42),
                        androidx.compose.foundation.shape.CircleShape
                    )
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (error) {
            Text("Falsche PIN", color = Color(0xFFFF6B6B), fontSize = 14.sp)
        }
        Spacer(Modifier.height(20.dp))

        // Nummernfeld (4x3)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            buttons.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { key ->
                        val isBack = key == "⌫"
                        val isClear = key == "C"
                        Surface(
                            onClick = {
                                when {
                                    isBack -> input = input.dropLast(1)
                                    isClear -> input = ""
                                    input.length < 6 -> {
                                        input += key
                                        if (input.length == 6) submit(input)
                                    }
                                }
                                error = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF141A2A),
                            modifier = Modifier.size(width = 120.dp, height = 64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    key, color = Color(0xFFE8EEFC), fontSize = 22.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * PIN-Einstellungs-Sektion (in SettingsScreen einbetten).
 */
@Composable
fun PinSettings() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(ParentalControl.isEnabled(ctx)) }
    var mode by remember { mutableStateOf<String?>(null) } // "set" | "change" | "remove"
    var pinInput by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }

    fun reset() { mode = null; pinInput = ""; pinConfirm = ""; msg = null }

    if (!enabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Kindersicherung: Aus", color = Color(0xFF8FA3C8), fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { mode = "set" }) { Text("PIN festlegen", color = Color(0xFFFFC94D), fontSize = 14.sp) }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Kindersicherung: An", color = Color(0xFF6FCF9F), fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { mode = "change" }) { Text("Ändern", color = Color(0xFFFFC94D), fontSize = 13.sp) }
            TextButton(onClick = { mode = "remove" }) { Text("Entfernen", color = Color(0xFFFF6B6B), fontSize = 13.sp) }
        }
    }

    if (mode == "remove") {
        // Entfernen verlangt aktuelle PIN
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            OutlinedTextField(
                value = pinInput, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pinInput = it },
                label = { Text("Aktuelle PIN") }, singleLine = true,
                modifier = Modifier.width(200.dp)
            )
            Spacer(Modifier.width(10.dp))
            Button(onClick = {
                if (ParentalControl.verify(ctx, pinInput)) {
                    ParentalControl.disable(ctx); enabled = false; reset(); msg = "PIN entfernt"
                } else { msg = "Falsche PIN" }
            }) { Text("Entfernen") }
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = { reset() }) { Text("Abbrechen") }
        }
    } else if (mode == "set" || mode == "change") {
        Column(Modifier.padding(top = 10.dp)) {
            OutlinedTextField(
                value = pinInput, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pinInput = it },
                label = { Text(if (mode == "set") "Neue PIN (4–6 Ziffern)" else "Neue PIN") },
                singleLine = true, modifier = Modifier.width(280.dp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pinConfirm, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pinConfirm = it },
                label = { Text("PIN bestätigen") }, singleLine = true, modifier = Modifier.width(280.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    when {
                        pinInput.length < 4 -> msg = "PIN muss mindestens 4 Ziffern haben"
                        pinInput != pinConfirm -> msg = "PINs stimmen nicht überein"
                        else -> { ParentalControl.setPin(ctx, pinInput); enabled = true; reset(); msg = "PIN gesetzt" }
                    }
                }) { Text("Speichern") }
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = { reset() }) { Text("Abbrechen") }
            }
        }
    }

    msg?.let { Text(it, color = Color(0xFF8FA3C8), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
}

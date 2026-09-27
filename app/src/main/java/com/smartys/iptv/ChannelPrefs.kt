package com.smartys.iptv

import android.content.Context
import android.content.SharedPreferences

/**
 * Kanal-Verwaltung für Live-TV:
 * - Sortierung (Nummer / Name / Manuell) => manuelle Order-Liste der streamIds
 * - Ausblenden => hidden-Set der streamIds
 * - FSK-18-Sperre => locked-Set der streamIds (PIN-Abfrage vor dem Abspielen)
 * Pro Profil getrennt persistiert (wie CategoryPrefs).
 */
object ChannelPrefs {

    private const val PREFS = "smartys_channel_mgmt"
    private const val K_SORT = "sort_mode"          // "num" | "name" | "manual"
    private const val K_ORDER = "order_ids"         // kommagetrennte streamIds (manual)
    private const val K_HIDDEN = "hidden_ids"       // Set<String>
    private const val K_LOCKED = "locked_ids"       // Set<String> (FSK18)

    private fun prefs(ctx: Context): SharedPreferences {
        val pid = ProfileStore.activeId(ctx) ?: "default"
        return ctx.getSharedPreferences("${PREFS}__$pid", Context.MODE_PRIVATE)
    }

    fun sortMode(ctx: Context): String =
        prefs(ctx).getString(K_SORT, "num") ?: "num"

    fun setSortMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString(K_SORT, mode).apply()
    }

    fun isHidden(ctx: Context, streamId: String): Boolean =
        prefs(ctx).getStringSet(K_HIDDEN, emptySet())?.contains(streamId) == true

    fun hide(ctx: Context, streamId: String) {
        val p = prefs(ctx)
        val s = (p.getStringSet(K_HIDDEN, emptySet()) ?: emptySet()).toMutableSet()
        s.add(streamId)
        p.edit().putStringSet(K_HIDDEN, s).apply()
    }

    fun unhide(ctx: Context, streamId: String) {
        val p = prefs(ctx)
        val s = (p.getStringSet(K_HIDDEN, emptySet()) ?: emptySet()).toMutableSet()
        s.remove(streamId)
        p.edit().putStringSet(K_HIDDEN, s).apply()
    }

    fun unhideAll(ctx: Context) {
        prefs(ctx).edit().putStringSet(K_HIDDEN, emptySet()).apply()
    }

    fun hiddenCount(ctx: Context): Int =
        prefs(ctx).getStringSet(K_HIDDEN, emptySet())?.size ?: 0

    fun isLocked(ctx: Context, streamId: String): Boolean =
        prefs(ctx).getStringSet(K_LOCKED, emptySet())?.contains(streamId) == true

    fun setLocked(ctx: Context, streamId: String, locked: Boolean) {
        val p = prefs(ctx)
        val s = (p.getStringSet(K_LOCKED, emptySet()) ?: emptySet()).toMutableSet()
        if (locked) s.add(streamId) else s.remove(streamId)
        p.edit().putStringSet(K_LOCKED, s).apply()
    }

    // ---- manuelle Reihenfolge ----

    private fun orderIds(ctx: Context): List<String> =
        prefs(ctx).getString(K_ORDER, "")!!.split(",").filter { it.isNotBlank() }

    /** Sortiert die übergebene Liste gemäß Modus. hidden wird NICHT hier gefiltert. */
    fun apply(ctx: Context, list: List<LiveStream>): List<LiveStream> {
        val visible = list.filter { !isHidden(ctx, it.streamId) }
        return when (sortMode(ctx)) {
            "name" -> visible.sortedBy { it.name.lowercase() }
            "manual" -> {
                val order = orderIds(ctx)
                val inOrder = order.mapNotNull { id -> visible.firstOrNull { it.streamId == id } }
                val rest = visible.filter { it.streamId !in order }.sortedBy { it.num ?: 0 }
                inOrder + rest
            }
            else -> visible.sortedBy { it.num ?: 0 }
        }
    }

    /** Verschiebt einen Kanal innerhalb der sichtbaren, aktuell sortierten Liste. */
    fun move(ctx: Context, channels: List<LiveStream>, streamId: String, delta: Int) {
        val current = apply(ctx, channels).toMutableList()
        val idx = current.indexOfFirst { it.streamId == streamId }
        if (idx < 0) return
        val target = (idx + delta).coerceIn(0, current.size - 1)
        if (target == idx) return
        val item = current.removeAt(idx)
        current.add(target, item)
        // manuelle Order speichern + Modus auf "manual" stellen
        saveOrder(ctx, current.map { it.streamId })
        setSortMode(ctx, "manual")
    }

    private fun saveOrder(ctx: Context, ids: List<String>) {
        prefs(ctx).edit().putString(K_ORDER, ids.joinToString(",")).apply()
    }
}

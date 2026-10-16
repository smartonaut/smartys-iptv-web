package com.smartys.iptv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Kategorie-Verwaltung für VOD-Sidebar (Filme & Serien):
 * - Sortierung (rauf/runter schieben) => order-Liste der Kategorie-IDs
 * - Ausblenden => hidden-Set der Kategorie-IDs
 * Pro Bereich ("movies"/"series") getrennt in SharedPreferences persistiert.
 */
object CategoryPrefs {

    private const val PREFS = "smartys_vod_categories"
    private const val K_ORDER = "order_%s"       // "movies" | "series"
    private const val K_HIDDEN = "hidden_%s"

    private fun prefs(ctx: Context): SharedPreferences {
        val pid = ProfileStore.activeId(ctx) ?: "default"
        return ctx.getSharedPreferences("${PREFS}__$pid", Context.MODE_PRIVATE)
    }

    /** Sichtbar sortierte Kategorie-Liste: zuerst nach manueller Order, Rest in Original-Reihenfolge; hidden gefiltert. */
    fun applyTo(scope: String, cats: List<Category>): List<Category> {
        val ctx = AppCtx.get() ?: return cats
        val p = prefs(ctx)
        val order = p.getString(K_ORDER.format(scope), "")!!.split(",").filter { it.isNotBlank() }
        val hidden = p.getStringSet(K_HIDDEN.format(scope), emptySet()) ?: emptySet()
        val visible = cats.filter { it.id !in hidden }
        if (order.isEmpty()) return visible
        // Manuelle Order vorne (nur sichtbare), danach der Rest original:
        val inOrder = order.mapNotNull { id -> visible.firstOrNull { it.id == id } }
        val rest = visible.filter { it.id !in order }
        return inOrder + rest
    }

    fun moveUp(scope: String, catId: String, cats: List<Category>) {
        val list = currentOrder(scope, cats).toMutableList()
        val idx = list.indexOf(catId)
        if (idx > 0) {
            list.removeAt(idx); list.add(idx - 1, catId)
            saveOrder(scope, list)
        }
    }

    fun moveDown(scope: String, catId: String, cats: List<Category>) {
        val list = currentOrder(scope, cats).toMutableList()
        val idx = list.indexOf(catId)
        if (idx in 0 until list.size - 1) {
            list.removeAt(idx); list.add(idx + 1, catId)
            saveOrder(scope, list)
        }
    }

    /**
     * Mehrere Kategorien als EIN Block um eine Position verschieben:
     * Der Nachbar direkt über/unter dem gewählten Bereich tauscht mit dem Block —
     * interne Reihenfolge bleibt erhalten, andere Kategorien wandern nicht mit.
     */
    fun moveBlock(scope: String, catIds: List<String>, cats: List<Category>, up: Boolean) {
        if (catIds.isEmpty()) return
        val list = currentOrder(scope, cats).toMutableList()
        val selSet = catIds.toSet()
        val firstIdx = list.indexOfFirst { it in selSet }
        val lastIdx = list.indexOfLast { it in selSet }
        if (firstIdx < 0 || firstIdx == lastIdx) {
            // Einzelne Kategorie => klassischen Pfad nehmen
            if (firstIdx >= 0) { if (up) moveUp(scope, catIds.first(), cats) else moveDown(scope, catIds.first(), cats) }
            return
        }
        val neighbor = if (up) firstIdx - 1 else lastIdx + 1
        if (neighbor < 0 || neighbor >= list.size) return
        val item = list.removeAt(neighbor)
        list.add(if (up) lastIdx + 1 else firstIdx, item)
        saveOrder(scope, list)
    }

    fun hide(scope: String, catId: String) {
        val ctx = AppCtx.get() ?: return
        val p = prefs(ctx)
        val hidden = (p.getStringSet(K_HIDDEN.format(scope), emptySet()) ?: emptySet()).toMutableSet()
        hidden.add(catId)
        p.edit().putStringSet(K_HIDDEN.format(scope), hidden).apply()
    }

    fun unhideAll(scope: String) {
        val ctx = AppCtx.get() ?: return
        prefs(ctx).edit().putStringSet(K_HIDDEN.format(scope), emptySet()).apply()
    }
    /** Setzt die komplette Kategorieverwaltung (Reihenfolge, Ausblenden, eigene Namen) eines Bereichs zurueck. */
    fun resetAll(scope: String) {
        val ctx = AppCtx.get() ?: return
        val p = prefs(ctx)
        val edit = p.edit()
        edit.remove(K_ORDER.format(scope))
        edit.remove(K_HIDDEN.format(scope))
        p.all.keys.filter { it.startsWith("rename_${scope}_") }.forEach { edit.remove(it) }
        edit.apply()
    }

    fun hideMany(scope: String, catIds: List<String>) {
        if (catIds.isEmpty()) return
        val ctx = AppCtx.get() ?: return
        val p = prefs(ctx)
        val hidden = (p.getStringSet(K_HIDDEN.format(scope), emptySet()) ?: emptySet()).toMutableSet()
        hidden.addAll(catIds)
        p.edit().putStringSet(K_HIDDEN.format(scope), hidden).apply()
    }

    // ---------- Eigene Namen (Umbenennen) ----------

    private fun renameKey(scope: String, catId: String) = "rename_${scope}_${catId}"

    fun customLabel(scope: String, catId: String): String? {
        val ctx = AppCtx.get() ?: return null
        return prefs(ctx).getString(renameKey(scope, catId), "")?.takeIf { it.isNotBlank() }
    }

    /** Setzt einen eigenen Namen; null/leer = zurück zum Original-Namen des Providers. */
    fun setLabel(scope: String, catId: String, label: String?) {
        val ctx = AppCtx.get() ?: return
        val key = renameKey(scope, catId)
        prefs(ctx).edit().apply {
            val t = label?.trim().orEmpty()
            if (t.isEmpty()) remove(key) else putString(key, t)
        }.apply()
    }

    fun labelOf(scope: String, cat: Category): String = customLabel(scope, cat.id) ?: cat.name

    fun isHidden(scope: String, catId: String): Boolean {
        val ctx = AppCtx.get() ?: return false
        val hidden = prefs(ctx).getStringSet(K_HIDDEN.format(scope), emptySet()) ?: emptySet()
        return catId in hidden
    }

    /** Menge aller ausgeblendeten Kategorie-IDs eines Bereichs (einmalig lesen, für List-Filter). */
    fun hiddenIds(scope: String): Set<String> {
        val ctx = AppCtx.get() ?: return emptySet()
        return prefs(ctx).getStringSet(K_HIDDEN.format(scope), emptySet()) ?: emptySet()
    }

    /** Aktuelle Order-IDs inkl. hidden (damit moveUp/Down auch ausgeblendete Treffer sauber behandelt). */
    private fun currentOrder(scope: String, cats: List<Category>): List<String> {
        val ctx = AppCtx.get() ?: return cats.map { it.id }
        val saved = prefs(ctx).getString(K_ORDER.format(scope), "")!!.split(",").filter { it.isNotBlank() }
        val ids = cats.map { it.id }
        val inOrder = saved.filter { it in ids }
        return inOrder + ids.filter { it !in inOrder }
    }

    private fun saveOrder(scope: String, ids: List<String>) {
        val ctx = AppCtx.get() ?: return
        prefs(ctx).edit().putString(K_ORDER.format(scope), ids.joinToString(",")).apply()
    }
}

/** Application-Context-Halter, damit CategoryPrefs ohne Parameter an Context kommt. */
object AppCtx {
    @Volatile private var appContext: Context? = null
    fun set(ctx: Context) { appContext = ctx.applicationContext }
    fun get(): Context? = appContext
}
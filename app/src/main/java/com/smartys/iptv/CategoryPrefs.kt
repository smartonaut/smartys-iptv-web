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

    fun isHidden(scope: String, catId: String): Boolean {
        val ctx = AppCtx.get() ?: return false
        val hidden = prefs(ctx).getStringSet(K_HIDDEN.format(scope), emptySet()) ?: emptySet()
        return catId in hidden
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
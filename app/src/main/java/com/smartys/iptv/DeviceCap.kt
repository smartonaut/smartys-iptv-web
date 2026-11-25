package com.smartys.iptv

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Geräte-Check für den Live-TV-Puffer (Cache).
 *
 * Liest RAM und CMA direkt aus /proc/meminfo — `memoryClass` deckt nur die
 * Heap-Grenze ab, nicht den echten Gerätespeicher. CMA (Contiguous Memory
 * Allocator) ist der eigentliche 4K-Engpass: ist er klein, bricht der
 * Hardware-Decoder bei zu grossem Puffer mit "Source error" ab.
 *
 * `maxBufferSec()` liefert die harte Obergrenze, die eine Nutzer-Einstellung
 * niemals überschreiten darf.
 */
object DeviceCap {

    fun totalRamMb(): Long = memInfoMb("MemTotal")

    fun cmaTotalMb(): Long = memInfoMb("CmaTotal")

    fun freeStorageMb(ctx: Context): Long = try {
        ctx.filesDir.usableSpace / 1048576L
    } catch (_: Exception) { 0L }

    private fun memInfoMb(key: String): Long = try {
        val line = File("/proc/meminfo").readLines().firstOrNull { it.startsWith(key) } ?: ""
        line.replace(Regex("[^0-9]"), "").toLongOrNull()?.let { it / 1024 } ?: 0L
    } catch (_: Exception) { 0L }

    /**
     * Max. Puffer-Sekunden, die dieses Gerät sicher verkraftet.
     * Gleiche Schwellen wie RobustLivePlayer.detectBufferDurations(), hier als
     * harte Obergrenze für die Nutzer-Einstellung.
     */
    fun maxBufferSec(ctx: Context): Int {
        val ram = totalRamMb()
        val cma = cmaTotalMb()
        val free = freeStorageMb(ctx)
        // MemTotal liegt rund 5 % unter der Kataloggroesse (Kernel-Reservierung):
        // ein 2-GB-Cube meldet 1989 MB, ein 1,8-GB-Stick 1793 MB. Mit den
        // Katalogschwellen (2048) fiele der Cube faelschlich in die 20-s-Klasse.
        var max = when {
            ram >= 3800 -> 60
            ram >= 1800 -> 40
            else -> 20
        }
        if (cma in 1..255) max = minOf(max, 25)
        if (free in 1..511) max = minOf(max, 15)
        return max
    }

    fun ramText(): String = String.format(Locale.GERMAN, "%.1f GB", totalRamMb() / 1024.0)

    fun cmaText(): String = if (cmaTotalMb() > 0) "${cmaTotalMb()} MB" else "nicht gemeldet"

    fun freeText(ctx: Context): String =
        String.format(Locale.GERMAN, "%.1f GB", freeStorageMb(ctx) / 1024.0)
}

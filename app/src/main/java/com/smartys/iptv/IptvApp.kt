package com.smartys.iptv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Zentraler Application-Einstieg. Konfiguriert Coil bewusst fuer schwache
 * Geräte (Fire TV Stick mt8695): Crossfade AUS und Speicher-Cache begrenzt.
 *
 * Ohne Crossfade-Aus "verziehen" sich die Cover beim Scrollen, weil Coil
 * jedes neu geladene Bild ueberblendet (Standard-Crossfade 100 ms) und die
 * Poster in voller Aufloesung dekodiert — auf dem Cube (mehr GPU) faellt das
 * nicht auf, auf dem Stick schon.
 */
class IptvApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // Kein Ueberblenden beim Einblenden: verhindert das "Verziehen"
            // beim Scrollen auf schwacher GPU.
            .crossfade(false)
            // Speicher-Cache auf ~15% des Heaps deckeln (Stick hat wenig RAM).
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            // Disk-Cache auf 32 MB deckeln.
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(32L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}

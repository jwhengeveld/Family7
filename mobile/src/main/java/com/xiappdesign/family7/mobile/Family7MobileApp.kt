package com.xiappdesign.family7.mobile

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.xiappdesign.family7.core.data.Family7AuthRepository
import com.xiappdesign.family7.core.data.Family7CatalogRepository
import com.xiappdesign.family7.core.data.Family7LiveRepository
import com.xiappdesign.family7.core.data.Family7MyListRepository
import com.xiappdesign.family7.core.data.Family7VideoRepository
import com.xiappdesign.family7.core.data.NetworkMonitor
import com.xiappdesign.family7.mobile.playback.PlaybackManager

/**
 * Houdt de repositories en de speler één keer bij voor de hele app, zodat
 * schermen hun cache delen en een cast-sessie doorloopt tussen schermen.
 */
class Family7MobileApp : Application(), ImageLoaderFactory {

    /** Werk dat langer leeft dan een scherm, zoals de cast-sessie. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val auth by lazy { Family7AuthRepository(this) }
    val catalog by lazy { Family7CatalogRepository(this) }
    val video by lazy { Family7VideoRepository(this) }
    val live by lazy { Family7LiveRepository(this) }
    val myList by lazy { Family7MyListRepository(this) }
    val network by lazy { NetworkMonitor(this) }
    val playback by lazy { PlaybackManager(this, video, live, network, appScope) }

    /**
     * Afbeeldingen: een ruime cache op schijf, zodat de omslagen er bij een
     * koude start of zonder netwerk meteen staan, en een zachte overgang
     * als ze binnenkomen.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("family7_images"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            // Family7 stuurt vaak "no-cache"; voor omslagen is dat te streng.
            .respectCacheHeaders(false)
            .build()
}

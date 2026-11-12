package com.lite.iptv

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val prevH = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { Store.setStrNow("last_crash", android.util.Log.getStackTraceString(e).take(3500)) } catch (_: Throwable) {}
            prevH?.uncaughtException(t, e)
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(Store.lang))
    }

    // Small caches, no fade animation: logos/posters load on demand only.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(false)
        .allowRgb565(true)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(80L * 1024 * 1024).build() }
        .build()
}

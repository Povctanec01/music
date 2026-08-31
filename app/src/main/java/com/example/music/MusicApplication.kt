package com.example.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import okhttp3.OkHttpClient

class MusicApplication : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }

    override fun newImageLoader(): ImageLoader {
        val httpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request()
                val withAgent = if (request.header("User-Agent") == null) {
                    request.newBuilder()
                        .header("User-Agent", "MusicApp/1.0 (Android)")
                        .build()
                } else {
                    request
                }
                chain.proceed(withAgent)
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(httpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.03)
                    .build()
            }
            .respectCacheHeaders(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .build()
    }
}

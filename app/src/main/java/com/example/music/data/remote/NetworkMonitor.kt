package com.example.music.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.music.domain.model.ConnectionChannel
import com.example.music.domain.model.DownloadNetworkMode

class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun isWifi(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun canDownload(mode: DownloadNetworkMode, channel: ConnectionChannel): Boolean {
        return when (mode) {
            DownloadNetworkMode.ANY -> true
            DownloadNetworkMode.WIFI_ONLY -> isWifi()
            DownloadNetworkMode.WIFI_OR_TAILSCALE ->
                isWifi() || channel == ConnectionChannel.TAILSCALE
        }
    }
}

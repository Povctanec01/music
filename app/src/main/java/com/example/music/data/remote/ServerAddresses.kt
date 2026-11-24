package com.example.music.data.remote

/** Device migration only replaces the previous deployment's standard endpoints. */
object ServerAddresses {
    const val LAN = "http://192.168.1.76:8080"
    const val TAILSCALE = "http://100.96.151.91:8080"
    private const val OLD_LAN = "http://192.168.1.80:8080"
    private const val OLD_TAILSCALE = "http://100.106.26.26:8080"

    fun lan(saved: String?): String = migrate(saved, OLD_LAN, LAN)
    fun tailscale(saved: String?): String = migrate(saved, OLD_TAILSCALE, TAILSCALE)

    private fun migrate(saved: String?, old: String, current: String): String =
        if (saved == null || saved.trim().trimEnd('/').equals(old, ignoreCase = true)) current else saved
}

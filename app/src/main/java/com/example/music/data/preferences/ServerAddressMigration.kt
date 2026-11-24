package com.example.music.data.preferences

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.music.data.remote.ServerAddresses

/** Runs once before DataStore emits settings; subsequent manual edits are respected. */
internal class ServerAddressMigration : DataMigration<Preferences> {
    private val revision = intPreferencesKey("server_addresses_revision")
    private val lan = stringPreferencesKey("lan_url")
    private val tailscale = stringPreferencesKey("tailscale_url")

    override suspend fun shouldMigrate(currentData: Preferences) = (currentData[revision] ?: 0) < 1
    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        currentData[lan]?.let { this[lan] = ServerAddresses.lan(it) }
        currentData[tailscale]?.let { this[tailscale] = ServerAddresses.tailscale(it) }
        this[revision] = 1
    }
    override suspend fun cleanUp() = Unit
}

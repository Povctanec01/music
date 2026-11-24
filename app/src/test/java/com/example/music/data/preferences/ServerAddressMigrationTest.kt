package com.example.music.data.preferences

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.music.data.remote.ServerAddresses
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ServerAddressMigrationTest {
    @Test fun migratesStoredDefaultsOnceAndPreservesLaterManualEdits() = runBlocking {
        val lan = stringPreferencesKey("lan_url")
        val tailscale = stringPreferencesKey("tailscale_url")
        val migration = ServerAddressMigration()
        val old = preferencesOf(lan to "http://192.168.1.80:8080", tailscale to "http://100.106.26.26:8080")
        assertTrue(migration.shouldMigrate(old))
        val updated = migration.migrate(old)
        assertEquals(ServerAddresses.LAN, updated[lan])
        assertEquals(ServerAddresses.TAILSCALE, updated[tailscale])
        assertFalse(migration.shouldMigrate(updated))
        val manuallyEdited = updated.toMutablePreferences().apply { this[lan] = "http://192.168.1.80:8080" }
        assertFalse(migration.shouldMigrate(manuallyEdited))
        assertEquals("http://192.168.1.80:8080", manuallyEdited[lan])
    }
}

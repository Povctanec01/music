package com.example.music.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ServerClientTest {
    @Test fun invalidUrlsAreOfflineInsteadOfCrashing() = runBlocking {
        val client = ServerClient(Json { ignoreUnknownKeys = true })
        assertFalse(client.health("not a URL"))
        assertFalse(client.health(""))
        assertNull(client.pickEndpoint("bad LAN URL", "bad Tailscale URL"))
    }
}

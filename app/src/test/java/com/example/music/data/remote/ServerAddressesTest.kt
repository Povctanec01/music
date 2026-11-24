package com.example.music.data.remote

import org.junit.Assert.*
import org.junit.Test

class ServerAddressesTest {
    @Test fun newInstallsAndPreviousDefaultSettingsUseTheNewDevice() {
        assertEquals("http://192.168.1.76:8080", ServerAddresses.lan(null))
        assertEquals("http://100.96.151.91:8080", ServerAddresses.tailscale(null))
        assertEquals(ServerAddresses.LAN, ServerAddresses.lan("http://192.168.1.80:8080"))
        assertEquals(ServerAddresses.TAILSCALE, ServerAddresses.tailscale(" http://100.106.26.26:8080/ "))
    }

    @Test fun customEndpointsAndExplicitlyDisabledChannelsArePreserved() {
        assertEquals("https://music.example.com", ServerAddresses.lan("https://music.example.com"))
        assertEquals("http://100.1.2.3:9999", ServerAddresses.tailscale("http://100.1.2.3:9999"))
        assertEquals("", ServerAddresses.lan(""))
        assertEquals("", ServerAddresses.tailscale(""))
    }
}

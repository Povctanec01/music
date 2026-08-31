package com.example.music.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/** Base URL of reachable Orange Pi server for `/api/art/...` (LAN or Tailscale). */
val LocalServerArtBase = staticCompositionLocalOf<String?> { null }

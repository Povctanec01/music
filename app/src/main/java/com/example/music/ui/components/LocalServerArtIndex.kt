package com.example.music.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.example.music.domain.art.ServerArtIndex

val LocalServerArtIndex = staticCompositionLocalOf { ServerArtIndex.EMPTY }

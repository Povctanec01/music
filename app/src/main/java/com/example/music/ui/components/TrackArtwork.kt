package com.example.music.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.music.MusicApplication
import com.example.music.domain.art.TrackArtRepository
import com.example.music.domain.model.Track

@Composable
fun TrackArtwork(
    track: Track,
    modifier: Modifier = Modifier,
    cornerSize: Dp = 20.dp,
    fallbackLabel: String? = null,
) {
    val repo = (LocalContext.current.applicationContext as MusicApplication).container.trackArtRepository
    LaunchedEffect(track.relativePath) {
        repo.ensureLoadedForTrack(track)
    }
    TrackArtworkContent(
        artState = repo.observeForTrack(track),
        modifier = modifier,
        cornerSize = cornerSize,
        fallbackLabel = fallbackLabel ?: track.title.firstOrNull()?.uppercase(),
        onReload = { repo.requestLoadForTrack(track) },
    )
}

@Composable
fun TrackArtworkFromPath(
    relativePath: String,
    version: Long,
    modifier: Modifier = Modifier,
    cornerSize: Dp = 20.dp,
    fallbackLabel: String? = null,
    embeddedUri: android.net.Uri? = null,
    vararg extraPaths: String,
) {
    val repo = (LocalContext.current.applicationContext as MusicApplication).container.trackArtRepository
    val loadKey = listOf(relativePath, version.toString(), embeddedUri?.toString().orEmpty(), *extraPaths).joinToString("|")
    LaunchedEffect(loadKey) {
        repo.requestLoad(relativePath, version, embeddedUri, force = false, *extraPaths)
    }
    TrackArtworkContent(
        artState = repo.observeFor(relativePath, version, embeddedUri, *extraPaths),
        modifier = modifier,
        cornerSize = cornerSize,
        fallbackLabel = fallbackLabel,
        onReload = { repo.requestLoad(relativePath, version, embeddedUri, force = false, *extraPaths) },
    )
}

@Composable
private fun TrackArtworkContent(
    artState: kotlinx.coroutines.flow.StateFlow<TrackArtRepository.ArtLoadState>,
    modifier: Modifier = Modifier,
    cornerSize: Dp = 20.dp,
    fallbackLabel: String? = null,
    onReload: () -> Unit,
) {
    val state by artState.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state is TrackArtRepository.ArtLoadState.Evicted) onReload()
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerSize))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = state) {
            is TrackArtRepository.ArtLoadState.Ready -> {
                Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            is TrackArtRepository.ArtLoadState.Missing,
            is TrackArtRepository.ArtLoadState.Loading,
            is TrackArtRepository.ArtLoadState.Evicted,
            -> {
                Text(
                    text = fallbackLabel ?: "♪",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

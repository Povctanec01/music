package com.example.music.domain.recommendation

import com.example.music.domain.model.Track
import kotlinx.serialization.Serializable

@Serializable
data class TrackBehavior(
    val playCount: Int = 0,
    val completionCount: Int = 0,
    val skipCount: Int = 0,
    val totalListenedMs: Long = 0L,
    val lastPlayedAt: Long = 0L,
)

data class RecommendationSection(
    val key: String,
    val title: String,
    val subtitle: String,
    val tracks: List<Track>,
)
